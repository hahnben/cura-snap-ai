# Refactoring-Plan: Zirkuläre Abhängigkeiten Beseitigung
## CuraSnap AI Backend - Spring Boot 3.5.0

## 🔴 Problem-Analyse

### Identifizierte Zirkuläre Abhängigkeiten
```
AsyncNoteController
    └── JobServiceImpl
        ⇄ AdaptiveRetryService
        ⇄ ErrorClassificationService  
        ⇄ WorkerHealthService
        ⇄ JobServiceImpl (zurück zum Start)
```

### Root Cause
Die Services sind zu eng gekoppelt und haben bidirektionale Abhängigkeiten:
- `JobServiceImpl` benötigt `AdaptiveRetryService` für Retry-Logik
- `AdaptiveRetryService` benötigt `ErrorClassificationService` für Fehleranalyse
- `ErrorClassificationService` benötigt `WorkerHealthService` für Worker-Status
- `WorkerHealthService` benötigt `JobServiceImpl` für Queue-Statistiken

## ✅ Lösungsstrategie

### Gewählter Ansatz: Event-Driven Architecture + Interface Segregation
Kombination aus:
1. **Spring Application Events** für lose Kopplung
2. **Interface Segregation** für klare Abhängigkeiten
3. **Facade Pattern** für Service-Koordination

## 📋 Schritt-für-Schritt Refactoring-Plan

### Phase 1: Vorbereitung (10 Minuten)

#### Schritt 1.1: Backup und Branch erstellen
```bash
# Create a new branch for refactoring
git checkout -b refactor/circular-dependencies

# Ensure all changes are committed
git add .
git commit -m "Backup: Before circular dependency refactoring"
```

#### Schritt 1.2: Tests laufen lassen und Status dokumentieren
```bash
cd backend
./mvnw clean test > test-results-before.txt 2>&1
```

---

### Phase 2: Event System einführen (20 Minuten)

#### Schritt 2.1: Event-Klassen erstellen
**Erstelle**: `backend/src/main/java/ai/curasnap/backend/event/`

```java
// JobProcessingEvent.java
package ai.curasnap.backend.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import java.time.Instant;

@Data
@Builder
@AllArgsConstructor
public class JobProcessingEvent {
    private String jobId;
    private String workerId;
    private boolean success;
    private long processingTime;
    private String errorMessage;
    private Instant timestamp;
}
```

```java
// WorkerStatusEvent.java
package ai.curasnap.backend.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor  
public class WorkerStatusEvent {
    public enum EventType {
        REGISTERED, HEARTBEAT, DEACTIVATED, JOB_COMPLETED, JOB_FAILED
    }
    
    private String workerId;
    private EventType eventType;
    private String workerType;
    private Long processingTime;
}
```

```java
// QueueStatsRequestEvent.java
package ai.curasnap.backend.event;

import lombok.Data;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

@Data
public class QueueStatsRequestEvent {
    private String queueName;
    private CompletableFuture<Map<String, Object>> responseFuture;
    
    public QueueStatsRequestEvent(String queueName) {
        this.queueName = queueName;
        this.responseFuture = new CompletableFuture<>();
    }
}
```

---

### Phase 3: Interface Segregation (30 Minuten)

#### Schritt 3.1: Neue Interfaces definieren
**Erstelle**: `backend/src/main/java/ai/curasnap/backend/service/interfaces/`

```java
// QueueStatsProvider.java
package ai.curasnap.backend.service.interfaces;

import java.util.Map;

public interface QueueStatsProvider {
    Map<String, Object> getQueueStats(String queueName);
}
```

```java
// JobStatusProvider.java
package ai.curasnap.backend.service.interfaces;

import ai.curasnap.backend.model.dto.JobData;
import java.util.Optional;

public interface JobStatusProvider {
    Optional<JobData> getJobById(String jobId);
    boolean updateJobRetryCount(String jobId);
}
```

