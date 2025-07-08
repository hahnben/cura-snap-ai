# Use-Case-Diagramme

## Diktat

@startuml

actor Arzt

 

rectangle System {

(Diktat starten)

(Audiodatei übertragen)

(Transkription erzeugen)

(Notiz generieren)

(Notiz anzeigen)

 

Arzt \--\> (Diktat starten)

(Diktat starten) \--\> (Audiodatei übertragen) : \<\<include\>\>

(Audiodatei übertragen) \--\> (Transkription erzeugen) : \<\<include\>\>

(Transkription erzeugen) \--\> (Notiz generieren) : \<\<include\>\>

(Notiz generieren) \--\> (Notiz anzeigen) : \<\<include\>\>

}

@enduml

 
## Deployment-Diagramm

@startuml

\' Akteur

node \"Browser\n(Aktiver Nutzer: Arzt)\" {

component \"Frontend\n(Svelte App)\" as FE

}

 

\' Reverse Proxy

node \"Caddy\n(Reverse Proxy)\" as CADDY

 

\' Server mit Docker

node \"Server\" {

node \"Docker Umgebung\" {

component \"Caddy Container\" as Caddy_Container

component \"Backend\n(Spring Boot)\" as BE

component \"KI-Agent\n(Python + Pydantic AI)\" as AI

component \"Whisper-Service\n(Python + Whisper)\" as WH

component \"Frontend\n(Svelte, containerisiert)\" as FE_Container

}

}

 

\' Externer Dienst

node \"Supabase Cloud\" {

database \"Auth & Datenbank\n(PostgreSQL + RLS)\" as DB

}

 

\' Kommunikationsflüsse

FE \--\> CADDY : HTTPS Request (Audio-Upload, Auth, Notiz anfordern)

CADDY \--\> BE : leitet /api/\... Request weiter

 

FE \--\> DB : Authentifizierung via Supabase SDK (z. B. Magic Link)

 

BE \--\> WH : sendet Audiodatei zur Transkription

WH \--\> BE : liefert Transkript zurück

 

BE \--\> AI : sendet Transkript zur Formatierung

AI \--\> BE : liefert strukturierte Notiz

 

BE \--\> DB : speichert Transkript + Notiz

 

@enduml

 
# Sequenzdiagramme

## Overview

@startuml

actor Arzt

participant \"Frontend\n(Svelte)\" as FE

participant \"Backend\n(Spring Boot)\" as BE

participant \"Transkriptionsdienst\n(Whisper/FastAPI)\" as WH

participant \"Agent-Service\n(pydantic-ai)\" as AG

database \"Supabase\" as DB

 

== Diktat ==

Arzt -\> FE : startet Diktat

FE -\> FE : nimmt Audio mit MediaRecorder auf

 

== Transkriptionsanfrage ==

FE -\> BE : sendet Audiodatei + JWT

 

== Authentifizierung ==

BE -\> DB : prüft JWT

DB \--\> BE : gültig

 

== Transkription ==

BE -\> WH : POST /transcribe (Audiodatei)

WH \--\> BE : Transkript

 

== Formatierung ==

BE -\> AG : POST /format (Transkript)

AG \--\> BE : strukturierte Notiz

 

== Antwort an Client ==

BE \--\> FE : strukturierte Notiz

FE -\> Arzt : zeigt Notiz an

@enduml



## Backend

### Nur Controller, Service & DTO {#nur-controller-service-dto}

@startuml

actor Arzt

 

participant \"NoteController\" as Controller

participant \"NoteServiceImpl\" as Service

participant \"NoteRequest\" as Request

participant \"NoteResponse\" as Response

 

== Formatierungsanfrage ==

Arzt -\> Controller : POST /api/v1/notes/format\nNoteRequest (JSON)

 

Controller -\> Controller : extract userId from JWT

Controller -\> Request : deserialize JSON → NoteRequest

Controller -\> Service : formatNote(userId, NoteRequest)

 

Service -\> Response : create NoteResponse\n(structured text, UUID,
timestamp)

Service \--\> Controller : return NoteResponse

 

