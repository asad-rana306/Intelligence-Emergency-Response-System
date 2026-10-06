from pydantic import BaseModel, Field
from typing import Optional


class PredictionRequest(BaseModel):
    """
    Exactly matches Service 3's MlPredictionRequest DTO.
    Sent by the IoT Service after a crash payload arrives.
    """
    gForce: float = Field(..., description="Peak resultant G-force (1g = normal gravity)")
    rolloverAngle: float = Field(..., description="Maximum roll angle in degrees (0 = upright)")
    speed: float = Field(..., description="Vehicle speed at impact in km/h")
    sensorData: Optional[dict] = Field(
        default=None,
        description="Raw sensor blob: accelerometerX/Y/Z, gyroscope, etc."
    )

    model_config = {
        "json_schema_extra": {
            "examples": [
                {
                    "gForce": 8.5,
                    "rolloverAngle": 45.0,
                    "speed": 120.0,
                    "sensorData": {
                        "accelerometerX": 2.3,
                        "accelerometerY": -8.1,
                        "accelerometerZ": 1.2,
                        "gyroscopeRoll": 44.7,
                        "gyroscopePitch": 5.2
                    }
                }
            ]
        }
    }


class PredictionResponse(BaseModel):
    """
    Exactly matches Service 3's MlPredictionResponse DTO.
    """
    severe: bool = Field(..., description="True if crash requires dispatch")
    priorityScore: int = Field(..., ge=1, le=5, description="1=false alarm, 5=critical")
    severity: str = Field(..., description="CRITICAL, HIGH, MODERATE, LOW, or FALSE_ALARM")


class HealthResponse(BaseModel):
    status: str
    model_loaded: bool
    model_accuracy: Optional[float] = None
    training_samples: Optional[int] = None
