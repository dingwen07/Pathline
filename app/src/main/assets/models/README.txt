Place the generated LiteRT models here:

  state_model.tflite
  transport_model.tflite

Generate them with:  python ml/training/build_models.py

Until they exist, the app falls back to HeuristicClassifier (see ml/Classifier.kt) and remains
fully functional; all predictions are marked "unconfirmed".
