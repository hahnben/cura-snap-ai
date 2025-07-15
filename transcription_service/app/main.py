from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import whisper
import os
from typing import Dict
import logging
from .config import config
from .security import (
    sanitize_filename, 
    validate_file_extension, 
    validate_audio_content, 
    create_secure_temp_file,
    safe_cleanup_temp_file,
    get_generic_error_message,
    SecurityError,
    PathTraversalError,
    InvalidFileError
)

# Configure logging
logging.basicConfig(level=getattr(logging, config.LOG_LEVEL))
logger = logging.getLogger(__name__)

app = FastAPI(title="Transcription Service", version="1.0.0")

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
    return {"message": "Transcription Service is running"}

@app.get("/health")
async def health_check():
    return {"status": "healthy", "model_loaded": model is not None}

@app.post("/transcribe")
async def transcribe_audio(file: UploadFile = File(...)) -> Dict[str, str]:
    """
    Transcribe audio file to text using the configured transcription engine.
    
    Args:
        file: Audio file (supported formats: .mp3, .wav, .webm, .m4a)
    
    Returns:
        JSON response with transcribed text
    """
    if not model:
        logger.error("Whisper model not loaded")
        raise HTTPException(status_code=500, detail=get_generic_error_message('server_error'))
    
    temp_file_path = None
    
    try:
        # Sanitize filename (prevents path traversal)
        sanitized_filename = sanitize_filename(file.filename)
        logger.info(f"Processing file: {sanitized_filename}")
        
        # Validate file extension
        file_extension = validate_file_extension(sanitized_filename, config.ALLOWED_EXTENSIONS)
        
        # Read and validate file content
        file_content = await file.read()
        
        # Validate file size
        if len(file_content) > config.MAX_FILE_SIZE:
            logger.warning(f"File too large: {len(file_content)} bytes")
            raise HTTPException(
                status_code=413, 
                detail=get_generic_error_message('file_size')
            )
        
        # Validate audio content using magic numbers
        if not validate_audio_content(file_content, file_extension):
            logger.warning(f"Invalid audio content for file: {sanitized_filename}")
            raise HTTPException(
                status_code=400,
                detail=get_generic_error_message('file_validation')
            )
        
        # Create secure temporary file
        temp_file_path = create_secure_temp_file(file_content, file_extension)
        
        # Transcribe audio using Whisper
        logger.info(f"Starting transcription for: {sanitized_filename}")
        result = model.transcribe(temp_file_path)
        logger.info(f"Transcription completed for: {sanitized_filename}")
        
        return {"transcript": result["text"].strip()}
        
    except PathTraversalError as e:
        logger.error(f"Path traversal attempt: {e}")
        raise HTTPException(status_code=400, detail=get_generic_error_message('path_traversal'))
        
    except InvalidFileError as e:
        logger.error(f"Invalid file: {e}")
        raise HTTPException(status_code=400, detail=get_generic_error_message('file_validation'))
        
    except SecurityError as e:
        logger.error(f"Security error: {e}")
        raise HTTPException(status_code=500, detail=get_generic_error_message('server_error'))
        
    except Exception as e:
        logger.error(f"Transcription failed: {e}")
        raise HTTPException(status_code=500, detail=get_generic_error_message('processing'))
        
    finally:
        # Always clean up temporary file
        if temp_file_path:
            safe_cleanup_temp_file(temp_file_path)

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=config.HOST, port=config.PORT)