"""
Synthetic Crash Dataset Generator for IERS

Generates realistic labeled data based on real-world crash physics:

    G-FORCE REFERENCE (resultant acceleration):
    ─────────────────────────────────────────────
    1.0g   — Normal driving (gravity only)
    1.5-2g — Hard braking or speed bump
    2-4g   — Minor fender bender, pothole
    4-8g   — Moderate collision (urban speeds)
    8-15g  — Severe crash (highway speeds)
    15-40g — Fatal / near-fatal (race car level)

    ROLLOVER REFERENCE:
    ─────────────────────────────────────────────
    0-5°   — Normal tilt (turning, road camber)
    5-15°  — Hard swerve
    15-45° — Partial rollover / severe lean
    45-90° — On its side
    90°+   — Full rollover / inverted

    PRIORITY SCORE:
    ─────────────────────────────────────────────
    1 — FALSE_ALARM  (speed bump, phone drop, door slam)
    2 — LOW          (minor fender bender, hard brake)
    3 — MODERATE     (real crash, injuries possible)
    4 — HIGH         (severe crash, injuries likely)
    5 — CRITICAL     (life-threatening, multi-vehicle or rollover at speed)
"""

import numpy as np
import pandas as pd


def generate_dataset(n_samples: int = 10000, seed: int = 42) -> pd.DataFrame:
    """
    Generate a labeled crash dataset with realistic distributions.
    Each row represents one sensor event from the car's crash detection hardware.
    """
    rng = np.random.default_rng(seed)
    records = []

    # Allocation: ~40% false alarms, ~15% low, ~15% moderate, ~15% high, ~15% critical
    scenario_weights = {
        "speed_bump":       int(n_samples * 0.10),
        "hard_braking":     int(n_samples * 0.08),
        "pothole":          int(n_samples * 0.07),
        "phone_drop":       int(n_samples * 0.08),
        "door_slam":        int(n_samples * 0.04),
        "parking_bump":     int(n_samples * 0.03),
        "fender_bender":    int(n_samples * 0.08),
        "minor_sideswipe":  int(n_samples * 0.07),
        "urban_collision":  int(n_samples * 0.10),
        "intersection_hit": int(n_samples * 0.05),
        "highway_crash":    int(n_samples * 0.10),
        "side_impact":      int(n_samples * 0.05),
        "rollover_low":     int(n_samples * 0.05),
        "rollover_high":    int(n_samples * 0.05),
        "head_on":          int(n_samples * 0.05),
    }

    # ═══════════════════════════════════════════════
    #  FALSE ALARM SCENARIOS (priority 1)
    # ═══════════════════════════════════════════════

    # Speed bumps: low speed, brief vertical g-spike, no rollover
    for _ in range(scenario_weights["speed_bump"]):
        records.append({
            "g_force":       rng.uniform(1.5, 3.0),
            "rollover_angle": rng.uniform(0, 3),
            "speed":         rng.uniform(5, 30),
            "accel_x":       rng.uniform(-0.5, 0.5),
            "accel_y":       rng.uniform(-0.3, 0.3),
            "accel_z":       rng.uniform(1.5, 3.0),   # Vertical spike
            "gyro_roll":     rng.uniform(-2, 2),
            "gyro_pitch":    rng.uniform(-3, 3),
            "priority_score": 1,
        })

    # Hard braking: longitudinal deceleration, no lateral/rollover
    for _ in range(scenario_weights["hard_braking"]):
        records.append({
            "g_force":       rng.uniform(1.5, 3.5),
            "rollover_angle": rng.uniform(0, 2),
            "speed":         rng.uniform(20, 80),
            "accel_x":       rng.uniform(-3.5, -1.0),  # Forward decel
            "accel_y":       rng.uniform(-0.3, 0.3),
            "accel_z":       rng.uniform(0.8, 1.2),
            "gyro_roll":     rng.uniform(-1, 1),
            "gyro_pitch":    rng.uniform(-5, 0),
            "priority_score": 1,
        })

    # Pothole: vertical + slight lateral, low-moderate speed
    for _ in range(scenario_weights["pothole"]):
        records.append({
            "g_force":       rng.uniform(2.0, 4.0),
            "rollover_angle": rng.uniform(0, 8),
            "speed":         rng.uniform(15, 60),
            "accel_x":       rng.uniform(-0.5, 0.5),
            "accel_y":       rng.uniform(-1.0, 1.0),
            "accel_z":       rng.uniform(2.0, 4.0),
            "gyro_roll":     rng.uniform(-5, 5),
            "gyro_pitch":    rng.uniform(-8, 8),
            "priority_score": 1,
        })

    # Phone dropped on car mount: sudden spike, car is stationary or slow
    for _ in range(scenario_weights["phone_drop"]):
        records.append({
            "g_force":       rng.uniform(3.0, 9.0),   # High g from drop
            "rollover_angle": rng.uniform(0, 2),
            "speed":         rng.uniform(0, 10),       # Key: near-zero speed
            "accel_x":       rng.uniform(-2, 2),
            "accel_y":       rng.uniform(-2, 2),
            "accel_z":       rng.uniform(3.0, 9.0),
            "gyro_roll":     rng.uniform(-3, 3),
            "gyro_pitch":    rng.uniform(-3, 3),
            "priority_score": 1,
        })

    # Door slam while parked: vibration, zero speed
    for _ in range(scenario_weights["door_slam"]):
        records.append({
            "g_force":       rng.uniform(1.5, 3.0),
            "rollover_angle": rng.uniform(0, 1),
            "speed":         0.0,
            "accel_x":       rng.uniform(-1.0, 1.0),
            "accel_y":       rng.uniform(-2.0, 2.0),
            "accel_z":       rng.uniform(0.8, 1.5),
            "gyro_roll":     rng.uniform(-1, 1),
            "gyro_pitch":    rng.uniform(-1, 1),
            "priority_score": 1,
        })

    # Parking lot bump: very slow, gentle contact
    for _ in range(scenario_weights["parking_bump"]):
        records.append({
            "g_force":       rng.uniform(1.5, 3.5),
            "rollover_angle": rng.uniform(0, 3),
            "speed":         rng.uniform(2, 15),
            "accel_x":       rng.uniform(-2.5, -0.5),
            "accel_y":       rng.uniform(-1.0, 1.0),
            "accel_z":       rng.uniform(0.8, 1.5),
            "gyro_roll":     rng.uniform(-2, 2),
            "gyro_pitch":    rng.uniform(-2, 2),
            "priority_score": 1,
        })

    # ═══════════════════════════════════════════════
    #  LOW SEVERITY (priority 2) — minor real incidents
    # ═══════════════════════════════════════════════

    # Fender bender: low speed, noticeable impact but not dangerous
    for _ in range(scenario_weights["fender_bender"]):
        records.append({
            "g_force":       rng.uniform(2.5, 5.0),
            "rollover_angle": rng.uniform(0, 5),
            "speed":         rng.uniform(10, 35),
            "accel_x":       rng.uniform(-4.0, -1.5),
            "accel_y":       rng.uniform(-1.5, 1.5),
            "accel_z":       rng.uniform(0.8, 2.0),
            "gyro_roll":     rng.uniform(-5, 5),
            "gyro_pitch":    rng.uniform(-5, 5),
            "priority_score": 2,
        })

    # Minor sideswipe: lateral impact, moderate speed
    for _ in range(scenario_weights["minor_sideswipe"]):
        records.append({
            "g_force":       rng.uniform(3.0, 5.5),
            "rollover_angle": rng.uniform(3, 15),
            "speed":         rng.uniform(25, 60),
            "accel_x":       rng.uniform(-1.5, 0.5),
            "accel_y":       rng.uniform(-5.0, 5.0),   # Lateral spike
            "accel_z":       rng.uniform(0.8, 1.5),
            "gyro_roll":     rng.uniform(-12, 12),
            "gyro_pitch":    rng.uniform(-3, 3),
            "priority_score": 2,
        })

    # ═══════════════════════════════════════════════
    #  MODERATE SEVERITY (priority 3) — real crashes
    # ═══════════════════════════════════════════════

    # Urban collision: city speed, solid impact
    for _ in range(scenario_weights["urban_collision"]):
        records.append({
            "g_force":       rng.uniform(4.0, 8.0),
            "rollover_angle": rng.uniform(0, 20),
            "speed":         rng.uniform(30, 60),
            "accel_x":       rng.uniform(-7.0, -3.0),
            "accel_y":       rng.uniform(-3.0, 3.0),
            "accel_z":       rng.uniform(0.5, 2.5),
            "gyro_roll":     rng.uniform(-15, 15),
            "gyro_pitch":    rng.uniform(-10, 10),
            "priority_score": 3,
        })

    # Intersection T-bone (moderate speed)
    for _ in range(scenario_weights["intersection_hit"]):
        records.append({
            "g_force":       rng.uniform(5.0, 9.0),
            "rollover_angle": rng.uniform(5, 30),
            "speed":         rng.uniform(35, 65),
            "accel_x":       rng.uniform(-2.0, 1.0),
            "accel_y":       rng.uniform(-8.0, 8.0),  # Severe lateral
            "accel_z":       rng.uniform(0.5, 2.0),
            "gyro_roll":     rng.uniform(-25, 25),
            "gyro_pitch":    rng.uniform(-8, 8),
            "priority_score": 3,
        })

    # ═══════════════════════════════════════════════
    #  HIGH SEVERITY (priority 4) — severe crashes
    # ═══════════════════════════════════════════════

    # Highway crash: high speed, high g-force
    for _ in range(scenario_weights["highway_crash"]):
        records.append({
            "g_force":       rng.uniform(7.0, 15.0),
            "rollover_angle": rng.uniform(0, 35),
            "speed":         rng.uniform(80, 140),
            "accel_x":       rng.uniform(-12.0, -5.0),
            "accel_y":       rng.uniform(-5.0, 5.0),
            "accel_z":       rng.uniform(0.3, 3.0),
            "gyro_roll":     rng.uniform(-25, 25),
            "gyro_pitch":    rng.uniform(-15, 15),
            "priority_score": 4,
        })

    # Severe side impact
    for _ in range(scenario_weights["side_impact"]):
        records.append({
            "g_force":       rng.uniform(6.0, 12.0),
            "rollover_angle": rng.uniform(10, 50),
            "speed":         rng.uniform(40, 100),
            "accel_x":       rng.uniform(-3.0, 1.0),
            "accel_y":       rng.uniform(-11.0, 11.0),
            "accel_z":       rng.uniform(0.3, 2.5),
            "gyro_roll":     rng.uniform(-40, 40),
            "gyro_pitch":    rng.uniform(-10, 10),
            "priority_score": 4,
        })

    # ═══════════════════════════════════════════════
    #  CRITICAL SEVERITY (priority 5) — life-threatening
    # ═══════════════════════════════════════════════

    # Rollover at low-moderate speed
    for _ in range(scenario_weights["rollover_low"]):
        records.append({
            "g_force":       rng.uniform(5.0, 10.0),
            "rollover_angle": rng.uniform(50, 180),
            "speed":         rng.uniform(40, 80),
            "accel_x":       rng.uniform(-6.0, 3.0),
            "accel_y":       rng.uniform(-8.0, 8.0),
            "accel_z":       rng.uniform(-5.0, 5.0),  # Inverted possible
            "gyro_roll":     rng.uniform(-90, 90),
            "gyro_pitch":    rng.uniform(-30, 30),
            "priority_score": 5,
        })

    # Rollover at high speed
    for _ in range(scenario_weights["rollover_high"]):
        records.append({
            "g_force":       rng.uniform(8.0, 20.0),
            "rollover_angle": rng.uniform(60, 360),
            "speed":         rng.uniform(80, 160),
            "accel_x":       rng.uniform(-10.0, 5.0),
            "accel_y":       rng.uniform(-10.0, 10.0),
            "accel_z":       rng.uniform(-8.0, 8.0),
            "gyro_roll":     rng.uniform(-180, 180),
            "gyro_pitch":    rng.uniform(-45, 45),
            "priority_score": 5,
        })

    # Head-on collision: extreme deceleration
    for _ in range(scenario_weights["head_on"]):
        records.append({
            "g_force":       rng.uniform(10.0, 30.0),
            "rollover_angle": rng.uniform(0, 40),
            "speed":         rng.uniform(60, 150),
            "accel_x":       rng.uniform(-25.0, -8.0),
            "accel_y":       rng.uniform(-4.0, 4.0),
            "accel_z":       rng.uniform(0.2, 3.0),
            "gyro_roll":     rng.uniform(-15, 15),
            "gyro_pitch":    rng.uniform(-20, 20),
            "priority_score": 5,
        })

    df = pd.DataFrame(records)

    # ── Derived features (feature engineering) ──
    df["impact_energy"] = 0.5 * (df["speed"] ** 2) * df["g_force"] / 10000
    df["rollover_risk"] = df["rollover_angle"] / 90.0
    df["speed_gforce"] = df["speed"] * df["g_force"] / 100
    df["lateral_ratio"] = df["accel_y"].abs() / (df["g_force"] + 0.01)
    df["is_stationary"] = (df["speed"] < 5).astype(int)

    # Shuffle
    df = df.sample(frac=1, random_state=seed).reset_index(drop=True)

    return df


# Severity label mapping
SEVERITY_MAP = {
    1: "FALSE_ALARM",
    2: "LOW",
    3: "MODERATE",
    4: "HIGH",
    5: "CRITICAL",
}

# Features used for training (order matters — must match inference)
FEATURE_COLUMNS = [
    "g_force", "rollover_angle", "speed",
    "accel_x", "accel_y", "accel_z",
    "gyro_roll", "gyro_pitch",
    "impact_energy", "rollover_risk", "speed_gforce",
    "lateral_ratio", "is_stationary",
]


if __name__ == "__main__":
    df = generate_dataset(10000)
    print(f"Dataset shape: {df.shape}")
    print(f"\nClass distribution:\n{df['priority_score'].value_counts().sort_index()}")
    print(f"\nSample rows:\n{df.head(10).to_string()}")