Controller \--\> Arzt : HTTP 200 OK\nNoteResponse (JSON)

@enduml

 



### Vollständig

**✅ Überarbeitete Zusammenfassung (inkl. Supabase)**

1.  Ein **HTTP-POST-Request** trifft am NoteController ein (z. B. auf
    /api/v1/notes/format).  
    Er enthält ein **Transkript** im JSON-Body.

2.  Der JSON-Body wird durch Spring automatisch in ein Objekt vom Typ
    NoteRequest deserialisiert.

3.  Der NoteController extrahiert die Benutzer-ID aus dem JWT und ruft:

4.  noteService.formatNote(userId, noteRequest)

auf.

5.  Die Methode formatNote(\...) wird in der Klasse NoteServiceImpl
    aufgerufen.  
    Diese ist für die **Logik zur Formatierung** und die **Kommunikation
    mit externen Diensten** verantwortlich.

6.  NoteServiceImpl sendet das Transkript über einen HTTP-Client an den
    **externen KI-Agenten** (FastAPI + pydantic-ai).

7.  Der **KI-Agent** verarbeitet den Text (z. B. mit GPT-4 oder
    DeepSeek) und liefert eine strukturierte SOAP-Note im JSON-Format
    zurück.

8.  NoteServiceImpl wandelt diese Antwort in ein Java-Objekt vom Typ
    NoteResponse um.

9.  **Zusätzlich wird die Note in Supabase gespeichert**, typischerweise
    in der Tabelle soap_notes.  
    Dabei werden z. B. folgende Felder übergeben:

    - user_id: aus dem JWT

    - text_raw: das ursprüngliche Transkript

    - text_structured: die generierte SOAP-Note

    - created_at: aktueller Zeitstempel

10. Das NoteResponse wird an den NoteController zurückgegeben.

11. Der NoteController sendet das DTO als JSON im HTTP-Response an das
    Frontend zurück.

@startuml

actor Arzt

 

participant \"NoteController\" as Controller

participant \"NoteServiceImpl\" as Service

participant \"DTO: NoteRequest\" as NoteRequest

participant \"HTTP-Client (WebClient)\" as HttpClient

participant \"KI-Agent (FastAPI)\" as Agent

participant \"DTO: NoteResponse\" as NoteResponse

database \"Supabase DB\" as DB

 

== Anfrage an API ==

Arzt -\> Controller : HTTP POST /api/v1/notes/format\n+ JWT\n+
Transkript (JSON)

 

note right of Controller

Spring Boot parses the JSON body

into a NoteRequest object and injects it

into the controller method.

end note

 

Controller -\> Controller : extract userId from JWT

Controller -\> Service : formatNote(userId, NoteRequest)

 

== Aufruf des KI-Agenten ==

Service -\> HttpClient : POST /format_note\n{ \"text\": textRaw }

HttpClient -\> Agent : HTTP request to FastAPI service

Agent -\> Agent : generate structured SOAP note

Agent \--\> HttpClient : JSON response (S/O/A/P)

 

== Verarbeitung und Speicherung ==

HttpClient \--\> Service : structured note as Java object

Service -\> NoteResponse : build DTO

Service -\> DB : INSERT INTO soap_notes (user_id, text_raw,
text_structured)

DB \--\> Service : OK

 

== Antwort an den Client ==

Service \--\> Controller : NoteResponse

Controller \--\> Arzt : HTTP 200 OK + JSON (NoteResponse)

@enduml

 

## Acitvity-Diagramm

@startuml

\|Arzt\|

start

:Startet Diktat;

 

\|Frontend\|

:Audio mit MediaRecorder aufnehmen;

:Audiodatei + JWT an Backend senden;

 

\|Backend\|

:JWT prüfen;

\|Supabase\|

:Token verifizieren;

 

\|Backend\|

:Audio an Transkriptionsdienst senden;

 

\|Transkriptionsdienst\|

:Transkription erzeugen;

:Text an Backend zurückgeben;

 

\|Backend\|

:Transkript an Agent-Service weiterleiten;

 

\|Agent-Service\|

:Strukturierte Notiz erzeugen;

 

\|Backend\|

:Antwort empfangen;

 

\|Frontend\|

:Strukturierte Notiz anzeigen;

 

\|Arzt\|

stop

@enduml

 


# Klassendiagramme

## Backend

@startuml

skinparam classAttributeIconSize 0

skinparam ArrowColor DarkGray

skinparam ArrowFontColor DarkSlateGray

skinparam class {

BackgroundColor White

ArrowThickness 1

ArrowColor Black

}

 

\' Package: Controller

package \"controller\" {

class NoteController {

\- noteService : NoteService

\+ hello(jwt: Jwt) : ResponseEntity\<String\>

\+ formatNote(jwt: Jwt, request: NoteRequest) :
ResponseEntity\<NoteResponse\>

\+ getNotes(jwt: Jwt) : ResponseEntity\<List\<NoteResponse\>\>

}

}

 

