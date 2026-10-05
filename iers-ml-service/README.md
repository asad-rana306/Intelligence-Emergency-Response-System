# IERS Crash Severity ML Service

## How Data Flows Through the System

```
Car Crash
   │
   ▼
┌──────────────────────────────────────────────────────────┐
│ CAR EMBEDDED DEVICE                                      │
│                                                          │
│  Accelerometer (3-axis) ──┐                              │
│  Gyroscope (3-axis) ──────┤──▶ Raw Sensor Payload        │
│  GPS Module ──────────────┤    {gForce, rollover, speed, │
│  Speed Sensor ────────────┘     gpsLat, gpsLng,          │
│                                 sensorData: {accelXYZ,   │
│                                              gyroRP}}    │
└──────────────────┬───────────────────────────────────────┘
                   │ HTTP POST
                   ▼
┌──────────────────────────────────────────────────────────┐
│ IOT SERVICE (Service 3)                                  │
│                                                          │
│  1. Redis dedup check (SETNX)                            │
│  2. Save crash_event (status=RECEIVED)                   │
│  3. Start 10-second timer                                │
│  4. Timer expires → call ML Service ─────────────────────┼──┐
│  5. If ML says severe → publish to Kafka                 │  │
└──────────────────────────────────────────────────────────┘  │
                                                              │
              POST /predict                                   │
              {gForce: 8.5, rolloverAngle: 45,                │
               speed: 120, sensorData: {...}}                 │
                                                              │
┌─────────────────────────────────────────────────────────────▼┐
│ ML SERVICE (this project)                                    │
│                                                              │
│  1. Extract features: gForce, rollover, speed, accelXYZ,     │
│     gyroRP from the request                                  │
│  2. Engineer derived features:                               │
│     • impact_energy = 0.5 × speed² × gForce / 10000         │
│     • rollover_risk = rolloverAngle / 90                     │
│     • speed_gforce  = speed × gForce / 100                   │
│     • lateral_ratio = |accelY| / gForce                      │
│     • is_stationary = 1 if speed < 5 else 0                  │
│  3. Feed 13-feature vector into Random Forest                │
│  4. Return {severe: true, priorityScore: 4, severity: HIGH}  │
└──────────────────────────────────────────────────────────────┘
```

## The ML Decision Process

The model answers one question: **"Is this a real crash that needs an ambulance, or a false alarm?"**

### Why G-Force Alone Is Not Enough

This is the most important insight for your viva:

| Event               | G-Force | Speed  | Rollover | Result       |
|---------------------|---------|--------|----------|--------------|
| Phone dropped       | **7.0g**| 0 km/h | 0.5°     | FALSE_ALARM  |
| Highway braking     | 2.5g    | 90 km/h| 1.0°     | FALSE_ALARM  |
| Urban T-bone        | **7.0g**| 50 km/h| 15.0°    | MODERATE ✅  |

Scenario 2 (phone drop) and Scenario 5 (T-bone) have the **same g-force** (7.0g), but completely different outcomes. The model distinguishes them because:

The phone drop has `speed=0` and `is_stationary=1`, so `impact_energy=0` and `speed_gforce=0`. These features tell the model: "High acceleration but the vehicle wasn't moving — this can't be a crash."

The T-bone has `speed=50` and `rollover=15°`, so `impact_energy=87.5` and `speed_gforce=3.5`. Plus the lateral accelerometer axis (`accel_y=-6.5`) is extreme. These features tell the model: "Side impact at urban speed — dispatch needed."

### Feature Engineering (What the Model Actually Sees)

| Feature | Formula | Purpose |
|---|---|---|
| `g_force` | Raw | Peak deceleration magnitude |
| `rollover_angle` | Raw | Maximum roll (0° = upright) |
| `speed` | Raw | Vehicle speed at impact |
| `accel_x` | From sensorData | Longitudinal force (braking = negative) |
| `accel_y` | From sensorData | Lateral force (side impact = extreme) |
| `accel_z` | From sensorData | Vertical force (rollover inverts this) |
| `gyro_roll` | From sensorData | Rotational velocity around roll axis |
| `gyro_pitch` | From sensorData | Rotational velocity around pitch axis |
| **`impact_energy`** | `0.5 × speed² × gForce / 10000` | Proxy for crash kinetic energy |
| **`rollover_risk`** | `rolloverAngle / 90` | Normalized rollover severity |
| **`speed_gforce`** | `speed × gForce / 100` | Interaction: high speed + high g = crash |
| **`lateral_ratio`** | `\|accel_y\| / gForce` | Detects side impacts vs braking |
| **`is_stationary`** | `1 if speed < 5 else 0` | Eliminates phone drops and door slams |

The **bold derived features** are what make the model work. Raw features alone would confuse a phone drop (7g) with a real crash (7g). The derived features encode the physics.

### Top Feature Importances (from trained model)

