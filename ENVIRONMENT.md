# Environment Variables Configuration

This document specifies all required and optional environment variables for CuraSnap AI deployment.

## Required Environment Variables (Production)

### Database Configuration
```bash
# PostgreSQL database connection (REQUIRED)
DATABASE_URL=postgresql://username:password@host:port/database
DATABASE_USER=your-database-username
DATABASE_PASSWORD=your-database-password
```

### Authentication Configuration
```bash
# Supabase JWT secret (CRITICAL - minimum 32 characters)
SUPABASE_JWT_SECRET=your-supabase-jwt-secret-minimum-32-chars
```

### Service URLs
```bash
# Agent service for SOAP note generation
AGENT_SERVICE_URL=http://your-agent-service:8001

# Transcription service for audio processing
TRANSCRIPTION_SERVICE_URL=http://your-transcription-service:8002
```

## Optional Environment Variables

### Service Configuration
```bash
# Agent service settings
AGENT_SERVICE_ENABLED=true  # Default: true

# Transcription service settings
TRANSCRIPTION_SERVICE_ENABLED=true  # Default: true
TRANSCRIPTION_SERVICE_TIMEOUT=30000  # Default: 30000ms

# OpenAI API key for agent service
OPENAI_API_KEY=your-openai-api-key
```

### Database Tuning
```bash
# JPA/Hibernate settings
DDL_AUTO=none  # Default: none (production), create-drop (development)
SHOW_SQL=false  # Default: true (development), false (production)
```

### Application Configuration
```bash
# Spring profiles
SPRING_PROFILES_ACTIVE=prod  # Options: test, prod

# Logging configuration
LOG_LEVEL=INFO  # Default: INFO (production), DEBUG (development)

# Auto-configuration exclusions
AUTOCONFIGURE_EXCLUDE=  # Default: empty (will auto-detect)
```

### Transcription Service Configuration
```bash
# Whisper model configuration
WHISPER_MODEL=base  # Options: base, small, medium, large

# File upload limits
MAX_FILE_SIZE=26214400  # Default: 25MB in bytes

# Server configuration
HOST=0.0.0.0  # Default: 0.0.0.0
PORT=8002  # Default: 8002
```

## Environment-Specific Examples

### Development Environment
```bash
# .env file for development
SUPABASE_JWT_SECRET=development-secret-at-least-32-characters-long
DATABASE_URL=postgresql://localhost:5432/curasnap_dev
DATABASE_USER=dev_user
DATABASE_PASSWORD=dev_password
AGENT_SERVICE_URL=http://localhost:8001
TRANSCRIPTION_SERVICE_URL=http://localhost:8002
OPENAI_API_KEY=sk-your-openai-api-key
SPRING_PROFILES_ACTIVE=default
LOG_LEVEL=DEBUG
SHOW_SQL=true
```

### Test Environment
```bash
# .env file for testing
SUPABASE_JWT_SECRET=test-secret-at-least-32-characters-long
AGENT_SERVICE_URL=http://localhost:8001
TRANSCRIPTION_SERVICE_URL=http://localhost:8002
SPRING_PROFILES_ACTIVE=test
LOG_LEVEL=DEBUG
AUTOCONFIGURE_EXCLUDE=org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
```

### Production Environment
```bash
# .env file for production
SUPABASE_JWT_SECRET=your-actual-production-jwt-secret-minimum-32-characters
DATABASE_URL=postgresql://prod_user:secure_password@db.company.com:5432/curasnap_prod
DATABASE_USER=prod_user
DATABASE_PASSWORD=secure_password
AGENT_SERVICE_URL=https://agent.curasnap.ai
TRANSCRIPTION_SERVICE_URL=https://transcription.curasnap.ai
OPENAI_API_KEY=sk-your-production-openai-api-key
SPRING_PROFILES_ACTIVE=prod
LOG_LEVEL=INFO
SHOW_SQL=false
```

## Security Considerations

### JWT Secret Requirements
- **Minimum length**: 32 characters
- **Character requirements**: Mix of letters, numbers, and special characters
- **Entropy**: High randomness (no predictable patterns)
- **Uniqueness**: Different secret per environment

