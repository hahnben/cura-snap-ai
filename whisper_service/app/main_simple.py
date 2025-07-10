from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import tempfile
import os
from typing import Dict
import logging
from .config import config

# Configure logging
logging.basicConfig(level=getattr(logging, config.LOG_LEVEL))
logger = logging.getLogger(__name__)

app = FastAPI(title="Whisper Service", version="1.0.0")

@app.get("/")
async def root():
    return {"message": "Whisper Service is running"}

@app.get("/health")
async def health_check():
    return {"status": "healthy", "model_loaded": False}

@app.post("/transcribe")
async def transcribe_audio(file: UploadFile = File(...)) -> Dict[str, str]:
    """
    Mock transcription endpoint for testing without Whisper model.
    
    Args:
        file: Audio file (supported formats: .mp3, .wav, .webm, .m4a)
    
    Returns:
        JSON response with mock transcribed text
    """
    
    # Validate file type
    file_extension = os.path.splitext(file.filename)[1].lower()
    
    if file_extension not in config.ALLOWED_EXTENSIONS:
        raise HTTPException(
            status_code=400, 
            detail=f"Unsupported file format: {file_extension}. Supported formats: {', '.join(config.ALLOWED_EXTENSIONS)}"
        )
    
    # Validate file size
    max_size = config.MAX_FILE_SIZE
    
    try:
        # Read file content
        file_content = await file.read()
        
        if len(file_content) > max_size:
            raise HTTPException(
                status_code=413, 
                detail=f"File too large. Maximum size: {max_size // (1024*1024)}MB"
            )
        
        logger.info(f"Mock transcription for file: {file.filename}")
        
        # Mock transcription result
        mock_transcript = f"Mock transcription for {file.filename} - This is a test transcription."
        
        return {"transcript": mock_transcript}
    
    except Exception as e:
        logger.error(f"Transcription failed: {e}")
        raise HTTPException(status_code=500, detail=f"Transcription failed: {str(e)}")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=config.HOST, port=config.PORT)