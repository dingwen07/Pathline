package net.extrawdw.apps.locationhistory.ml

import org.tensorflow.lite.Interpreter
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Thin wrapper around a LiteRT/TFLite model exported with on-device-training signatures
 * (`infer`, `train`, `get_weights`, `set_weights`). The base graph ships as an asset; personalized
 * weights live in a checkpoint file that is loaded on start and rewritten after each
 * (charging-gated) training run. Weights are persisted on the Kotlin side rather than via TF
 * Save/Restore ops, so the model needs no Flex/Select-TF-ops delegate.
 *
 * Signature I/O contract (see ml/training/build_models.py):
 *  - infer:       in {"x":[1,featureDim]}                  -> out {probs:[1,numClasses]}
 *  - train:       in {"x":[B,featureDim], "y":[B,classes]} -> out {"loss": scalar}
 *  - get_weights: in {}                                    -> out {"w":[featureDim,classes], "b":[classes]}
 *  - set_weights: in {"w":[featureDim,classes], "b":[classes]} -> out {"ok": scalar}
 */
class TrainableTfliteModel(
    modelBytes: ByteBuffer,
    val featureDim: Int,
    val numClasses: Int,
) : AutoCloseable {

    private val interpreter = Interpreter(modelBytes, Interpreter.Options())

    private val signatures: Set<String> = interpreter.signatureKeys?.toSet() ?: emptySet()

    private val inferOutputKey: String =
        signatures.takeIf { "infer" in it }
            ?.let { interpreter.getSignatureOutputs("infer").firstOrNull() } ?: "output"

    fun supportsTraining(): Boolean =
        "train" in signatures && "get_weights" in signatures && "set_weights" in signatures

    /** Restore personalized weights from [checkpoint] if it exists and the model supports it. */
    fun restoreIfPresent(checkpoint: File) {
        if (!checkpoint.exists() || "set_weights" !in signatures) return
        runCatching {
            DataInputStream(checkpoint.inputStream().buffered()).use { input ->
                val rows = input.readInt()
                val cols = input.readInt()
                if (rows != featureDim || cols != numClasses) return
                val w = Array(rows) { FloatArray(cols) { input.readFloat() } }
                val b = FloatArray(input.readInt()) { input.readFloat() }
                val outputs = HashMap<String, Any>().apply { put("ok", FloatArray(1)) }
                interpreter.runSignature(mapOf<String, Any>("w" to w, "b" to b), outputs, "set_weights")
            }
        }
    }

    /** @return softmax probabilities over [numClasses], or null on failure. */
    fun infer(features: FloatArray): FloatArray? = runCatching {
        require(features.size == featureDim)
        val input = arrayOf(features)
        val output = Array(1) { FloatArray(numClasses) }
        val outputs = HashMap<String, Any>().apply { put(inferOutputKey, output) }
        interpreter.runSignature(mapOf<String, Any>("x" to input), outputs, "infer")
        output[0]
    }.getOrNull()

    /** Runs [epochs] of gradient steps over the batch and returns the final loss. */
    fun train(x: Array<FloatArray>, y: Array<FloatArray>, epochs: Int): Float {
        if (!supportsTraining() || x.isEmpty()) return Float.NaN
        var lastLoss = Float.NaN
        repeat(epochs) {
            val loss = FloatArray(1)
            val outputs = HashMap<String, Any>().apply { put("loss", loss) }
            runCatching {
                interpreter.runSignature(mapOf<String, Any>("x" to x, "y" to y), outputs, "train")
                lastLoss = loss[0]
            }
        }
        return lastLoss
    }

    /** Persist current weights to [checkpoint] so the next launch restores them. */
    fun save(checkpoint: File) {
        if ("get_weights" !in signatures) return
        runCatching {
            val w = Array(featureDim) { FloatArray(numClasses) }
            val b = FloatArray(numClasses)
            val outputs = HashMap<String, Any>().apply { put("w", w); put("b", b) }
            interpreter.runSignature(emptyMap<String, Any>(), outputs, "get_weights")
            DataOutputStream(checkpoint.outputStream().buffered()).use { out ->
                out.writeInt(featureDim)
                out.writeInt(numClasses)
                for (row in w) for (v in row) out.writeFloat(v)
                out.writeInt(numClasses)
                for (v in b) out.writeFloat(v)
            }
        }
    }

    override fun close() = interpreter.close()

    companion object {
        /** Copies model bytes into a direct, native-order buffer as required by the interpreter. */
        fun directBuffer(bytes: ByteArray): ByteBuffer =
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).apply {
                put(bytes)
                rewind()
            }
    }
}
