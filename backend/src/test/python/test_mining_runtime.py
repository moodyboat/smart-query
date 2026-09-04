import importlib.util
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import joblib
import numpy as np
import pandas as pd


RUNTIME_PATH = Path(__file__).parents[2] / "main" / "resources" / "python" / "mining_runtime.py"
CATALOG_PATH = Path(__file__).parents[2] / "main" / "resources" / "catalog" / "builtin-algorithms.json"
SPEC = importlib.util.spec_from_file_location("mining_runtime", RUNTIME_PATH)
runtime = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runtime
SPEC.loader.exec_module(runtime)
with CATALOG_PATH.open(encoding="utf-8") as catalog_file:
    ALGORITHM_CATALOG = json.load(catalog_file)
ALGORITHMS = {item["algorithmId"]: item for item in ALGORITHM_CATALOG["algorithms"]}


class MiningRuntimePipelineTest(unittest.TestCase):

    def test_every_catalog_template_builds_a_sklearn_compatible_estimator(self):
        self.assertEqual(len(ALGORITHMS), len(ALGORITHM_CATALOG["algorithms"]))
        frame = pd.DataFrame({"x1": [0.0, 1.0, 2.0], "x2": [1.0, 0.0, 1.0]})
        target = pd.Series([0, 1, 0])
        for algorithm in ALGORITHM_CATALOG["algorithms"]:
            with self.subTest(algorithm=algorithm["algorithmId"]):
                self.assertTrue(algorithm["modelTypes"])
                estimator = runtime._build_estimator({
                    "algorithmId": algorithm["algorithmId"],
                    "algorithmCode": algorithm["pythonCodeTemplate"],
                    "modelType": algorithm["modelTypes"][0],
                    "hyperparameters": {},
                }, frame, target, frame)
                self.assertTrue(callable(estimator.fit))
                self.assertTrue(callable(estimator.predict))

    def test_training_saves_one_pipeline_and_prediction_replays_every_transform(self):
        rows = 120
        frame = pd.DataFrame({
            "amount": np.linspace(1, 500, rows),
            "category": ["a", "b", "c"] * 40,
            "event_date": pd.date_range("2025-01-01", periods=rows, freq="D"),
            "target": [0, 1] * 60,
        })
        with tempfile.TemporaryDirectory() as directory:
            artifact = Path(directory) / "model.joblib"
            request = {
                "sourceTable": "training_data",
                "featureColumns": ["amount", "category", "event_date"],
                "targetColumn": "target",
                "modelType": "classification",
                "algorithmId": "test_random_forest",
                "algorithmCode": (
                    "from sklearn.ensemble import RandomForestClassifier\n"
                    "clf = RandomForestClassifier(n_estimators=10, random_state=7, **params)"
                ),
                "hyperparameters": {},
                "preprocessing": {
                    "handleMissing": "fill_median",
                    "encoding": "onehot",
                    "scaling": "standard",
                    "transforms": [
                        {"type": "log", "columns": ["amount"]},
                        {"type": "target_encode", "columns": ["category"]},
                        {"type": "date_extract", "columns": ["event_date"]},
                        {"type": "polynomial", "columns": ["amount"], "degree": 2},
                    ],
                },
                "targetPreprocessing": {},
                "validation": {
                    "mode": "cv", "cvFolds": 3, "testSize": 0.2, "randomState": 42,
                },
                "artifactPath": str(artifact),
                "overfittingGapThreshold": 0.5,
            }

            with patch.object(runtime, "_read_table", return_value=frame.copy()):
                result = runtime._train(request)

            self.assertEqual("success", result["status"])
            self.assertTrue(result["validation"]["preprocessing_fitted_per_fold"])
            bundle = joblib.load(artifact)
            self.assertIn("pipeline", bundle)
            self.assertNotIn("preprocessors", bundle)

            prediction_frame = frame.iloc[:3].copy()
            prediction_frame.loc[prediction_frame.index[0], "category"] = "unseen-category"
            values, probabilities = runtime._prediction_values(bundle, prediction_frame)
            self.assertEqual(3, len(values))
            self.assertEqual((3, 2), probabilities.shape)

    def test_common_builtin_estimators_build_train_and_predict(self):
        rows = 90
        supervised = pd.DataFrame({
            "x1": np.linspace(-3, 3, rows),
            "x2": np.sin(np.linspace(0, 8, rows)),
        })
        supervised["class_target"] = (supervised["x1"] + supervised["x2"] > 0).astype(int)
        supervised["reg_target"] = supervised["x1"] * 2.5 - supervised["x2"] * 0.8
        unsupervised = supervised[["x1", "x2"]].copy()

        cases = [
            ("logistic_regression", "classification", supervised, "class_target"),
            ("extra_trees", "classification", supervised, "class_target"),
            ("ridge", "regression", supervised, "reg_target"),
            ("sgd", "regression", supervised, "reg_target"),
            ("gaussian_mixture", "clustering", unsupervised, None),
            ("one_class_svm", "anomaly_detection", unsupervised, None),
            ("local_outlier_factor", "anomaly_detection", unsupervised, None),
        ]

        with tempfile.TemporaryDirectory() as directory:
            for algorithm_id, model_type, frame, target in cases:
                with self.subTest(algorithm=algorithm_id):
                    algorithm = ALGORITHMS[algorithm_id]
                    artifact = Path(directory) / f"{algorithm_id}.joblib"
                    request = {
                        "sourceTable": "training_data",
                        "featureColumns": ["x1", "x2"],
                        "targetColumn": target,
                        "modelType": model_type,
                        "algorithmId": algorithm_id,
                        "algorithmCode": algorithm["pythonCodeTemplate"],
                        "hyperparameters": {},
                        "preprocessing": {"handleMissing": "fill_median", "scaling": "standard"},
                        "targetPreprocessing": {},
                        "validation": {"mode": "none", "testSize": 0.2, "randomState": 42},
                        "artifactPath": str(artifact),
                        "overfittingGapThreshold": 1.0,
                    }

                    with patch.object(runtime, "_read_table", return_value=frame.copy()):
                        result = runtime._train(request)

                    self.assertEqual("success", result["status"])
                    self.assertTrue(artifact.exists())
                    bundle = joblib.load(artifact)
                    predictions, _ = runtime._prediction_values(bundle, frame.iloc[:5].copy())
                    self.assertEqual(5, len(predictions))


if __name__ == "__main__":
    unittest.main()
