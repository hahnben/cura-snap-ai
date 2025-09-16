// Enhanced input validation for medical application security

export interface ValidationResult {
  isValid: boolean;
  error?: string;
  sanitizedValue?: string;
}

// Rate limiting for login attempts
class RateLimiter {
  private attempts: Map<string, number[]> = new Map();
  private readonly maxAttempts: number = 5;
  private readonly windowMs: number = 15 * 60 * 1000; // 15 minutes

  isRateLimited(identifier: string): boolean {
    const now = Date.now();
    const attempts = this.attempts.get(identifier) || [];

    // Remove old attempts outside the window
    const recentAttempts = attempts.filter(time => now - time < this.windowMs);
    this.attempts.set(identifier, recentAttempts);

    return recentAttempts.length >= this.maxAttempts;
  }

  recordAttempt(identifier: string): void {
    const attempts = this.attempts.get(identifier) || [];
    attempts.push(Date.now());
    this.attempts.set(identifier, attempts);
  }

  getRemainingCooldown(identifier: string): number {
    const attempts = this.attempts.get(identifier) || [];
    if (attempts.length === 0) return 0;

    const oldestAttempt = Math.min(...attempts);
    const cooldownEnd = oldestAttempt + this.windowMs;
    return Math.max(0, cooldownEnd - Date.now());
  }

  clear(identifier: string): void {
    this.attempts.delete(identifier);
  }
}

export const loginRateLimiter = new RateLimiter();

/**
 * Enhanced email validation with security considerations
 */
export function validateEmail(email: string): ValidationResult {
  const trimmedEmail = email.trim();

  // Check length limits
  if (trimmedEmail.length === 0) {
    return { isValid: false, error: 'E-Mail-Adresse ist erforderlich.' };
  }

  if (trimmedEmail.length > 254) {
    return { isValid: false, error: 'E-Mail-Adresse ist zu lang.' };
  }

  // Enhanced email regex
  const emailRegex = /^[a-zA-Z0-9.!#$%&'*+/=?^_`{|}~-]+@[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?(?:\.[a-zA-Z0-9](?:[a-zA-Z0-9-]{0,61}[a-zA-Z0-9])?)*$/;

  if (!emailRegex.test(trimmedEmail)) {
    return { isValid: false, error: 'Bitte geben Sie eine gültige E-Mail-Adresse ein.' };
  }

  // Check for common injection attempts
  const dangerousPatterns = [
    /<script/i,
    /javascript:/i,
    /onload=/i,
    /onerror=/i,
    /'[^']*'|"[^"]*"/,
    /\bexec\b/i,
    /\beval\b/i,
  ];

  for (const pattern of dangerousPatterns) {
    if (pattern.test(trimmedEmail)) {
      return { isValid: false, error: 'E-Mail-Adresse enthält unerlaubte Zeichen.' };
    }
  }

  return { isValid: true, sanitizedValue: trimmedEmail.toLowerCase() };
}

/**
 * Enhanced password validation with security requirements
 */