```java
// WorkerMetricsProvider.java
package ai.curasnap.backend.service.interfaces;

public interface WorkerMetricsProvider {
    void recordJobProcessing(String workerId, boolean success, long processingTime);
    boolean isWorkerHealthy(String workerId);
    int getActiveWorkerCount();
}
```

#### Schritt 3.2: JobService Interface aufteilen
**Modifiziere**: `backend/src/main/java/ai/curasnap/backend/service/JobService.java`

```java
// JobService extends the new interfaces
public interface JobService extends QueueStatsProvider, JobStatusProvider {
    // Existing methods remain...
}
```

---

### Phase 4: Services Refactoring (45 Minuten)

#### Schritt 4.1: WorkerHealthService entkoppeln
**Modifiziere**: `backend/src/main/java/ai/curasnap/backend/service/WorkerHealthService.java`

```java
@Service
@Slf4j
public class WorkerHealthService implements WorkerMetricsProvider {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    
    // REMOVE: private final JobService jobService;
    
    // Constructor without JobService dependency
    public WorkerHealthService(RedisTemplate<String, Object> redisTemplate,
                              ApplicationEventPublisher eventPublisher) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
    }
    
    // Replace direct JobService calls with events
    public SystemHealthReport getSystemHealthReport() {
        // Instead of: jobService.getQueueStats()
        QueueStatsRequestEvent audioEvent = new QueueStatsRequestEvent("audio_processing");
        QueueStatsRequestEvent textEvent = new QueueStatsRequestEvent("text_processing");
        
        eventPublisher.publishEvent(audioEvent);
        eventPublisher.publishEvent(textEvent);
        
        try {
            Map<String, Object> audioStats = audioEvent.getResponseFuture().get(1, TimeUnit.SECONDS);
            Map<String, Object> textStats = textEvent.getResponseFuture().get(1, TimeUnit.SECONDS);
            // Use stats...
        } catch (Exception e) {
            log.warn("Could not retrieve queue stats: {}", e.getMessage());
            // Use default values
        }
        // Rest of implementation...
    }
}
```

#### Schritt 4.2: ErrorClassificationService entkoppeln
**Modifiziere**: `backend/src/main/java/ai/curasnap/backend/service/ErrorClassificationService.java`

```java
@Service
@Slf4j
public class ErrorClassificationService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final ApplicationEventPublisher eventPublisher;
    // Use interface instead of concrete class
    private final WorkerMetricsProvider workerMetricsProvider;
    
    public ErrorClassificationService(RedisTemplate<String, Object> redisTemplate,
                                     ApplicationEventPublisher eventPublisher,
                                     WorkerMetricsProvider workerMetricsProvider) {
        this.redisTemplate = redisTemplate;
        this.eventPublisher = eventPublisher;
        this.workerMetricsProvider = workerMetricsProvider;
    }
    
    // Methods now use workerMetricsProvider interface
}
```

#### Schritt 4.3: AdaptiveRetryService entkoppeln
**Modifiziere**: `backend/src/main/java/ai/curasnap/backend/service/AdaptiveRetryService.java`

```java
@Service
@Slf4j
public class AdaptiveRetryService {
    
    private final ErrorClassificationService errorClassificationService;
    private final CircuitBreakerService circuitBreakerService;
    private final WorkerMetricsProvider workerMetricsProvider;
    private final RedisTemplate<String, Object> redisTemplate;
    
    // No more circular dependency!
    public AdaptiveRetryService(ErrorClassificationService errorClassificationService,
                               CircuitBreakerService circuitBreakerService,
                               WorkerMetricsProvider workerMetricsProvider,
                               RedisTemplate<String, Object> redisTemplate) {
        this.errorClassificationService = errorClassificationService;
        this.circuitBreakerService = circuitBreakerService;
        this.workerMetricsProvider = workerMetricsProvider;
        this.redisTemplate = redisTemplate;
    }
}
```

