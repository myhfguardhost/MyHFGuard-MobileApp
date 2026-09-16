-- Run in Supabase SQL Editor. This lets each signed-in patient read and save
-- only rows where public.weight_sample.patient_id is their auth user id.
alter table public.weight_sample enable row level security;

drop policy if exists "Patients manage their own weight samples" on public.weight_sample;
create policy "Patients manage their own weight samples"
on public.weight_sample
for all
to authenticated
using (auth.uid() = patient_id)
with check (auth.uid() = patient_id);
