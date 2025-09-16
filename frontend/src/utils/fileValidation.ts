// File validation utilities for secure file uploads

export interface FileValidationResult {
  isValid: boolean;
  error?: string;
  sanitizedFileName?: string;
}

// Allowed MIME types for audio files (medical transcription)
const ALLOWED_AUDIO_TYPES = [
  'audio/webm',
  'audio/webm;codecs=opus',
  'audio/wav',
  'audio/mp3',
  'audio/mpeg',
  'audio/mp4',
  'audio/aac',
  'audio/ogg',
] as const;

// Allowed file extensions
const ALLOWED_AUDIO_EXTENSIONS = [
  '.webm',
  '.wav',
  '.mp3',
  '.mp4',
  '.m4a',
  '.aac',
  '.ogg',
] as const;

// File size limits
export const FILE_SIZE_LIMITS = {
  AUDIO_MAX_SIZE: 50 * 1024 * 1024, // 50MB for audio files
  GENERAL_MAX_SIZE: 10 * 1024 * 1024, // 10MB for other files
} as const;

/**
 * Validates audio files for medical transcription uploads
 */
export function validateAudioFile(file: File): FileValidationResult {
  // Check file size
  if (file.size > FILE_SIZE_LIMITS.AUDIO_MAX_SIZE) {
    return {
      isValid: false,
      error: `Audio file too large. Maximum size is ${FILE_SIZE_LIMITS.AUDIO_MAX_SIZE / (1024 * 1024)}MB.`,
    };
  }

  // Check MIME type
  if (!ALLOWED_AUDIO_TYPES.includes(file.type as any)) {
    return {
      isValid: false,
      error: `Invalid audio file type: ${file.type}. Allowed types: ${ALLOWED_AUDIO_TYPES.join(', ')}`,
    };
  }

  // Check file extension
  const extension = getFileExtension(file.name);
  if (!ALLOWED_AUDIO_EXTENSIONS.includes(extension as any)) {
    return {
      isValid: false,
      error: `Invalid file extension: ${extension}. Allowed extensions: ${ALLOWED_AUDIO_EXTENSIONS.join(', ')}`,
    };
  }

  // Sanitize filename
  const sanitizedFileName = sanitizeFileName(file.name);

  return {
    isValid: true,
    sanitizedFileName,
  };
}

/**
 * Validates general file uploads
 */
export function validateGeneralFile(file: File, maxSize: number = FILE_SIZE_LIMITS.GENERAL_MAX_SIZE): FileValidationResult {
  // Check file size
  if (file.size > maxSize) {
    return {
      isValid: false,
      error: `File too large. Maximum size is ${maxSize / (1024 * 1024)}MB.`,
    };
  }

  // Check for potentially dangerous file types
  const dangerousExtensions = ['.exe', '.bat', '.sh', '.cmd', '.scr', '.com', '.pif', '.js', '.jar'];
  const extension = getFileExtension(file.name);

  if (dangerousExtensions.includes(extension.toLowerCase())) {
    return {
      isValid: false,
      error: `File type ${extension} is not allowed for security reasons.`,
    };
  }

  // Sanitize filename
  const sanitizedFileName = sanitizeFileName(file.name);

  return {
    isValid: true,
    sanitizedFileName,
  };
}

/**
 * Sanitizes filename to prevent path traversal and other attacks
 */
function sanitizeFileName(filename: string): string {
  return filename
    // Remove path separators and dangerous characters
    .replace(/[\/\\:*?"<>|]/g, '_')
    // Remove leading/trailing dots and spaces
    .replace(/^[.\s]+|[.\s]+$/g, '')
    // Limit length
    .substring(0, 255)
    // Ensure it's not empty
    || 'upload';
}

/**
 * Extracts file extension from filename
 */
function getFileExtension(filename: string): string {
  const lastDot = filename.lastIndexOf('.');
  return lastDot === -1 ? '' : filename.substring(lastDot).toLowerCase();
}

/**
 * Check if a file is likely to be a valid audio file based on header bytes
 * This provides an additional layer of validation beyond MIME type and extension
 */
export async function validateAudioFileHeader(file: File): Promise<boolean> {
  return new Promise((resolve) => {
    const reader = new FileReader();
    reader.onload = () => {
      const buffer = reader.result as ArrayBuffer;
      const bytes = new Uint8Array(buffer);

      // Check for common audio file signatures
      if (isWebMFile(bytes) || isWAVFile(bytes) || isMP3File(bytes) ||
          isMP4File(bytes) || isOggFile(bytes)) {
        resolve(true);
      } else {
        resolve(false);
      }
    };
    reader.onerror = () => resolve(false);

    // Read only first 12 bytes for signature checking
    reader.readAsArrayBuffer(file.slice(0, 12));
  });
}

// File signature checking functions
function isWebMFile(bytes: Uint8Array): boolean {
  // WebM starts with 0x1A 0x45 0xDF 0xA3
  return bytes[0] === 0x1A && bytes[1] === 0x45 &&
         bytes[2] === 0xDF && bytes[3] === 0xA3;
}

function isWAVFile(bytes: Uint8Array): boolean {
  // WAV starts with "RIFF" and has "WAVE" at offset 8
  return bytes[0] === 0x52 && bytes[1] === 0x49 &&
         bytes[2] === 0x46 && bytes[3] === 0x46 &&
         bytes[8] === 0x57 && bytes[9] === 0x41 &&
         bytes[10] === 0x56 && bytes[11] === 0x45;
}

function isMP3File(bytes: Uint8Array): boolean {
  // MP3 can start with ID3 tag or MP3 frame sync
  return (bytes[0] === 0x49 && bytes[1] === 0x44 && bytes[2] === 0x33) || // ID3
         (bytes[0] === 0xFF && (bytes[1] & 0xE0) === 0xE0); // MP3 frame sync
}

function isMP4File(bytes: Uint8Array): boolean {
  // MP4 has "ftyp" at offset 4
  return bytes[4] === 0x66 && bytes[5] === 0x74 &&
         bytes[6] === 0x79 && bytes[7] === 0x70;
}

function isOggFile(bytes: Uint8Array): boolean {
  // Ogg starts with "OggS"
  return bytes[0] === 0x4F && bytes[1] === 0x67 &&
         bytes[2] === 0x67 && bytes[3] === 0x53;
}