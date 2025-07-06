-- RLS aktivieren
alter table session enable row level security;
alter table transcript enable row level security;
alter table soap_note enable row level security;
alter table assistant_interaction enable row level security;
alter table chat_message enable row level security;
alter table user_profile enable row level security;

-- session
create policy "User can read own sessions"
on session
for select
using (user_id = auth.uid());

create policy "User can insert own sessions"
on session
for insert
with check (user_id = auth.uid());

create policy "User can update own sessions"
on session
for update
using (user_id = auth.uid());

-- transcript
create policy "User can read own transcripts"
on transcript
for select
using (user_id = auth.uid());

create policy "User can insert own transcripts"
on transcript
for insert
with check (user_id = auth.uid());

create policy "User can update own transcripts"
on transcript
for update
using (user_id = auth.uid());

-- soap_note
create policy "User can read own soap notes"
on soap_note
for select
using (user_id = auth.uid());

create policy "User can insert own soap notes"
on soap_note
for insert
with check (user_id = auth.uid());

create policy "User can update own soap notes"
on soap_note
for update
using (user_id = auth.uid());

-- assistant_interaction
create policy "User can read own interactions"
on assistant_interaction
for select
using (user_id = auth.uid());

create policy "User can insert own interactions"
on assistant_interaction
for insert
with check (user_id = auth.uid());

create policy "User can update own interactions"
on assistant_interaction
for update
using (user_id = auth.uid());

-- chat_message
create policy "User can read own messages"
on chat_message
for select
using (user_id = auth.uid());

create policy "User can insert own messages"
on chat_message
for insert
with check (user_id = auth.uid());

create policy "User can update own messages"
on chat_message
for update
using (user_id = auth.uid());

-- user_profile
create policy "User can view own profile"
on user_profile
for select
using (user_id = auth.uid());

create policy "User can insert own profile"
on user_profile
for insert
with check (user_id = auth.uid());

create policy "User can update own profile"
on user_profile
for update
using (user_id = auth.uid());
