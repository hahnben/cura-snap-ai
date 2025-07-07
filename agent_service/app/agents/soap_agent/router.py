# app/agents/soap_agent/router.py

from fastapi import APIRouter
from app.agents.soap_agent.models import TranscriptInput, SoapNote
from app.agents.soap_agent.service import format_transcript_to_soap

router = APIRouter()

@router.post("/format_note", response_model=SoapNote)
async def format_note(input_data: TranscriptInput) -> SoapNote:
    """
    Receives unstructured transcript and returns a structured SOAP note.
    """
    return await format_transcript_to_soap(input_data)
