"""HTTP client with retry mechanism for reliable API calls.

This module provides a robust HTTP client with automatic retries,
exponential backoff, and proper error handling for OpenAI API calls.
"""

import httpx
from httpx import AsyncClient, HTTPStatusError
from tenacity import (
    retry_if_exception_type,
    stop_after_attempt,
    wait_exponential,
    retry_if_result
)
from typing import Optional
from loguru import logger

from pydantic_ai.retries import AsyncTenacityTransport, RetryConfig
from .config import Config


def should_retry_status(response: httpx.Response) -> bool:
    """Determine if HTTP response should trigger a retry.

    Args:
        response: HTTP response to check

    Returns:
        True if response indicates a retryable error
    """
    # Retry on server errors and rate limiting
    retryable_status_codes = {429, 500, 502, 503, 504}
    return response.status_code in retryable_status_codes


def create_retrying_client(config: Config) -> AsyncClient:
    """Create an HTTP client with smart retry handling.

    This client will automatically retry on:
    - Network connection errors
    - HTTP status errors (429, 5xx)
    - Timeout exceptions

    Args:
        config: Service configuration containing retry settings

    Returns:
        Configured async HTTP client with retry capabilities
    """
    def validate_response(response: httpx.Response) -> None:
        """Validate HTTP response and raise exceptions for retryable errors."""
        if should_retry_status(response):
            logger.warning(
                f"Retryable HTTP error: {response.status_code} - {response.text[:100]}"
            )
            response.raise_for_status()

    # Configure retry transport
    transport = AsyncTenacityTransport(
        config=RetryConfig(
            # Retry on HTTP errors and connection issues
            retry=retry_if_exception_type((
                HTTPStatusError,
                httpx.ConnectError,
                httpx.TimeoutException,
                httpx.ReadError,
                ConnectionError
            )),
            # Smart waiting with exponential backoff
            wait=wait_exponential(
                multiplier=config.retry_delay,
                max=60,  # Maximum wait time
                min=config.retry_delay  # Minimum wait time
            ),
            # Stop after configured number of attempts
            stop=stop_after_attempt(config.max_retries),
            # Re-raise the last exception if all retries fail
            reraise=True
        ),
        validate_response=validate_response
    )

    # Create client with retry transport and reasonable timeouts
    client = AsyncClient(
        transport=transport,
        timeout=httpx.Timeout(
            connect=10.0,   # Connection timeout
            read=60.0,      # Read timeout for long AI responses
            write=10.0,     # Write timeout
            pool=10.0       # Pool timeout
        ),
        limits=httpx.Limits(
            max_keepalive_connections=10,
            max_connections=20
        )
    )

    logger.info(
        f"Created HTTP client with {config.max_retries} retries, "
        f"{config.retry_delay}s base delay"
    )

    return client


class HTTPClientManager:
    """Manager for HTTP client lifecycle."""

    def __init__(self, config: Config):
        self.config = config
        self._client: Optional[AsyncClient] = None

    async def get_client(self) -> AsyncClient:
        """Get or create HTTP client instance.

        Returns:
            Configured async HTTP client
        """
        if self._client is None:
            self._client = create_retrying_client(self.config)
        return self._client

    async def close(self) -> None:
        """Close HTTP client and cleanup resources."""
        if self._client is not None:
            await self._client.aclose()
            self._client = None
            logger.debug("HTTP client closed")

    async def __aenter__(self) -> AsyncClient:
        """Async context manager entry."""
        return await self.get_client()

    async def __aexit__(self, exc_type, exc_val, exc_tb) -> None:
        """Async context manager exit."""
        await self.close()


# Global client manager instance
_client_manager: Optional[HTTPClientManager] = None


def get_client_manager(config: Config) -> HTTPClientManager:
    """Get global client manager instance.

    Args:
        config: Service configuration

    Returns:
        HTTP client manager
    """
    global _client_manager
    if _client_manager is None:
        _client_manager = HTTPClientManager(config)
    return _client_manager


async def get_http_client(config: Config) -> AsyncClient:
    """Get configured HTTP client for dependency injection.

    Args:
        config: Service configuration

    Returns:
        Configured async HTTP client
    """
    manager = get_client_manager(config)
    return await manager.get_client()