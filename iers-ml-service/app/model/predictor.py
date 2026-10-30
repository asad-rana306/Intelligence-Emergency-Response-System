import os

import joblib
import numpy as np
import pandas as pd

from app.model.dataset_generator import (
    FEATURE_COLUMNS, G_MAX, GYRO_MAX, ROLL_MAX, SEVERITY_MAP, SPEED_MAX, derive,
)
from app.model.trainer import META_PATH, MODEL_PATH, SEVERE_THRESHOLD, train_model


def _num(d, *keys, default=0.0):
    """Read the first matching numeric key from the sensorData dict."""
    for k in keys:
        if d and k in d and d[k] is not None:
            try:
                return float(d[k])
            except (TypeError, ValueError):
                pass
    return default


class CrashPredictor:
    def __init__(self):
        self.model = None
        self.threshold = SEVERE_THRESHOLD

    @property
    def is_loaded(self) -> bool:
        return self.model is not None

    def load(self):
        if not (os.path.exists(MODEL_PATH) and os.path.exists(META_PATH)):
            train_model(verbose=False)
        self.model = joblib.load(MODEL_PATH)
        meta = joblib.load(META_PATH)
        self.threshold = meta.get("severe_threshold", SEVERE_THRESHOLD)

    def predict(self, g_force, rollover_angle, speed, sensor_data=None) -> dict:
        if self.model is None:
            self.load()

        # Clamp to what the real sensor can report
        g = float(np.clip(abs(g_force), 0.0, G_MAX))
        roll = float(np.clip(abs(rollover_angle), 0.0, ROLL_MAX))
        spd = float(np.clip(abs(speed), 0.0, SPEED_MAX))

        # Names the device, the Flutter app and the older React app use are all accepted.
        ax = _num(sensor_data, "accelX", "accel_x", "accelerometerX", "accelerometer_x")
        ay = _num(sensor_data, "accelY", "accel_y", "accelerometerY", "accelerometer_y")
        az = _num(sensor_data, "accelZ", "accel_z", "accelerometerZ", "accelerometer_z", default=1.0)
        if abs(az) > 4:  # looks like m/s^2 (gravity = 9.8), not g
            ax, ay, az = ax / 9.80665, ay / 9.80665, az / 9.80665
        ax, ay, az = (float(np.clip(v, -G_MAX, G_MAX)) for v in (ax, ay, az))
        gr = float(np.clip(_num(sensor_data, "gyroRoll", "gyro_roll", "gyroscopeRoll", "gyroscope_roll"),
                           -GYRO_MAX, GYRO_MAX))
        gp = float(np.clip(_num(sensor_data, "gyroPitch", "gyro_pitch", "gyroscopePitch", "gyroscope_pitch"),
                           -GYRO_MAX, GYRO_MAX))

        row = pd.DataFrame([{
            "g_force": g, "rollover_angle": roll, "speed": spd,
            "accel_x": ax, "accel_y": ay, "accel_z": az,
            "gyro_roll": gr, "gyro_pitch": gp,
        }])
        X = derive(row)[FEATURE_COLUMNS]

        proba = self.model.predict_proba(X)[0]
        classes = list(self.model.classes_)
        p_severe = float(sum(p for c, p in zip(classes, proba) if c >= 3))
        best = int(classes[int(np.argmax(proba))])

        severe = p_severe >= self.threshold
        if severe:
            priority = best if best >= 3 else 3
        else:
            priority = best if best < 3 else 2
        return {"severe": bool(severe), "priorityScore": int(priority), "severity": SEVERITY_MAP[priority]}


predictor = CrashPredictor()
