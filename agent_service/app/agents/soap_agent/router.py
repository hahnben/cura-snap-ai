"""Router for SOAP agent endpoints with dependency injection and error handling."""

from fastapi import APIRouter, Depends, HTTPException
from loguru import logger

from ...core.dependencies import get_soap_agent_service
from ...core.schemas import TranscriptInput, SoapNoteResponse, HealthCheckResponse
from ...core.exceptions import (
    get_error_responses,
    SoapGenerationError,
    ValidationError,
    ModelError,
    RetryExhaustedError
)
from .service import SoapAgentService

router = APIRouter(
    prefix="/soap",
    tags=["SOAP Generation"],
    responses=get_error_responses()
)


@router.post(
    "/format_note",
    response_model=SoapNoteResponse,
    summary="Generate SOAP Note",
    description="Convert medical transcript to structured SOAP note format",
    responses={
        200: {
            "description": "Successfully generated SOAP note",
            "model": SoapNoteResponse
        },
        **get_error_responses()
    }
)
async def format_note(
    input_data: TranscriptInput,
    service: SoapAgentService = Depends(get_soap_agent_service)
) -> SoapNoteResponse:
    """Generate structured SOAP note from medical transcript.

    This endpoint processes unstructured medical transcripts and converts them
    into standardized SOAP note format using AI-powered analysis.

    Args:
        input_data: Medical transcript input
        service: SOAP agent service (injected)

    Returns:
        Structured SOAP note with metadata

    Raises:
        HTTPException: For various error conditions (400, 500, 503)
    """
    try:
        logger.info("Received SOAP generation request")
        result = await service.format_transcript_to_soap(input_data)
        logger.info("SOAP generation completed successfully")
        return result

    except ValidationError as e:
        logger.warning(f"Validation error: {e.message}")
        raise HTTPException(
            status_code=400,
            detail={
                "message": e.message,
                "code": e.error_code,
                "type": "ValidationError"
            }
        )

    except RetryExhaustedError as e:
        logger.error(f"Retry exhausted: {e.message}")
        raise HTTPException(
            status_code=503,
            detail={
                "message": "Service temporarily unavailable due to repeated failures",
                "code": e.error_code,
                "type": "RetryExhaustedError"
            }
        )

    except ModelError as e:
        logger.error(f"Model error: {e.message}")
        raise HTTPException(
            status_code=503,
            detail={
                "message": "AI service temporarily unavailable",
                "code": e.error_code,
                "type": "ModelError"
            }
        )

    except SoapGenerationError as e:
        logger.error(f"SOAP generation error: {e.message}")
        raise HTTPException(
            status_code=500,
            detail={
                "message": "Failed to generate SOAP note",
                "code": e.error_code,
                "type": "SoapGenerationError"
            }
        )

    except Exception as e:
        logger.error(f"Unexpected error in SOAP generation: {str(e)}")
        raise HTTPException(
            status_code=500,
            detail={
                "message": "An unexpected error occurred",
                "code": "INTERNAL_SERVER_ERROR",
                "type": "InternalServerError"
            }
        )


@router.get(
    "/health",
    response_model=HealthCheckResponse,
    summary="Service Health Check",
    description="Get detailed health status of the SOAP generation service"
)
async def health_check(
    service: SoapAgentService = Depends(get_soap_agent_service)
) -> HealthCheckResponse:
    """Get detailed health status of the SOAP agent service.

    Returns comprehensive health information including model status,
    configuration, and uptime metrics.

    Args:
        service: SOAP agent service (injected)

    Returns:
        Detailed health check response
    """
    try:
        status_info = service.get_service_status()

        return HealthCheckResponse(
            status=status_info["status"],
            service="soap_agent",
            version="1.0.0",
            model_loaded=status_info["model_loaded"],
            uptime=status_info["uptime"]
        )

    except Exception as e:
        logger.error(f"Health check failed: {str(e)}")
        return HealthCheckResponse(
            status="unhealthy",
            service="soap_agent",
            version="1.0.0",
            model_loaded=False
        )
