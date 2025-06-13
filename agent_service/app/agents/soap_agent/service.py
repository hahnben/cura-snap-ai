# app/agents/soap_agent/service.py

from app.agents.soap_agent.models import TranscriptInput, SoapNote
from app.agents.soap_agent.prompts import build_prompt
from pydantic_ai import Agent
from pydantic_ai.models.openai import OpenAIModel
from dotenv import load_dotenv
import os

load_dotenv()

# Configure the OpenAI model
# Ensure the environment variable for the LLM model is set
llm = os.getenv('LLM_MODEL', 'gpt-4o')
model = OpenAIModel(
    llm,
    provider="openai",
)

# Initialize the agent with the model and system prompt
agent = Agent(
    model=model,
    system_prompt="Du bist ein medizinischer Assistent. Erstelle eine strukturierte SOAP-Notiz.",
    retries=1
)



async def format_transcript_to_soap(input_data: TranscriptInput) -> SoapNote:
    prompt = build_prompt(input_data.transcript)
    return await agent.run(prompt)

