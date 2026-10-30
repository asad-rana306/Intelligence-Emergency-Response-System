"""Quick sanity check: run with  python test_scenarios.py
Prints PASS/FAIL for each real-life situation. Exit code 1 if anything fails."""
import sys

from app.model.predictor import predictor

# (name, gForce, rolloverAngle, speed, sensorData, should_dispatch)
CASES = [
    ("Speed bump",                     3.5,   2,  25, None, False),
    ("Hard braking",                   2.0,   1,  60, None, False),
    ("Pothole",                        5.0,   3,  40, None, False),
    ("Device knocked / bumped",        6.0,  20,   1, None, False),
    ("Door slam",                      4.0,   1,   0, None, False),
    ("Parking bump",                   5.0,   2,   4, None, False),
    ("Fender bender (minor)",          8.0,   3,  15, None, False),
    ("Urban T-bone",                  12.0,   8,  40, None, True),
    ("Highway rear-end",              15.0,   5,  95, None, True),
    ("Rollover",                      11.0, 120,  60, None, True),
    ("Head-on",                       16.0,  10,  80, None, True),
    ("Stopped at light, hit from behind", 13.0, 4, 0, None, True),
    ("Control Center preset 22g (clamped)", 22.0, 5, 90, None, True),
    ("Control Center preset 35g (clamped)", 35.0, 10, 100, None, True),
    ("Highway crash with sensor axes", 14.0, 6, 90,
     {"accelX": 13.0, "accelY": 2.0, "accelZ": 1.2, "gyroRoll": 120, "gyroPitch": 60}, True),
    ("Axes sent in m/s2 (9.8 at rest)", 2.5, 1, 30,
     {"accelX": 1.0, "accelY": 0.5, "accelZ": 9.8}, False),
    ("accelerometerX-style names (crash)", 14.0, 6, 90,
     {"accelerometerX": 13.0, "accelerometerY": 2.0, "accelerometerZ": 1.2,
      "gyroscopeRoll": 120, "gyroscopePitch": 60}, True),
    ("accelerometerX-style names (speed bump)", 2.1, 1.5, 25,
     {"accelerometerX": -0.3, "accelerometerY": 0.1, "accelerometerZ": 1.0,
      "gyroscopeRoll": 1.0, "gyroscopePitch": -2.0}, False),
]

predictor.load()
failed = 0
print(f"{'Situation':<40}{'g':>5}{'roll':>6}{'km/h':>6}   result")
for name, g, roll, spd, sd, expect in CASES:
    r = predictor.predict(g_force=g, rollover_angle=roll, speed=spd, sensor_data=sd)
    ok = r["severe"] == expect
    failed += 0 if ok else 1
    print(f"{name:<40}{g:>5}{roll:>6}{spd:>6}   {'PASS' if ok else 'FAIL'}  severe={r['severe']!s:<5} {r['severity']} ({r['priorityScore']})")

print("\nBorderline (no pass/fail, just for information):")
for g, roll, spd in [(9.5, 1, 0), (9.0, 2, 20), (10.0, 5, 30)]:
    r = predictor.predict(g_force=g, rollover_angle=roll, speed=spd)
    print(f"  g={g} roll={roll} speed={spd} -> severe={r['severe']} {r['severity']}")

print(f"\n{len(CASES) - failed}/{len(CASES)} passed")
sys.exit(1 if failed else 0)