#### Schritt 4.4: JobServiceImpl mit Event Listener
**Modifiziere**: `backend/src/main/java/ai/curasnap/backend/service/JobServiceImpl.java`

```java
@Service
@Slf4j
public class JobServiceImpl implements JobService {
    
    private final RedisTemplate<String, Object> redisTemplate;
    private final AdaptiveRetryService adaptiveRetryService;
    private final ApplicationEventPublisher eventPublisher;
    
    // Constructor remains similar but now publishes events
    
    @EventListener
    public void handleQueueStatsRequest(QueueStatsRequestEvent event) {
        // Respond to queue stats requests from WorkerHealthService
        Map<String, Object> stats = getQueueStats(event.getQueueName());
        event.getResponseFuture().complete(stats);
    }
    
    @EventListener
    public void handleJobProcessingEvent(JobProcessingEvent event) {
        // Update job status based on worker events
        log.debug("Processing job event for job: {}", event.getJobId());
        // Implementation...
    }
}
```

---

### Phase 5: Koordinations-Facade (Optional, 15 Minuten)

#### Schritt 5.1: JobCoordinatorFacade erstellen
**Erstelle**: `backend/src/main/java/ai/curasnap/backend/service/JobCoordinatorFacade.java`

```java
@Service
@Slf4j
public class JobCoordinatorFacade {
    
    private final JobService jobService;
    private final WorkerHealthService workerHealthService;
    private final AdaptiveRetryService adaptiveRetryService;
    private final ApplicationEventPublisher eventPublisher;
    
    // Provides a unified interface for complex operations
    // that require coordination between services
    
    public JobResponse processJobWithRetry(String userId, JobRequest request) {
        // Coordinate between services using events
        JobResponse response = jobService.createJob(userId, request);
        
        // Publish event for worker assignment
        eventPublisher.publishEvent(new JobCreatedEvent(response.getJobId()));
        
        return response;
    }
}
```

---

### Phase 6: Tests anpassen (30 Minuten)

#### Schritt 6.1: Test-Konfiguration aktualisieren
**Modifiziere**: `backend/src/test/java/ai/curasnap/backend/config/TestEventConfig.java`

```java
@TestConfiguration
public class TestEventConfig {
    
    @Bean
    @Primary
    public ApplicationEventPublisher testEventPublisher() {
        return new ApplicationEventPublisher() {
            @Override
            public void publishEvent(Object event) {
                // No-op for tests or simple mock behavior
                log.debug("Test event published: {}", event.getClass().getSimpleName());
            }
        };
    }
}
```

#### Schritt 6.2: Service-Tests anpassen
**Modifiziere alle betroffenen Test-Klassen**:
- `WorkerHealthServiceTest.java`
- `ErrorClassificationServiceTest.java`
- `AdaptiveRetryServiceTest.java`
- `JobServiceImplTest.java`

Beispiel für WorkerHealthServiceTest:
```java
@ExtendWith(MockitoExtension.class)
class WorkerHealthServiceTest {
    
    @Mock
    private RedisTemplate<String, Object> redisTemplate;
    
    @Mock
    private ApplicationEventPublisher eventPublisher;
    
    private WorkerHealthService workerHealthService;
    
    @BeforeEach
    void setUp() {
        workerHealthService = new WorkerHealthService(redisTemplate, eventPublisher);
    }
    
    // Tests remain mostly the same, just mock event publishing
}
```

---

### Phase 7: Validierung und Cleanup (15 Minuten)

#### Schritt 7.1: Application starten und testen
```bash
# Clean build
./mvnw clean compile

# Run tests
./mvnw test

# Start application
./mvnw spring-boot:run
```

#### Schritt 7.2: Integration Tests
```bash
# Test async job creation
curl -X POST http://localhost:8080/api/v1/notes/async \
  -H "Authorization: Bearer $JWT_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"textRaw": "Test nach Refactoring"}'

# Check job status
curl -X GET http://localhost:8080/api/v1/jobs/{jobId}/status \
  -H "Authorization: Bearer $JWT_TOKEN"
```

