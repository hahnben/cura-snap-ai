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
    output_model=str,  # Expecting a single text block
    retries=1
)



async def format_transcript_to_soap(input_data: TranscriptInput) -> SoapNote:
    """
    Accepts a transcript and transforms it into a structured SOAP note using an LLM.
    The result is wrapped into a SoapNote object with a single text field.
    """
    # Build the GPT prompt from the transcript
    prompt = build_prompt(input_data.transcript)

    # Run the agent and receive plain structured text (as a single string)
    result = await agent.run(prompt)
    raw_output = result.output


    # Return the result as a SoapNote (containing a single field: structured_text)
    return SoapNote(structured_text=raw_output)

