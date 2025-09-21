"""Dependency injection for Agent Service.

This module provides dependency injection functions for FastAPI
to manage configuration, clients, and service instances.
"""

from functools import lru_cache
from fastapi import Depends
from httpx import AsyncClient

from .config import Config, config
from .client import get_http_client
from ..agents.soap_agent.service import SoapAgentService


@lru_cache()
def get_config() -> Config:
    """Get application configuration.

    This function is cached to ensure we use the same configuration
    instance throughout the application lifecycle.

    Returns:
        Application configuration
    """
    return config


async def get_http_client_dependency(
    config: Config = Depends(get_config)
) -> AsyncClient:
    """Get HTTP client for dependency injection.

    Args:
        config: Application configuration

    Returns:
        Configured HTTP client with retry capabilities
    """
    return await get_http_client(config)


async def get_soap_agent_service(
    config: Config = Depends(get_config),
    http_client: AsyncClient = Depends(get_http_client_dependency)
) -> SoapAgentService:
    """Get SOAP agent service instance.

    Args:
        config: Application configuration
        http_client: HTTP client with retry capabilities

    Returns:
        Configured SOAP agent service
    """
    return SoapAgentService(config=config, http_client=http_client)