export function validatePassword(password: string, confirmPassword?: string): ValidationResult {
  // Check length
  if (password.length < 8) {
    return { isValid: false, error: 'Das Passwort muss mindestens 8 Zeichen lang sein.' };
  }

  if (password.length > 128) {
    return { isValid: false, error: 'Das Passwort ist zu lang (maximal 128 Zeichen).' };
  }

  // Check complexity requirements
  const hasUpperCase = /[A-Z]/.test(password);
  const hasLowerCase = /[a-z]/.test(password);
  const hasNumbers = /\d/.test(password);
  const hasSpecialChars = /[!@#$%^&*(),.?":{}|<>]/.test(password);

  const complexity = [hasUpperCase, hasLowerCase, hasNumbers, hasSpecialChars].filter(Boolean).length;

  if (complexity < 3) {
    return {
      isValid: false,
      error: 'Das Passwort muss mindestens 3 der folgenden Kriterien erfüllen: Großbuchstaben, Kleinbuchstaben, Zahlen, Sonderzeichen.'
    };
  }

  // Check for common weak patterns
  const weakPatterns = [
    /123456/,
    /password/i,
    /admin/i,
    /qwerty/i,
    /(.)\1{3,}/, // Repeating characters
  ];

  for (const pattern of weakPatterns) {
    if (pattern.test(password)) {
      return { isValid: false, error: 'Das Passwort enthält häufig verwendete unsichere Muster.' };
    }
  }

  // Check password confirmation
  if (confirmPassword !== undefined && password !== confirmPassword) {
    return { isValid: false, error: 'Die Passwörter stimmen nicht überein.' };
  }

  return { isValid: true };
}

/**
 * General text input validation with length limits and injection protection
 */
export function validateTextInput(
  input: string,
  options: {
    maxLength?: number;
    minLength?: number;
    allowEmpty?: boolean;
    fieldName?: string;
    allowSpecialChars?: boolean;
  } = {}
): ValidationResult {
  const {
    maxLength = 1000,
    minLength = 0,
    allowEmpty = true,
    fieldName = 'Eingabe',
    allowSpecialChars = true
  } = options;

  const trimmedInput = input.trim();

  // Check empty input
  if (!allowEmpty && trimmedInput.length === 0) {
    return { isValid: false, error: `${fieldName} ist erforderlich.` };
  }

  // Check length limits
  if (trimmedInput.length < minLength) {
    return { isValid: false, error: `${fieldName} muss mindestens ${minLength} Zeichen lang sein.` };
  }

  if (trimmedInput.length > maxLength) {
    return { isValid: false, error: `${fieldName} darf maximal ${maxLength} Zeichen lang sein.` };
  }

  // Check for injection attempts
  const injectionPatterns = [
    /<script/i,
    /<\/script>/i,
    /javascript:/i,
    /on\w+\s*=/i,
    /<iframe/i,
    /<object/i,
    /<embed/i,
    /\beval\s*\(/i,
    /\bexec\s*\(/i,
    /\bFunction\s*\(/i,
  ];

  for (const pattern of injectionPatterns) {
    if (pattern.test(input)) {
      return { isValid: false, error: `${fieldName} enthält nicht erlaubte Zeichen oder Code.` };
    }
  }

  // Check for special characters if not allowed
  if (!allowSpecialChars) {
    const specialCharPattern = /[<>'"&]/;
    if (specialCharPattern.test(input)) {
      return { isValid: false, error: `${fieldName} enthält nicht erlaubte Sonderzeichen.` };
    }
  }

  return { isValid: true, sanitizedValue: trimmedInput };
}

/**
 * Medical-specific text validation for patient data
 */
export function validateMedicalText(input: string, fieldName: string = 'Medizinischer Text'): ValidationResult {
  return validateTextInput(input, {
    maxLength: 5000,
    minLength: 0,
    allowEmpty: true,
    fieldName,
    allowSpecialChars: false, // Stricter for medical data
  });
}

/**
 * Sanitize HTML to prevent XSS
 */
export function sanitizeHtml(input: string): string {
  return input
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#x27;')
    .replace(/\//g, '&#x2F;');
}

/**
 * Validate and sanitize session identifiers
 */
export function validateSessionId(sessionId: string): ValidationResult {
  if (!sessionId || typeof sessionId !== 'string') {
    return { isValid: false, error: 'Ungültige Session-ID.' };
  }

  // UUID v4 format validation
  const uuidRegex = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i;

  if (!uuidRegex.test(sessionId)) {
    return { isValid: false, error: 'Session-ID hat ungültiges Format.' };
  }

  return { isValid: true, sanitizedValue: sessionId.toLowerCase() };
}

/**
 * Check for rate limiting on login attempts
 */
export function checkLoginRateLimit(email: string): { allowed: boolean; cooldownMs?: number; message?: string } {
  const identifier = email.toLowerCase();

  if (loginRateLimiter.isRateLimited(identifier)) {
    const cooldownMs = loginRateLimiter.getRemainingCooldown(identifier);
    const minutes = Math.ceil(cooldownMs / (1000 * 60));

    return {
      allowed: false,
      cooldownMs,
      message: `Zu viele Anmeldeversuche. Bitte warten Sie ${minutes} Minuten und versuchen Sie es erneut.`
    };
  }

  return { allowed: true };
}