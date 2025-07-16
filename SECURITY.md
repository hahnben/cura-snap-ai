# Security Guidelines

This document outlines the security measures implemented in CuraSnap AI and provides guidelines for secure deployment and development.

## Security Architecture

### Multi-Layer Security Approach

CuraSnap AI implements a comprehensive security architecture with multiple layers of protection:

1. **Authentication & Authorization**
   - JWT-based authentication via Supabase
   - Row-Level Security (RLS) in PostgreSQL
   - Role-based access control

2. **Input Validation & Sanitization**
   - Comprehensive file upload validation
   - Magic number verification for audio files
   - Malware pattern detection
   - Path traversal prevention

3. **Data Protection**
   - Secure temporary file handling
   - Memory-efficient stream processing
   - Automatic cleanup of sensitive data

4. **Network Security**
   - HTTPS-only communication
   - Reverse proxy with rate limiting
   - Secure cookie configuration

## Security Features

### Audio File Processing Security

**File Validation:**
- Magic number verification for genuine audio files
- MIME type validation
- File size limits (25MB maximum)
- Extension whitelist (.mp3, .wav, .webm, .m4a, .ogg, .flac)

**Malware Detection:**
- Executable header detection (PE, ELF, Mach-O)
- Script pattern detection (shell, PHP, JavaScript)
- Archive detection (ZIP, RAR, GZIP)
- Suspicious function detection (eval, exec, system)

**Secure File Handling:**
- Cryptographically secure temporary filenames
- Restrictive file permissions (0600)
- Guaranteed file cleanup after processing
- Stream-based processing to prevent memory exhaustion

### JWT Security

**Token Validation:**
- Minimum secret length (32 characters)
- Weak secret detection
- Production environment validation
- Automatic fallback prevention

**Security Checks:**
- Repeated character detection
- Sequential character detection
- Common weak pattern detection
- Production deployment validation

## Environment Configuration

### Required Environment Variables

**Production Environment:**
```bash
# CRITICAL: These must be set in production
SUPABASE_JWT_SECRET=your-actual-supabase-jwt-secret
DATABASE_URL=postgresql://user:pass@host:port/database
DATABASE_USER=your-db-user
DATABASE_PASSWORD=your-db-password
AGENT_SERVICE_URL=http://your-agent-service:8001
TRANSCRIPTION_SERVICE_URL=http://your-transcription-service:8002
```

**Development Environment:**
```bash
# Optional for development (fallbacks provided)
SUPABASE_JWT_SECRET=your-supabase-jwt-secret
DATABASE_URL=postgresql://localhost:5432/curasnap_dev
AGENT_SERVICE_URL=http://localhost:8001
TRANSCRIPTION_SERVICE_URL=http://localhost:8002
```

### Profile-Based Configuration

**Test Profile (`--spring.profiles.active=test`):**
- Disables JPA for testing
- Uses development JWT secret
- Enables debug logging
- Suitable for unit tests and integration tests

**Production Profile (`--spring.profiles.active=prod`):**
- Requires all environment variables
- Enforces secure cookie settings
- Disables debug logging
- Enables production optimizations

## Security Best Practices

### Development

1. **Never commit secrets to version control**
2. **Use environment variables for all sensitive data**
3. **Test with the security validation enabled**
4. **Regularly update dependencies**
5. **Follow secure coding practices**

### Deployment

1. **Use the production profile**
2. **Set strong JWT secrets (minimum 32 chars)**
3. **Configure HTTPS with valid certificates**
4. **Enable rate limiting at reverse proxy level**
5. **Monitor security logs**

### File Upload Security

1. **Always validate file types**
2. **Use stream processing for large files**
3. **Implement file size limits**
4. **Clean up temporary files**
5. **Monitor for suspicious patterns**

## Security Testing

### Automated Security Checks

The application includes automated security validation:
- JWT secret strength validation
- Environment configuration checks
- Production readiness validation
- Weak secret detection

### Manual Security Testing

**File Upload Testing:**
```bash
# Test with various file types
curl -X POST -F "audio=@test.mp3" http://localhost:8080/api/v1/notes/format-audio
curl -X POST -F "audio=@malicious.exe" http://localhost:8080/api/v1/notes/format-audio
curl -X POST -F "audio=@large-file.wav" http://localhost:8080/api/v1/notes/format-audio
```

**Authentication Testing:**
```bash
# Test without JWT
curl -X POST http://localhost:8080/api/v1/notes/format -d '{"textRaw":"test"}'

# Test with invalid JWT
curl -X POST -H "Authorization: Bearer invalid-token" http://localhost:8080/api/v1/notes/format
```

## Incident Response

### Security Monitoring

Monitor the following logs for security incidents:
- Authentication failures
- File upload rejections
- Malware detection alerts
- Weak secret warnings

### Common Security Issues

**Issue: Weak JWT Secret**
- **Symptom:** Security validation warnings at startup
- **Solution:** Set strong SUPABASE_JWT_SECRET environment variable

**Issue: Malicious File Upload**
- **Symptom:** File validation errors in logs
- **Solution:** Review file upload patterns, update detection rules

**Issue: Memory Exhaustion**
- **Symptom:** Application crashes during file processing
- **Solution:** Check file size limits, ensure stream processing

## Security Updates

### Regular Maintenance

1. **Update dependencies monthly**
2. **Review security logs weekly**
3. **Test security validation quarterly**
4. **Audit file upload patterns monthly**

### Security Patches

1. **Apply security patches immediately**
2. **Test in staging environment first**
3. **Document security changes**
4. **Monitor for regressions**

## Compliance

### Data Protection

- **GDPR Compliance**: Personal data handling procedures
- **HIPAA Readiness**: Healthcare data protection measures
- **SOC 2**: Security controls documentation

### Audit Requirements

- **Security logging**: All security events are logged
- **Access control**: User access is tracked and auditable
- **Data retention**: Temporary files are automatically cleaned up

## Contact

For security-related questions or to report security vulnerabilities, contact:
- **Security Team**: security@curasnap.ai
- **Emergency**: security-emergency@curasnap.ai

---

**Last Updated:** July 2025
**Version:** 1.0
**Review Date:** October 2025