\' Package: Service

package \"service\" {

interface NoteService {

\+ formatNote(userId: String, request: NoteRequest) : NoteResponse

\+ getNotes(userId: String) : List\<NoteResponse\>

}

 

class NoteServiceImpl {

\+ formatNote(userId: String, request: NoteRequest) : NoteResponse

\+ getNotes(userId: String) : List\<NoteResponse\>

}

 

\' IMPLEMENTS (Vererbungspfeil, leer)

NoteService \<\|.. NoteServiceImpl : implements

}

 

\' Package: DTO

package \"dto\" {

class NoteRequest {

\- textRaw : String

}

 

class NoteResponse {

\- id : UUID

\- textRaw : String

\- textStructured : String

\- createdAt : Instant

}

}

 

\' DEPENDENCIES (Verwendung, durchgezogene Pfeile)

NoteController \--\> NoteService : uses

NoteController \--\> NoteRequest : uses

NoteController \--\> NoteResponse : returns

NoteServiceImpl \--\> NoteRequest : receives

NoteServiceImpl \--\> NoteResponse : returns

@enduml

 


# Datenbank

| Entität              | Zweck                                                         |
|----------------------|---------------------------------------------------------------|
| User                 | Authentifizierter Arzt (via Supabase-User)                    |
| Session              | Einheit eines Patientenkontakts (z. B. Tab oder Zeitleiste)   |
| Transcript           | Rohtext aus Audio oder Freitexteingabe                        |
| SoapNote             | Strukturierte Notiz im SOAP-Format                            |
| AssistantInteraction | Generische Prompt/Response-Struktur (z. B. auch Summary etc.) |
| ChatMessage          | Darstellung des Gesprächsverlaufs im UI                       |

## ER-Modell

@startuml

entity \"User\" as User {

\* id : UUID \<\<PK\>\>

\--

email : TEXT

first_name : TEXT

last_name : TEXT

display_name : TEXT

}

 

entity \"Session\" as Session {

\* id : UUID \<\<PK\>\>

\--

user_id : UUID \<\<FK\>\>

patient_name : TEXT

started_at : TIMESTAMP

ended_at : TIMESTAMP

}

 

entity \"Transcript\" as Transcript {

\* id : UUID \<\<PK\>\>

\--

session_id : UUID \<\<FK\>\>

user_id : UUID \<\<FK\>\>

input_type : TEXT

text_raw : TEXT

created_at : TIMESTAMP

}

 

entity \"SoapNote\" as SoapNote {

\* id : UUID \<\<PK\>\>

\--

transcript_id : UUID \<\<FK\>\>

user_id : UUID \<\<FK\>\>

session_id : UUID \<\<FK\>\>

text_structured : JSONB

created_at : TIMESTAMP

}

 

entity \"AssistantInteraction\" as Interaction {

\* id : UUID \<\<PK\>\>

\--

session_id : UUID \<\<FK\>\>

assistant_id : UUID

user_id : UUID \<\<FK\>\>

input_type : TEXT

input_json : JSONB

output_type : TEXT

output_json : JSONB

created_at : TIMESTAMP

}

 

