"""Custom exceptions and error handling for Agent Service.

This module defines custom exception types and provides centralized
error handling with proper HTTP status codes and user-friendly messages.
"""

from typing import Optional, Dict, Any
from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse
from loguru import logger


class AgentServiceException(Exception):
    """Base exception for Agent Service errors."""

    def __init__(
        self,
        message: str,
        error_code: Optional[str] = None,
        details: Optional[Dict[str, Any]] = None
    ):
        self.message = message
        self.error_code = error_code
        self.details = details or {}
        super().__init__(message)


class SoapGenerationError(AgentServiceException):
    """Raised when SOAP note generation fails."""

    def __init__(
        self,
        message: str = "Failed to generate SOAP note",
        error_code: str = "SOAP_GENERATION_FAILED",
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, error_code, details)


class ConfigurationError(AgentServiceException):
    """Raised when configuration is invalid or missing."""

    def __init__(
        self,
        message: str = "Invalid or missing configuration",
        error_code: str = "CONFIGURATION_ERROR",
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, error_code, details)


class ModelError(AgentServiceException):
    """Raised when AI model interaction fails."""

    def __init__(
        self,
        message: str = "AI model interaction failed",
        error_code: str = "MODEL_ERROR",
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, error_code, details)


class ValidationError(AgentServiceException):
    """Raised when input or output validation fails."""

    def __init__(
        self,
        message: str = "Validation failed",
        error_code: str = "VALIDATION_ERROR",
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, error_code, details)


class RetryExhaustedError(AgentServiceException):
    """Raised when all retry attempts are exhausted."""

    def __init__(
        self,
        message: str = "All retry attempts exhausted",
        error_code: str = "RETRY_EXHAUSTED",
        details: Optional[Dict[str, Any]] = None
    ):
        super().__init__(message, error_code, details)


# Exception handlers for FastAPI

async def agent_service_exception_handler(
    request: Request,
    exc: AgentServiceException
) -> JSONResponse:
    """Handle custom AgentService exceptions.

    Args:
        request: FastAPI request object
        exc: AgentService exception

    Returns:
        JSON error response
    """
    logger.error(
        f"AgentService error: {exc.message}",
        extra={
            "error_code": exc.error_code,
            "details": exc.details,
            "url": str(request.url),
            "method": request.method
        }
    )

    return JSONResponse(
        status_code=500,
        content={
            "error": {
                "message": exc.message,
                "code": exc.error_code,
                "type": type(exc).__name__
            }
        }
    )


async def soap_generation_exception_handler(
    request: Request,
    exc: SoapGenerationError
) -> JSONResponse:
    """Handle SOAP generation errors.

    Args:
        request: FastAPI request object
        exc: SOAP generation exception

    Returns:
        JSON error response
    """
    logger.error(
        f"SOAP generation failed: {exc.message}",
        extra={
            "error_code": exc.error_code,
            "details": exc.details,
            "url": str(request.url)
        }
    )

    return JSONResponse(
        status_code=500,
        content={
            "error": {
                "message": "Failed to generate SOAP note. Please check your input and try again.",
                "code": exc.error_code,
                "type": "SoapGenerationError"
            }
        }
    )


async def validation_exception_handler(
    request: Request,
    exc: ValidationError
) -> JSONResponse:
    """Handle validation errors.

    Args:
        request: FastAPI request object
        exc: Validation exception

    Returns:
        JSON error response
    """
    logger.warning(
        f"Validation error: {exc.message}",
        extra={
            "error_code": exc.error_code,
            "details": exc.details,
            "url": str(request.url)
        }
    )

    return JSONResponse(
        status_code=400,
        content={
            "error": {
                "message": exc.message,
                "code": exc.error_code,
                "type": "ValidationError"
            }
        }
    )


async def model_exception_handler(
    request: Request,
    exc: ModelError
) -> JSONResponse:
    """Handle AI model errors.

    Args:
        request: FastAPI request object
        exc: Model exception

    Returns:
        JSON error response
    """
    logger.error(
        f"Model error: {exc.message}",
        extra={
            "error_code": exc.error_code,
            "details": exc.details,
            "url": str(request.url)
        }
    )

    return JSONResponse(
        status_code=503,  # Service Unavailable
        content={
            "error": {
                "message": "AI service temporarily unavailable. Please try again later.",
                "code": exc.error_code,
                "type": "ModelError"
            }
        }
    )


async def generic_exception_handler(request: Request, exc: Exception) -> JSONResponse:
    """Handle unexpected exceptions.

    Args:
        request: FastAPI request object
        exc: Generic exception

    Returns:
        JSON error response
    """
    logger.error(
        f"Unexpected error: {str(exc)}",
        extra={
            "error_type": type(exc).__name__,
            "url": str(request.url),
            "method": request.method
        }
    )

    return JSONResponse(
        status_code=500,
        content={
            "error": {
                "message": "An unexpected error occurred. Please try again later.",
                "code": "INTERNAL_SERVER_ERROR",
                "type": "InternalServerError"
            }
        }
    )


# Error response models for OpenAPI documentation
def get_error_responses() -> Dict[int, Dict[str, Any]]:
    """Get error response schemas for OpenAPI documentation.

    Returns:
        Dictionary of HTTP status codes and their error schemas
    """
    return {
        400: {
            "description": "Validation Error",
            "content": {
                "application/json": {
                    "example": {
                        "error": {
                            "message": "Invalid input data",
                            "code": "VALIDATION_ERROR",
                            "type": "ValidationError"
                        }
                    }
                }
            }
        },
        500: {
            "description": "Internal Server Error",
            "content": {
                "application/json": {
                    "example": {
                        "error": {
                            "message": "Failed to generate SOAP note",
                            "code": "SOAP_GENERATION_FAILED",
                            "type": "SoapGenerationError"
                        }
                    }
                }
            }
        },
        503: {
            "description": "Service Unavailable",
            "content": {
                "application/json": {
                    "example": {
                        "error": {
                            "message": "AI service temporarily unavailable",
                            "code": "MODEL_ERROR",
                            "type": "ModelError"
                        }
                    }
                }
            }
        }
    }