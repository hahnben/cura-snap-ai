# app/main.py

from fastapi import FastAPI
from app.agents.soap_agent.router import router as soap_router

app = FastAPI()

# Register the SOAP agent route under a base path (e.g., /soap)
app.include_router(soap_router, prefix="/soap")

# Optional: Root endpoint for health checks
@app.get("/")
def read_root():
    return {"status": "Agent service is running"}
