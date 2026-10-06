"""
IERS Crash Severity ML Service

FastAPI application that evaluates raw crash sensor data and returns
a severity classification. Called by the IoT Service (Service 3) via
a synchronous REST call wrapped in a Resilience4j circuit breaker.

Endpoints:
    POST /predict   — classify a crash event
    GET  /health    — health check + model status
    GET  /           — API info
"""

import logging
from contextlib import asynccontextmanager
from fastapi import FastAPI, HTTPException
from app.schemas import PredictionRequest, PredictionResponse, HealthResponse
from app.model.predictor import CrashPredictor

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(name)s: %(message)s"
)
logger = logging.getLogger(__name__)

# ── Global predictor instance ──
predictor = CrashPredictor()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Load (or train + load) the model on startup."""
    logger.info("Starting IERS ML Service...")
    predictor.load()
    logger.info("ML Service ready — model loaded")
    yield
    logger.info("Shutting down ML Service")


app = FastAPI(
    title="IERS Crash Severity ML Service",
    description="Evaluates car crash sensor telemetry and classifies severity",
    version="1.0.0",
    lifespan=lifespan,
)


# ═══════════════════════════════════════════════════
#  POST /predict
# ═══════════════════════════════════════════════════

@app.post("/predict", response_model=PredictionResponse)
async def predict(request: PredictionRequest):
    """
    Classify incoming crash sensor data.

    **Called by:** IoT Service (Service 3) after the 10-second cancellation
    window expires. The IoT Service wraps this call in a Resilience4j circuit
    breaker — if this service is down, it falls back to isSevere=true.

    **Input:** Raw sensor readings from the car's crash detection hardware.
    **Output:** Severity classification (1-5) and dispatch recommendation.
    """
    if not predictor.is_loaded:
        raise HTTPException(status_code=503, detail="Model not yet loaded")

    try:
        result = predictor.predict(
            g_force=request.gForce,
            rollover_angle=request.rolloverAngle,
            speed=request.speed,
            sensor_data=request.sensorData,
        )
        return PredictionResponse(**result)

    except Exception as e:
        logger.error(f"Prediction failed: {e}", exc_info=True)
        raise HTTPException(status_code=500, detail=f"Prediction error: {str(e)}")


# ═══════════════════════════════════════════════════
#  GET /health
# ═══════════════════════════════════════════════════

@app.get("/health", response_model=HealthResponse)
async def health():
    """Health check endpoint — returns model status and accuracy."""
    return HealthResponse(
        status="UP" if predictor.is_loaded else "LOADING",
        model_loaded=predictor.is_loaded,
        model_accuracy=predictor.metadata.get("accuracy") if predictor.metadata else None,
        training_samples=predictor.metadata.get("n_samples") if predictor.metadata else None,
    )


# ═══════════════════════════════════════════════════
#  GET /
# ═══════════════════════════════════════════════════

@app.get("/")
async def root():
    return {
        "service": "IERS Crash Severity ML Service",
        "version": "1.0.0",
        "endpoints": {
            "POST /predict": "Classify crash severity",
            "GET /health": "Health check",
            "GET /docs": "Swagger UI",
        }
    }
