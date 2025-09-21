"""Enhanced SOAP Agent Service with validation and error handling.

This service provides robust SOAP note generation with output validation,
retry mechanisms, and comprehensive error handling.
"""

import time
from datetime import datetime
from typing import Dict, Any, Optional
from httpx import AsyncClient

from pydantic_ai import Agent, RunContext, ModelRetry
from pydantic_ai.models.openai import OpenAIChatModel
from pydantic_ai.providers.openai import OpenAIProvider
from pydantic_ai import ModelSettings
from loguru import logger

from ...core.config import Config
from ...core.exceptions import (
    SoapGenerationError,
    ModelError,
    ValidationError,
    RetryExhaustedError
)
from ...core.logging import log_ai_interaction, log_error_with_context
from ...core.schemas import (
    TranscriptInput,
    SoapNote,
    SoapNoteResponse,
    ValidationDetails
)
from .prompts import build_prompt


class SoapAgentService:
    """Service for generating medical SOAP notes from transcripts."""

    def __init__(self, config: Config, http_client: AsyncClient):
        """Initialize SOAP agent service.

        Args:
            config: Service configuration
            http_client: HTTP client with retry capabilities
        """
        self.config = config
        self.http_client = http_client
        self._agent: Optional[Agent] = None
        self._service_start_time = time.time()

        logger.info("Initializing SOAP Agent Service")

    def _get_agent(self) -> Agent:
        """Get or create Pydantic AI agent instance.

        Returns:
            Configured Pydantic AI agent
        """
        if self._agent is None:
            try:
                # Configure OpenAI model with settings
                model = OpenAIChatModel(
                    self.config.llm_model,
                    provider=OpenAIProvider(
                        api_key=self.config.openai_api_key,
                        http_client=self.http_client
                    ),
                    settings=ModelSettings(
                        temperature=self.config.temperature,
                        max_tokens=self.config.max_tokens
                    )
                )

                # Create agent with validation
                self._agent = Agent(
                    model=model,
                    system_prompt=self.config.system_prompt,
                    retries=self.config.max_retries
                )

                # Register output validator
                @self._agent.output_validator
                async def validate_soap_note(ctx: RunContext, output: SoapNote) -> SoapNote:
                    """Validate generated SOAP note quality and structure."""
                    return await self._validate_soap_output(output)

                logger.info(
                    f"Agent initialized with model {self.config.llm_model}, "
                    f"temperature {self.config.temperature}"
                )

            except Exception as e:
                logger.error(f"Failed to initialize agent: {e}")
                raise ModelError(
                    f"Failed to initialize AI model: {str(e)}",
                    details={"model": self.config.llm_model}
                )

        return self._agent

    async def _validate_soap_output(self, output: SoapNote) -> SoapNote:
        """Validate SOAP note output for quality and completeness.

        Args:
            output: Generated SOAP note

        Returns:
            Validated SOAP note

        Raises:
            ModelRetry: If validation fails and retry is possible
        """
        validation = self._validate_soap_structure(output.structured_text)

        if not validation.is_valid:
            error_msg = "; ".join(validation.validation_errors)
            logger.warning(f"SOAP validation failed: {error_msg}")
            raise ModelRetry(f"Generated SOAP note failed validation: {error_msg}")

        logger.debug("SOAP note validation passed")
        return output

    def _validate_soap_structure(self, text: str) -> ValidationDetails:
        """Validate SOAP note structure and content.

        Args:
            text: SOAP note text to validate

        Returns:
            Validation details
        """
        validation_errors = []
        required_sections = [
            "ANAMNESE:",
            "UNTERSUCHUNG",
            "BEURTEILUNG:",
            "PROZEDERE",
            "DIAGNOSEN:"
        ]

        text_upper = text.upper()

        # Check for empty or too short content
        if not text.strip():
            validation_errors.append("SOAP note is empty")

        if len(text.strip()) < 50:
            validation_errors.append("SOAP note too short (minimum 50 characters)")

        # Check for required sections
        missing_sections = []
        found_sections = []

        for section in required_sections:
            if section in text_upper:
                found_sections.append(section)
            else:
                missing_sections.append(section)

        if missing_sections:
            validation_errors.append(f"Missing required sections: {', '.join(missing_sections)}")

        # Check for forbidden characters (like asterisks)
        if "*" in text:
            validation_errors.append("SOAP note contains forbidden asterisks (*)")

        # Calculate content quality score
        quality_score = self._calculate_quality_score(text, found_sections, required_sections)

        return ValidationDetails(
            is_valid=len(validation_errors) == 0,
            validation_errors=validation_errors,
            required_sections_found=found_sections,
            missing_sections=missing_sections,
            content_quality_score=quality_score
        )

    def _calculate_quality_score(
        self,
        text: str,
        found_sections: list[str],
        required_sections: list[str]
    ) -> float:
        """Calculate content quality score.

        Args:
            text: SOAP note text
            found_sections: Sections found in the text
            required_sections: Required sections

        Returns:
            Quality score from 0.0 to 1.0
        """
        # Base score from section completeness
        section_score = len(found_sections) / len(required_sections)

        # Length score (optimal range 200-2000 characters)
        length = len(text)
        if length < 100:
            length_score = length / 100
        elif length > 2000:
            length_score = 1.0 - min((length - 2000) / 2000, 0.5)
        else:
            length_score = 1.0

        # Content richness score (based on word count and medical terms)
        word_count = len(text.split())
        word_score = min(word_count / 100, 1.0)  # Optimal around 100 words

        # Combined score
        return (section_score * 0.5 + length_score * 0.3 + word_score * 0.2)

    async def format_transcript_to_soap(self, input_data: TranscriptInput) -> SoapNoteResponse:
        """Convert transcript to structured SOAP note.

        Args:
            input_data: Input transcript data

        Returns:
            SOAP note response with metadata

        Raises:
            SoapGenerationError: If SOAP generation fails
            ValidationError: If input validation fails
        """
        start_time = time.time()

        try:
            # Validate input
            if not input_data.transcript.strip():
                raise ValidationError("Transcript cannot be empty")

            if len(input_data.transcript) > 50000:
                raise ValidationError("Transcript too long (maximum 50,000 characters)")

            logger.info(f"Processing transcript of {len(input_data.transcript)} characters")

            # Get agent and build prompt
            agent = self._get_agent()
            prompt = build_prompt(input_data.transcript)

            # Generate SOAP note
            try:
                result = await agent.run(prompt)
                soap_note = result.output

                # Prepare metadata
                generation_time = time.time() - start_time
                metadata = {
                    "generation_time": round(generation_time, 3),
                    "model_used": self.config.llm_model,
                    "temperature": self.config.temperature,
                    "max_tokens": self.config.max_tokens,
                    "input_length": len(input_data.transcript),
                    "output_length": len(soap_note.structured_text)
                }

                # Add token usage if available
                if hasattr(result, "usage") and result.usage:
                    metadata["token_usage"] = {
                        "prompt_tokens": getattr(result.usage, "prompt_tokens", 0),
                        "completion_tokens": getattr(result.usage, "completion_tokens", 0),
                        "total_tokens": getattr(result.usage, "total_tokens", 0)
                    }

                    # Log AI interaction for monitoring
                    log_ai_interaction(
                        model_name=self.config.llm_model,
                        prompt_tokens=metadata["token_usage"]["prompt_tokens"],
                        completion_tokens=metadata["token_usage"]["completion_tokens"],
                        duration=generation_time,
                        temperature=self.config.temperature,
                        success=True
                    )

                logger.info(f"SOAP note generated successfully in {generation_time:.3f}s")

                return SoapNoteResponse(
                    soap_note=soap_note,
                    metadata=metadata,
                    timestamp=datetime.utcnow()
                )

            except ModelRetry as e:
                # This should be handled by the agent's retry mechanism
                # If we get here, all retries were exhausted
                raise RetryExhaustedError(
                    f"Failed to generate valid SOAP note after {self.config.max_retries} attempts: {str(e)}",
                    details={"last_error": str(e)}
                )

            except Exception as e:
                # Log the error with context
                log_error_with_context(
                    error=e,
                    context={
                        "input_length": len(input_data.transcript),
                        "model": self.config.llm_model,
                        "temperature": self.config.temperature
                    },
                    endpoint="format_transcript_to_soap"
                )

                raise SoapGenerationError(
                    f"AI model error: {str(e)}",
                    details={
                        "model": self.config.llm_model,
                        "error_type": type(e).__name__
                    }
                )

        except (ValidationError, SoapGenerationError, RetryExhaustedError):
            # Re-raise our custom exceptions
            raise

        except Exception as e:
            # Handle unexpected errors
            generation_time = time.time() - start_time
            log_error_with_context(
                error=e,
                context={
                    "input_length": len(input_data.transcript) if input_data else 0,
                    "generation_time": generation_time
                },
                endpoint="format_transcript_to_soap"
            )

            raise SoapGenerationError(
                f"Unexpected error during SOAP generation: {str(e)}",
                details={"error_type": type(e).__name__}
            )

    def get_service_status(self) -> Dict[str, Any]:
        """Get service status information.

        Returns:
            Service status dictionary
        """
        uptime = time.time() - self._service_start_time

        return {
            "status": "healthy",
            "model_loaded": self._agent is not None,
            "uptime": round(uptime, 2),
            "config": {
                "model": self.config.llm_model,
                "temperature": self.config.temperature,
                "max_tokens": self.config.max_tokens,
                "max_retries": self.config.max_retries
            }
        }