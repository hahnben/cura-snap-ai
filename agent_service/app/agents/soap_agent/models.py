# app/agents/soap_agent/models.py

from pydantic import BaseModel

class TranscriptInput(BaseModel):
    transcript: str

class SoapNote(BaseModel):
    anamnese: str
    untersuchung_und_befunde: str
    beurteilung: str
    prozedere_und_therapie: str
    aktuelle_diagnosen: str
