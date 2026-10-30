from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.model.predictor import predictor
from app.schemas import HealthResponse, PredictionRequest, PredictionResponse


@asynccontextmanager
async def lifespan(app: FastAPI):
    predictor.load()  # loads the saved model, or trains one if none exists
    yield


app = FastAPI(
    title="IERS Crash Triage Service",
    description="Decides whether sensor data from the IoT device is a real crash or a false alarm.",
    version="2.0.0",
    lifespan=lifespan,
)


@app.post("/predict", response_model=PredictionResponse)
def predict(req: PredictionRequest) -> PredictionResponse:
    result = predictor.predict(
        g_force=req.gForce,
        rollover_angle=req.rolloverAngle,
        speed=req.speed,
        sensor_data=req.sensorData,
    )
    return PredictionResponse(**result)


@app.get("/health", response_model=HealthResponse)
def health() -> HealthResponse:
    return HealthResponse(status="UP", modelLoaded=predictor.is_loaded)


@app.get("/")
def root():
    return {"service": "iers-ml-service", "version": "2.0.0", "docs": "/docs"}
