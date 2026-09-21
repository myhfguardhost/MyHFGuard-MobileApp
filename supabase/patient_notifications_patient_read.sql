-- Run once in Supabase SQL Editor. The Android app reads only notifications
-- addressed to the signed-in patient; admin inserts remain managed separately.
alter table public.patient_notifications enable row level security;

drop policy if exists "Patients read their own notifications" on public.patient_notifications;
create policy "Patients read their own notifications"
on public.patient_notifications
for select
to authenticated
using (auth.uid() = patient_id);
