-- Erweiterungen aktivieren
create extension if not exists "uuid-ossp";

-- User-Tabelle wird durch Supabase bereitgestellt (auth.users)

-- Session-Tabelle
create table if not exists session (
    id uuid primary key default uuid_generate_v4(),
    user_id uuid not null references auth.users(id) on delete cascade,
    patient_name text,
    started_at timestamp with time zone default timezone('utc', now()),
    ended_at timestamp with time zone
);

-- Transcript-Tabelle
create table if not exists transcript (
    id uuid primary key default uuid_generate_v4(),
    session_id uuid not null references session(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    input_type text,
    text_raw text,
    created_at timestamp with time zone default timezone('utc', now())
);

-- SoapNote-Tabelle
create table if not exists soap_note (
    id uuid primary key default uuid_generate_v4(),
    transcript_id uuid not null references transcript(id) on delete cascade,
    session_id uuid not null references session(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    text_structured jsonb,
    created_at timestamp with time zone default timezone('utc', now())
);

-- AssistantInteraction-Tabelle
create table if not exists assistant_interaction (
    id uuid primary key default uuid_generate_v4(),
    session_id uuid not null references session(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    assistant_id uuid,
    input_type text,
    input_json jsonb,
    output_type text,
    output_json jsonb,
    created_at timestamp with time zone default timezone('utc', now())
);

-- ChatMessage-Tabelle
create table if not exists chat_message (
    id uuid primary key default uuid_generate_v4(),
    session_id uuid not null references session(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    assistant_id uuid,
    interaction_id uuid not null references assistant_interaction(id) on delete cascade,
    sender text,
    message_type text,
    content text,
    created_at timestamp with time zone default timezone('utc', now())
);

-- UserProfile-Tabelle

create table if not exists user_profile (
  user_id uuid primary key references auth.users(id) on delete cascade,
  first_name text,
  last_name text,
  display_name text,
  created_at timestamp with time zone default timezone('utc', now())
);

