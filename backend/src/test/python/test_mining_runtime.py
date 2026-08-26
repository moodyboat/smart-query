import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import joblib
import numpy as np
import pandas as pd


RUNTIME_PATH = Path(__file__).parents[2] / "main" / "resources" / "python" / "mining_runtime.py"
SPEC = importlib.util.spec_from_file_location("mining_runtime", RUNTIME_PATH)
runtime = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = runtime
SPEC.loader.exec_module(runtime)


class MiningRuntimePipelineTest(unittest.TestCase):

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


if __name__ == "__main__":
    unittest.main()
