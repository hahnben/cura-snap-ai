from fastapi import FastAPI, UploadFile, File, HTTPException
from fastapi.responses import JSONResponse
import os
from typing import Dict
import logging
from .config import config
from .security import (
    sanitize_filename, 
    validate_file_extension, 
    validate_audio_content, 
    get_generic_error_message,
    SecurityError,
    PathTraversalError,
    InvalidFileError
)

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
    
    try:
        # Sanitize filename (prevents path traversal)
        sanitized_filename = sanitize_filename(file.filename)
        logger.info(f"Processing mock transcription for: {sanitized_filename}")
        
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
        
        # Validate audio content using magic numbers (optional for mock)
        if not validate_audio_content(file_content, file_extension):
            logger.warning(f"Invalid audio content for file: {sanitized_filename}")
            # For mock service, we'll be more lenient
            logger.info("Mock service: allowing file despite invalid magic number")
        
        # Mock transcription result
        mock_transcript = f"Mock transcription for {sanitized_filename} - This is a test transcription."
        
        return {"transcript": mock_transcript}
        
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
        logger.error(f"Mock transcription failed: {e}")
        raise HTTPException(status_code=500, detail=get_generic_error_message('processing'))

if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host=config.HOST, port=config.PORT)