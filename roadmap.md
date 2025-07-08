*CuraSnap AI* ist ein webbasiertes Tool, das Ärzten ermöglicht,
Sprachnotizen automatisch in strukturierte medizinische Notizen (z. B.
im **SOAP-Format**) umzuwandeln. Die folgende Planung beschreibt die
**Umsetzungsreihenfolge** als Schritt-für-Schritt-Plan mit **klaren
Meilensteinen**, gibt für jede Phase eine **Checkliste** der Aufgaben
und empfiehlt **kostenlose Tools/Bibliotheken** für Logging, Testing,
Security sowie einen Fahrplan für Deployment (Docker, Caddy Reverse
Proxy, CI/CD). Der Entwickler arbeitet alleine auf Windows -- dies wird
durch geeignete Toolauswahl (z. B. Docker Desktop auf Windows, VS Code
etc.) berücksichtigt.

# Architekturüberblick

**Architekturtyp:** Modularer Monolith mit ausgelagerten KI-Services
(FastAPI) und Frontend (Svelte) in isolierten Containern -- orchestriert
über ein zentrales Spring Boot Backend.

Die Kernarchitektur von CuraSnap AI ist **modular** aufgebaut. Die
Tabelle fasst alle Komponenten, Technologien und Funktionen
übersichtlich zusammen:

