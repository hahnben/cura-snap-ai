# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

CuraSnap AI is a medical application that processes transcripts and generates SOAP notes using AI. The system consists of multiple services:

- **Backend**: Java Spring Boot 3.5.0 API with OAuth2 authentication via Supabase
- **Agent Service**: Python FastAPI service with pydantic-ai for SOAP note generation
- **Frontend**: SvelteKit web application (limited development)
- **Transcription Service**: Python service for audio processing
- **Database**: PostgreSQL via Supabase with comprehensive schema for medical sessions

## Architecture

### Core Services Communication
- Backend serves as the main API gateway with JWT authentication
- Agent service processes medical transcripts into structured SOAP notes
- Services communicate through REST APIs
- Database relationships: Session → Transcript → SoapNote → AssistantInteraction → ChatMessage

### Key Technologies
- **Backend**: Spring Boot, Spring Security, JPA/Hibernate, PostgreSQL
- **Agent Service**: FastAPI, pydantic-ai, OpenAI integration
- **Database**: Supabase (PostgreSQL with auth.users integration)
- **Containerization**: Docker with docker-compose orchestration

## Development Commands

### Backend (Java Spring Boot)
```bash
# Navigate to backend directory
cd backend

# Run with Maven wrapper
./mvnw spring-boot:run

# Run tests
./mvnw test

# Build
./mvnw clean package
```

### Agent Service (Python FastAPI)
```bash
# Navigate to agent service directory
cd agent_service

# Install dependencies with Pipenv
pipenv install

# Run development server
pipenv run uvicorn app.main:app --reload --port 8001

# Run tests
pipenv run pytest
```

### Docker Development
```bash
# Start all services
docker-compose up

# Build and start
docker-compose up --build

# Stop services
docker-compose down
```

## Configuration

### Environment Variables Required
- `SUPABASE_JWT_SECRET`: JWT secret for authentication
- `DATABASE_URL`: PostgreSQL connection string via Supabase
- `LLM_MODEL`: OpenAI model for SOAP generation (defaults to gpt-4o)
- `OPENAI_API_KEY`: OpenAI API key for agent service

### Database Schema
The system uses a comprehensive medical session schema:
- `session`: Patient sessions with start/end timestamps
- `transcript`: Raw input transcripts linked to sessions
- `soap_note`: Structured SOAP notes with JSONB storage
- `assistant_interaction`: AI agent interactions
- `chat_message`: Conversation history
- `user_profile`: User information extending Supabase auth

## Key Code Patterns

### Backend Spring Boot
- Uses Java 24 with Lombok for reduced boilerplate
- OAuth2 Resource Server configuration with Supabase JWT
- JPA entities follow medical domain model
- Controller → Service → Repository pattern
- Environment-based configuration via application.properties

### Agent Service
- FastAPI with pydantic-ai integration
- OpenAI model configuration for SOAP generation
- Structured output using Pydantic models
- Async processing for AI operations
- German system prompts for medical context

### Database Design
- UUID primary keys throughout
- Cascade deletions for data integrity
- JSONB for flexible structured data storage
- Timezone-aware timestamps
- Foreign key relationships maintaining referential integrity

## Testing

### Backend Tests
```bash
cd backend
./mvnw test
```

### Agent Service Tests
```bash
cd agent_service
pipenv run pytest
```

## Development Notes

- Backend runs on port 8080 by default
- Agent service runs on port 8001
- Database migrations handled through Supabase schema files
- SOAP agent generates German medical notes
- Authentication flow integrates with Supabase Auth
- All services support Docker containerization

## Standard Workflow
1. Denke zunächst über das Problem nach, suche in der Codebasis nach relevanten Dateien und schreibe einen Plan in tasks/todo.md.
2. Der Plan sollte eine Liste mit Aufgaben enthalten, die du abhaken kannst, sobald du sie erledigt hast.
3. Bevor du mit der Arbeit beginnst, melde dich bei mir, damit ich den Plan überprüfen kann.
4. Beginne dann mit der Bearbeitung der Aufgaben und markiere sie nach und nach als erledigt.
5. Bitte erläutere mir bei jedem Schritt detailliert, welche Änderungen du vorgenommen hast.
6. Gestalte alle Aufgaben und Codeänderungen so einfach wie möglich. Wir möchten massive oder komplexe Änderungen vermeiden. Jede Änderung sollte sich so wenig wie möglich auf den Code auswirken. Einfachheit ist alles.
7. Füge abschließend einen Überprüfungsbereich in die Datei [todo.md] ein, der eine Zusammenfassung der vorgenommenen Änderungen und alle anderen relevanten Informationen enthält.