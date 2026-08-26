"""Versioned Smart Query mining runtime.

Java supplies JSON request/result paths. This module owns all Python training and
prediction logic so Java never assembles Python source. stdout/stderr are logs only;
the machine-readable response is atomically written to --result.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import logging
import os
import sys
import traceback
from pathlib import Path
from typing import Any

import joblib
import numpy as np
import pandas as pd
import sklearn
from sklearn.base import BaseEstimator, TransformerMixin, clone
from sklearn.metrics import (
    accuracy_score,
    average_precision_score,
    balanced_accuracy_score,
    brier_score_loss,
    confusion_matrix,
    f1_score,
    mean_absolute_error,
    mean_squared_error,
    precision_score,
    precision_recall_curve,
    r2_score,
    recall_score,
    roc_auc_score,
    roc_curve,
    silhouette_score,
)
from sklearn.model_selection import (
    GroupKFold,
    GroupShuffleSplit,
    KFold,
    StratifiedGroupKFold,
    StratifiedKFold,
    TimeSeriesSplit,
    train_test_split,
)
from sklearn.calibration import CalibratedClassifierCV
from sklearn.pipeline import Pipeline
from sklearn.preprocessing import LabelEncoder, MinMaxScaler, PolynomialFeatures, StandardScaler
from sklearn.compose import TransformedTargetRegressor
from sklearn.utils.validation import check_is_fitted
from sqlalchemy import create_engine, text


PROTOCOL_VERSION = 1
ARTIFACT_SCHEMA_VERSION = 3
LOG = logging.getLogger("smartquery.mining")
_PROGRESS_PATH: str | None = None
_EXECUTION_ID: int | None = None


def _json_default(value: Any) -> Any:
    if isinstance(value, (np.integer,)):
        return int(value)
    if isinstance(value, (np.floating,)):
        return None if not np.isfinite(value) else float(value)
    if isinstance(value, np.ndarray):
        return value.tolist()
    if isinstance(value, (pd.Timestamp,)):
        return value.isoformat()
    if pd.isna(value):
        return None
    return str(value)


def _target_log1p(values):
    return np.log1p(np.clip(values, 0, None))


def _target_expm1(values):
    return np.expm1(values)


def _atomic_write_json(path: str, payload: dict[str, Any]) -> None:
    target = Path(path)
    target.parent.mkdir(parents=True, exist_ok=True)
    temporary = target.with_suffix(target.suffix + ".tmp")
    with temporary.open("w", encoding="utf-8") as handle:
        json.dump(payload, handle, ensure_ascii=False, default=_json_default)
        handle.flush()
        os.fsync(handle.fileno())
    os.replace(temporary, target)


def _report_progress(stage: str, progress: int, message: str) -> None:
    """Publish machine-readable progress outside stdout."""
    if not _PROGRESS_PATH:
        return
    _atomic_write_json(_PROGRESS_PATH, {
        "protocolVersion": PROTOCOL_VERSION,
        "executionId": _EXECUTION_ID,
        "stage": stage,
        "progress": max(0, min(100, int(progress))),
        "message": message,
        "updatedAt": pd.Timestamp.now().isoformat(),
    })


def _load_request(path: str) -> dict[str, Any]:
    with open(path, "r", encoding="utf-8") as handle:
        request = json.load(handle)
    version = request.get("protocolVersion")
    if version != PROTOCOL_VERSION:
        raise ValueError(f"unsupported protocolVersion: {version}")
    return request


def _engine():
    db_url = os.environ.get("_SMARTQUERY_DB_URL", "")
    if not db_url:
        raise ValueError("data source URL is missing")
    return create_engine(db_url)


def _quoted_table(engine, table_name: str) -> str:
    return engine.dialect.identifier_preparer.quote(table_name)


def _read_table(table_name: str, where_filter: str | None, limit: int | None = None) -> pd.DataFrame:
    engine = _engine()
    query = f"SELECT * FROM {_quoted_table(engine, table_name)}"
    if where_filter:
        query += " WHERE " + where_filter
    if limit is not None:
        query += f" LIMIT {max(1, int(limit))}"
    LOG.info("loading training/prediction data from table=%s", table_name)
    return pd.read_sql(text(query), engine)


def _normalise_feature_columns(value: Any) -> list[str]:
    if isinstance(value, list):
        return [str(item) for item in value]
    if isinstance(value, str):
        stripped = value.strip()
        if stripped.startswith("["):
            parsed = json.loads(stripped)
            return [str(item) for item in parsed]
        return [item.strip() for item in value.split(",") if item.strip()]
    raise ValueError("featureColumns must be a JSON array or comma-separated string")


def _column_strategy(config: dict[str, Any], column: str, numeric: bool) -> str:
    configured = (config.get("columnStrategies") or {}).get(column)
    if configured is None and column in (config.get("fillMissingColumns") or []):
        configured = config.get("fillMissingStrategy")
    if isinstance(configured, dict):
        configured = configured.get("strategy")
    strategy = str(configured or config.get("handleMissing") or "drop").lower()
    if strategy.startswith("fill_"):
        strategy = strategy[5:]
    if strategy in {"mean", "median"} and not numeric:
        return "mode"
    if strategy in {"drop", "none", ""}:
        # Row filtering is applied before splitting for configured drop semantics.
        # A learned fallback still protects online prediction from a missing value.
        return "median" if numeric else "mode"
    if strategy == "auto":
        return "median" if numeric else "mode"
    return strategy


class ConfigurablePreprocessor(BaseEstimator, TransformerMixin):
    """A cloneable sklearn transformer that learns every feature transform in fit()."""

    def __init__(self, config: dict[str, Any] | None = None):
        self.config = config

    def fit(self, X, y=None):
        frame = self._as_frame(X)
        self.input_columns_ = list(frame.columns)
        self.transform_states_ = {}
        engineered = self._engineer(frame, y, fit=True)
        self.engineered_columns_ = list(engineered.columns)
        self.numeric_columns_ = list(engineered.select_dtypes(include=[np.number, "bool"]).columns)
        self.categorical_columns_ = [c for c in engineered.columns if c not in self.numeric_columns_]
        self.fill_values_ = {}

        config = self.config or {}
        for column in self.engineered_columns_:
            numeric = column in self.numeric_columns_
            series = engineered[column]
            strategy = _column_strategy(config, column, numeric)
            non_null = series.dropna()
            if strategy == "mean" and numeric:
                value = float(pd.to_numeric(non_null, errors="coerce").mean()) if len(non_null) else 0.0
            elif strategy == "median" and numeric:
                value = float(pd.to_numeric(non_null, errors="coerce").median()) if len(non_null) else 0.0
            elif strategy in {"constant", "zero"}:
                value = 0.0 if numeric else str(config.get("missingConstant", "unknown"))
            else:
                modes = non_null.mode()
                value = modes.iloc[0] if len(modes) else (0.0 if numeric else "unknown")
            if numeric and (value is None or not np.isfinite(float(value))):
                value = 0.0
            self.fill_values_[column] = value

        filled = self._fill(engineered)
        encoding = str(config.get("encoding", "label")).lower()
        self.encoding_ = encoding
        self.category_values_ = {}
        for column in self.categorical_columns_:
            self.category_values_[column] = sorted(filled[column].astype(str).unique().tolist())

        encoded = self._encode(filled)
        self.output_columns_ = list(encoded.columns)
        scaling = str(config.get("scaling", "none")).lower()
        self.scaling_ = scaling
        self.scaler_ = None
        if scaling == "standard":
            self.scaler_ = StandardScaler().fit(encoded.astype(float))
        elif scaling == "minmax":
            self.scaler_ = MinMaxScaler().fit(encoded.astype(float))
        return self

    def transform(self, X):
        check_is_fitted(self, "output_columns_")
        frame = self._as_frame(X)
        missing = [column for column in self.input_columns_ if column not in frame.columns]
        if missing:
            raise ValueError(f"prediction input is missing feature columns: {missing}")
        frame = frame[self.input_columns_]
        engineered = self._engineer(frame, None, fit=False)
        for column in self.engineered_columns_:
            if column not in engineered.columns:
                engineered[column] = np.nan
        engineered = engineered[self.engineered_columns_]
        encoded = self._encode(self._fill(engineered))
        for column in self.output_columns_:
            if column not in encoded.columns:
                encoded[column] = 0.0
        encoded = encoded[self.output_columns_].astype(float)
        if self.scaler_ is not None:
            encoded = pd.DataFrame(
                self.scaler_.transform(encoded), index=encoded.index, columns=self.output_columns_
            )
        return encoded

    def get_feature_names_out(self, input_features=None):
        check_is_fitted(self, "output_columns_")
        return np.asarray(self.output_columns_, dtype=object)

    @staticmethod
    def _as_frame(X) -> pd.DataFrame:
        if isinstance(X, pd.DataFrame):
            return X.copy()
        return pd.DataFrame(X)

    def _fill(self, frame: pd.DataFrame) -> pd.DataFrame:
        result = frame.copy()
        for column, value in self.fill_values_.items():
            if column in self.numeric_columns_:
                result[column] = pd.to_numeric(result[column], errors="coerce").fillna(value)
            else:
                result[column] = result[column].astype("object").where(result[column].notna(), value).astype(str)
        return result

    def _encode(self, frame: pd.DataFrame) -> pd.DataFrame:
        output = pd.DataFrame(index=frame.index)
        for column in self.numeric_columns_:
            output[column] = pd.to_numeric(frame[column], errors="coerce").fillna(self.fill_values_[column])
        for column in self.categorical_columns_:
            values = frame[column].astype(str)
            categories = self.category_values_[column]
            if self.encoding_ == "onehot":
                for category in categories:
                    output[f"{column}={category}"] = (values == category).astype(float)
            else:
                mapping = {category: index for index, category in enumerate(categories)}
                output[column] = values.map(mapping).fillna(-1).astype(float)
        return output

    def _engineer(self, frame: pd.DataFrame, y, fit: bool) -> pd.DataFrame:
        result = frame.copy()
        transforms = (self.config or {}).get("transforms") or []
        for index, transform in enumerate(transforms):
            kind = str(transform.get("type", "")).lower()
            columns = [str(c) for c in transform.get("columns", []) if str(c) in result.columns]
            if not columns:
                continue
            state_key = str(index)
            if kind == "date_extract":
                for column in columns:
                    values = pd.to_datetime(result[column], errors="coerce")
                    result[f"{column}_year"] = values.dt.year
                    result[f"{column}_month"] = values.dt.month
                    result[f"{column}_day"] = values.dt.day
                    result[f"{column}_dow"] = values.dt.dayofweek
                    result = result.drop(columns=[column])
            elif kind == "log":
                for column in columns:
                    numeric = pd.to_numeric(result[column], errors="coerce")
                    result[column] = np.log1p(numeric.clip(lower=0))
            elif kind == "interaction":
                for left_index, left in enumerate(columns):
                    for right in columns[left_index + 1:]:
                        result[f"{left}_x_{right}"] = (
                            pd.to_numeric(result[left], errors="coerce").fillna(0)
                            * pd.to_numeric(result[right], errors="coerce").fillna(0)
                        )
            elif kind == "frequency_encode":
                state = self.transform_states_.get(state_key)
                if fit:
                    state = {
                        column: result[column].astype(str).value_counts(normalize=True).to_dict()
                        for column in columns
                    }
                    self.transform_states_[state_key] = state
                for column in columns:
                    result[column] = result[column].astype(str).map(state[column]).fillna(0.0)
            elif kind == "target_encode":
                if fit:
                    if y is None:
                        raise ValueError("target_encode requires a supervised target")
                    target = pd.Series(np.asarray(y), index=result.index, dtype=float)
                    global_mean = float(target.mean())
                    mappings = {
                        column: pd.DataFrame({"key": result[column].astype(str), "target": target})
                        .groupby("key")["target"].mean().to_dict()
                        for column in columns
                    }
                    state = {"global": global_mean, "mappings": mappings}
                    self.transform_states_[state_key] = state
                else:
                    state = self.transform_states_[state_key]
                for column in columns:
                    result[column] = (
                        result[column].astype(str).map(state["mappings"][column]).fillna(state["global"])
                    )
            elif kind == "binning":
                bins = max(2, int(transform.get("bins", 5)))
                if fit:
                    state = {}
                    for column in columns:
                        numeric = pd.to_numeric(result[column], errors="coerce").dropna()
                        edges = np.unique(np.quantile(numeric, np.linspace(0, 1, bins + 1))) if len(numeric) else np.array([])
                        state[column] = edges.tolist()
                    self.transform_states_[state_key] = state
                else:
                    state = self.transform_states_[state_key]
                for column in columns:
                    edges = np.asarray(state[column], dtype=float)
                    numeric = pd.to_numeric(result[column], errors="coerce")
                    result[column] = 0.0 if len(edges) < 2 else pd.cut(
                        numeric, bins=edges, labels=False, include_lowest=True, duplicates="drop"
                    )
            elif kind == "polynomial":
                degree = max(2, int(transform.get("degree", 2)))
                numeric = result[columns].apply(pd.to_numeric, errors="coerce").fillna(0.0)
                if fit:
                    polynomial = PolynomialFeatures(degree=degree, include_bias=False)
                    values = polynomial.fit_transform(numeric)
                    names = polynomial.get_feature_names_out(columns).tolist()
                    self.transform_states_[state_key] = {"transformer": polynomial, "names": names}
                else:
                    state = self.transform_states_[state_key]
                    polynomial = state["transformer"]
                    names = state["names"]
                    values = polynomial.transform(numeric)
                result = result.drop(columns=columns)
                for column_index, name in enumerate(names):
                    result[f"poly:{name}"] = values[:, column_index]

        for column in list(result.select_dtypes(include=["datetime", "datetimetz"]).columns):
            result[column] = result[column].astype("int64") / 1_000_000_000
        return result


def _filter_training_rows(frame: pd.DataFrame, feature_columns: list[str], target_column: str | None,
                          preprocessing: dict[str, Any]) -> pd.DataFrame:
    mask = pd.Series(True, index=frame.index)
    if target_column:
        mask &= frame[target_column].notna()
    default_missing = preprocessing.get("handleMissing")
    if default_missing is None:
        default_missing = "none" if preprocessing.get("fillMissingStrategy") else "drop"
    if str(default_missing).lower() == "drop":
        mask &= frame[feature_columns].notna().all(axis=1)
    for column, configured in (preprocessing.get("columnStrategies") or {}).items():
        strategy = configured.get("strategy") if isinstance(configured, dict) else configured
        if str(strategy).lower() == "drop" and column in frame.columns:
            mask &= frame[column].notna()
    dropped = int((~mask).sum())
    if dropped:
        LOG.info("row missing-value policy removed %s rows before data split", dropped)
    return frame.loc[mask].reset_index(drop=True)


def _build_estimator(request: dict[str, Any], X: pd.DataFrame, y: pd.Series | None, frame: pd.DataFrame):
    code = request.get("algorithmCode")
    if not code:
        raise ValueError(f"algorithm code is missing: {request.get('algorithmId')}")
    namespace = {
        "params": dict(request.get("hyperparameters") or {}),
        "X": X.copy(),
        "y": None if y is None else y.copy(),
        "df": frame.copy(),
        "_model_type": str(request.get("modelType", "")),
        "pd": pd,
        "np": np,
    }
    exec(compile(code, f"algorithm:{request.get('algorithmId')}", "exec"), namespace, namespace)
    estimator = namespace.get("clf")
    if estimator is None or not hasattr(estimator, "fit") or not hasattr(estimator, "predict"):
        raise ValueError("algorithm template must assign a sklearn-compatible estimator to variable 'clf'")
    return estimator


def _apply_target_pipeline(estimator, request: dict[str, Any]):
    target_config = request.get("targetPreprocessing") or {}
    if bool(target_config.get("smote")):
        raise ValueError("SMOTE is not enabled in the leakage-safe runtime; configure class_weight or a reviewed sampler pipeline")
    if bool(target_config.get("logTransform")):
        if "regression" not in str(request.get("modelType", "")):
            raise ValueError("target logTransform is only valid for regression")
        return TransformedTargetRegressor(
            regressor=estimator,
            func=_target_log1p,
            inverse_func=_target_expm1,
            check_inverse=False,
        )
    return estimator


def _split_supervised(X, y, frame, request):
    validation = request.get("validation") or {}
    test_size = float(validation.get("testSize", 0.2))
    random_state = int(validation.get("randomState", 42))
    mode = str(validation.get("mode", "none"))
    temporal_column = validation.get("temporalColumn")
    group_columns = _normalise_feature_columns(validation.get("groupColumns") or [])
    missing_groups = [column for column in group_columns if column not in frame.columns]
    if missing_groups:
        raise ValueError(f"group split columns are missing: {missing_groups}")
    groups = _group_values(frame, group_columns) if group_columns else None
    if mode == "temporal":
        if not temporal_column or temporal_column not in frame.columns:
            raise ValueError("temporal validation requires an existing temporalColumn")
        timestamps = pd.to_datetime(frame[temporal_column], errors="coerce")
        if timestamps.isna().any():
            raise ValueError("temporalColumn contains null or invalid timestamps")
        if groups is not None:
            group_order = pd.DataFrame({"group": groups, "time": timestamps}) \
                .groupby("group", sort=False)["time"].min().sort_values()
            split_at = int(len(group_order) * (1.0 - test_size))
            if split_at <= 0 or split_at >= len(group_order):
                raise ValueError("group-temporal split produced an empty train or test group set")
            train_groups = set(group_order.index[:split_at])
            train_mask = groups.isin(train_groups)
            test_mask = ~train_mask
            train_order = timestamps.loc[train_mask].sort_values().index
            test_order = timestamps.loc[test_mask].sort_values().index
            return X.loc[train_order], X.loc[test_order], y.loc[train_order], y.loc[test_order]
        order = timestamps.sort_values().index
        X_ordered, y_ordered = X.loc[order].reset_index(drop=True), y.loc[order].reset_index(drop=True)
        split_at = int(len(X_ordered) * (1.0 - test_size))
        if split_at <= 0 or split_at >= len(X_ordered):
            raise ValueError("temporal split produced an empty train or test set")
        return X_ordered.iloc[:split_at], X_ordered.iloc[split_at:], y_ordered.iloc[:split_at], y_ordered.iloc[split_at:]
    if groups is not None:
        splitter = GroupShuffleSplit(n_splits=1, test_size=test_size, random_state=random_state)
        train_index, test_index = next(splitter.split(X, y, groups))
        return X.iloc[train_index], X.iloc[test_index], y.iloc[train_index], y.iloc[test_index]
    stratify = None
    if "classification" in str(request.get("modelType", "")):
        counts = y.value_counts()
        if len(counts) > 1 and int(counts.min()) >= 2:
            stratify = y
    return train_test_split(
        X, y, test_size=test_size, random_state=random_state, stratify=stratify
    )


def _group_values(frame: pd.DataFrame, columns: list[str]) -> pd.Series:
    if not columns:
        return pd.Series(index=frame.index, dtype="object")
    return frame[columns].astype(str).agg("\x1f".join, axis=1)


def _positive_index(y: pd.Series, target_encoder, configured: Any) -> Any:
    classes = sorted(pd.Series(y).dropna().unique().tolist())
    if len(classes) != 2:
        return None
    if configured is not None and str(configured).strip():
        if target_encoder is not None:
            matches = np.where(target_encoder.classes_.astype(str) == str(configured))[0]
            if len(matches) != 1:
                raise ValueError(f"positiveClass is not present in target labels: {configured}")
            return int(matches[0])
        for value in classes:
            if str(value) == str(configured):
                return value.item() if isinstance(value, np.generic) else value
        raise ValueError(f"positiveClass is not present in target labels: {configured}")
    # A risk class is commonly the minority. Persist the resolved value so the
    # decision is explicit and reproducible instead of relying on label order.
    value = pd.Series(y).value_counts().idxmin()
    return value.item() if isinstance(value, np.generic) else value


def _supervised_metrics(model_type: str, y_train, train_prediction, y_test, test_prediction,
                        overfit_threshold: float, test_probabilities=None,
                        positive_index: Any = None) -> dict[str, Any]:
    result: dict[str, Any] = {}
    if "classification" in model_type:
        labels = sorted(pd.Series(y_test).dropna().unique().tolist())
        per_precision, per_recall, per_f1, per_support = (
            precision_score(y_test, test_prediction, labels=labels, average=None, zero_division=0),
            recall_score(y_test, test_prediction, labels=labels, average=None, zero_division=0),
            f1_score(y_test, test_prediction, labels=labels, average=None, zero_division=0),
            np.asarray([(pd.Series(y_test) == label).sum() for label in labels]),
        )
        result.update({
            "test_accuracy": round(float(accuracy_score(y_test, test_prediction)), 4),
            "test_balanced_accuracy": round(float(balanced_accuracy_score(y_test, test_prediction)), 4),
            "test_precision": round(float(precision_score(y_test, test_prediction, average="weighted", zero_division=0)), 4),
            "test_recall": round(float(recall_score(y_test, test_prediction, average="weighted", zero_division=0)), 4),
            "test_f1": round(float(f1_score(y_test, test_prediction, average="weighted", zero_division=0)), 4),
            "test_precision_macro": round(float(precision_score(y_test, test_prediction, average="macro", zero_division=0)), 4),
            "test_recall_macro": round(float(recall_score(y_test, test_prediction, average="macro", zero_division=0)), 4),
            "test_f1_macro": round(float(f1_score(y_test, test_prediction, average="macro", zero_division=0)), 4),
            "train_accuracy": round(float(accuracy_score(y_train, train_prediction)), 4),
            "confusion_matrix": confusion_matrix(y_test, test_prediction).tolist(),
            "per_class": {
                str(label): {
                    "precision": round(float(per_precision[index]), 4),
                    "recall": round(float(per_recall[index]), 4),
                    "f1": round(float(per_f1[index]), 4),
                    "support": int(per_support[index]),
                }
                for index, label in enumerate(labels)
            },
        })
        majority_rate = float(pd.Series(y_test).value_counts(normalize=True).max())
        result["majority_baseline_accuracy"] = round(majority_rate, 4)
        result["balanced_accuracy_lift_over_baseline"] = round(
            result["test_balanced_accuracy"] - 0.5, 4)
        if test_probabilities is not None:
            probabilities = np.asarray(test_probabilities)
            try:
                if positive_index is not None and probabilities.ndim == 2 and probabilities.shape[1] == 2:
                    classes_for_probability = sorted(pd.Series(y_train).dropna().unique().tolist())
                    positive_column = classes_for_probability.index(positive_index)
                    score = probabilities[:, positive_column]
                    binary_target = (np.asarray(y_test) == positive_index).astype(int)
                    result["positive_class"] = positive_index
                    result["positive_rate"] = round(float(binary_target.mean()), 6)
                    result["risk_precision"] = round(float(precision_score(
                        binary_target, np.asarray(test_prediction) == positive_index, zero_division=0)), 4)
                    result["risk_recall"] = round(float(recall_score(
                        binary_target, np.asarray(test_prediction) == positive_index, zero_division=0)), 4)
                    result["pr_auc"] = round(float(average_precision_score(binary_target, score)), 4)
                    result["roc_auc"] = round(float(roc_auc_score(binary_target, score)), 4)
                    fpr, tpr, _ = roc_curve(binary_target, score)
                    result["ks"] = round(float(np.max(tpr - fpr)), 4)
                    result["brier_score"] = round(float(brier_score_loss(binary_target, score)), 4)
                    top_count = max(1, int(np.ceil(len(score) * 0.1)))
                    top_rate = float(binary_target[np.argsort(score)[-top_count:]].mean())
                    base_rate = float(binary_target.mean())
                    result["lift_at_10pct"] = round(top_rate / base_rate, 4) if base_rate > 0 else None
                elif probabilities.ndim == 2 and probabilities.shape[1] > 2:
                    result["roc_auc_ovr_weighted"] = round(float(roc_auc_score(
                        y_test, probabilities, multi_class="ovr", average="weighted")), 4)
            except ValueError as metric_error:
                result["probability_metric_warning"] = str(metric_error)
        gap = round(abs(result["train_accuracy"] - result["test_accuracy"]), 4)
    else:
        mse = mean_squared_error(y_test, test_prediction)
        result.update({
            "test_mse": round(float(mse), 4),
            "test_rmse": round(float(np.sqrt(mse)), 4),
            "test_r2": round(float(r2_score(y_test, test_prediction)), 4),
            "test_mae": round(float(mean_absolute_error(y_test, test_prediction)), 4),
            "train_r2": round(float(r2_score(y_train, train_prediction)), 4),
        })
        gap = round(abs(result["train_r2"] - result["test_r2"]), 4)
    result["overfitting_gap"] = gap
    if gap > overfit_threshold:
        result["overfitting_warning"] = f"train/test metric gap {gap} exceeds {overfit_threshold}"
    return result


def _cross_validation(pipeline, X, y, request, groups=None) -> dict[str, Any]:
    validation = request.get("validation") or {}
    mode = str(validation.get("mode", "none"))
    if mode not in {"cv", "oos", "temporal"}:
        return {}
    requested_folds = int(validation.get("cvFolds", 5))
    random_state = int(validation.get("randomState", 42))
    model_type = str(request.get("modelType", ""))
    if mode == "temporal":
        temporal_units = int(pd.Series(groups).nunique()) if groups is not None else len(X)
        folds = min(requested_folds, temporal_units - 1)
        if folds < 2:
            raise ValueError("temporal cross-validation requires at least 3 rows or independent groups")
        splitter = TimeSeriesSplit(n_splits=folds)
    elif groups is not None:
        unique_groups = int(pd.Series(groups).nunique())
        folds = min(requested_folds, unique_groups)
        if folds < 2:
            raise ValueError("group cross-validation requires at least 2 independent groups")
        splitter = (StratifiedGroupKFold(n_splits=folds, shuffle=True, random_state=random_state)
                    if "classification" in model_type else GroupKFold(n_splits=folds))
    elif "classification" in model_type:
        folds = min(requested_folds, int(pd.Series(y).value_counts().min()))
        if folds < 2:
            raise ValueError("each class needs at least 2 rows for stratified cross-validation")
        splitter = StratifiedKFold(n_splits=folds, shuffle=True, random_state=random_state)
    else:
        folds = min(requested_folds, len(X))
        if folds < 2:
            raise ValueError("cross-validation requires at least 2 rows")
        splitter = KFold(n_splits=folds, shuffle=True, random_state=random_state)
    scoring = "f1_weighted" if "classification" in model_type else "r2"
    scores = []
    if groups is not None and mode == "temporal":
        group_series = pd.Series(np.asarray(groups), index=np.arange(len(X)))
        ordered_groups = group_series.drop_duplicates().to_numpy()
        split_iterator = (
            (np.flatnonzero(group_series.isin(ordered_groups[train_groups]).to_numpy()),
             np.flatnonzero(group_series.isin(ordered_groups[validation_groups]).to_numpy()))
            for train_groups, validation_groups in splitter.split(ordered_groups)
        )
    elif groups is not None:
        split_iterator = splitter.split(X, y, groups)
    else:
        split_iterator = splitter.split(X, y) if "classification" in model_type else splitter.split(X)
    windows = []
    for index, (train_index, validation_index) in enumerate(split_iterator, start=1):
        fold_pipeline = clone(pipeline)
        X_train = X.iloc[train_index]
        X_validation = X.iloc[validation_index]
        y_train = y.iloc[train_index]
        y_validation = y.iloc[validation_index]
        fold_pipeline.fit(X_train, y_train)
        prediction = fold_pipeline.predict(X_validation)
        score = (f1_score(y_validation, prediction, average="weighted", zero_division=0)
                 if scoring == "f1_weighted" else r2_score(y_validation, prediction))
        scores.append(float(score))
        windows.append({
            "fold": index,
            "train_size": len(train_index),
            "validation_size": len(validation_index),
            "score": round(float(score), 4),
        })
        _report_progress(
            "CROSS_VALIDATING",
            45 + round(index / folds * 25),
            f"正在执行第 {index}/{folds} 折交叉验证",
        )
    scores = np.asarray(scores)
    return {
        "cv_mean": round(float(scores.mean()), 4),
        "cv_std": round(float(scores.std()), 4),
        "cv_folds": folds,
        "cv_worst": round(float(scores.min()), 4),
        "cv_recent": round(float(scores[-1]), 4),
        "rolling_windows": windows if mode == "temporal" else [],
        "group_isolation": groups is not None,
        "requested_cv_folds": requested_folds,
        "preprocessing_fitted_per_fold": True,
    }


def _probability_predictions(estimator, X, positive_index, threshold):
    probabilities = estimator.predict_proba(X)
    classes = list(estimator.classes_)
    positive_column = classes.index(positive_index)
    negative_class = next(value for value in classes if value != positive_index)
    predictions = np.where(probabilities[:, positive_column] >= threshold,
                           positive_index, negative_class)
    return predictions, probabilities


def _select_threshold(y_true, probabilities, positive_index, policy: dict[str, Any]) -> float:
    mode = str(policy.get("mode", "default")).lower()
    if mode == "default":
        return 0.5
    if mode == "fixed":
        value = float(policy.get("value", 0.5))
        if not 0 < value < 1:
            raise ValueError("fixed decision threshold must be between 0 and 1")
        return value
    binary_target = (np.asarray(y_true) == positive_index).astype(int)
    candidates = np.unique(np.concatenate(([0.0, 0.5, 1.0], np.asarray(probabilities))))
    best_threshold, best_value = 0.5, -np.inf
    fp_cost = float(policy.get("falsePositiveCost", 1.0))
    fn_cost = float(policy.get("falseNegativeCost", 1.0))
    target_recall = float(policy.get("targetRecall", 0.8))
    for threshold in candidates:
        predicted = probabilities >= threshold
        tp = int(np.sum(predicted & (binary_target == 1)))
        fp = int(np.sum(predicted & (binary_target == 0)))
        fn = int(np.sum(~predicted & (binary_target == 1)))
        precision = tp / (tp + fp) if tp + fp else 0.0
        recall = tp / (tp + fn) if tp + fn else 0.0
        if mode == "min_cost":
            value = -(fp * fp_cost + fn * fn_cost)
        elif mode == "min_recall":
            value = precision if recall >= target_recall else -1.0
        elif mode == "max_f1":
            value = 2 * precision * recall / (precision + recall) if precision + recall else 0.0
        else:
            raise ValueError(f"unsupported threshold policy mode: {mode}")
        if value > best_value:
            best_threshold, best_value = float(threshold), value
    return round(max(0.000001, min(0.999999, best_threshold)), 6)


def _fit_classification_evaluation(base_pipeline, X_train, y_train, request, positive_index,
                                   groups=None):
    method = str(request.get("calibrationMethod") or "none").lower()
    threshold_policy = request.get("thresholdPolicy") or {"mode": "default"}
    needs_calibration_holdout = positive_index is not None and (
        method in {"sigmoid", "isotonic"} or str(threshold_policy.get("mode", "default")) != "default")
    if not needs_calibration_holdout:
        fitted = clone(base_pipeline).fit(X_train, y_train)
        return fitted, 0.5, {"method": "none", "threshold_source": "default"}

    if groups is not None:
        split = GroupShuffleSplit(n_splits=1, test_size=0.2, random_state=42)
        fit_index, calibration_index = next(split.split(X_train, y_train, groups))
        X_fit, X_calibration = X_train.iloc[fit_index], X_train.iloc[calibration_index]
        y_fit, y_calibration = y_train.iloc[fit_index], y_train.iloc[calibration_index]
    elif str((request.get("validation") or {}).get("mode")) == "temporal":
        split_at = int(len(X_train) * 0.8)
        X_fit, X_calibration = X_train.iloc[:split_at], X_train.iloc[split_at:]
        y_fit, y_calibration = y_train.iloc[:split_at], y_train.iloc[split_at:]
    else:
        stratify = y_train if int(pd.Series(y_train).value_counts().min()) >= 2 else None
        X_fit, X_calibration, y_fit, y_calibration = train_test_split(
            X_train, y_train, test_size=0.2, random_state=42, stratify=stratify)
    if method in {"sigmoid", "isotonic"}:
        minimum_class = int(pd.Series(y_fit).value_counts().min())
        folds = min(3, minimum_class)
        if folds < 2:
            raise ValueError("probability calibration requires at least 2 samples per class")
        fitted = CalibratedClassifierCV(estimator=clone(base_pipeline), method=method, cv=folds)
    elif method == "none":
        fitted = clone(base_pipeline)
    else:
        raise ValueError("calibrationMethod must be none, sigmoid or isotonic")
    fitted.fit(X_fit, y_fit)
    probabilities = fitted.predict_proba(X_calibration)
    positive_column = list(fitted.classes_).index(positive_index)
    threshold = _select_threshold(
        y_calibration, probabilities[:, positive_column], positive_index, threshold_policy)
    return fitted, threshold, {
        "method": method,
        "calibration_rows": len(X_calibration),
        "threshold_source": "calibration_holdout",
    }


def _fit_deployment_classifier(base_pipeline, X, y, method):
    if method in {"sigmoid", "isotonic"}:
        folds = min(3, int(pd.Series(y).value_counts().min()))
        if folds < 2:
            raise ValueError("probability calibration requires at least 2 samples per class")
        return CalibratedClassifierCV(
            estimator=clone(base_pipeline), method=method, cv=folds).fit(X, y)
    return clone(base_pipeline).fit(X, y)


def _inner_pipeline(estimator):
    if isinstance(estimator, Pipeline):
        return estimator
    calibrated = getattr(estimator, "calibrated_classifiers_", None)
    if calibrated:
        candidate = getattr(calibrated[0], "estimator", None)
        if isinstance(candidate, Pipeline):
            return candidate
    return None


def _feature_names(estimator) -> list[str]:
    pipeline = _inner_pipeline(estimator)
    if pipeline is None:
        return []
    return pipeline.named_steps["preprocessor"].get_feature_names_out().tolist()


def _feature_importance(estimator) -> dict[str, float]:
    pipeline = _inner_pipeline(estimator)
    if pipeline is None:
        return {}
    estimator = pipeline.named_steps["estimator"]
    if isinstance(estimator, TransformedTargetRegressor):
        estimator = estimator.regressor_
    names = pipeline.named_steps["preprocessor"].get_feature_names_out()
    values = None
    if hasattr(estimator, "feature_importances_"):
        values = np.asarray(estimator.feature_importances_)
    elif hasattr(estimator, "coef_"):
        coefficients = np.asarray(estimator.coef_)
        values = np.mean(np.abs(coefficients), axis=0) if coefficients.ndim > 1 else np.abs(coefficients)
    if values is None or len(values) != len(names):
        return {}
    return {str(name): round(float(value), 6) for name, value in zip(names, values)}


def _monitoring_baseline(frame: pd.DataFrame, feature_columns: list[str], estimator) -> dict[str, Any]:
    features: dict[str, Any] = {}
    for column in feature_columns:
        series = frame[column]
        entry: dict[str, Any] = {"missing_rate": round(float(series.isna().mean()), 6)}
        if pd.api.types.is_numeric_dtype(series):
            clean = pd.to_numeric(series, errors="coerce").dropna()
            edges = np.unique(clean.quantile(np.linspace(0, 1, 11)).to_numpy()) if len(clean) else np.asarray([])
            if len(edges) >= 2:
                # Keep the baseline valid JSON.  Overflow values are clipped back
                # into the outer bins when drift is calculated.
                margin = max(abs(float(edges[0])), abs(float(edges[-1])), 1.0) * 1e-9
                edges[0], edges[-1] = float(edges[0]) - margin, float(edges[-1]) + margin
                counts, _ = np.histogram(clean, bins=edges)
                entry.update({
                    "kind": "numeric",
                    "edges": edges.tolist(),
                    "proportions": (counts / max(1, counts.sum())).tolist(),
                })
            else:
                entry.update({"kind": "numeric", "edges": [], "proportions": []})
        else:
            proportions = series.fillna("__MISSING__").astype(str).value_counts(normalize=True)
            top = proportions.head(20)
            entry.update({
                "kind": "categorical",
                "proportions": {str(key): float(value) for key, value in top.items()},
                "other": float(max(0.0, 1.0 - top.sum())),
            })
        features[column] = entry
    baseline: dict[str, Any] = {
        "rows": len(frame),
        "created_at": pd.Timestamp.now().isoformat(),
        "features": features,
    }
    if hasattr(estimator, "predict_proba"):
        try:
            scores = np.max(estimator.predict_proba(frame[feature_columns]), axis=1)
            counts, edges = np.histogram(scores, bins=np.linspace(0, 1, 11))
            baseline["score_distribution"] = {
                "edges": edges.tolist(),
                "proportions": (counts / max(1, counts.sum())).tolist(),
            }
        except (ValueError, AttributeError):
            pass
    return baseline


def _psi(expected, actual, epsilon=1e-6) -> float:
    expected = np.clip(np.asarray(expected, dtype=float), epsilon, None)
    actual = np.clip(np.asarray(actual, dtype=float), epsilon, None)
    expected, actual = expected / expected.sum(), actual / actual.sum()
    return float(np.sum((actual - expected) * np.log(actual / expected)))


def _train(request: dict[str, Any]) -> dict[str, Any]:
    _report_progress("LOADING_DATA", 10, "正在加载训练数据")
    frame = _read_table(request["sourceTable"], request.get("inputFilter"))
    feature_columns = _normalise_feature_columns(request.get("featureColumns"))
    target_column = request.get("targetColumn") or None
    if not feature_columns:
        feature_columns = [column for column in frame.columns if column != target_column]
    required = feature_columns + ([target_column] if target_column else [])
    missing = [column for column in required if column not in frame.columns]
    if missing:
        raise ValueError(f"training table is missing columns: {missing}")

    _report_progress("PREPARING_DATA", 20, "正在检查特征并处理缺失行")
    preprocessing = request.get("preprocessing") or {}
    frame = _filter_training_rows(frame, feature_columns, target_column, preprocessing)
    if len(frame) < 2:
        raise ValueError("not enough rows remain after missing-value filtering")
    X = frame[feature_columns].copy()
    raw_y = frame[target_column].copy() if target_column else None
    model_type = str(request.get("modelType", ""))

    target_encoder = None
    y = raw_y
    if raw_y is not None and "classification" in model_type and not pd.api.types.is_numeric_dtype(raw_y):
        target_encoder = LabelEncoder()
        y = pd.Series(target_encoder.fit_transform(raw_y.astype(str)), index=raw_y.index)
    positive_index = (_positive_index(y, target_encoder, request.get("positiveClass"))
                      if y is not None and "classification" in model_type else None)

    estimator = _apply_target_pipeline(_build_estimator(request, X, y, frame), request)
    base_pipeline = Pipeline([
        ("preprocessor", ConfigurablePreprocessor(preprocessing)),
        ("estimator", estimator),
    ])
    metrics: dict[str, Any] = {}
    validation_metrics: dict[str, Any] = {}
    decision_threshold = 0.5
    calibration_info = {"method": "none", "threshold_source": "default"}

    if y is not None:
        _report_progress("SPLITTING_DATA", 30, "正在切分原始训练集和测试集")
        validation = request.get("validation") or {}
        if str(validation.get("mode")) == "oos":
            oos_table = validation.get("oosTable")
            if not oos_table:
                raise ValueError("true OOS validation requires an independent oosTable")
            if oos_table == request.get("sourceTable"):
                raise ValueError("OOS must use a different locked table from the development dataset")
            oos_frame = _read_table(str(oos_table), validation.get("oosFilter"))
            oos_missing = [column for column in required if column not in oos_frame.columns]
            if oos_missing:
                raise ValueError(f"OOS table is missing columns: {oos_missing}")
            oos_frame = _filter_training_rows(oos_frame, feature_columns, target_column, preprocessing)
            X_train, y_train = X, y
            X_test = oos_frame[feature_columns].copy()
            raw_oos_y = oos_frame[target_column]
            if target_encoder is not None:
                try:
                    y_test = pd.Series(target_encoder.transform(raw_oos_y.astype(str)), index=raw_oos_y.index)
                except ValueError as error:
                    raise ValueError(f"OOS target contains unseen labels: {error}") from error
            else:
                y_test = raw_oos_y
            validation_metrics.update({
                "oos_independent": True,
                "oos_table": str(oos_table),
                "oos_rows": len(oos_frame),
                "oos_snapshot_sha256": hashlib.sha256(
                    pd.util.hash_pandas_object(oos_frame[required], index=True).values.tobytes()).hexdigest(),
            })
        else:
            X_train, X_test, y_train, y_test = _split_supervised(X, y, frame, request)
        group_columns = _normalise_feature_columns(validation.get("groupColumns") or [])
        cv_groups = (_group_values(frame, group_columns).loc[X_train.index]
                     if group_columns else None)
        _report_progress("FITTING_HOLDOUT", 38, "正在拟合评估 Pipeline")
        if "classification" in model_type:
            evaluation_pipeline, decision_threshold, calibration_info = _fit_classification_evaluation(
                base_pipeline, X_train, y_train, request, positive_index, cv_groups)
            if positive_index is not None and hasattr(evaluation_pipeline, "predict_proba"):
                train_prediction, _ = _probability_predictions(
                    evaluation_pipeline, X_train, positive_index, decision_threshold)
                test_prediction, test_probabilities = _probability_predictions(
                    evaluation_pipeline, X_test, positive_index, decision_threshold)
            else:
                train_prediction = evaluation_pipeline.predict(X_train)
                test_prediction = evaluation_pipeline.predict(X_test)
                test_probabilities = (evaluation_pipeline.predict_proba(X_test)
                                      if hasattr(evaluation_pipeline, "predict_proba") else None)
        else:
            evaluation_pipeline = clone(base_pipeline).fit(X_train, y_train)
            train_prediction = evaluation_pipeline.predict(X_train)
            test_prediction = evaluation_pipeline.predict(X_test)
            test_probabilities = None
        metrics = _supervised_metrics(
            model_type, y_train, train_prediction, y_test, test_prediction,
            float(request.get("overfittingGapThreshold", 0.15)),
            test_probabilities, positive_index,
        )
        metrics["decision_threshold"] = decision_threshold
        metrics["calibration"] = calibration_info
        if target_encoder is not None:
            metrics["class_labels"] = target_encoder.classes_.tolist()
        # Keep the final holdout untouched by cross-validation. Pipeline cloning
        # makes every CV fold fit its own preprocessing state from fold-train only.
        validation_metrics.update(_cross_validation(base_pipeline, X_train, y_train, request, cv_groups))
        validation_metrics["holdout_train_size"] = len(X_train)
        validation_metrics["holdout_test_size"] = len(X_test)
        validation_metrics["decision_threshold"] = decision_threshold
        validation_metrics["calibration"] = calibration_info
        validation_metrics["holdout_locked"] = True
        # Evaluation remains untouched; deployment is independently refitted on all rows.
        _report_progress("FITTING_FINAL", 75, "正在使用全部训练数据拟合最终 Pipeline")
        if "classification" in model_type:
            deployment_pipeline = _fit_deployment_classifier(
                base_pipeline, X, y, str(request.get("calibrationMethod") or "none").lower())
        else:
            deployment_pipeline = clone(base_pipeline).fit(X, y)
    else:
        _report_progress("FITTING_FINAL", 60, "正在拟合无监督 Pipeline")
        deployment_pipeline = clone(base_pipeline).fit(X)
        transformed = deployment_pipeline.named_steps["preprocessor"].transform(X)
        estimator = deployment_pipeline.named_steps["estimator"]
        labels = estimator.labels_ if hasattr(estimator, "labels_") else estimator.predict(transformed)
        metrics["inertia"] = getattr(estimator, "inertia_", None)
        metrics["n_clusters"] = len(set(np.asarray(labels).tolist()))
        unique, counts = np.unique(labels, return_counts=True)
        metrics["cluster_sizes"] = {str(key): int(value) for key, value in zip(unique, counts)}
        if len(set(np.asarray(labels).tolist())) > 1 and len(X) > len(set(np.asarray(labels).tolist())):
            metrics["silhouette_score"] = round(float(silhouette_score(transformed, labels)), 4)

    if len(frame) < 100:
        metrics["sample_warning"] = f"sample size is only {len(frame)} rows"
    if y is not None and "classification" in model_type:
        counts = pd.Series(y).value_counts()
        if len(counts) >= 2 and counts.min() / counts.max() < 0.1:
            metrics["imbalance_warning"] = "class imbalance ratio is below 0.1"

    model_path = Path(request["artifactPath"])
    model_path.parent.mkdir(parents=True, exist_ok=True)
    _report_progress("SAVING_ARTIFACT", 90, "正在保存版本化模型制品")
    monitoring_baseline = _monitoring_baseline(frame, feature_columns, deployment_pipeline)
    bundle = {
        "protocol_version": PROTOCOL_VERSION,
        "artifact_schema_version": ARTIFACT_SCHEMA_VERSION,
        "training_execution_id": request.get("executionId"),
        "pipeline": deployment_pipeline,
        "target_encoder": target_encoder,
        "feature_columns": feature_columns,
        "model_type": model_type,
        "algorithm_id": request.get("algorithmId"),
        "positive_index": positive_index,
        "decision_threshold": decision_threshold,
        "calibration": calibration_info,
        "monitoring_baseline": monitoring_baseline,
        "sklearn_version": sklearn.__version__,
    }
    joblib.dump(bundle, model_path)
    checksum = hashlib.sha256(model_path.read_bytes()).hexdigest()
    response = {
        "status": "success",
        "protocolVersion": PROTOCOL_VERSION,
        "metrics": metrics,
        "validation": validation_metrics,
        "feature_importance": _feature_importance(deployment_pipeline),
        "model_path": str(model_path),
        "artifact_sha256": checksum,
        "artifact_schema_version": ARTIFACT_SCHEMA_VERSION,
        "sklearn_version": sklearn.__version__,
        "feature_names": _feature_names(deployment_pipeline),
        "feature_columns": feature_columns,
        "monitoring_baseline": monitoring_baseline,
    }
    output_table = request.get("outputTable")
    if output_table:
        output_values, output_probabilities = _prediction_values(bundle, frame)
        output_frame = frame.copy()
        output_frame["prediction"] = output_values
        if output_probabilities is not None:
            output_frame["prediction_proba"] = np.max(output_probabilities, axis=1)
        output_frame["predicted_at"] = pd.Timestamp.now()
        if_exists = "replace" if request.get("outputMode") == "replace" else "append"
        output_frame.to_sql(output_table, _engine(), if_exists=if_exists, index=False)
        response["output_table"] = output_table
        response["output_rows"] = len(output_frame)
    return response


def _load_bundle(model_path: str) -> dict[str, Any]:
    artifact = joblib.load(model_path)
    if not isinstance(artifact, dict) or "pipeline" not in artifact:
        raise ValueError("legacy model artifact has no sklearn Pipeline; retrain the model before prediction")
    if artifact.get("artifact_schema_version") != ARTIFACT_SCHEMA_VERSION:
        raise ValueError(
            f"legacy or unsupported artifact schema: {artifact.get('artifact_schema_version')}; "
            "retrain the model before prediction"
        )
    if artifact.get("protocol_version") != PROTOCOL_VERSION:
        raise ValueError(f"unsupported model artifact protocol: {artifact.get('protocol_version')}")
    return artifact


def _prediction_values(bundle: dict[str, Any], frame: pd.DataFrame):
    feature_columns = bundle["feature_columns"]
    missing = [column for column in feature_columns if column not in frame.columns]
    if missing:
        raise ValueError(f"prediction input is missing columns: {missing}")
    pipeline = bundle["pipeline"]
    probabilities = None
    if hasattr(pipeline, "predict_proba"):
        try:
            probabilities = pipeline.predict_proba(frame[feature_columns].copy())
        except (AttributeError, NotImplementedError):
            probabilities = None
    positive_index = bundle.get("positive_index")
    threshold = float(bundle.get("decision_threshold", 0.5))
    if probabilities is not None and positive_index is not None and probabilities.shape[1] == 2:
        classes = list(pipeline.classes_)
        positive_column = classes.index(positive_index)
        negative_class = next(value for value in classes if value != positive_index)
        values = np.where(probabilities[:, positive_column] >= threshold,
                          positive_index, negative_class)
    else:
        values = pipeline.predict(frame[feature_columns].copy())
    target_encoder = bundle.get("target_encoder")
    if target_encoder is not None:
        values = target_encoder.inverse_transform(np.asarray(values, dtype=int))
    return values, probabilities


def _predict(request: dict[str, Any]) -> dict[str, Any]:
    bundle = _load_bundle(request["modelPath"])
    mode = request.get("mode", "rows")
    if mode == "rows":
        frame = pd.DataFrame(request.get("inputRows") or [])
    elif mode == "batch":
        frame = _read_table(request["inputTable"], request.get("inputFilter"))
    else:
        raise ValueError(f"unsupported prediction mode: {mode}")
    if frame.empty:
        empty_response = {
            "status": "success", "protocolVersion": PROTOCOL_VERSION,
            "saved_rows": 0, "warning": "input contains no rows",
        }
        if mode == "rows":
            empty_response["predictions"] = []
        if request.get("resultTable"):
            empty_response["saved_to"] = request["resultTable"]
        return empty_response

    values, probabilities = _prediction_values(bundle, frame)
    response: dict[str, Any] = {
        "status": "success",
        "protocolVersion": PROTOCOL_VERSION,
    }
    if mode == "rows":
        response["predictions"] = np.asarray(values).tolist()
    if mode == "rows" and probabilities is not None:
        response["probabilities"] = np.asarray(probabilities).tolist()

    result_table = request.get("resultTable")
    if result_table:
        output = frame.copy()
        output["prediction"] = values
        if probabilities is not None:
            output["prediction_proba"] = np.max(probabilities, axis=1)
        output["predicted_at"] = pd.Timestamp.now()
        engine = _engine()
        output.to_sql(result_table, engine, if_exists="append", index=False)
        response["saved_to"] = result_table
        response["saved_rows"] = len(output)
        response["columns"] = list(output.columns)
    return response


def _drift(request: dict[str, Any]) -> dict[str, Any]:
    bundle = _load_bundle(request["modelPath"])
    baseline = bundle.get("monitoring_baseline") or {}
    feature_columns = bundle["feature_columns"]
    frame = _read_table(request["inputTable"], request.get("inputFilter"))
    missing = [column for column in feature_columns if column not in frame.columns]
    if missing:
        return {
            "status": "success", "protocolVersion": PROTOCOL_VERSION,
            "drift_status": "critical", "schema_missing_columns": missing,
            "rows": len(frame), "feature_drift": {},
        }
    feature_drift: dict[str, Any] = {}
    max_psi = 0.0
    for column, expected in (baseline.get("features") or {}).items():
        series = frame[column]
        if expected.get("kind") == "numeric" and len(expected.get("edges") or []) >= 2:
            edges = np.asarray(expected["edges"], dtype=float)
            values = pd.to_numeric(series, errors="coerce").dropna().to_numpy(dtype=float)
            if len(values):
                values = np.clip(values, edges[0], edges[-1])
            counts, _ = np.histogram(values, bins=edges)
            actual = counts / max(1, counts.sum())
            psi = _psi(expected["proportions"], actual)
        else:
            expected_values = expected.get("proportions") or {}
            actual_values = series.fillna("__MISSING__").astype(str).value_counts(normalize=True)
            keys = list(expected_values.keys())
            expected_vector = [expected_values[key] for key in keys] + [expected.get("other", 0.0)]
            actual_vector = [float(actual_values.get(key, 0.0)) for key in keys]
            actual_vector.append(float(max(0.0, 1.0 - sum(actual_vector))))
            psi = _psi(expected_vector, actual_vector)
        missing_delta = abs(float(series.isna().mean()) - float(expected.get("missing_rate", 0.0)))
        max_psi = max(max_psi, psi)
        feature_drift[column] = {
            "psi": round(float(psi), 6),
            "missing_rate_delta": round(missing_delta, 6),
            "status": "critical" if psi >= 0.25 else "warning" if psi >= 0.1 else "ok",
        }
    score_psi = None
    score_baseline = baseline.get("score_distribution")
    if score_baseline and hasattr(bundle["pipeline"], "predict_proba") and len(frame):
        scores = np.max(bundle["pipeline"].predict_proba(frame[feature_columns]), axis=1)
        edges = np.asarray(score_baseline["edges"], dtype=float)
        counts, _ = np.histogram(scores, bins=edges)
        score_psi = _psi(score_baseline["proportions"], counts / max(1, counts.sum()))
        max_psi = max(max_psi, score_psi)
    return {
        "status": "success",
        "protocolVersion": PROTOCOL_VERSION,
        "drift_status": "critical" if max_psi >= 0.25 else "warning" if max_psi >= 0.1 else "ok",
        "rows": len(frame),
        "max_psi": round(max_psi, 6),
        "score_psi": round(float(score_psi), 6) if score_psi is not None else None,
        "feature_drift": feature_drift,
        "checked_at": pd.Timestamp.now().isoformat(),
    }


def _frame_records(frame: pd.DataFrame, limit: int = 10) -> list[dict[str, Any]]:
    return json.loads(frame.head(limit).to_json(orient="records", date_format="iso"))


def _preview(request: dict[str, Any]) -> dict[str, Any]:
    node_type = str(request.get("nodeType", "data_source"))
    sample_limit = max(10, int(request.get("sampleRows", 100)))
    frame = _read_table(request["sourceTable"], request.get("inputFilter"), sample_limit)
    base = {
        "status": "success",
        "protocolVersion": PROTOCOL_VERSION,
        "nodeType": node_type,
    }
    if node_type == "data_source":
        base.update({
            "tableName": request["sourceTable"],
            "rowCount": len(frame),
            "columnCount": len(frame.columns),
            "nullSummary": {column: int(frame[column].isna().sum()) for column in frame.columns},
            "columns": [
                {
                    "name": column,
                    "dtype": str(frame[column].dtype),
                    "nulls": int(frame[column].isna().sum()),
                    "sample": None if frame[column].dropna().empty else frame[column].dropna().iloc[0],
                }
                for column in frame.columns
            ],
            "sampleRows": _frame_records(frame),
        })
        return base

    feature_columns = _normalise_feature_columns(request.get("featureColumns"))
    target_column = request.get("targetColumn") or None
    if not feature_columns:
        feature_columns = [column for column in frame.columns if column != target_column]
    missing = [column for column in feature_columns if column not in frame.columns]
    if missing:
        raise ValueError(f"preview table is missing feature columns: {missing}")
    preprocessing = request.get("preprocessing") or {}
    before_nulls = {column: int(frame[column].isna().sum()) for column in feature_columns}
    filtered = _filter_training_rows(frame, feature_columns, target_column, preprocessing)
    X = filtered[feature_columns].copy()
    raw_y = filtered[target_column].copy() if target_column else None
    model_type = str(request.get("modelType", ""))
    y = raw_y
    if raw_y is not None and "classification" in model_type and not pd.api.types.is_numeric_dtype(raw_y):
        y = pd.Series(LabelEncoder().fit_transform(raw_y.astype(str)), index=raw_y.index)
    preprocessor = ConfigurablePreprocessor(preprocessing)
    transformed = preprocessor.fit_transform(X, y)

    if node_type in {"preprocessing", "fill_missing"}:
        base.update({
            "beforeRows": len(frame),
            "rowCount": len(filtered),
            "columnCount": transformed.shape[1],
            "beforeNulls": before_nulls,
            "remainingNulls": {column: int(transformed[column].isna().sum()) for column in transformed.columns},
            "columnStrategies": preprocessing.get("columnStrategies") or {},
            "columns": [
                {"name": column, "dtype": str(transformed[column].dtype), "nulls": int(transformed[column].isna().sum())}
                for column in transformed.columns
            ],
            "sampleRows": _frame_records(transformed),
        })
        return base

    if node_type == "feature_engineering":
        target_distribution = raw_y.astype(str).value_counts().to_dict() if raw_y is not None else {}
        base.update({
            "featureCount": transformed.shape[1],
            "featureColumns": transformed.columns.tolist(),
            "targetColumn": target_column,
            "sampleShape": [transformed.shape[0], transformed.shape[1]],
            "targetDistribution": target_distribution,
            "featureStats": [
                {
                    "name": column,
                    "dtype": str(transformed[column].dtype),
                    "nullPct": round(float(transformed[column].isna().mean() * 100), 2),
                }
                for column in transformed.columns
            ],
            "sampleRows": _frame_records(transformed),
        })
        return base

    estimator = _apply_target_pipeline(_build_estimator(request, X, y, filtered), request)
    pipeline = Pipeline([
        ("preprocessor", ConfigurablePreprocessor(preprocessing)),
        ("estimator", estimator),
    ])
    if y is not None:
        X_train, X_test, y_train, y_test = _split_supervised(X, y, filtered, request)
        pipeline.fit(X_train, y_train)
        metrics = _supervised_metrics(
            model_type, y_train, pipeline.predict(X_train), y_test, pipeline.predict(X_test),
            float(request.get("overfittingGapThreshold", 0.15)),
        )
        train_size, test_size = len(X_train), len(X_test)
    else:
        pipeline.fit(X)
        fitted_estimator = pipeline.named_steps["estimator"]
        metrics = {"inertia": getattr(fitted_estimator, "inertia_", None)}
        train_size, test_size = len(X), 0

    if node_type in {"training", "evaluation"}:
        base.update({
            "trainSize": train_size,
            "testSize": test_size,
            "featureCount": len(pipeline.named_steps["preprocessor"].get_feature_names_out()),
            "metrics": metrics,
            "featureImportance": _feature_importance(pipeline),
        })
        return base

    predictions = pipeline.predict(X)
    output_frame = filtered.copy()
    output_frame["prediction"] = predictions
    base.update({
        "totalRows": len(output_frame),
        "outputTable": request.get("outputTable") or "",
        "columns": list(output_frame.columns),
        "sampleRows": _frame_records(output_frame),
        "predictionDistribution": pd.Series(predictions).value_counts().to_dict(),
    })
    return base


def main() -> int:
    global _PROGRESS_PATH, _EXECUTION_ID
    logging.basicConfig(
        level=logging.INFO,
        stream=sys.stdout,
        format="%(asctime)s %(levelname)s %(name)s - %(message)s",
    )
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["train", "predict", "preview", "drift"])
    parser.add_argument("--request", required=True)
    parser.add_argument("--result", required=True)
    parser.add_argument("--progress")
    args = parser.parse_args()
    try:
        request = _load_request(args.request)
        _PROGRESS_PATH = args.progress
        _EXECUTION_ID = request.get("executionId")
        _report_progress("VALIDATING", 5, "正在验证训练请求")
        LOG.info("starting action=%s protocol=%s", args.action, PROTOCOL_VERSION)
        if args.action == "train":
            response = _train(request)
        elif args.action == "predict":
            response = _predict(request)
        elif args.action == "drift":
            response = _drift(request)
        else:
            response = _preview(request)
        _atomic_write_json(args.result, response)
        _report_progress("COMPLETED", 100, "训练完成" if args.action == "train" else "执行完成")
        LOG.info("completed action=%s status=success", args.action)
        return 0
    except Exception as error:
        LOG.error("action=%s failed: %s", args.action, error)
        LOG.debug("runtime traceback:\n%s", traceback.format_exc())
        _atomic_write_json(args.result, {
            "status": "error",
            "protocolVersion": PROTOCOL_VERSION,
            "error": str(error),
            "errorType": error.__class__.__name__,
        })
        _report_progress("FAILED", 100, str(error))
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
