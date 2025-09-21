"""Dependency injection for Transcription Service.

This module provides dependency injection functions for FastAPI
to manage configuration and model instances.
"""

from functools import lru_cache
from fastapi import Depends
import whisper

from .config import config


@lru_cache()
def get_config():
    """Get application configuration.

    This function is cached to ensure we use the same configuration
    instance throughout the application lifecycle.

    Returns:
        Application configuration
    """
    return config


@lru_cache()
def get_whisper_model():
    """Get Whisper model instance.

    This function is cached to ensure we load the model only once
    and reuse it across requests.

    Returns:
        Loaded Whisper model
    """
    return whisper.load_model(config.WHISPER_MODEL)