```
g_force             0.1573   ─ Still the #1 signal
speed_gforce        0.1508   ─ Interaction: speed × g matters more than either alone
impact_energy       0.1236   ─ Crash energy proxy
rollover_angle      0.1184   ─ Rollover is a strong severity indicator
accel_x             0.1171   ─ Frontal impact signature
rollover_risk       0.1011   ─ Normalized rollover
accel_z             0.0798   ─ Vertical axis (inverted in rollover)
speed               0.0766   ─ Speed at impact
```

## All 8 Test Scenarios

### FALSE ALARMS (No Dispatch)

**Scenario 1 — Speed Bump**
`gForce=2.1g, rollover=1.5°, speed=25 km/h → Priority 1 (FALSE_ALARM)`
Low g-force, low speed, negligible rollover. Every metric is below crash thresholds.

**Scenario 2 — Phone Dropped**
`gForce=7.0g, rollover=0.5°, speed=0 km/h → Priority 1 (FALSE_ALARM)`
High g-force but `speed=0` and `is_stationary=1` → `impact_energy=0`. The model correctly identifies this as a non-vehicular acceleration event.

**Scenario 3 — Hard Braking**
`gForce=2.5g, rollover=1°, speed=90 km/h → Priority 1 (FALSE_ALARM)`
Speed is high but g-force is only 2.5g (normal braking range). No lateral force, no rollover. The `speed_gforce=2.25` is too low for a real crash at this speed.

**Scenario 4 — Fender Bender**
`gForce=3.5g, rollover=2°, speed=20 km/h → Priority 2 (LOW)`
Real contact occurred, but low speed + low g = minor incident. Dispatch is not triggered (priority < 3).

### REAL CRASHES (Dispatch Triggered)

**Scenario 5 — Urban T-Bone**
`gForce=7.0g, rollover=15°, speed=50 km/h → Priority 3 (MODERATE)`
Same g-force as phone drop, but speed=50 + lateral accel_y=-6.5 + rollover=15° = real side impact. `impact_energy=87.5`, `lateral_ratio=0.93`. Dispatch triggered.

**Scenario 6 — Highway Rear-End**
`gForce=10.0g, rollover=8°, speed=110 km/h → Priority 4 (HIGH)`
High speed + high g + strong longitudinal deceleration (accel_x=-9.5). `impact_energy=605`, `speed_gforce=11.0`. Severe crash, urgent dispatch.

**Scenario 7 — High-Speed Rollover**
`gForce=12.0g, rollover=120°, speed=130 km/h → Priority 5 (CRITICAL)`
Vehicle inverted (`rollover=120°`), extreme forces, `rollover_risk=1.33` (past 90°). `accel_z` is negative (car upside down). Life-threatening, highest priority.

**Scenario 8 — Head-On Collision**
`gForce=18.0g, rollover=5°, speed=100 km/h → Priority 5 (CRITICAL)`
Extreme frontal deceleration (accel_x=-17g). `impact_energy=900`, `speed_gforce=18.0`. Fatal-risk impact forces. Maximum priority dispatch.

## Running Locally

```bash
pip install -r requirements.txt
python train.py                     # Train the model (98.6% accuracy)
uvicorn app.main:app --port 8000    # Start the API
```

Then test:
```bash
curl -X POST http://localhost:8000/predict \
  -H "Content-Type: application/json" \
  -d '{
    "gForce": 12.0,
    "rolloverAngle": 120.0,
    "speed": 130.0,
    "sensorData": {
      "accelerometerX": -5.0,
      "accelerometerY": 7.0,
      "accelerometerZ": -4.0,
      "gyroscopeRoll": 95.0,
      "gyroscopePitch": -20.0
    }
  }'
```

Response:
```json
{
  "severe": true,
  "priorityScore": 5,
  "severity": "CRITICAL"
}
```

Swagger docs: `http://localhost:8000/docs`

## Docker

```bash
docker build -t iers-ml-service .    # Trains model during build
docker run -p 8000:8000 iers-ml-service
```

Add to `docker-compose.yml`:
```yaml
ml-service:
  build: ./iers-ml-service
  container_name: iers-ml
  ports:
    - "8000:8000"
  healthcheck:
    test: ["CMD-SHELL", "curl -f http://localhost:8000/health || exit 1"]
    interval: 10s
    timeout: 5s
    retries: 5
  networks:
    - iers-network
```

## Model Details

| Property | Value |
|---|---|
| Algorithm | Random Forest Classifier |
| Trees | 200 |
| Max Depth | 15 |
| Training Samples | 10,000 (synthetically generated) |
| Test Accuracy | **98.6%** |
| Features | 13 (8 raw + 5 engineered) |
| Classes | 5 (FALSE_ALARM, LOW, MODERATE, HIGH, CRITICAL) |
| Dispatch Threshold | Priority ≥ 3 (MODERATE and above) |
| Output | `{severe: bool, priorityScore: 1-5, severity: string}` |
