"""Configuration management for Agent Service.

This module provides centralized configuration with validation,
environment variable support, and sensible defaults.
"""

import os
from typing import Optional
from pydantic import BaseModel, Field
from dotenv import load_dotenv

# Load environment variables
load_dotenv()


class Config(BaseModel):
    """Configuration class for the Agent Service.

    All configuration values can be overridden via environment variables.
    """

    # AI Model Configuration
    llm_model: str = Field(
        default="gpt-4o",
        description="OpenAI model to use for SOAP generation"
    )
    openai_api_key: Optional[str] = Field(
        default=None,
        description="OpenAI API key (will use OPENAI_API_KEY env var if not set)"
    )
    temperature: float = Field(
        default=0.7,
        ge=0.0,
        le=2.0,
        description="Temperature for AI model (0.0 = deterministic, 2.0 = very creative)"
    )
    max_tokens: int = Field(
        default=1000,
        gt=0,
        le=4000,
        description="Maximum tokens for AI response"
    )

    # Service Configuration
    host: str = Field(
        default="0.0.0.0",
        description="Host to bind the service to"
    )
    port: int = Field(
        default=8001,
        gt=0,
        le=65535,
        description="Port to bind the service to"
    )
    log_level: str = Field(
        default="INFO",
        description="Logging level (DEBUG, INFO, WARNING, ERROR)"
    )

    # System Prompt Configuration
    system_prompt: str = Field(
        default="Du bist ein medizinischer Assistent. Erstelle eine strukturierte SOAP-Notiz.",
        description="System prompt for SOAP generation"
    )

    # Retry Configuration
    max_retries: int = Field(
        default=3,
        ge=1,
        le=10,
        description="Maximum number of retries for OpenAI API calls"
    )
    retry_delay: float = Field(
        default=1.0,
        ge=0.1,
        le=60.0,
        description="Base delay between retries in seconds"
    )

    # Health Check Configuration
    service_name: str = Field(
        default="agent_service",
        description="Name of the service for health checks"
    )
    service_version: str = Field(
        default="1.0.0",
        description="Version of the service"
    )

    @classmethod
    def from_env(cls) -> "Config":
        """Create configuration from environment variables."""
        return cls(
            llm_model=os.getenv("LLM_MODEL", "gpt-4o"),
            openai_api_key=os.getenv("OPENAI_API_KEY"),
            temperature=float(os.getenv("TEMPERATURE", "0.7")),
            max_tokens=int(os.getenv("MAX_TOKENS", "1000")),
            host=os.getenv("HOST", "0.0.0.0"),
            port=int(os.getenv("PORT", "8001")),
            log_level=os.getenv("LOG_LEVEL", "INFO"),
            system_prompt=os.getenv("SYSTEM_PROMPT", "Du bist ein medizinischer Assistent. Erstelle eine strukturierte SOAP-Notiz."),
            max_retries=int(os.getenv("MAX_RETRIES", "3")),
            retry_delay=float(os.getenv("RETRY_DELAY", "1.0")),
            service_name=os.getenv("SERVICE_NAME", "agent_service"),
            service_version=os.getenv("SERVICE_VERSION", "1.0.0")
        )

    def validate_config(self) -> None:
        """Validate configuration and raise errors for invalid settings."""
        if not self.openai_api_key:
            if not os.getenv("OPENAI_API_KEY"):
                raise ValueError("OPENAI_API_KEY environment variable is required")

        if self.log_level not in ["DEBUG", "INFO", "WARNING", "ERROR"]:
            raise ValueError(f"Invalid log level: {self.log_level}")


# Global configuration instance
config = Config.from_env()

# Validate configuration on import
try:
    config.validate_config()
except ValueError as e:
    # In production, you might want to log this and exit gracefully
    print(f"Configuration error: {e}")
    # For development, we'll continue but note the issue