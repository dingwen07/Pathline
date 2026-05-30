# Pathline on-device models

Pathline classifies the **device physical state** and **transport mode** with two tiny LiteRT
(TensorFlow Lite) models that **personalize on-device** from the user's confirmations. Training
runs only while the device is charging (a WorkManager constraint), so it never costs the user
battery.

## Files

- `build_models.py` — generates the base models with on-device-training signatures
  (`infer`, `train`, `save`, `restore`).
- Output: `app/src/main/assets/models/{state_model,transport_model}.tflite`

## Build the base models

```bash
pip install "tensorflow>=2.15"
python ml/training/build_models.py
```

Then rebuild the app; the `.tflite` files are bundled as assets and
`LiteRtModelStore` picks them up automatically.

## Behavior without the models

The app is fully functional **before** these assets exist: `Classifier` falls back to
`HeuristicClassifier`, and all predictions are surfaced as **unconfirmed**. Once the assets are
present, the LiteRT model is used whenever its confidence clears
`Constants.CONFIRM_CONFIDENCE_THRESHOLD`, and `ModelTrainingWorker` retrains it from accumulated
user-confirmed examples.

## Contract

The feature layout is defined once in `app/.../ml/Features.kt` and must match this script:

| Model            | Features | Classes | Class order                                  |
|------------------|----------|---------|----------------------------------------------|
| `state_model`    | 13       | 5       | `DevicePhysicalState.MODEL_CLASSES`          |
| `transport_model`| 16       | 8       | `TransportMode.MODEL_CLASSES`                |

If you change the feature vector or class set, update both sides and rebuild the models.
