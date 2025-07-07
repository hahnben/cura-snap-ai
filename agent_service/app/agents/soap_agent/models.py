# app/agents/soap_agent/models.py

from pydantic import BaseModel

class TranscriptInput(BaseModel):
    transcript: str

class SoapNote(BaseModel):
    structured_text: str

    class Config:
        json_schema_extra = {
            "example": {
                "structured_text": (
                    "ANAMNESE:\nDer Patient berichtet über...\n\n"
                    "UNTERSUCHUNG und BEFUNDE:\n...\n\n"
                    "BEURTEILUNG:\n...\n\n"
                    "PROZEDERE und THERAPIE:\n...\n\n"
                    "AKTUELLE DIAGNOSEN:\n..."
                )
            }
        }
