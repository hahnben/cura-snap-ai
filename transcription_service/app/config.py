import os
from dotenv import load_dotenv

load_dotenv()

class Config:
    """Configuration settings for Transcription Service"""
    
    # Server settings
    HOST = os.getenv("HOST", "0.0.0.0")
    PORT = int(os.getenv("PORT", "8002"))
    
    # Transcription engine settings (currently Whisper)
    WHISPER_MODEL = os.getenv("WHISPER_MODEL", "base")
    
    # File upload settings
    MAX_FILE_SIZE = int(os.getenv("MAX_FILE_SIZE", "26214400"))  # 25MB in bytes
    ALLOWED_EXTENSIONS = {'.mp3', '.wav', '.webm', '.m4a', '.ogg', '.flac'}
    
    # Logging
    LOG_LEVEL = os.getenv("LOG_LEVEL", "INFO")

config = Config()