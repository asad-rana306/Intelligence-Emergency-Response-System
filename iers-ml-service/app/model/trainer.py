"""
IERS Crash Severity Model Trainer

Trains a Random Forest classifier on synthetically generated crash data.
The model learns to distinguish 15 distinct crash scenarios across 5 priority levels.

Usage:
    python -m app.model.trainer          (from project root)
    python train.py                      (convenience entry point)
"""

import os
import joblib
import numpy as np
from sklearn.ensemble import RandomForestClassifier
from sklearn.model_selection import train_test_split
from sklearn.metrics import classification_report, accuracy_score
from app.model.dataset_generator import generate_dataset, FEATURE_COLUMNS, SEVERITY_MAP


MODEL_DIR = os.path.join(os.path.dirname(__file__), "artifacts")
MODEL_PATH = os.path.join(MODEL_DIR, "crash_model.pkl")
METADATA_PATH = os.path.join(MODEL_DIR, "model_metadata.pkl")


def train_model(n_samples: int = 10000, seed: int = 42) -> dict:
    """
    Full training pipeline:
    1. Generate synthetic dataset
    2. Feature selection
    3. Train/test split (80/20)
    4. Train Random Forest (200 trees, max_depth=15)
    5. Evaluate and print classification report
    6. Save model + metadata to disk

    Returns metadata dict with accuracy and sample count.
    """
    print("=" * 60)
    print("  IERS Crash Severity Model — Training Pipeline")
    print("=" * 60)

    # ── 1. Generate dataset ──
    print(f"\n[1/5] Generating {n_samples} synthetic crash samples...")
    df = generate_dataset(n_samples=n_samples, seed=seed)

    print(f"  Class distribution:")
    for score, count in df["priority_score"].value_counts().sort_index().items():
        pct = count / len(df) * 100
        print(f"    {score} ({SEVERITY_MAP[score]:12s}): {count:5d} samples ({pct:.1f}%)")

    # ── 2. Feature selection ──
    X = df[FEATURE_COLUMNS].values
    y = df["priority_score"].values

    # ── 3. Train/test split ──
    print(f"\n[2/5] Splitting: 80% train / 20% test...")
    X_train, X_test, y_train, y_test = train_test_split(
        X, y, test_size=0.2, random_state=seed, stratify=y
    )
    print(f"  Train: {X_train.shape[0]} samples")
    print(f"  Test:  {X_test.shape[0]} samples")

    # ── 4. Train ──
    print(f"\n[3/5] Training Random Forest (200 trees, max_depth=15)...")
    model = RandomForestClassifier(
        n_estimators=200,
        max_depth=15,
        min_samples_split=5,
        min_samples_leaf=2,
        class_weight="balanced",   # Handle any class imbalance
        random_state=seed,
        n_jobs=-1,                 # Use all CPU cores
    )
    model.fit(X_train, y_train)

    # ── 5. Evaluate ──
    print(f"\n[4/5] Evaluating on test set...")
    y_pred = model.predict(X_test)
    accuracy = accuracy_score(y_test, y_pred)

    target_names = [f"{s} ({SEVERITY_MAP[s]})" for s in sorted(SEVERITY_MAP.keys())]
    report = classification_report(y_test, y_pred, target_names=target_names)
    print(f"\n  Accuracy: {accuracy:.4f} ({accuracy * 100:.1f}%)\n")
    print(report)

    # Feature importance
    print("  Top features by importance:")
    importances = list(zip(FEATURE_COLUMNS, model.feature_importances_))
    importances.sort(key=lambda x: x[1], reverse=True)
    for name, imp in importances[:8]:
        print(f"    {name:20s} {imp:.4f}")

    # ── 6. Save ──
    print(f"\n[5/5] Saving model to {MODEL_PATH}...")
    os.makedirs(MODEL_DIR, exist_ok=True)

    joblib.dump(model, MODEL_PATH)

    metadata = {
        "accuracy": float(accuracy),
        "n_samples": n_samples,
        "n_features": len(FEATURE_COLUMNS),
        "feature_columns": FEATURE_COLUMNS,
        "severity_map": SEVERITY_MAP,
        "model_type": "RandomForestClassifier",
        "n_estimators": 200,
        "max_depth": 15,
    }
    joblib.dump(metadata, METADATA_PATH)

    print(f"  Model saved: {os.path.getsize(MODEL_PATH) / 1024:.0f} KB")
    print(f"\n{'=' * 60}")
    print(f"  Training complete — accuracy: {accuracy * 100:.1f}%")
    print(f"{'=' * 60}\n")

    return metadata


if __name__ == "__main__":
    train_model()
