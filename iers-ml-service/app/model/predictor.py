"""
Crash Severity Predictor

Loads the trained Random Forest model and classifies incoming sensor payloads.
Auto-trains the model on first startup if no saved model exists.
"""

import os
import numpy as np
import joblib
import logging
from app.model.dataset_generator import FEATURE_COLUMNS, SEVERITY_MAP
from app.model.trainer import train_model, MODEL_PATH, METADATA_PATH

logger = logging.getLogger(__name__)


class CrashPredictor:
    """
    Stateful predictor that holds the loaded model in memory.
    Initialized once at FastAPI startup via the lifespan handler.
    """

    def __init__(self):
        self.model = None
        self.metadata = None
        self._loaded = False

    def load(self):
        """Load the model from disk, or train first if it doesn't exist."""
        if not os.path.exists(MODEL_PATH):
            logger.info("No trained model found — running training pipeline...")
            train_model()

        logger.info(f"Loading model from {MODEL_PATH}")
        self.model = joblib.load(MODEL_PATH)
        self.metadata = joblib.load(METADATA_PATH) if os.path.exists(METADATA_PATH) else {}
        self._loaded = True
        logger.info(f"Model loaded — accuracy: {self.metadata.get('accuracy', 'N/A')}")

    @property
    def is_loaded(self) -> bool:
        return self._loaded

    def predict(self, g_force: float, rollover_angle: float, speed: float,
                sensor_data: dict | None = None) -> dict:
        """
        Classify a crash event.

        Args:
            g_force:        Peak resultant G-force
            rollover_angle: Maximum roll angle (degrees)
            speed:          Vehicle speed at impact (km/h)
            sensor_data:    Optional raw sensor blob with accel/gyro axes

        Returns:
            {"severe": bool, "priorityScore": int, "severity": str}
        """
        if not self._loaded:
            raise RuntimeError("Model not loaded — call load() first")

        # ── Extract axis data from sensorData or use defaults ──
        accel_x = 0.0
        accel_y = 0.0
        accel_z = 1.0  # Normal gravity
        gyro_roll = 0.0
        gyro_pitch = 0.0

        if sensor_data:
            accel_x = float(sensor_data.get("accelerometerX", 0.0))
            accel_y = float(sensor_data.get("accelerometerY", 0.0))
            accel_z = float(sensor_data.get("accelerometerZ", 1.0))
            gyro_roll = float(sensor_data.get("gyroscopeRoll", 0.0))
            gyro_pitch = float(sensor_data.get("gyroscopePitch", 0.0))

        # ── Compute derived features (must match training order) ──
        impact_energy = 0.5 * (speed ** 2) * g_force / 10000
        rollover_risk = rollover_angle / 90.0
        speed_gforce = speed * g_force / 100
        lateral_ratio = abs(accel_y) / (g_force + 0.01)
        is_stationary = 1 if speed < 5 else 0

        # ── Assemble feature vector (same order as FEATURE_COLUMNS) ──
        features = np.array([[
            g_force, rollover_angle, speed,
            accel_x, accel_y, accel_z,
            gyro_roll, gyro_pitch,
            impact_energy, rollover_risk, speed_gforce,
            lateral_ratio, is_stationary,
        ]])

        # ── Predict ──
        priority_score = int(self.model.predict(features)[0])
        severity = SEVERITY_MAP.get(priority_score, "UNKNOWN")
        is_severe = priority_score >= 3

        logger.info(
            f"Prediction: gForce={g_force}, rollover={rollover_angle}°, "
            f"speed={speed}km/h → priority={priority_score} ({severity}), "
            f"severe={is_severe}"
        )

        return {
            "severe": is_severe,
            "priorityScore": priority_score,
            "severity": severity,
        }
