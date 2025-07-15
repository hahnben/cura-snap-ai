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

# Audio file magic numbers for validation (enhanced with more patterns)
AUDIO_MAGIC_NUMBERS = {
    # MP3 patterns
    b'\xFF\xFB': '.mp3',  # MPEG-1 Layer 3
    b'\xFF\xF3': '.mp3',  # MPEG-2 Layer 3
    b'\xFF\xF2': '.mp3',  # MPEG-2.5 Layer 3
    b'\x49\x44\x33': '.mp3',  # MP3 with ID3 tag
    
    # WAV patterns
    b'RIFF': '.wav',      # WAV (needs further WAVE check)
    
    # OGG patterns
    b'OggS': '.ogg',      # OGG container
    
    # FLAC patterns
    b'fLaC': '.flac',     # FLAC native
    
    # M4A/MP4 patterns
    b'\x00\x00\x00\x20ftypM4A ': '.m4a',  # M4A container
    b'\x00\x00\x00\x18ftypmp42': '.mp4',  # MP4 container
    b'\x00\x00\x00\x20ftypmp42': '.mp4',  # MP4 container variant
    b'\x00\x00\x00\x18ftypM4A ': '.m4a',  # M4A container variant
    
    # WebM patterns
    b'\x1A\x45\xDF\xA3': '.webm',  # WebM/Matroska container
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
    Validate audio file content using magic numbers (enhanced validation).
    
    Args:
        file_content: Raw file content
        expected_extension: Expected file extension
        
    Returns:
        True if content matches expected format
    """
    if len(file_content) < 20:
        logger.warning(f"File too small for magic number validation: {len(file_content)} bytes")
        return False
    
    # Check magic numbers with enhanced validation
    for magic_bytes, extension in AUDIO_MAGIC_NUMBERS.items():
        if file_content.startswith(magic_bytes):
            # Special validation for specific formats
            if magic_bytes == b'RIFF' and expected_extension == '.wav':
                # WAV files have "WAVE" at offset 8
                if len(file_content) > 12 and file_content[8:12] == b'WAVE':
                    logger.debug("Valid WAV file detected")
                    return True
                else:
                    logger.warning("RIFF file found but not a valid WAV (missing WAVE signature)")
                    return False
            
            elif magic_bytes.startswith(b'\x00\x00\x00') and expected_extension in ['.m4a', '.mp4']:
                # Additional validation for M4A/MP4 containers
                if b'ftyp' in file_content[:32]:
                    logger.debug(f"Valid {expected_extension} container detected")
                    return extension == expected_extension
                else:
                    logger.warning("MP4 container found but invalid ftyp header")
                    return False
            
            elif magic_bytes == b'\x1A\x45\xDF\xA3' and expected_extension == '.webm':
                # WebM/Matroska container validation
                if len(file_content) > 32:
                    logger.debug("Valid WebM/Matroska container detected")
                    return True
                else:
                    logger.warning("WebM container too small")
                    return False
            
            # Standard validation for other formats
            if extension == expected_extension:
                logger.debug(f"Valid {expected_extension} file detected with magic number {magic_bytes.hex()}")
                return True
    
    # Check if we have a valid audio file but wrong extension
    detected_extensions = []
    for magic_bytes, extension in AUDIO_MAGIC_NUMBERS.items():
        if file_content.startswith(magic_bytes):
            detected_extensions.append(extension)
    
    if detected_extensions:
        logger.warning(f"Magic number mismatch: expected {expected_extension}, detected {detected_extensions}")
        return False
    
    # If no magic number matches, it's suspicious
    logger.warning(f"No valid audio magic number found for file with extension {expected_extension}")
    return False

def detect_malware_patterns(file_content: bytes) -> Tuple[bool, Optional[str]]:
    """
    Detect potential malware patterns in audio files.
    
    Args:
        file_content: Raw file content
        
    Returns:
        Tuple of (is_suspicious, reason)
    """
    # Common malware signatures to detect
    malware_patterns = [
        # Executable headers
        (b'MZ', 'PE executable header detected'),
        (b'\x7fELF', 'ELF executable header detected'),
        (b'\xCA\xFE\xBA\xBE', 'Mach-O executable header detected'),
        
        # Script patterns
        (b'#!/bin/', 'Shell script detected'),
        (b'#!/usr/bin/', 'Shell script detected'),
        (b'<script', 'JavaScript detected'),
        (b'<?php', 'PHP script detected'),
        (b'python', 'Python script detected'),
        
        # Archive patterns (potentially dangerous)
        (b'PK\x03\x04', 'ZIP archive detected'),
        (b'Rar!', 'RAR archive detected'),
        (b'\x1f\x8b', 'GZIP archive detected'),
        
        # Suspicious strings
        (b'eval(', 'Suspicious eval() function detected'),
        (b'system(', 'Suspicious system() function detected'),
        (b'exec(', 'Suspicious exec() function detected'),
        (b'shell_exec', 'Suspicious shell_exec() function detected'),
    ]
    
    # Check for suspicious patterns
    for pattern, reason in malware_patterns:
        if pattern in file_content[:1024]:  # Check first 1KB
            logger.warning(f"Potential malware detected: {reason}")
            return True, reason
    
    # Check for excessive null bytes (potential padding attack)
    null_count = file_content[:1024].count(b'\x00')
    if null_count > 512:  # More than 50% null bytes in first 1KB
        logger.warning(f"Excessive null bytes detected: {null_count}/1024")
        return True, f"Excessive null bytes: {null_count}/1024"
    
    # Check for extremely long lines (potential buffer overflow)
    try:
        # Look for long sequences without line breaks
        max_line_length = 0
        current_length = 0
        for byte in file_content[:1024]:
            if byte in [0x0A, 0x0D]:  # \n or \r
                max_line_length = max(max_line_length, current_length)
                current_length = 0
            else:
                current_length += 1
        
        max_line_length = max(max_line_length, current_length)
        if max_line_length > 800:  # Very long line
            logger.warning(f"Extremely long line detected: {max_line_length} bytes")
            return True, f"Extremely long line: {max_line_length} bytes"
    except Exception:
        pass  # Not critical for audio files
    
    return False, None

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
    Enhanced with more comprehensive error types and security-focused messages.
    
    This function prevents information disclosure by providing generic error messages
    that don't reveal internal system details or specific security vulnerabilities.
    
    Args:
        error_type: Type of error (e.g., 'file_validation', 'malware_detected')
        
    Returns:
        Generic error message suitable for client display
    """
    generic_messages = {
        'file_validation': 'The uploaded file format is not supported or contains invalid content',
        'file_size': 'File size exceeds the maximum allowed limit',
        'processing': 'Audio processing could not be completed',
        'server_error': 'A server error occurred while processing your request',
        'path_traversal': 'The filename contains invalid characters',
        'malware_detected': 'The uploaded file contains suspicious content',
        'invalid_extension': 'The file extension is not supported',
        'content_mismatch': 'The file content does not match the expected format',
        'timeout': 'The request timed out while processing',
        'quota_exceeded': 'The service quota has been exceeded',
    }
    
    default_message = 'An error occurred while processing your request. Please try again with a valid audio file.'
    return generic_messages.get(error_type, default_message)