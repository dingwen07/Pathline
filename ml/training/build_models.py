#!/usr/bin/env python3
"""Build the base LiteRT/TFLite models for Pathline with on-device-training signatures.

Pathline ships two small classifiers that personalize on-device from the user's confirmations:

  * state_model.tflite     — device physical state  (13 features -> 5 classes)
  * transport_model.tflite — transport mode          (16 features -> 8 classes)

Each model exports four signatures used by `TrainableTfliteModel.kt`:
  infer(x)        -> probabilities (softmax)
  train(x, y)     -> loss           (one SGD step)
  get_weights()   -> {w, b}         (Kotlin serializes these to a checkpoint file)
  set_weights(w, b) -> {ok}         (Kotlin restores them on load)

All ops are TFLite builtins (incl. variable ops) — no Flex/Select-TF-ops delegate required.

The feature dimensions and class counts MUST match `ml/Features.kt`,
`DevicePhysicalState.MODEL_CLASSES` and `TransportMode.MODEL_CLASSES`.

Usage:
    pip install "tensorflow>=2.15"
    python ml/training/build_models.py

Output is written to app/src/main/assets/models/ so the next app build bundles the base models.
Until this script is run the app falls back to HeuristicClassifier — it remains fully functional.
"""

import os
import tempfile
import numpy as np
import tensorflow as tf

OUT_DIR = os.path.join(
    os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "models"
)


class TrainableClassifier(tf.Module):
    """A single dense softmax layer trainable on-device, with save/restore checkpoints."""

    def __init__(self, feature_dim: int, num_classes: int):
        super().__init__()
        self.feature_dim = feature_dim
        self.num_classes = num_classes
        # Small init so the bootstrap model is near-uniform until it learns.
        self.w = tf.Variable(tf.zeros([feature_dim, num_classes]), name="w")
        self.b = tf.Variable(tf.zeros([num_classes]), name="b")
        self.optimizer = tf.keras.optimizers.SGD(learning_rate=0.05)

    def _logits(self, x):
        return tf.matmul(x, self.w) + self.b

    @tf.function
    def infer(self, x):
        return {"output": tf.nn.softmax(self._logits(x))}

    @tf.function
    def train(self, x, y):
        with tf.GradientTape() as tape:
            logits = self._logits(x)
            loss = tf.reduce_mean(
                tf.nn.softmax_cross_entropy_with_logits(labels=y, logits=logits)
            )
        grads = tape.gradient(loss, [self.w, self.b])
        self.optimizer.apply_gradients(zip(grads, [self.w, self.b]))
        return {"loss": loss}

    # Weights are read/assigned via builtin TFLite variable ops (no Flex/Select-TF-ops needed);
    # the Kotlin side serializes them to a checkpoint file. See TrainableTfliteModel.kt.
    @tf.function
    def get_weights(self):
        return {"w": self.w.read_value(), "b": self.b.read_value()}

    @tf.function
    def set_weights(self, w, b):
        self.w.assign(w)
        self.b.assign(b)
        return {"ok": tf.constant(1.0)}


def build(name: str, feature_dim: int, num_classes: int):
    model = TrainableClassifier(feature_dim, num_classes)

    signatures = {
        "infer": model.infer.get_concrete_function(
            tf.TensorSpec([1, feature_dim], tf.float32)
        ),
        "train": model.train.get_concrete_function(
            tf.TensorSpec([None, feature_dim], tf.float32),
            tf.TensorSpec([None, num_classes], tf.float32),
        ),
        "get_weights": model.get_weights.get_concrete_function(),
        "set_weights": model.set_weights.get_concrete_function(
            tf.TensorSpec([feature_dim, num_classes], tf.float32),
            tf.TensorSpec([num_classes], tf.float32),
        ),
    }

    # Export a SavedModel with named signatures, then convert it: this is the supported path for
    # multi-signature on-device-training models across TF versions.
    with tempfile.TemporaryDirectory() as saved_dir:
        tf.saved_model.save(model, saved_dir, signatures=signatures)
        converter = tf.lite.TFLiteConverter.from_saved_model(saved_dir)
        # Builtins only (incl. variable ops) — no Flex/Select-TF-ops, so the model runs on the
        # core LiteRT runtime with no extra native delegate.
        converter.target_spec.supported_ops = [tf.lite.OpsSet.TFLITE_BUILTINS]
        converter.experimental_enable_resource_variables = True
        tflite_model = converter.convert()

    os.makedirs(OUT_DIR, exist_ok=True)
    out_path = os.path.join(OUT_DIR, f"{name}.tflite")
    with open(out_path, "wb") as f:
        f.write(tflite_model)
    print(f"wrote {out_path} ({len(tflite_model)} bytes)")


if __name__ == "__main__":
    build("state_model", feature_dim=13, num_classes=5)
    build("transport_model", feature_dim=16, num_classes=8)
    print("Done. Rebuild the app to bundle the models.")
