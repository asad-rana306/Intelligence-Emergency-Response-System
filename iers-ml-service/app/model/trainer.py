import os

import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.metrics import classification_report, confusion_matrix
from sklearn.model_selection import train_test_split

from app.model.dataset_generator import FEATURE_COLUMNS, G_MAX, SEVERITY_MAP, generate_dataset

ARTIFACT_DIR = os.path.join(os.path.dirname(__file__), "artifacts")
MODEL_PATH = os.path.join(ARTIFACT_DIR, "crash_model.pkl")
META_PATH = os.path.join(ARTIFACT_DIR, "model_metadata.pkl")

# If the chance of "real crash" is at least this, we call it severe. Below 0.5 on purpose:
# a missed crash is worse than a false alarm the driver can cancel in 10 seconds.
SEVERE_THRESHOLD = 0.40


def train_model(verbose: bool = True) -> dict:
    df = generate_dataset()
    X = df[FEATURE_COLUMNS]
    y = df["priority_score"]
    X_train, X_test, y_train, y_test = train_test_split(X, y, test_size=0.2, random_state=42, stratify=y)

    model = RandomForestClassifier(
        n_estimators=250,
        max_depth=18,
        min_samples_split=5,
        min_samples_leaf=2,
        class_weight="balanced",
        n_jobs=-1,
        random_state=42,
    )
    model.fit(X_train, y_train)

    pred = model.predict(X_test)
    acc = float((pred == y_test).mean())

    # The decision that matters: send an ambulance or not
    proba = model.predict_proba(X_test)
    severe_cols = [i for i, c in enumerate(model.classes_) if c >= 3]
    p_severe = proba[:, severe_cols].sum(axis=1)
    pred_severe = p_severe >= SEVERE_THRESHOLD
    true_severe = (y_test >= 3).to_numpy()
    tp = int((pred_severe & true_severe).sum())
    fn = int((~pred_severe & true_severe).sum())
    fp = int((pred_severe & ~true_severe).sum())
    recall = tp / max(tp + fn, 1)
    precision = tp / max(tp + fp, 1)

    if verbose:
        print(f"Rows: {len(df)}   g range: 0 to {G_MAX}")
        print(f"5-level accuracy: {acc:.3f}")
        print(f"Dispatch decision at threshold {SEVERE_THRESHOLD}: recall {recall:.3f}, precision {precision:.3f}  (missed crashes: {fn}, false dispatches: {fp})")
        print(classification_report(y_test, pred, target_names=[SEVERITY_MAP[c] for c in model.classes_]))
        print(confusion_matrix(y_test, pred))

    os.makedirs(ARTIFACT_DIR, exist_ok=True)
    joblib.dump(model, MODEL_PATH)
    metadata = {
        "feature_columns": FEATURE_COLUMNS,
        "severe_threshold": SEVERE_THRESHOLD,
        "g_max": G_MAX,
        "accuracy_5_level": acc,
        "severe_recall": recall,
        "severe_precision": precision,
    }
    joblib.dump(metadata, META_PATH)
    return metadata
