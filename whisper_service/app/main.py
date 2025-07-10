from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import whisper
import tempfile
import os
from typing import Dict
import logging
from .config import config

# Configure logging
logging.basicConfig(level=getattr(logging, config.LOG_LEVEL))
logger = logging.getLogger(__name__)

app = FastAPI(title="Whisper Service", version="1.0.0")

# Load Whisper model on startup
model = None

@app.on_event("startup")
async def startup_event():
    global model
    try:
        logger.info(f"Loading Whisper model: {config.WHISPER_MODEL}")
        model = whisper.load_model(config.WHISPER_MODEL)
        logger.info("Whisper model loaded successfully")
    except Exception as e:
        logger.error(f"Failed to load Whisper model: {e}")
        raise

@app.get("/")
async def root():
    return {"message": "Whisper Service is running"}

@app.get("/health")
async def health_check():
    return {"status": "healthy", "model_loaded": model is not None}

@app.post("/transcribe")
async def transcribe_audio(file: UploadFile = File(...)) -> Dict[str, str]:
    """
    Transcribe audio file to text using Whisper model.
    
    Args:
        file: Audio file (supported formats: .mp3, .wav, .webm, .m4a)
    
    Returns:
        JSON response with transcribed text
    """
    if not model:
        raise HTTPException(status_code=500, detail="Whisper model not loaded")
    
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
        
        # Create temporary file for Whisper processing
        with tempfile.NamedTemporaryFile(delete=False, suffix=file_extension) as temp_file:
            temp_file.write(file_content)
            temp_file_path = temp_file.name
        
        try:
            logger.info(f"Transcribing audio file: {file.filename}")
            
            # Transcribe audio using Whisper
            result = model.transcribe(temp_file_path)
            
            logger.info(f"Transcription completed for: {file.filename}")
            
            return {"transcript": result["text"].strip()}
            
        finally:
            # Clean up temporary file
            if os.path.exists(temp_file_path):
                os.unlink(temp_file_path)
    
    except Exception as e:
        logger.error(f"Transcription failed: {e}")
        raise HTTPException(status_code=500, detail=f"Transcription failed: {str(e)}")

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=config.HOST, port=config.PORT)