entity \"ChatMessage\" as Message {

\* id : UUID \<\<PK\>\>

\--

session_id : UUID \<\<FK\>\>

user_id : UUID \<\<FK\>\>

assistant_id : UUID

interaction_id : UUID \<\<FK\>\>

sender : TEXT

message_type : TEXT

content : TEXT

created_at : TIMESTAMP

}

 

\' Beziehungen

User \|\|\--o{ Session : has

User \|\|\--o{ Transcript : creates

User \|\|\--o{ SoapNote : owns

User \|\|\--o{ Interaction : initiates

User \|\|\--o{ Message : sends

 

Session \|\|\--o{ Transcript : contains

Session \|\|\--o{ SoapNote : contains

Session \|\|\--o{ Interaction : contains

Session \|\|\--o{ Message : contains

 

Interaction \|\|\--o{ Message : linked

Transcript \|\|\--\|\| SoapNote : source

@enduml
 

# Datenflüsse und Zuständigkeiten

## Klasse: NoteServiceImpl

**🧠 Verständnisprüfung: Was passiert in formatNote(\...)?**

**▶️ Eingabe (Input)**

NoteResponse formatNote(String userId, NoteRequest request)

- userId: kommt aus dem **JWT**, über den Controller übergeben
  (jwt.getSubject()).

- request: enthält das **unstrukturierte Transkript** (aus dem Frontend
  via POST).

**🛠️ Verarbeitung (Logik)**

**1. SOAP-Text generieren (noch Dummy)**

String dummySoap = \"\"\"

S: %s

O: \...

A: \...

P: \...

\"\"\".formatted(request.getTextRaw());

→ Ziel: Der Text aus request.getTextRaw() wird als \"S\" (Subjective) in
einen SOAP-Text eingebettet.  
→ Die Abschnitte O, A, P sind **noch Platzhalter**, später übernimmt das
der Python-AI-Agent.

**2. Zeitstempel erstellen**

Instant now = Instant.now();

→ wird als createdAt gespeichert.

**3. SoapNote-Entity erzeugen**

SoapNote note = new SoapNote();

note.setUserId(UUID.fromString(userId));

→ SoapNote ist die **JPA-Entity**, die in der Supabase-Postgres-Tabelle
soap_note gespeichert wird.

**4. Optionale Felder verarbeiten (Transkript & Session)**

try {

note.setTranscriptId(UUID.fromString(request.getTranscriptId()));

note.setSessionId(UUID.fromString(request.getSessionId()));

} catch (IllegalArgumentException e) {

logger.warn(\"Invalid UUID format in request: {}\", e.getMessage());

note.setTranscriptId(null);

note.setSessionId(null);

}

→ Diese Felder sind **optional und nullfähig** (wie von dir
konfiguriert).  
→ Sie ermöglichen später die **Verknüpfung mit Audio-Transkripten** und
**Sitzungen**.

**5. Inhalt + Zeitstempel setzen**

note.setTextStructured(dummySoap);

note.setCreatedAt(now);

→ textStructured: der generierte SOAP-Text  
→ createdAt: Zeitpunkt der Erstellung (nicht vom Client übergeben!)

**💾 Persistenz (Speichern in DB)**

soapNoteRepository.save(note);

→ Speichert die Entität **in Supabase** über das JPA-Repository.

**⏎ Ausgabe (Return-Wert)**

return new NoteResponse(note.getId(), request.getTextRaw(), dummySoap,
now);

→ Die Methode liefert ein NoteResponse DTO zurück -- für den Controller
/ das Frontend.

**✅ Kurzgefasst: Datenfluss in formatNote**

\[Frontend\]

\|

V

NoteRequest (enthält: textRaw, sessionId?, transcriptId?)

\|

V

NoteServiceImpl.formatNote(userId, request)

\|

├─ erzeugt SOAP-Text

├─ erstellt SoapNote-Entity

├─ validiert & parst UUIDs

├─ speichert in DB

└─ erzeugt NoteResponse

\|

V

NoteResponse → an Controller → an Frontend

 
