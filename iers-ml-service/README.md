# IERS ML Service (v2 - retrained for the real sensor)

FastAPI service that decides: **is this a real crash that needs an ambulance?**

## What changed from v1
- The accelerometer on the device (MPU6050) can only measure up to **16g**, so the model is now trained on 0-16g
  (old model: up to 35g). Anything above 16g sent in is treated as 16g.
- "Phone Drop" is now **"Device Bump / Knock"** (the device itself being bumped or handled).
- New scenario **Stationary Hit** (stopped at a light, hit from behind): speed is 0 but it IS a crash.
- Leans towards sending help: if the model is 40% sure or more, it says `severe = true`.
  A false alert can be cancelled by the driver in 10 seconds; a missed crash cannot be undone.
- Handles sensor axes sent in m/s2 (9.8 at rest) as well as in g.

## API (unchanged - no Java change needed)
- `POST /predict` body `{"gForce":12,"rolloverAngle":5,"speed":40,"sensorData":{...optional}}`
  returns `{"severe":true,"priorityScore":3,"severity":"MODERATE"}`
- `GET /health` returns `{"status":"UP","modelLoaded":true}`

## Run
    docker compose up --build ml-service        # trains the model while building the image

Or without Docker:

    pip install -r requirements.txt
    python train.py                              # trains and saves to app/model/artifacts/
    python test_scenarios.py                     # 16 real-life checks, all should say PASS
    uvicorn app.main:app --port 8000
