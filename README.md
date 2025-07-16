# CuraSnap AI

A medical transcription application that converts audio recordings into structured SOAP notes using AI technology.

## Features

- **Audio Transcription**: Convert medical audio recordings to text using OpenAI Whisper
- **SOAP Note Generation**: AI-powered conversion of transcripts into structured medical notes
- **Secure File Processing**: Comprehensive security measures for audio file handling
- **Multi-format Support**: Supports MP3, WAV, WebM, M4A, OGG, and FLAC audio formats
- **Authentication**: JWT-based authentication via Supabase
- **Production Ready**: Comprehensive security validation and monitoring

## Quick Start

### Prerequisites

- Java 21+
- Python 3.8+
- Docker (optional)
- Supabase account
- OpenAI API key

### Development Setup

1. **Clone the repository**
   ```bash
   git clone https://github.com/your-org/cura-snap-ai.git
   cd cura-snap-ai
   ```

2. **Set up environment variables**
   ```bash
   cp .env.example .env
   # Edit .env with your configuration
   ```

3. **Start the backend**
   ```bash
   cd backend
   ./mvnw spring-boot:run
   ```

4. **Start the transcription service**
   ```bash
   cd transcription_service
   pipenv install
   pipenv run uvicorn app.main:app --reload --port 8002
   ```

5. **Start the agent service**
   ```bash
   cd agent_service
   pipenv install
   pipenv run uvicorn app.main:app --reload --port 8001
   ```

### Docker Deployment

```bash
docker-compose up --build
```

## Documentation

- **[Security Guidelines](SECURITY.md)** - Comprehensive security documentation
- **[Environment Variables](ENVIRONMENT.md)** - Configuration requirements
- **[API Documentation](docs/api.md)** - REST API endpoints
- **[Deployment Guide](docs/deployment.md)** - Production deployment instructions

## Security

CuraSnap AI implements comprehensive security measures:

- **Multi-layer file validation** with magic number verification
- **Malware detection** for uploaded files
- **JWT-based authentication** with strength validation
- **Secure temporary file handling** with automatic cleanup
- **Production-ready security validation**

For detailed security information, see [SECURITY.md](SECURITY.md).

## Architecture

- **Backend**: Java Spring Boot 3.5.0 with OAuth2 authentication
- **Agent Service**: Python FastAPI with pydantic-ai for SOAP generation
- **Transcription Service**: Python FastAPI with OpenAI Whisper
- **Database**: PostgreSQL via Supabase with Row-Level Security
- **Frontend**: SvelteKit (limited development)

## Development

### Running Tests

```bash
# Backend tests
cd backend
./mvnw test

# Agent service tests
cd agent_service
pipenv run pytest

# Transcription service tests
cd transcription_service
pipenv run pytest
```

### Profiles

- **Default**: Development configuration
- **Test**: Testing with JPA disabled
- **Production**: Production configuration with enhanced security

```bash
# Run with specific profile
./mvnw spring-boot:run -Dspring.profiles.active=prod
```

## Contributing

1. Fork the repository
2. Create a feature branch
3. Follow security guidelines
4. Add tests for new features
5. Submit a pull request

## License

This project is licensed under the MIT License - see the LICENSE file for details.

## Support

For support and questions:
- **Documentation**: Check the docs/ directory
- **Issues**: Report bugs via GitHub Issues
- **Security**: See [SECURITY.md](SECURITY.md) for security-related concerns