### Database Security
- **Connection encryption**: Always use SSL/TLS
- **User permissions**: Principle of least privilege
- **Password strength**: Complex passwords with rotation policy

### API Keys
- **Secure storage**: Never commit to version control
- **Access control**: Restrict API key permissions
- **Rotation**: Regular key rotation schedule

## Validation & Testing

### Security Validation
The application automatically validates:
- JWT secret strength
- Environment configuration completeness
- Production readiness checks
- Weak secret detection

### Testing Configuration
```bash
# Test environment variables
./mvnw test -Dspring.profiles.active=test

# Validate security configuration
./mvnw spring-boot:run -Dspring.profiles.active=prod
```

## Docker Configuration

### Docker Compose Environment
```yaml
# docker-compose.yml
version: '3.8'
services:
  backend:
    environment:
      - SUPABASE_JWT_SECRET=${SUPABASE_JWT_SECRET}
      - DATABASE_URL=${DATABASE_URL}
      - DATABASE_USER=${DATABASE_USER}
      - DATABASE_PASSWORD=${DATABASE_PASSWORD}
      - AGENT_SERVICE_URL=http://agent:8001
      - TRANSCRIPTION_SERVICE_URL=http://transcription:8002
      - SPRING_PROFILES_ACTIVE=prod
```

### Kubernetes Configuration
```yaml
# k8s-configmap.yaml
apiVersion: v1
kind: ConfigMap
metadata:
  name: curasnap-config
data:
  AGENT_SERVICE_URL: "http://agent-service:8001"
  TRANSCRIPTION_SERVICE_URL: "http://transcription-service:8002"
  SPRING_PROFILES_ACTIVE: "prod"
---
apiVersion: v1
kind: Secret
metadata:
  name: curasnap-secrets
type: Opaque
stringData:
  SUPABASE_JWT_SECRET: "your-jwt-secret"
  DATABASE_URL: "postgresql://user:pass@host:port/db"
  DATABASE_USER: "username"
  DATABASE_PASSWORD: "password"
  OPENAI_API_KEY: "sk-your-api-key"
```

## Troubleshooting

### Common Issues

**Issue: "JWT secret is too short"**
```bash
# Solution: Ensure minimum 32 characters
SUPABASE_JWT_SECRET=your-very-long-secret-at-least-32-characters
```

**Issue: "Database connection failed"**
```bash
# Solution: Check database URL format
DATABASE_URL=postgresql://user:password@host:port/database
```

**Issue: "Service not available"**
```bash
# Solution: Verify service URLs
AGENT_SERVICE_URL=http://localhost:8001
TRANSCRIPTION_SERVICE_URL=http://localhost:8002
```

### Validation Commands
```bash
# Check environment variables
env | grep -E "(SUPABASE|DATABASE|AGENT|TRANSCRIPTION)"

# Test database connection
psql $DATABASE_URL -c "SELECT 1"

# Test service endpoints
curl -f $AGENT_SERVICE_URL/health
curl -f $TRANSCRIPTION_SERVICE_URL/health
```

## Environment Templates

### Template for .env.example
```bash
# Copy this file to .env and fill in your values

# Authentication (REQUIRED)
SUPABASE_JWT_SECRET=your-supabase-jwt-secret-minimum-32-characters

# Database (REQUIRED for production)
DATABASE_URL=postgresql://username:password@host:port/database
DATABASE_USER=your-database-username
DATABASE_PASSWORD=your-database-password

# Services (REQUIRED)
AGENT_SERVICE_URL=http://localhost:8001
TRANSCRIPTION_SERVICE_URL=http://localhost:8002

# OpenAI API (REQUIRED for agent service)
OPENAI_API_KEY=sk-your-openai-api-key

# Optional configuration
SPRING_PROFILES_ACTIVE=default
LOG_LEVEL=INFO
WHISPER_MODEL=base
```

---

**Last Updated:** July 2025
**Version:** 1.0
**Review Date:** October 2025