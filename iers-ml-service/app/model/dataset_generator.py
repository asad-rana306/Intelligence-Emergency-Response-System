"""Synthetic crash dataset sized for the real hardware.

The MPU6050 accelerometer on the IoT device is set to a +/-16g range, so every
g value here is capped at 16 (severe crashes pile up at exactly 16, just like
the real sensor would saturate).

Inputs the device can really measure:
  g_force        peak acceleration in g            (0 to 16)
  rollover_angle tilt from upright, degrees        (0 to 180)
  speed          GPS speed in km/h
  accel_x/y/z    axes in g (z is about 1.0 at rest)
  gyro_roll/pitch  rotation speed in deg/s

Priority labels: 1 FALSE_ALARM, 2 LOW, 3 MODERATE, 4 HIGH, 5 CRITICAL.
Priority 3 and above means "send an ambulance".
"""
import numpy as np
import pandas as pd

G_MAX = 16.0
ROLL_MAX = 180.0
SPEED_MAX = 250.0
GYRO_MAX = 2000.0

SEVERITY_MAP = {1: "FALSE_ALARM", 2: "LOW", 3: "MODERATE", 4: "HIGH", 5: "CRITICAL"}

FEATURE_COLUMNS = [
    "g_force", "rollover_angle", "speed",
    "accel_x", "accel_y", "accel_z", "gyro_roll", "gyro_pitch",
    "impact_energy", "rollover_risk", "speed_gforce", "lateral_ratio", "is_stationary",
]

# name: (label, g range, speed range, roll range, axis direction (x, y, z), gyro spread deg/s)
SCENARIOS = {
    # ---- things that must NOT send an ambulance ----
    "speed_bump":       (1, (2, 5),    (15, 40),  (0, 5),    (0.2, 0.1, 0.97), 40),
    "hard_braking":     (1, (1.2, 3),  (30, 80),  (0, 4),    (0.97, 0.1, 0.2), 15),
    "pothole":          (1, (3, 7),    (20, 60),  (0, 6),    (0.15, 0.2, 0.96), 50),
    "device_knock":     (1, (3, 9),    (0, 4),    (0, 45),   (0.5, 0.5, 0.7), 250),   # device bumped, dropped or handled
    "door_slam":        (1, (2, 6),    (0, 2),    (0, 3),    (0.1, 0.95, 0.2), 20),
    "parking_bump":     (1, (3, 7),    (1, 8),    (0, 3),    (0.9, 0.2, 0.2), 20),
    # ---- minor ----
    "fender_bender":    (2, (6, 10),   (10, 25),  (0, 6),    (0.9, 0.3, 0.2), 60),
    "minor_sideswipe":  (2, (5, 9),    (25, 50),  (0, 8),    (0.3, 0.9, 0.2), 80),
    # ---- real crashes: send an ambulance ----
    "urban_collision":  (3, (9, 14),   (25, 50),  (0, 12),   (0.85, 0.4, 0.2), 120),
    "intersection_hit": (3, (10, 15),  (30, 55),  (0, 15),   (0.3, 0.9, 0.2), 150),
    "stationary_hit":   (3, (11, 16),  (0, 5),    (0, 10),   (0.95, 0.2, 0.1), 100),  # stopped at a light, hit from behind
    "highway_crash":    (4, (12, 16),  (70, 120), (0, 12),   (0.95, 0.2, 0.1), 150),
    "side_impact":      (4, (11, 16),  (40, 80),  (5, 25),   (0.2, 0.95, 0.2), 200),
    "rollover_low":     (4, (8, 14),   (30, 60),  (45, 90),  (0.4, 0.6, 0.6), 400),
    "rollover_high":    (5, (10, 16),  (40, 90),  (90, 180), (0.4, 0.5, 0.7), 800),
    "head_on":          (5, (14, 16),  (50, 110), (0, 20),   (0.97, 0.15, 0.1), 250),
}

AXIS_DROPOUT = 0.30  # share of rows with no axis data, so the model still works when sensorData is missing


def derive(df: pd.DataFrame) -> pd.DataFrame:
    """Add the engineered features. Used by training AND prediction so they can never drift apart."""
    df = df.copy()
    df["impact_energy"] = 0.5 * (df["speed"] ** 2) * df["g_force"] / 10000.0
    df["rollover_risk"] = df["rollover_angle"] / 90.0
    df["speed_gforce"] = df["speed"] * df["g_force"] / 100.0
    df["lateral_ratio"] = df["accel_y"].abs() / (df["g_force"] + 0.01)
    df["is_stationary"] = (df["speed"] < 5).astype(float)
    return df


def generate_dataset(n_samples: int = 12000, seed: int = 42) -> pd.DataFrame:
    rng = np.random.default_rng(seed)
    per_scenario = n_samples // len(SCENARIOS)
    rows = []

    for name, (label, g_rng, sp_rng, roll_rng, direction, gyro_spread) in SCENARIOS.items():
        n = per_scenario
        g = np.clip(rng.uniform(*g_rng, n) + rng.normal(0, 0.4, n), 0.5, G_MAX)
        speed = np.clip(rng.uniform(*sp_rng, n) + rng.normal(0, 1.5, n), 0, SPEED_MAX)
        roll = np.clip(rng.uniform(*roll_rng, n) + rng.normal(0, 1.0, n), 0, ROLL_MAX)

        dx, dy, dz = direction
        sign = rng.choice([-1.0, 1.0], n)
        ax = np.clip(sign * g * dx * rng.uniform(0.7, 1.0, n), -G_MAX, G_MAX)
        ay = np.clip(rng.choice([-1.0, 1.0], n) * g * dy * rng.uniform(0.7, 1.0, n), -G_MAX, G_MAX)
        az = np.clip(1.0 + g * dz * rng.uniform(0.0, 0.6, n), -G_MAX, G_MAX)
        gyro_roll = np.clip(rng.normal(0, gyro_spread, n), -GYRO_MAX, GYRO_MAX)
        gyro_pitch = np.clip(rng.normal(0, gyro_spread * 0.5, n), -GYRO_MAX, GYRO_MAX)

        # some rows arrive without axis data
        no_axes = rng.random(n) < AXIS_DROPOUT
        ax[no_axes], ay[no_axes], az[no_axes] = 0.0, 0.0, 1.0
        gyro_roll[no_axes], gyro_pitch[no_axes] = 0.0, 0.0

        rows.append(pd.DataFrame({
            "scenario": name,
            "g_force": g, "rollover_angle": roll, "speed": speed,
            "accel_x": ax, "accel_y": ay, "accel_z": az,
            "gyro_roll": gyro_roll, "gyro_pitch": gyro_pitch,
            "priority_score": label,
        }))

    df = pd.concat(rows, ignore_index=True)
    df = derive(df)
    return df.sample(frac=1.0, random_state=seed).reset_index(drop=True)