#### Schritt 7.3: Cleanup
- Entferne unused imports
- Update JavaDoc Kommentare
- Formatiere Code mit Spring Boot Standards

---

### Phase 8: Dokumentation und Commit (10 Minuten)

#### Schritt 8.1: Dokumentation aktualisieren
**Erstelle**: `backend/docs/architecture/event-driven-refactoring.md`

Dokumentiere:
- Neue Event-basierte Architektur
- Interface-Diagramm
- Event-Flow-Diagramm
- Migration Notes

#### Schritt 8.2: Final Commit
```bash
# Stage all changes
git add .

# Commit with detailed message
git commit -m "refactor: Eliminate circular dependencies via event-driven architecture

- Introduced Spring Application Events for service decoupling
- Implemented Interface Segregation Principle
- Separated JobService into multiple focused interfaces
- Added WorkerMetricsProvider interface
- Refactored WorkerHealthService to use events instead of direct JobService
- Updated all affected tests
- No functional changes, only architectural improvements

Fixes: Circular dependency between JobServiceImpl, AdaptiveRetryService, 
ErrorClassificationService, and WorkerHealthService"

# Create PR if needed
git push origin refactor/circular-dependencies
```

---

## 🎯 Erwartetes Ergebnis

### Neue Abhängigkeiten-Struktur (Azyklisch)
```
AsyncNoteController
    └── JobServiceImpl
        └── AdaptiveRetryService
            └── ErrorClassificationService
                └── WorkerMetricsProvider (Interface)
                    
WorkerHealthService (implements WorkerMetricsProvider)
    └── ApplicationEventPublisher
    └── RedisTemplate
    
Event Bus (Spring ApplicationEventPublisher)
    ├── JobProcessingEvent
    ├── WorkerStatusEvent
    └── QueueStatsRequestEvent
```

### Vorteile
1. **Keine zirkulären Abhängigkeiten mehr**
2. **Lose Kopplung** durch Events
3. **Bessere Testbarkeit** durch Interface Segregation
4. **Skalierbarkeit** für zukünftige Features
5. **Clean Architecture** Prinzipien befolgt

---

## ⚠️ Wichtige Hinweise

### Rollback-Strategie
Falls Probleme auftreten:
```bash
git stash
git checkout main
git branch -D refactor/circular-dependencies
```

### Performance-Überlegungen
- Event-basierte Kommunikation ist minimal langsamer (< 1ms overhead)
- CompletableFuture für synchrone Event-Responses mit Timeout
- Redis-basierte Events für verteilte Systeme (zukünftige Erweiterung)

### Testing-Strategie
- Unit Tests: Mock ApplicationEventPublisher
- Integration Tests: Verwende @SpringBootTest mit echten Events
- Load Tests: Validiere Performance nach Refactoring

---

## 📊 Metriken für Erfolg

✅ **Erfolgskriterien**:
- [ ] Spring Boot startet ohne Circular Dependency Fehler
- [ ] Alle bestehenden Tests laufen grün
- [ ] AsyncNoteController funktioniert wie vorher
- [ ] Job-Processing funktioniert End-to-End
- [ ] Worker Health Monitoring funktioniert
- [ ] Keine Performance-Degradation (< 5% Impact)

---

## 🚀 Nächste Schritte nach Refactoring

1. **Monitoring hinzufügen**: Event-Metriken mit Micrometer
2. **Event Sourcing**: Optional für Audit-Trail
3. **Distributed Events**: Redis Pub/Sub für Microservices
4. **CQRS Pattern**: Trennung von Command und Query

---

**Geschätzte Gesamtzeit**: 2-3 Stunden für vollständige Implementierung
**Komplexität**: Mittel bis Hoch
**Risiko**: Niedrig (mit Rollback-Option)