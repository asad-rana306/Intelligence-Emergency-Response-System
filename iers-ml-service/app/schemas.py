from typing import Any, Dict, Optional

from pydantic import BaseModel, Field


class PredictionRequest(BaseModel):
    """Same field names the IoT service already sends (camelCase)."""

    gForce: float = Field(..., description="Peak acceleration in g. The MPU6050 tops out at 16g; larger values are clamped.")
    rolloverAngle: float = Field(..., description="Tilt from upright, in degrees (0 to 180).")
    speed: float = Field(..., description="Speed in km/h from the GPS module.")
    sensorData: Optional[Dict[str, Any]] = Field(
        default=None,
        description="Optional raw axes: accelX, accelY, accelZ (in g), gyroRoll, gyroPitch (in deg/s).",
    )

    model_config = {
        "json_schema_extra": {
            "example": {
                "gForce": 16,
                "rolloverAngle": 5,
                "speed": 90,
                "sensorData": {"accelX": 2.1, "accelY": 11.0, "accelZ": 1.0, "gyroRoll": 40, "gyroPitch": 10},
            }
        }
    }


class PredictionResponse(BaseModel):
    severe: bool
    priorityScore: int
    severity: str


class HealthResponse(BaseModel):
    status: str
    modelLoaded: bool