| Komponente          | Technologie / Umgebung                                                                   | Funktion / Beschreibung                                                                                                                                   |
|---------------------|------------------------------------------------------------------------------------------|-----------------------------------------------------------------------------------------------------------------------------------------------------------|
| Frontend            | *Svelte* (Docker-Container)                                                              | Web-App im Browser für Ärzte (UI).                                                                                                                        |
| Backend API         | *Java Spring Boot* (in Eclipse IDE)                                                      | RESTful API-Server. Überprüft Authentifizierung (JWT via Supabase) und leitet Anfragen weiter.                                                            |
| KI-Agent Service    | *Python FastAPI* (in VS Code mit pipenv)                                                 | KI-Modul zur Umwandlung von Freitext in strukturierte Notizen. Verwendet *pydantic-ai* für modellbasierte AI-Antworten.                                   |
| Datenbank & Auth    | *Supabase* (PostgreSQL mit RLS)                                                          | Cloud-DB mit Row-Level Security. Stellt auch Auth-Service bereit (JWT-Ausstellung für User).                                                              |
| Deployment-Umgebung | *Docker*-Container orchestriert via *Docker Compose*;*Caddy* Webserver als Reverse Proxy | Containerisierung aller Komponenten. Caddy dient als Reverse Proxy mit automatischem HTTPS (Let\'s Encrypt). Geplant für Hosting auf z. B. Hetzner Cloud. |

**Hinweis:** Im Deployment-Diagramm sind Frontend, Backend und KI-Agent
als separate Docker-Container auf dem Server vorgesehen, während
Supabase als externer Cloud-Dienst läuft. Der Browser des Arztes
kommuniziert primär mit dem Spring-Boot-Backend (für API und Auth) und
optional direkt mit Supabase (z. B. bei Anmeldung). Das Backend
vermittelt zwischen Frontend, KI-Agent und Datenbank (siehe
Kommunikationsflüsse: Frontend → Backend → KI-Agent / DB).

# Umsetzungsphasen und Meilensteine

Im Folgenden werden die Phasen der Entwicklung in sinnvoller Reihenfolge
beschrieben. Jede Phase schließt mit einer **Checkliste** der
wichtigsten To-Dos ab.

## Phase 1: Supabase-Konfiguration -- Auth & Datenmodell {#phase-1-supabase-konfiguration-auth-datenmodell}

**Ziel:** Konfiguration von Supabase zur Bereitstellung von
Authentifizierung (JWT), Datenbanktabellen und Zugriffsschutz (RLS).
Diese Phase ist Voraussetzung für Backend- und Integrationstests.

**Wichtige Aufgaben:**

- Supabase-Projekt im Dashboard anlegen
- Comprehensive Database Schema erstellen (session, transcript, soap_note, assistant_interaction, chat_message, user_profile)
- Auth aktivieren (z. B. E-Mail/Magic Link Login)
- RLS aktivieren, Policies schreiben (z. B. user_id = auth.uid())
- Service-API-Keys notieren, .env-Datei vorbereiten

### Checkliste Phase 1

- [x] Supabase-Projekt im Dashboard erfolgreich erstellt
- [x] Comprehensive database schema implementiert:
  - [x] session Tabelle (id, user_id, patient_name, started_at, ended_at)
  - [x] transcript Tabelle (id, session_id, user_id, input_type, text_raw, created_at)
  - [x] soap_note Tabelle (id, transcript_id, session_id, user_id, text_structured, created_at)
  - [x] assistant_interaction Tabelle (id, session_id, user_id, assistant_id, input_type, input_json, output_type, output_json, created_at)
  - [x] chat_message Tabelle (id, session_id, user_id, assistant_id, interaction_id, sender, message_type, content, created_at)
  - [x] user_profile Tabelle (user_id, first_name, last_name, display_name, created_at)
- [ ] Authentifizierung aktiviert (z. B. Magic Link oder OAuth-Flow)
- [x] RLS aktiviert: Zugriffsregeln wie user_id = auth.uid() implementiert
- [x] Test-User angelegt und Email-Invite verschickt
- [x] Supabase Service-API-Key kopiert und sicher gespeichert (nicht ins Git!)
- [x] .env-Datei für Backend vorbereitet mit Supabase URL + Key

**Meilenstein:** Authentifizierung und sichere Datenhaltung sind bereit
zur Nutzung in Backend & Frontend. 🔐

## Phase 2: Backend Foundation -- Session & Transcript Management {#phase-2-backend-foundation}

**Ziel:** Erweitere das Spring Boot Backend um Session und Transcript Management mit vollständigen CRUD-Operationen und Entity-Beziehungen.

**Wichtige Aufgaben:**

- Session Management implementieren (Create, Read, Update, Delete)
- Transcript Management mit File Upload Support
- User Profile Integration
- Enhanced Database Relations mit JPA Entities
- Repository Layer für alle Entities
- Service Layer für Business Logic

### Checkliste Phase 2

- [x] Spring Boot Backend Grundstruktur erstellt
- [x] SoapNote Entity implementiert
- [ ] Session Entity und Repository implementiert
- [ ] Transcript Entity und Repository implementiert
- [ ] User Profile Entity und Service
- [ ] Enhanced Database Relations (Foreign Keys, Cascade Operations)
- [ ] REST Endpoints für Session Management
- [ ] REST Endpoints für Transcript Management
- [ ] File Upload Logic für Transcript Input
- [ ] Service Layer für Business Logic
- [ ] Unit Tests für Repository Layer

**Meilenstein:** Backend kann Sessions und Transcripts vollständig verwalten mit persistenter Datenhaltung.

## Phase 3: Backend-Entwicklung -- REST API und Authentifizierung {#phase-3-backend-entwicklung-rest-api-und-authentifizierung}

**Ziel:** Aufbau der Spring-Boot-Backend-Anwendung mit allen nötigen
Endpoints und Sicherheitsmechanismen. Insbesondere soll hier die
**Authentifizierung** via Supabase-JWT integriert und getestet werden.

**Wichtige Aufgaben:**

- REST-Endpoints definieren (POST /notes/format, GET /notes, etc.)
- Supabase JWT-Authentifizierung implementieren
- Security-Konfiguration mit Spring Security
- Datenbank-Zugriff über JPA einrichten
- Testen der Authentifizierung

### Checkliste Phase 3

- [x] Spring Boot Endpoints angelegt (POST /notes/format, GET /notes, etc.) und Controller/Service-Struktur erstellt
- [x] Spring Security mit JWT-Filter eingerichtet -- Supabase JWT erfolgreich validiert
- [x] Supabase-Projekt: JWT-Secret konfiguriert
- [ ] Verbindung aus Backend geprüft (z. B. SELECT oder INSERT via JDBC/PostgREST)
- [ ] RLS-Policy in DB für notes gesetzt (z. B. nur Einträge mit user_id = auth.uid() lesbar/schreibbar)
- [ ] End-to-End Test mit gültigem JWT Token

**Meilenstein:** *Das Java-Backend läuft lokal und schützt die API mit
JWT-Auth. Anfragen an geschützte Endpoints werden abgewiesen oder
verarbeitet (z. B. Test-Endpunkt liefert bei gültigem Token Daten).* ✅

## Phase 4: KI-Agent entwickeln -- Freitext zu SOAP konvertieren {#phase-4-ki-agent-entwickeln-freitext-zu-soap-konvertieren}

**Ziel:** Implementierung des Python-basierten KI-Agents, der die
eigentliche Umwandlung der transkribierten Freitexte in strukturierte
Notizen übernimmt. Einsatz von **FastAPI** für eine einfache
API-Schnittstelle und **pydantic-ai** zur KI-Integration.

**Wichtige Aufgaben:**

- FastAPI Endpoint entwickeln
- Datenmodell mit Pydantic definieren
- KI-Logik mit pydantic-ai implementieren
- Environment Secrets sicher handhaben
- Test des KI-Agents

### Checkliste Phase 4

- [x] FastAPI-App aufgesetzt mit benötigten Routen (v.a. POST /format_note)
- [x] Pydantic-Modell(e) erstellt für Input und Output (z. B. TranscriptInput, SOAPNoteOutput)
- [x] Anbindung an LLM/API umgesetzt (OpenAI) -- Schlüssel sicher verwahrt (.env)
- [x] **pydantic-ai** eingesetzt für KI-Verarbeitung
- [ ] Lokaler Test: Beispiel-Request an den KI-Service gibt plausibles Ergebnis
- [ ] Logging im KI-Service geprüft (Start/Stop Meldungen von Uvicorn, Request-Logs)
- [ ] Error Handling für ungültige Eingaben

**Meilenstein:** *Der KI-Agent läuft eigenständig und kann einen
gegebenen Freitext in strukturierte Notizdaten umwandeln.* 🤖

## Phase 5: Frontend MVP -- Text-Input und Ergebnisanzeige {#phase-5-frontend-mvp-text-input-und-ergebnisanzeige}

**Ziel:** Einen einfachen Frontend-Prototypen entwickeln, der zunächst mit Text-Input arbeitet (statt Audio). Dies ermöglicht frühe End-to-End Tests und UI-Entwicklung parallel zum Whisper-Service.

**Wichtige Aufgaben:**

- UI-Grundgerüst mit Svelte entwickeln
- Text-Input Feld für Transkript-Simulation
- Backend-Integration für SOAP-Generierung
- Einfache Ergebnisanzeige
- Authentication Flow mit Supabase

### Checkliste Phase 5

- [ ] Svelte-App Grundstruktur erstellt
- [ ] Login-Komponente mit Supabase Auth
- [ ] Text-Input Feld für Transkript-Eingabe
- [ ] Submit-Button zum Senden an Backend
- [ ] Backend-Aufruf implementiert (POST /api/notes/format)
- [ ] JWT-Token im Authorization Header korrekt übertragen
- [ ] Antwort (strukturierte SOAP-Notiz) wird im UI angezeigt
- [ ] Einfaches Layout mit:
  - [ ] Login/Logout Funktionalität
  - [ ] Text-Input Bereich
  - [ ] Submit-Button
  - [ ] Ergebnisanzeige (strukturierter Text, z. B. SOAP)
- [ ] Fehlerbehandlung bei Netzwerkfehlern, Authentication-Problemen
- [ ] Loading-States während API-Aufrufen

**Meilenstein:** Nutzer kann sich einloggen, Text eingeben und erhält strukturierte SOAP-Notizen zurück. End-to-End Flow ohne Audio funktioniert. 📝

## Phase 6: Whisper-Service -- Audio zu Transkript {#phase-6-whisper-service-audio-zu-transkript}

**Ziel:** Aufbau eines Python-Dienstes (FastAPI), der gesprochene
Audiodaten entgegennimmt und lokal mittels Whisper transkribiert. Dieser
Dienst ersetzt die Web Speech API aus der früheren Architektur
vollständig.

**Wichtige Aufgaben:**

- REST-Endpunkt /transcribe (POST) erstellen
- UploadFile (Audio) empfangen und lokal speichern
- Transkription mit Whisper oder faster-whisper durchführen
- Antwort als JSON { transcript: "..." }
- Test mit Beispieldateien (mp3/wav)

### Checkliste Phase 6

- [ ] FastAPI-Route /transcribe erstellt (POST, FormData oder UploadFile)
- [ ] Whisper (openai-whisper) oder faster-whisper lokal installiert
- [ ] Eingehende Audiodatei wird entgegengenommen, temporär gespeichert
- [ ] Transkription durch Whisper-Modell durchgeführt und geprüft
- [ ] JSON-Antwort im Format { "transcript": "..." } wird zurückgegeben
- [ ] Fehlerhandling für ungültige/missing Dateien implementiert
- [ ] Pipenv + requirements.txt oder Pipfile eingerichtet
- [ ] Dockerfile für den Whisper-Service geschrieben
- [ ] Funktion mit curl, Postman oder REST Client getestet
- [ ] Verschiedene Audioformate getestet (mind. .mp3, .wav, optional .webm)

**Meilenstein:** Whisper-Container ist lokal lauffähig und kann
gesprochene Audiodateien zuverlässig in Text umwandeln. ✅

## Phase 7: Frontend Audio-Integration -- Spracheingabe hinzufügen {#phase-7-frontend-audio-integration-spracheingabe-hinzufuegen}

**Ziel:** Das bestehende Frontend um Audioaufnahme-Funktionalität erweitern. Integration des Whisper-Services für automatische Transkription.

**Wichtige Aufgaben:**

- MediaRecorder für Audioaufnahme implementieren
- Audio-Upload an Whisper-Service integrieren
- UI für Audio-Aufnahme erweitern
- Fallback auf Text-Input beibehalten

### Checkliste Phase 7

- [ ] Audioaufnahme im Browser per MediaRecorder umgesetzt
- [ ] Aufnahme wird als Blob gespeichert und an Whisper-Service gesendet
- [ ] Backend Integration: Audio → Whisper → Text → SOAP Agent
- [ ] UI erweitert um:
  - [ ] Aufnahme-Button (zusätzlich zu Text-Input)
  - [ ] Aufnahme-Status Anzeige
  - [ ] Ladeanimation („Audio wird verarbeitet...")
- [ ] Fehlerbehandlung bei Netzwerkfehlern, fehlender Mikrofonfreigabe etc.
- [ ] Beide Input-Modi funktionieren: Text-Input UND Audio-Aufnahme
- [ ] Verschiedene Audio-Formate getestet

**Meilenstein:** Nutzer kann wahlweise Text eingeben ODER Audio aufnehmen. Beide Wege führen zu strukturierten SOAP-Notizen. 🎙️

## Phase 8: Integration -- Frontend, Backend und KI-Agent verbinden {#phase-8-integration-frontend-backend-und-ki-agent-verbinden}

**Ziel:** Zusammenspiel der Komponenten herstellen, sodass ein
End-to-End-Prototyp entsteht. Vollständige Integration aller Services mit
Datenpersistierung und SMTP-Konfiguration.

**Wichtige Aufgaben:**

- Vollständige Backend-Integration (Session/Transcript Management)
- Datenpersistierung in Supabase implementieren
- Ende-zu-Ende-Tests durchführen
- SMTP-Dienst für E-Mail-Zustellung einrichten

### Checkliste Phase 8

- [ ] Session Management im Backend vollständig implementiert
- [ ] Transcript wird korrekt in DB gespeichert und mit Session verknüpft
- [ ] SOAP Note wird mit korrekten Foreign Keys persistiert
- [ ] Gesamte Fluss getestet: User Authentifizierung → Input (Text/Audio) → Backend → KI → DB → Frontend UI
- [ ] Fehlerbehandlung end-to-end: z. B. KI-Service down -- Backend fängt Exception ab
- [ ] SMTP-Dienst für produktive E-Mail-Zustellung einrichten
  - [ ] Anbieter ausgewählt (z. B. Mailgun, Postmark, Brevo)
  - [ ] Zugangsdaten in Supabase Auth Settings eingetragen
  - [ ] Absenderadresse konfiguriert (no-reply@...)
  - [ ] SPF/DKIM/DMARC-DNS-Einträge gesetzt und geprüft

**Meilenstein:** *Der komplette Prototyp funktioniert lokal: Ein
angemeldeter Nutzer kann Text oder Audio eingeben und erhält strukturierte
SOAP-Notizen zurück, die korrekt in der Datenbank gespeichert werden.* 🚀

## Phase 9: Testing & Qualitätssicherung {#phase-9-testing-qualitätssicherung}

**Ziel:** Einführung von Tests für die wichtigsten Komponenten, um als
Einzelentwickler die Zuverlässigkeit sicherzustellen. Verwendung
gängiger **Testing-Frameworks** -- alle vorgeschlagenen Tools sind
kostenlos und etabliert.

**Wichtige Aufgaben:**

- Unit-Tests (Java) mit JUnit 5 und Mockito
- Integrationstests (Java) mit Spring Boot Test
- Python-Tests mit pytest
- Frontend-Tests mit Vitest und Svelte Testing Library
- Manuelles Testen und Edge-Cases

### Checkliste Phase 9

- [ ] JUnit5 in Backend eingerichtet; mindestens ein Unit-Test (Service-Layer) und ein Controller-Test geschrieben
- [ ] Mockito/MockBean genutzt, um externe Aufrufe (KI, DB) in Unit-Tests zu simulieren
- [ ] Pytest eingerichtet; Beispieltest für FastAPI läuft (evtl. via pytest CLI oder VS Code Test Runner)
- [ ] Svelte/Vitest Setup durchgeführt (Testing-Library installiert); ein trivialer Komponententest erfolgreich
- [ ] Cypress oder Playwright E2E-Test: Prototyp in Docker oder lokal gestartet und Testskript navigiert durch Login, Diktat, Ergebnisanzeige
- [ ] Alle Tests in CI-Umgebung (z. B. GitHub Actions) konfiguriert, sodass bei jedem Push die Tests laufen

**Meilenstein:** Robuste Testabdeckung für kritische Komponenten gewährleistet Qualität und Stabilität.

## Phase 10: Logging & Monitoring einbauen {#phase-10-logging-monitoring-einbauen}

**Ziel:** Einführung von Logging in allen Teilen der Anwendung, um
Debugging zu erleichtern und im Betrieb wichtige Events nachvollziehen
zu können. Auswahl von **kostenlosen Logging-Bibliotheken** pro
Komponente.

**Wichtige Aufgaben:**

- Backend-Logging (Java) mit SLF4J und Logback
- KI-Service Logging (Python) mit Loguru
- Frontend-Logging mit Console-Logs und optional Sentry
- Zentrales Logging Setup
- Monitoring mit Health-Checks

### Checkliste Phase 10

- [ ] Logging in allen Komponenten aktiviert: Spring Boot (Logback) konfiguriert, Python-Logging (oder Loguru) hinzugefügt, wichtige Client-Aktionen per console.log versehen
- [ ] Sicherstellen, dass keine sensiblen Informationen in Logs gelangen (insbes. keine JWTs, keine Patientendetails)
- [ ] Log-Level überprüft: Fehler als ERROR/WARN, normale Operationen als INFO, Detailabläufe (optional) als DEBUG
- [ ] (Optional) Sentry oder ähnliches im Frontend eingebunden und mit einem Test-Error verifiziert
- [ ] Logrotation bzw. Begrenzung geprüft (besonders falls Datei-Logging genutzt)

**Meilenstein:** Comprehensive Logging ermöglicht effizientes Debugging und Monitoring.

## Phase 11: Sicherheit & Best Practices umsetzen {#phase-11-sicherheit-best-practices-umsetzen}

**Ziel:** Die Anwendung gegen häufige Sicherheitsrisiken schützen und
bewährte Sicherheitspraktiken anwenden. Dies betrifft **API Keys**,
**JWT-Nutzung**, **Datenbank-Zugriff (RLS)** und **HTTP Security
Header**.

**Wichtige Aufgaben:**

- API Keys & Secrets sicher handhaben
- JWT-Handhabung optimieren
- Row-Level Security implementieren
- Secure HTTP Headers konfigurieren
- Abschirmung KI-Service
- Debugging in Produktion deaktivieren

### Checkliste Phase 11

- [ ] .env/Konfigurations-Dateien geprüft: alle API-Schlüssel, Passwörter, Secrets dort und nicht im Code
- [ ] JWT-Einstellungen verifiziert: Ablaufzeit angemessen (Supabase default ~1h, okay), Tokens nur über HTTPS transportiert
- [ ] Datenbank-RLS wirksam: manuelle Tests durchgeführt, ob unerlaubte Zugriffe geblockt werden
- [ ] HTTP Security-Header geprüft (via Browser-Netzwerkanalyse oder Curl): HSTS, X-Frame-Options, CSP etc. vorhanden
- [ ] KI-Agent ist intern abgesichert (z. B. Firewall oder Docker-Netz) -- keine öffentlichen Ports offen für diesen Container im Produktivbetrieb
- [ ] Debug/Verbose-Modi ausgeschaltet für Release (Logging auf INFO, keine sensiblen Infos im Output, keine Stacktraces nach außen)

**Meilenstein:** Anwendung entspricht Sicherheits-Best-Practices und ist produktionsreif.

## Phase 12: Containerisierung & lokales Deployment aller Komponenten {#phase-12-containerisierung-lokales-deployment-aller-komponenten}

**Ziel:** Alle Komponenten dockerisieren und die Anwendung als Ganzes
via Docker Compose starten. Dies erleichtert sowohl das produktionsnahe
Testing auf Windows (Docker Desktop) als auch die spätere
Cloud-Inbetriebnahme.

**Wichtige Aufgaben:**

- Dockerfiles für alle Komponenten schreiben
- Docker Compose Konfiguration erstellen
- Caddy Reverse Proxy Setup
- Supabase-Anbindung konfigurieren
- Test des gesamten Stacks

### Checkliste Phase 12

- [ ] Dockerfiles für FE, BE, AI erstellt und Images bauen erfolgreich (docker build getestet)
- [ ] docker-compose.yml konfiguriert: Services mit richtigen Ports/Abhängigkeiten; Caddy oder Nginx als Reverse Proxy inkludiert
- [ ] Caddyfile für Routing hinterlegt und überprüft (lokal via HTTP funktionsfähig)
- [ ] Compose-Stack gestartet: Alle Container „healthy" (Gesundheitschecks optional definieren)
- [ ] End-to-End-Test über den Reverse Proxy bestanden
- [ ] Dokumentation: Docker-Anweisungen im README ergänzt, damit reproduzierbares Setup existiert

**Meilenstein:** *Die gesamte Anwendung läuft containerisiert in einer
lokalen Docker-Umgebung. Damit ist der Prototyp bereit für das
Deployment in der Cloud.* 🐳

## Phase 13: Deployment in der Cloud & CI/CD {#phase-13-deployment-in-der-cloud-cicd}

**Ziel:** Die Anwendung auf einer echten Server-Umgebung (z. B. Hetzner
Cloud VM) bereitstellen, mit öffentlicher Erreichbarkeit über HTTPS.
Einführung eines einfachen **CI/CD**-Prozesses, um Deployments für den
Solo-Entwickler zu automatisieren.

**Wichtige Aufgaben:**

- Hetzner Cloud Vorbereitung
- Produktiv-Compose Setup
- Datenbank konfigurieren
- Deployment der Container
- Reverse Proxy & Scaling
- Überwachung & Betrieb
- CI/CD Dokumentation

### Checkliste Phase 13

- [ ] Hetzner VM erstellt, Docker installiert, Domain auf Server aufgeschaltet (DNS live)
- [ ] Produktions-docker-compose.yml und Caddyfile auf Server übertragen und mit realer Domain konfiguriert
- [ ] Erstes Deployment manuell durchgeführt: Container laufen auf VM, App über HTTPS erreichbar (Zertifikat von Let's Encrypt automatisch via Caddy)
- [ ] CI-Pipeline eingerichtet: Build & Push aller Images beim Commit. Secrets in CI hinterlegt
- [ ] Watchtower im Compose hinzugefügt und erfolgreich getestet: Push eines neuen Images führt zum Update auf Server
- [ ] (Optional) Benachrichtigungen eingerichtet: z. B. Watchtower mit E-Mail bei Update, oder CI mit Slack/Webhook Meldung bei erfolgtem Deployment
- [ ] Load-Test im kleinen Umfang, um zu sehen, ob alles stabil

**Meilenstein:** *Der CuraSnap AI Prototyp ist erfolgreich auf einem
öffentlichen Server deployed, unter eigener Domain via HTTPS erreichbar
und kann von autorisierten Nutzern verwendet werden. Zudem ist ein
einfacher CI/CD-Prozess etabliert, sodass Updates schnell ausgerollt
werden können.* 🎉🌐
