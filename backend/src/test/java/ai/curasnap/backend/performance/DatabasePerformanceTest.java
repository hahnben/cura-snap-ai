package ai.curasnap.backend.performance;

import ai.curasnap.backend.model.entity.SoapNote;
import ai.curasnap.backend.model.entity.Transcript;
import ai.curasnap.backend.repository.SoapNoteRepository;
import ai.curasnap.backend.repository.TranscriptRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive database performance tests for Phase 3.2 query optimization.
 * Tests the effectiveness of database indexes and query patterns.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DatabasePerformanceTest {

    @Autowired
    private SoapNoteRepository soapNoteRepository;

    @Autowired
    private TranscriptRepository transcriptRepository;

    private UUID testUserId;
    private UUID testSessionId;
    private List<UUID> testUserIds;

    @BeforeEach
    void setUp() {
        testUserId = UUID.randomUUID();
        testSessionId = UUID.randomUUID();
        testUserIds = new ArrayList<>();
        
        // Generate multiple test users for concurrent testing
        for (int i = 0; i < 10; i++) {
            testUserIds.add(UUID.randomUUID());
        }
    }

    @Test
    @DisplayName("Performance: User-based queries with large dataset")
    void testUserBasedQueryPerformance() {
        // Create test data (1000 SOAP notes for single user)
        long setupStart = System.currentTimeMillis();
        createTestSoapNotes(testUserId, 1000);
        long setupTime = System.currentTimeMillis() - setupStart;
        
        System.out.println("Setup time for 1000 SOAP notes: " + setupTime + "ms");

        // Test findAllByUserId performance
        long queryStart = System.currentTimeMillis();
        List<SoapNote> userNotes = soapNoteRepository.findAllByUserId(testUserId);
        long queryTime = System.currentTimeMillis() - queryStart;
        
        assertEquals(1000, userNotes.size());
        System.out.println("findAllByUserId query time: " + queryTime + "ms");
        
        // Performance assertion: should be under 100ms with proper index
        assertTrue(queryTime < 100, "User query should be under 100ms with index");

        // Test ordered query performance
        queryStart = System.currentTimeMillis();
        List<SoapNote> orderedNotes = soapNoteRepository.findByUserIdOrderByCreatedAtDesc(testUserId);
        queryTime = System.currentTimeMillis() - queryStart;
        
        assertEquals(1000, orderedNotes.size());
        System.out.println("findByUserIdOrderByCreatedAtDesc query time: " + queryTime + "ms");
        
        // Verify ordering
        for (int i = 0; i < orderedNotes.size() - 1; i++) {
            assertTrue(
                orderedNotes.get(i).getCreatedAt().isAfter(orderedNotes.get(i + 1).getCreatedAt()) ||
                orderedNotes.get(i).getCreatedAt().equals(orderedNotes.get(i + 1).getCreatedAt()),
                "Results should be ordered by creation date descending"
            );
        }
        
        assertTrue(queryTime < 150, "Ordered user query should be under 150ms with composite index");
    }

    @Test
    @DisplayName("Performance: Date-range queries with large dataset")
    void testDateRangeQueryPerformance() {
        // Create test data spanning 30 days
        Instant now = Instant.now();
        createTestSoapNotesWithDateRange(testUserId, 500, now.minus(30, ChronoUnit.DAYS), now);
        
        // Test date range query (last 7 days)
        Instant weekAgo = now.minus(7, ChronoUnit.DAYS);
        
        long queryStart = System.currentTimeMillis();
        List<SoapNote> recentNotes = soapNoteRepository.findByUserIdAndCreatedAtBetween(
            testUserId, weekAgo, now);
        long queryTime = System.currentTimeMillis() - queryStart;
        
        System.out.println("Date range query time (7 days): " + queryTime + "ms");
        System.out.println("Recent notes found: " + recentNotes.size());
        
        // Verify date range
        for (SoapNote note : recentNotes) {
            assertTrue(note.getCreatedAt().isAfter(weekAgo) || note.getCreatedAt().equals(weekAgo));
            assertTrue(note.getCreatedAt().isBefore(now) || note.getCreatedAt().equals(now));
        }
        
        assertTrue(queryTime < 50, "Date range query should be under 50ms with composite index");
    }

    @Test
    @DisplayName("Performance: Session-based queries")
    void testSessionBasedQueryPerformance() {
        // Create transcripts for session
        createTestTranscripts(testSessionId, testUserId, 200);
        
        // Test session query performance
        long queryStart = System.currentTimeMillis();
        List<Transcript> sessionTranscripts = transcriptRepository.findAllBySessionId(testSessionId);
        long queryTime = System.currentTimeMillis() - queryStart;
        
        assertEquals(200, sessionTranscripts.size());
        System.out.println("Session transcript query time: " + queryTime + "ms");
        
        // Test composite session + user query (replaces stream filtering)
        queryStart = System.currentTimeMillis();
        List<Transcript> authorizedTranscripts = transcriptRepository.findBySessionIdAndUserId(
            testSessionId, testUserId);
        queryTime = System.currentTimeMillis() - queryStart;
        
        assertEquals(200, authorizedTranscripts.size());
        System.out.println("Session + User composite query time: " + queryTime + "ms");
        
        assertTrue(queryTime < 30, "Composite session+user query should be under 30ms");
    }

    @Test
    @DisplayName("Performance: Concurrent query stress test")
    void testConcurrentQueryPerformance() throws InterruptedException, ExecutionException {
        // Setup data for concurrent testing
        for (UUID userId : testUserIds) {
            createTestSoapNotes(userId, 100);
        }

        ExecutorService executor = Executors.newFixedThreadPool(10);
        List<Callable<Long>> tasks = new ArrayList<>();

        // Create concurrent query tasks
        for (UUID userId : testUserIds) {
            tasks.add(() -> {
                long start = System.currentTimeMillis();
                List<SoapNote> notes = soapNoteRepository.findByUserIdOrderByCreatedAtDesc(userId);
                long duration = System.currentTimeMillis() - start;
                assertEquals(100, notes.size());
                return duration;
            });
        }

        long overallStart = System.currentTimeMillis();
        List<Future<Long>> results = executor.invokeAll(tasks);
        long overallTime = System.currentTimeMillis() - overallStart;

        // Collect individual query times
        List<Long> queryTimes = new ArrayList<>();
        for (Future<Long> result : results) {
            queryTimes.add(result.get());
        }

        executor.shutdown();

        double avgQueryTime = queryTimes.stream().mapToLong(Long::longValue).average().orElse(0);
        long maxQueryTime = queryTimes.stream().mapToLong(Long::longValue).max().orElse(0);

        System.out.println("Concurrent queries - Overall time: " + overallTime + "ms");
        System.out.println("Concurrent queries - Average individual time: " + avgQueryTime + "ms");
        System.out.println("Concurrent queries - Max individual time: " + maxQueryTime + "ms");
        System.out.println("Concurrent queries - Total operations: " + testUserIds.size());

        // Performance assertions
        assertTrue(avgQueryTime < 100, "Average concurrent query time should be under 100ms");
        assertTrue(maxQueryTime < 200, "No concurrent query should take more than 200ms");
        assertTrue(overallTime < 1000, "All concurrent queries should complete within 1 second");
    }

    @Test
    @DisplayName("Performance: Memory usage with large result sets")
    void testMemoryUsageWithLargeResultSets() {
        // Force garbage collection to get baseline
        System.gc();
        long memoryBefore = getUsedMemory();

        // Create large dataset
        createTestSoapNotes(testUserId, 2000);
        
        System.gc();
        long memoryAfterSetup = getUsedMemory();
        
        // Query large result set
        List<SoapNote> allNotes = soapNoteRepository.findByUserIdOrderByCreatedAtDesc(testUserId);
        
        System.gc();
        long memoryAfterQuery = getUsedMemory();

        System.out.println("Memory before setup: " + (memoryBefore / 1024 / 1024) + " MB");
        System.out.println("Memory after setup: " + (memoryAfterSetup / 1024 / 1024) + " MB");
        System.out.println("Memory after query: " + (memoryAfterQuery / 1024 / 1024) + " MB");
        System.out.println("Result set size: " + allNotes.size());
        
        long memoryUsedByQuery = memoryAfterQuery - memoryAfterSetup;
        double memoryPerEntity = (double) memoryUsedByQuery / allNotes.size();
        
        System.out.println("Memory used by result set: " + (memoryUsedByQuery / 1024) + " KB");
        System.out.println("Memory per entity: " + memoryPerEntity + " bytes");
        
        assertEquals(2000, allNotes.size());
        
        // Memory usage should be reasonable (less than 10MB for 2000 entities)
        assertTrue(memoryUsedByQuery < 10 * 1024 * 1024, 
            "Memory usage for 2000 entities should be under 10MB");
    }

    /**
     * Benchmark comparison: Stream filtering vs Database query
     */
    @Test
    @DisplayName("Benchmark: Stream filtering vs Database query")
    void benchmarkStreamVsDatabaseFiltering() {
        // Create test data
        createTestTranscripts(testSessionId, testUserId, 1000);
        // Add some transcripts from other users to the same session
        for (int i = 0; i < 5; i++) {
            createTestTranscripts(testSessionId, UUID.randomUUID(), 50);
        }

        // Method 1: Database query (current stream approach simulation)
        long streamStart = System.currentTimeMillis();
        List<Transcript> allSessionTranscripts = transcriptRepository.findAllBySessionId(testSessionId);
        List<Transcript> filteredByStream = allSessionTranscripts.stream()
            .filter(transcript -> transcript.getUserId().equals(testUserId))
            .toList();
        long streamTime = System.currentTimeMillis() - streamStart;

        // Method 2: Database composite query
        long dbQueryStart = System.currentTimeMillis();
        List<Transcript> filteredByDB = transcriptRepository.findBySessionIdAndUserId(
            testSessionId, testUserId);
        long dbQueryTime = System.currentTimeMillis() - dbQueryStart;

        System.out.println("Stream filtering time: " + streamTime + "ms");
        System.out.println("Database composite query time: " + dbQueryTime + "ms");
        System.out.println("Stream result size: " + filteredByStream.size());
        System.out.println("Database result size: " + filteredByDB.size());
        System.out.println("Performance improvement: " + 
            (streamTime > 0 ? (double) streamTime / dbQueryTime : "N/A") + "x");

        assertEquals(filteredByStream.size(), filteredByDB.size());
        assertEquals(1000, filteredByDB.size());
        
        // Database query should be faster
        assertTrue(dbQueryTime <= streamTime, 
            "Database composite query should be faster than or equal to stream filtering");
    }

    // Helper methods

    private void createTestSoapNotes(UUID userId, int count) {
        List<SoapNote> notes = new ArrayList<>();
        Instant baseTime = Instant.now().minus(count, ChronoUnit.MINUTES);
        
        for (int i = 0; i < count; i++) {
            SoapNote note = new SoapNote();
            note.setUserId(userId);
            note.setSessionId(testSessionId);
            note.setTranscriptId(UUID.randomUUID());
            note.setTextStructured("Test SOAP note #" + i);
            note.setCreatedAt(baseTime.plus(i, ChronoUnit.MINUTES));
            notes.add(note);
        }
        
        soapNoteRepository.saveAll(notes);
    }

    private void createTestSoapNotesWithDateRange(UUID userId, int count, Instant startDate, Instant endDate) {
        List<SoapNote> notes = new ArrayList<>();
        long timeRangeMs = endDate.toEpochMilli() - startDate.toEpochMilli();
        long intervalMs = timeRangeMs / count;
        
        for (int i = 0; i < count; i++) {
            SoapNote note = new SoapNote();
            note.setUserId(userId);
            note.setSessionId(testSessionId);
            note.setTranscriptId(UUID.randomUUID());
            note.setTextStructured("Test SOAP note #" + i);
            note.setCreatedAt(Instant.ofEpochMilli(startDate.toEpochMilli() + (i * intervalMs)));
            notes.add(note);
        }
        
        soapNoteRepository.saveAll(notes);
    }

    private void createTestTranscripts(UUID sessionId, UUID userId, int count) {
        List<Transcript> transcripts = new ArrayList<>();
        Instant baseTime = Instant.now().minus(count, ChronoUnit.MINUTES);
        
        for (int i = 0; i < count; i++) {
            Transcript transcript = new Transcript();
            transcript.setSessionId(sessionId);
            transcript.setUserId(userId);
            transcript.setInputType("test");
            transcript.setTextRaw("Test transcript #" + i);
            transcript.setCreatedAt(baseTime.plus(i, ChronoUnit.MINUTES));
            transcripts.add(transcript);
        }
        
        transcriptRepository.saveAll(transcripts);
    }

    private long getUsedMemory() {
        Runtime runtime = Runtime.getRuntime();
        return runtime.totalMemory() - runtime.freeMemory();
    }
}