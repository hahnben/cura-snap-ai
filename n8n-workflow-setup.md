# CuraSnap AI n8n Workflow Setup

## Übersicht
Dieser n8n-Workflow testet das CuraSnap AI Backend über eine Chat-Schnittstelle. Unstrukturierte Arztnotizen werden eingegeben und als strukturierte SOAP-Notes zurückgeliefert.

## Workflow-Architektur

### Workflow-Ablauf
```
Chat Input → Prepare Request Data → Call Backend API → Extract SOAP Response → Chat Output
```

### 1. Chat Trigger Node - "When chat message received"
- **Node-Type**: `@n8n/n8n-nodes-langchain.chatTrigger` (LangChain Chat Trigger)
- **Zweck**: Startet den Workflow bei Chat-Eingaben
- **Konfiguration**: Integrierte Chat-Schnittstelle für interaktive Tests
- **Input**: Freitext-Arztnotizen vom Benutzer
- **Warum diese Wahl**: Bietet eine benutzerfreundliche Chat-Oberfläche zum Testen

### 2. Prepare Request Data Node (Set Node)
- **Funktion**: Konvertiert Chat-Input in Backend-kompatibles JSON-Format
- **Konfiguration**:
  - `include: "none"` → Keine bestehenden Felder übernehmen
  - `textRaw: "={{ $json.chatInput }}"` → Chat-Input in API-Format umwandeln
- **Zweck**: Bereitet minimale Request-Daten für Backend vor
- **Warum diese Wahl**: 
  - Dein Backend erwartet nur `textRaw` als Parameter
  - Saubere Trennung zwischen Chat-Format und API-Format

### 3. Call Backend API Node (HTTP Request Node)
- **Endpoint**: `POST http://localhost:8080/api/v1/notes/format`
- **Authentication**: `genericCredentialType` mit `httpHeaderAuth`
- **Headers**: `Content-Type: application/json`
- **Body**: `textRaw` Parameter aus vorherigem Node
- **Warum diese Wahl**:
  - Generic Credential bietet mehr Flexibilität für Bearer Token
  - POST-Request entspricht deinem funktionierenden cURL-Befehl
  - Minimale Datenübertragung (nur `textRaw`)

### 4. Extract SOAP Response Node (Set Node)
- **Funktion**: Extrahiert SOAP-Note aus Backend-Response für Chat-Ausgabe
- **Konfiguration**:
  - `include: "none"` → Nur relevante Daten behalten
  - `text: "={{ $json.textStructured }}"` → Backend-Response für Chat vorbereiten
- **Zweck**: Bereitet die generierte SOAP-Note für die Chat-Rückgabe vor
- **Warum diese Wahl**:
  - Chat Trigger benötigt Parameter namens `text` für automatische Rückgabe
  - `textStructured` enthält die fertige SOAP-Note vom Backend
  - Direkte Textausgabe ohne JSON-Wrapping

## Setup-Anleitung

### Voraussetzungen
- n8n-Instanz (lokal oder Cloud)
- CuraSnap AI Backend läuft auf Port 8080
- Agent Service läuft auf Port 8001
- Gültiger Supabase JWT Token

### 1. Workflow importieren
```bash
# In n8n-Instanz
1. Gehe zu "Workflows" → "Import"
2. Lade `n8n-curasnap-workflow.json` hoch
3. Bestätige Import
```

### 2. JWT Credentials konfigurieren
```bash
# In n8n unter "Credentials"
1. Erstelle neue Credential
2. Wähle "Generic Credential Type"
3. Wähle "Header Auth"
4. Konfiguration:
   - Name: "Authorization"
   - Value: "Bearer YOUR_SUPABASE_JWT_TOKEN"
5. Speichere als "Header Auth account"
```

### 3. JWT Token beziehen
```bash
# Supabase Magic Link Methode
1. Gehe zu Supabase Dashboard
2. Authentication → Users → Create Magic Link
3. Klicke auf Magic Link in Email
4. Kopiere JWT Token aus Browser-URL
5. Füge Token in n8n Credentials ein
```

### 4. Backend-URL anpassen (optional)
```bash
# Wenn Backend nicht auf localhost:8080 läuft
1. Öffne Workflow-Editor
2. Wähle "Call Backend API" Node
3. Passe URL in Parameters an
```

