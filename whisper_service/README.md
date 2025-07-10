# Whisper Service

FastAPI-basierter Service für Audio-Transkription mit OpenAI Whisper.

## Funktionen

- **Audio-Transkription**: Konvertiert Audio-Dateien in Text
- **Format-Unterstützung**: MP3, WAV, WebM, M4A, OGG, FLAC
- **Dateigröße**: Bis zu 25MB
- **Whisper-Modell**: Konfigurierbar (base, small, medium, large)

## Installation

```bash
# Dependencies installieren
pipenv install

# Whisper Service starten
pipenv run uvicorn app.main:app --reload --port 8002
```

## API Endpoints

### POST /transcribe

Audio-Datei zu Text transkribieren.

**Request:**
- `file`: Audio-Datei (multipart/form-data)

**Response:**
```json
{
  "transcript": "Transkribierter Text..."
}
```

### GET /health

Service-Status prüfen.

## Konfiguration

Erstelle eine `.env` Datei basierend auf `.env.example`:

```bash
cp .env.example .env
```

## Beispiel-Nutzung

```bash
# Test mit cURL
curl -X POST "http://localhost:8002/transcribe" \
  -H "Content-Type: multipart/form-data" \
  -F "file=@audio.mp3"
```