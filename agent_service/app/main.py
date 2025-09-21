"""Main FastAPI application with middleware, exception handlers, and routing."""

import time
from contextlib import asynccontextmanager
from datetime import datetime

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware
from fastapi.middleware.gzip import GZipMiddleware
from fastapi.responses import JSONResponse
from loguru import logger

from .core.config import config
from .core.logging import setup_logging
from .core.exceptions import (
    AgentServiceException,
    SoapGenerationError,
    ValidationError,
    ModelError,
    agent_service_exception_handler,
    soap_generation_exception_handler,
    validation_exception_handler,
    model_exception_handler,
    generic_exception_handler
)
from .core.schemas import HealthCheckResponse
from .agents.soap_agent.router import router as soap_router

# Track service start time for uptime calculation
SERVICE_START_TIME = time.time()


@asynccontextmanager
async def lifespan(app: FastAPI):
    """Application lifespan events."""
    # Startup
    setup_logging(config)
    logger.info(f"Starting {config.service_name} v{config.service_version}")
    logger.info(f"Configuration: {config.llm_model} with temperature {config.temperature}")

    yield

    # Shutdown
    logger.info("Shutting down Agent Service")


# Create FastAPI application
app = FastAPI(
    title="CuraSnap AI Agent Service",
    description="Medical transcript to SOAP note conversion service using AI",
    version=config.service_version,
    docs_url="/docs",
    redoc_url="/redoc",
    lifespan=lifespan
)

# Add CORS middleware
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],  # Configure appropriately for production
    allow_credentials=True,
    allow_methods=["GET", "POST"],
    allow_headers=["*"],
)

# Add GZip compression middleware
app.add_middleware(
    GZipMiddleware,
    minimum_size=1000,
    compresslevel=6
)


# Request timing middleware
@app.middleware("http")
async def add_process_time_header(request: Request, call_next):
    """Add request processing time to response headers."""
    start_time = time.time()
    response = await call_next(request)
    process_time = time.time() - start_time
    response.headers["X-Process-Time"] = str(round(process_time, 4))

    # Log request for monitoring
    logger.info(
        f"{request.method} {request.url.path} - "
        f"Status: {response.status_code} - "
        f"Time: {process_time:.4f}s"
    )

    return response


# Register exception handlers
app.add_exception_handler(AgentServiceException, agent_service_exception_handler)
app.add_exception_handler(SoapGenerationError, soap_generation_exception_handler)
app.add_exception_handler(ValidationError, validation_exception_handler)
app.add_exception_handler(ModelError, model_exception_handler)
app.add_exception_handler(Exception, generic_exception_handler)

# Register routers
app.include_router(soap_router, prefix="/api")


@app.get(
    "/",
    response_model=HealthCheckResponse,
    summary="Root Health Check",
    description="Basic health check endpoint"
)
async def read_root() -> HealthCheckResponse:
    """Root endpoint with basic service information."""
    uptime = time.time() - SERVICE_START_TIME

    return HealthCheckResponse(
        status="healthy",
        service=config.service_name,
        version=config.service_version,
        model_loaded=True,  # Will be determined by actual agent initialization
        uptime=round(uptime, 2)
    )


@app.get(
    "/health",
    response_model=HealthCheckResponse,
    summary="Detailed Health Check",
    description="Comprehensive health check with service details"
)
async def health_check() -> HealthCheckResponse:
    """Detailed health check endpoint."""
    uptime = time.time() - SERVICE_START_TIME

    return HealthCheckResponse(
        status="healthy",
        service=config.service_name,
        version=config.service_version,
        model_loaded=True,
        uptime=round(uptime, 2)
    )


@app.get("/metrics", include_in_schema=False)
async def metrics():
    """Basic metrics endpoint for monitoring."""
    uptime = time.time() - SERVICE_START_TIME

    return {
        "service": config.service_name,
        "version": config.service_version,
        "uptime_seconds": round(uptime, 2),
        "status": "healthy",
        "timestamp": datetime.utcnow().isoformat()
    }


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(
        "app.main:app",
        host=config.host,
        port=config.port,
        reload=True,
        log_level=config.log_level.lower()
    )