### 5. Workflow aktivieren
```bash
1. Klicke "Active" Toggle in Workflow-Editor
2. Teste über Chat-Interface
```

## Verwendung

### Beispiel-Interaktion
```
User: Patient klagt über Kopfschmerzen seit 3 Tagen, Übelkeit am Morgen

Bot: ANAMNESE:
Patient berichtet über Kopfschmerzen seit 3 Tagen mit morgendlicher Übelkeit.

UNTERSUCHUNG:
[Weitere Untersuchungen erforderlich]

BEURTEILUNG:
Verdacht auf Spannungskopfschmerz oder Migräne.

THERAPIE:
Symptomatische Behandlung empfohlen.
```

### Testszenarien
- Einfache Symptombeschreibungen
- Komplexe Anamnesen mit mehreren Beschwerden
- Unvollständige Informationen
- Verschiedene medizinische Fachbereiche
- Nachsorge-Dokumentation

## Fehlerbehebung

### Häufige Probleme

**1. "Unauthorized" Fehler**
- Prüfe JWT Token in Credentials
- Stelle sicher, dass Token noch gültig ist (Magic Link läuft ab)
- Überprüfe "Bearer " Prefix in Header Auth Value
- Teste Backend direkt mit cURL

**2. "Connection refused" Fehler**
- Backend muss auf Port 8080 laufen
- Überprüfe `docker-compose up` Status
- Teste Backend direkt: `curl http://localhost:8080/api/v1/hello`

**3. "Internal Server Error"**
- Agent Service muss auf Port 8001 laufen
- Überprüfe Environment Variables (OPENAI_API_KEY)
- Schaue in Backend-Logs nach Details

**4. JSON-Output statt Text**
- Stelle sicher, dass letzter Node Parameter `text` hat
- Nicht `textStructured`, `response` oder andere Namen verwenden
- Chat Trigger Response Mode auf "When Last Node Finishes"

**5. Workflow wird nicht ausgelöst**
- Workflow muss "Active" sein
- Chat Trigger Node richtig konfiguriert
- n8n-Instanz muss laufen

### Debugging
```bash
# Backend-Logs prüfen
docker-compose logs backend

# Agent Service-Logs prüfen
docker-compose logs agent_service

# n8n Workflow-Ausführung prüfen
# In n8n: Workflows → Executions → Details
```

## Technische Details

### Backend-Integration
- Repliziert exakt das funktionierende cURL-Pattern
- Minimale Datenübertragung (nur `textRaw`)
- Automatische Transcript-ID-Generierung im Backend
- Ignoriert transcriptId aus Request (wird automatisch erstellt)
- sessionId ist optional

### Datenfluss
```
Chat Input 
→ textRaw JSON (minimiert)
→ Backend Processing 
→ Agent Service (SOAP Generation)
→ Database Persistence (Supabase)
→ textStructured Response
→ Direkte Chat-Ausgabe
```

### Sicherheit
- JWT-basierte Authentifizierung über Supabase
- Credentials sicher in n8n gespeichert
- Keine sensiblen Daten in Workflow-JSON
- Token-Expiration berücksichtigen

### Workflow-Optimierungen
- **Saubere Trennung**: Jeder Node hat spezifische Aufgabe
- **Minimale Architektur**: Nur notwendige Nodes
- **Chat-Optimierung**: Direkte Textausgabe ohne Formatierung
- **Fehlerresistenz**: Robuste Authentifizierung-Konfiguration

## Erweitertung

### Zusätzliche Features
- Error Handling Nodes für robuste Fehlerbehandlung
- Retry-Mechanismus bei API-Ausfällen
- Logging/Monitoring für Workflow-Ausführungen
- Multiple Backend-Environments (Dev/Prod)
- Session-Management mit automatischer ID-Generierung

### Integration
- Webhook-basierte Trigger für externe Systeme
- Email-Notifications bei Fehlern
- Database-Logging für Audit-Trails
- External API-Calls für erweiterte Funktionalität

## Wartung

### Regelmäßige Aufgaben
- JWT Token erneuern (Magic Link läuft ab)
- Backend/Agent Service Updates
- Workflow-Ausführungen monitoren
- Credentials-Sicherheit überprüfen