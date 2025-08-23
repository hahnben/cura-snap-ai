-- Migration: Phase 3.2 Database Query Optimization - Performance Indexes
-- Created: 2025-08-23
-- Purpose: Add database indexes for optimizing frequent query patterns

-- Drop existing indexes if they exist (for re-running migration)
drop index if exists idx_soap_note_user_id;
drop index if exists idx_transcript_user_id;
drop index if exists idx_session_user_id;
drop index if exists idx_soap_note_user_created;
drop index if exists idx_transcript_user_created;
drop index if exists idx_session_user_started;
drop index if exists idx_transcript_session_id;
drop index if exists idx_soap_note_session_id;
drop index if exists idx_transcript_session_user;
drop index if exists idx_soap_note_session_user;
drop index if exists idx_soap_note_created_at;
drop index if exists idx_transcript_created_at;
drop index if exists idx_session_started_at;

-- User-basierte Queries (häufigste Pattern)
-- Optimiert: SELECT * FROM soap_note WHERE user_id = ?
create index idx_soap_note_user_id on soap_note(user_id);
create index idx_transcript_user_id on transcript(user_id);
create index idx_session_user_id on session(user_id);

-- Date-basierte Queries für Reports und chronologische Abfragen
-- Optimiert: SELECT * FROM soap_note WHERE user_id = ? ORDER BY created_at DESC
create index idx_soap_note_user_created on soap_note(user_id, created_at desc);
create index idx_transcript_user_created on transcript(user_id, created_at desc);
create index idx_session_user_started on session(user_id, started_at desc);

-- Session-basierte Queries für Transcript-Grouping
-- Optimiert: SELECT * FROM transcript WHERE session_id = ?
create index idx_transcript_session_id on transcript(session_id);
create index idx_soap_note_session_id on soap_note(session_id);

-- Composite Indizes für kombinierte User+Session-Queries
-- Optimiert: SELECT * FROM transcript WHERE session_id = ? AND user_id = ?
create index idx_transcript_session_user on transcript(session_id, user_id);
create index idx_soap_note_session_user on soap_note(session_id, user_id);

-- Date-Range-Queries für Analytics
-- Optimiert: SELECT * FROM soap_note WHERE created_at BETWEEN ? AND ?
create index idx_soap_note_created_at on soap_note(created_at desc);
create index idx_transcript_created_at on transcript(created_at desc);
create index idx_session_started_at on session(started_at desc);

-- Analyze tables after index creation for better query planning
analyze soap_note;
analyze transcript;
analyze session;

-- Performance verification queries (commented out for production)
-- Use these to verify index effectiveness with EXPLAIN ANALYZE

-- EXPLAIN ANALYZE SELECT * FROM soap_note WHERE user_id = 'test-uuid' ORDER BY created_at DESC LIMIT 10;
-- EXPLAIN ANALYZE SELECT * FROM transcript WHERE session_id = 'test-uuid' AND user_id = 'test-uuid';
-- EXPLAIN ANALYZE SELECT * FROM soap_note WHERE created_at >= now() - interval '7 days';