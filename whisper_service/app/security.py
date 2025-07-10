import os
import re
import tempfile
import secrets
from typing import Optional, Tuple
import logging

logger = logging.getLogger(__name__)

class SecurityError(Exception):
    """Base exception for security-related errors"""
    pass

class PathTraversalError(SecurityError):
    """Exception raised when path traversal is detected"""
    pass

class InvalidFileError(SecurityError):
    """Exception raised when file validation fails"""
    pass

# Audio file magic numbers for validation
AUDIO_MAGIC_NUMBERS = {
    b'\xFF\xFB': '.mp3',  # MP3
    b'\xFF\xF3': '.mp3',  # MP3
    b'\xFF\xF2': '.mp3',  # MP3
    b'RIFF': '.wav',      # WAV (needs further check)
    b'OggS': '.ogg',      # OGG
    b'fLaC': '.flac',     # FLAC
    b'\x00\x00\x00\x20ftypM4A': '.m4a',  # M4A
    b'\x00\x00\x00\x18ftypmp42': '.mp4',  # MP4
}

def sanitize_filename(filename: str) -> str:
    """
    Sanitize filename to prevent path traversal attacks.
    
    Args:
        filename: Original filename from upload
        
    Returns:
        Sanitized filename (basename only)
        
    Raises:
        PathTraversalError: If filename contains dangerous patterns
    """
    if not filename:
        raise PathTraversalError("Empty filename not allowed")
    
    # Extract basename to prevent path traversal
    basename = os.path.basename(filename)
    
    # Check for dangerous patterns
    dangerous_patterns = [
        '..',          # Parent directory
        '/',           # Unix path separator
        '\\',          # Windows path separator
        '\x00',        # Null byte
        '\r',          # Carriage return
        '\n',          # Line feed
    ]
    
    for pattern in dangerous_patterns:
        if pattern in basename:
            raise PathTraversalError(f"Filename contains dangerous pattern: {pattern}")
    
    # Check for valid characters (alphanumeric, dash, underscore, dot)
    if not re.match(r'^[a-zA-Z0-9._-]+$', basename):
        raise PathTraversalError("Filename contains invalid characters")
    
    # Prevent hidden files
    if basename.startswith('.'):
        raise PathTraversalError("Hidden files not allowed")
    
    # Prevent overly long filenames
    if len(basename) > 255:
        raise PathTraversalError("Filename too long")
    
    return basename

def validate_file_extension(filename: str, allowed_extensions: set) -> str:
    """
    Validate file extension.
    
    Args:
        filename: Sanitized filename
        allowed_extensions: Set of allowed extensions
        
    Returns:
        Validated file extension
        
    Raises:
        InvalidFileError: If extension is not allowed
    """
    extension = os.path.splitext(filename)[1].lower()
    
    if not extension:
        raise InvalidFileError("File must have an extension")
    
    if extension not in allowed_extensions:
        raise InvalidFileError(
            f"Unsupported file format: {extension}. "
            f"Allowed: {', '.join(sorted(allowed_extensions))}"
        )
    
    return extension

def validate_audio_content(file_content: bytes, expected_extension: str) -> bool:
    """
    Validate audio file content using magic numbers.
    
    Args:
        file_content: Raw file content
        expected_extension: Expected file extension
        
    Returns:
        True if content matches expected format
    """
    if len(file_content) < 20:
        return False
    
    # Check magic numbers
    for magic_bytes, extension in AUDIO_MAGIC_NUMBERS.items():
        if file_content.startswith(magic_bytes):
            # Special case for WAV files
            if magic_bytes == b'RIFF' and expected_extension == '.wav':
                # WAV files have "WAVE" at offset 8
                return len(file_content) > 12 and file_content[8:12] == b'WAVE'
            return extension == expected_extension
    
    # If no magic number matches, it's suspicious
    logger.warning(f"No magic number found for file with extension {expected_extension}")
    return False

def create_secure_temp_file(file_content: bytes, extension: str) -> str:
    """
    Create a secure temporary file.
    
    Args:
        file_content: File content to write
        extension: File extension
        
    Returns:
        Path to created temporary file
        
    Raises:
        SecurityError: If file creation fails
    """
    try:
        # Create secure temporary file with restricted permissions
        fd, temp_path = tempfile.mkstemp(
            suffix=extension,
            prefix=f"whisper_audio_{secrets.token_hex(8)}_"
        )
        
        try:
            # Set restrictive permissions (owner read/write only)
            os.chmod(temp_path, 0o600)
            
            # Write content
            with os.fdopen(fd, 'wb') as temp_file:
                temp_file.write(file_content)
            
            return temp_path
            
        except Exception as e:
            # Clean up on error
            try:
                os.unlink(temp_path)
            except:
                pass
            raise SecurityError(f"Failed to create secure temporary file: {e}")
            
    except Exception as e:
        raise SecurityError(f"Failed to create temporary file: {e}")

def safe_cleanup_temp_file(temp_path: str) -> None:
    """
    Safely remove temporary file.
    
    Args:
        temp_path: Path to temporary file
    """
    try:
        if os.path.exists(temp_path):
            # Ensure file is writable before deletion
            os.chmod(temp_path, 0o600)
            os.unlink(temp_path)
            logger.debug(f"Cleaned up temporary file: {temp_path}")
    except Exception as e:
        logger.error(f"Failed to cleanup temporary file {temp_path}: {e}")

def get_generic_error_message(error_type: str) -> str:
    """
    Get generic error message for client (no sensitive information).
    
    Args:
        error_type: Type of error
        
    Returns:
        Generic error message
    """
    generic_messages = {
        'file_validation': 'Invalid file format or content',
        'file_size': 'File size exceeds maximum allowed size',
        'processing': 'Audio processing failed',
        'server_error': 'Internal server error occurred',
        'path_traversal': 'Invalid filename provided',
    }
    
    return generic_messages.get(error_type, 'An error occurred while processing your request')