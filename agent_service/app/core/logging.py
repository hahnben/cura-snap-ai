"""Structured logging configuration for Agent Service.

This module provides centralized logging setup with proper formatting,
log levels, and file output for production monitoring.
"""

import sys
import os
from pathlib import Path
from loguru import logger
from .config import Config


def setup_logging(config: Config) -> None:
    """Setup structured logging with file output and console output.

    Args:
        config: Service configuration containing log level
    """
    # Remove default logger
    logger.remove()

    # Define log format
    log_format = (
        "{time:YYYY-MM-DD HH:mm:ss.SSS} | "
        "{level: <8} | "
        "{name}:{function}:{line} | "
        "{message}"
    )

    # Console output with colors
    logger.add(
        sys.stdout,
        level=config.log_level,
        format=log_format,
        colorize=True,
        serialize=False
    )

    # Create logs directory if it doesn't exist
    logs_dir = Path("logs")
    logs_dir.mkdir(exist_ok=True)

    # File output for persistent logging
    logger.add(
        logs_dir / "agent_service.log",
        level=config.log_level,
        format=log_format,
        rotation="1 day",    # Rotate daily
        retention="30 days", # Keep logs for 30 days
        compression="zip",   # Compress old logs
        serialize=False
    )

    # Error-specific log file
    logger.add(
        logs_dir / "agent_service_errors.log",
        level="ERROR",
        format=log_format,
        rotation="1 week",
        retention="60 days",
        compression="zip",
        serialize=False
    )

    # Log startup information
    logger.info(f"Logging configured with level: {config.log_level}")
    logger.info(f"Service: {config.service_name} v{config.service_version}")
    logger.debug("Debug logging enabled")


def log_request_response(
    endpoint: str,
    input_data: dict,
    response_data: dict,
    duration: float,
    success: bool = True
) -> None:
    """Log API request and response for monitoring.

    Args:
        endpoint: API endpoint name
        input_data: Request input data (sanitized)
        response_data: Response data (sanitized)
        duration: Request duration in seconds
        success: Whether request was successful
    """
    log_level = "INFO" if success else "ERROR"
    status = "SUCCESS" if success else "ERROR"

    logger.bind(
        endpoint=endpoint,
        duration=duration,
        status=status,
        input_size=len(str(input_data)),
        output_size=len(str(response_data))
    ).log(
        log_level,
        f"API {status}: {endpoint} completed in {duration:.3f}s"
    )

    # Log detailed information at debug level
    logger.debug(f"Request input: {input_data}")
    logger.debug(f"Response output: {response_data}")


def log_ai_interaction(
    model_name: str,
    prompt_tokens: int,
    completion_tokens: int,
    duration: float,
    temperature: float,
    success: bool = True
) -> None:
    """Log AI model interaction for monitoring and cost tracking.

    Args:
        model_name: Name of AI model used
        prompt_tokens: Number of prompt tokens
        completion_tokens: Number of completion tokens
        duration: Interaction duration in seconds
        temperature: Model temperature setting
        success: Whether interaction was successful
    """
    total_tokens = prompt_tokens + completion_tokens
    status = "SUCCESS" if success else "ERROR"

    logger.bind(
        model=model_name,
        prompt_tokens=prompt_tokens,
        completion_tokens=completion_tokens,
        total_tokens=total_tokens,
        duration=duration,
        temperature=temperature,
        status=status
    ).info(
        f"AI {status}: {model_name} used {total_tokens} tokens in {duration:.3f}s"
    )


def log_error_with_context(
    error: Exception,
    context: dict,
    endpoint: str = "unknown"
) -> None:
    """Log error with contextual information for debugging.

    Args:
        error: Exception that occurred
        context: Additional context information
        endpoint: API endpoint where error occurred
    """
    logger.bind(
        endpoint=endpoint,
        error_type=type(error).__name__,
        **context
    ).error(
        f"Error in {endpoint}: {str(error)}"
    )


def get_logger(name: str):
    """Get logger instance for specific module.

    Args:
        name: Module name for logger

    Returns:
        Logger instance
    """
    return logger.bind(module=name)