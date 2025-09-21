"""Response models and data schemas for Agent Service.

This module defines Pydantic models for API requests, responses,
and data validation with proper type hints and documentation.
"""

from datetime import datetime
from typing import Optional, Dict, Any
from pydantic import BaseModel, Field


class TranscriptInput(BaseModel):
    """Input model for transcript data."""

    transcript: str = Field(
        ...,
        description="Medical transcript text to convert to SOAP format",
        min_length=10,
        max_length=50000,
        example="Patient reports headache for 3 days. Physical examination shows normal vital signs."
    )


class SoapNote(BaseModel):
    """Model for generated SOAP note."""

    structured_text: str = Field(
        ...,
        description="Generated SOAP note in structured format",
        example=(
            "ANAMNESE:\nDer Patient berichtet über...\n\n"
            "UNTERSUCHUNG und BEFUNDE:\n...\n\n"
            "BEURTEILUNG:\n...\n\n"
            "PROZEDERE und THERAPIE:\n...\n\n"
            "AKTUELLE DIAGNOSEN:\n..."
        )
    )

    class Config:
        """Pydantic configuration."""
        json_schema_extra = {
            "example": {
                "structured_text": (
                    "ANAMNESE:\nDer Patient berichtet über...\n\n"
                    "UNTERSUCHUNG und BEFUNDE:\n...\n\n"
                    "BEURTEILUNG:\n...\n\n"
                    "PROZEDERE und THERAPIE:\n...\n\n"
                    "AKTUELLE DIAGNOSEN:\n..."
                )
            }
        }


class SoapNoteResponse(BaseModel):
    """Enhanced response model for SOAP note generation."""

    soap_note: SoapNote = Field(
        ...,
        description="Generated SOAP note"
    )

    metadata: Dict[str, Any] = Field(
        ...,
        description="Generation metadata",
        example={
            "generation_time": 2.5,
            "model_used": "gpt-4o",
            "temperature": 0.7,
            "token_usage": {
                "prompt_tokens": 150,
                "completion_tokens": 300,
                "total_tokens": 450
            }
        }
    )

    timestamp: datetime = Field(
        default_factory=datetime.utcnow,
        description="Timestamp when SOAP note was generated"
    )


class HealthCheckResponse(BaseModel):
    """Health check response model."""

    status: str = Field(
        ...,
        description="Service health status",
        example="healthy"
    )

    service: str = Field(
        ...,
        description="Service name",
        example="agent_service"
    )

    version: str = Field(
        ...,
        description="Service version",
        example="1.0.0"
    )

    model_loaded: bool = Field(
        ...,
        description="Whether AI model is loaded and ready"
    )

    timestamp: datetime = Field(
        default_factory=datetime.utcnow,
        description="Health check timestamp"
    )

    uptime: Optional[float] = Field(
        None,
        description="Service uptime in seconds"
    )


class ErrorResponse(BaseModel):
    """Error response model."""

    error: Dict[str, Any] = Field(
        ...,
        description="Error details",
        example={
            "message": "Failed to generate SOAP note",
            "code": "SOAP_GENERATION_FAILED",
            "type": "SoapGenerationError"
        }
    )


class ValidationDetails(BaseModel):
    """Model for SOAP note validation details."""

    is_valid: bool = Field(
        ...,
        description="Whether SOAP note passed validation"
    )

    validation_errors: list[str] = Field(
        default_factory=list,
        description="List of validation errors, if any"
    )

    required_sections_found: list[str] = Field(
        default_factory=list,
        description="List of required sections found in the note"
    )

    missing_sections: list[str] = Field(
        default_factory=list,
        description="List of missing required sections"
    )

    content_quality_score: Optional[float] = Field(
        None,
        description="Quality score from 0.0 to 1.0",
        ge=0.0,
        le=1.0
    )