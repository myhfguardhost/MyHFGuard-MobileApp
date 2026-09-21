-- MyHFGuard FINAL Current Medication fix
-- Run this ONCE in Supabase Dashboard -> SQL Editor -> New query -> Run.
--
-- MyHFGuard stores the Profile "Current Medication" text in
-- public.profiles.current_medication. It should NOT be inserted into the
-- public.medication table from the Profile page.
--
-- This script keeps one-time personal/baseline profile fields locked while
-- allowing current_medication to be changed later.

begin;

-- Ensure the two lock-state columns exist for older databases.
alter table public.profiles
  add column if not exists profile_completed boolean not null default false,
  add column if not exists baseline_locked boolean not null default false;

-- Remove older profile-lock triggers that raise the exact error previously
-- seen by the Android app: "Profile is locked and cannot be edited".
-- Using the function body lets this work even when an old trigger had a
-- different trigger name.
do $$
declare
  r record;
begin
  for r in
    select distinct t.tgname
    from pg_trigger t
    join pg_class c on c.oid = t.tgrelid
    join pg_namespace n on n.oid = c.relnamespace
    join pg_proc p on p.oid = t.tgfoid
    where not t.tgisinternal
      and n.nspname = 'public'
      and c.relname = 'profiles'
      and (
        p.proname in ('enforce_patient_profile_lock', 'myhfguard_guard_locked_profile_baseline')
        or pg_get_functiondef(p.oid) ilike '%Profile is locked and cannot be edited%'
        or pg_get_functiondef(p.oid) ilike '%Profile baseline is locked and cannot be edited%'
      )
  loop
    execute format('drop trigger if exists %I on public.profiles', r.tgname);
  end loop;
end
$$;

-- Patients must still be able to update their own profile row. The trigger
-- below is responsible for deciding which fields may change after locking.
alter table public.profiles enable row level security;
grant select, insert, update on public.profiles to authenticated;

drop policy if exists "Users can view own profile" on public.profiles;
create policy "Users can view own profile"
on public.profiles
for select
to authenticated
using (user_id = auth.uid());

drop policy if exists "Users can insert own profile" on public.profiles;
create policy "Users can insert own profile"
on public.profiles
for insert
to authenticated
with check (user_id = auth.uid());

drop policy if exists "Users can update own profile" on public.profiles;
create policy "Users can update own profile"
on public.profiles
for update
to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create or replace function public.myhfguard_guard_locked_profile_baseline()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  -- After first profile submission, lock only the one-time identity and
  -- health-baseline fields. current_medication remains intentionally editable.
  if coalesce(old.baseline_locked, false) then
    if new.user_id is distinct from old.user_id
       or new.full_name is distinct from old.full_name
       or new.age is distinct from old.age
       or new.ic is distinct from old.ic
       or new.systolic_bp is distinct from old.systolic_bp
       or new.diastolic_bp is distinct from old.diastolic_bp
       or new.heart_rate is distinct from old.heart_rate
       or new.dry_weight is distinct from old.dry_weight
       or new.height is distinct from old.height
       or new.bmi is distinct from old.bmi
       or new.profile_completed is distinct from old.profile_completed
       or new.baseline_locked is distinct from old.baseline_locked
    then
      raise exception using
        errcode = 'P0001',
        message = 'Profile baseline is locked and cannot be edited';
    end if;
  end if;

  return new;
end;
$$;

drop trigger if exists myhfguard_guard_locked_profile_baseline_trigger on public.profiles;
create trigger myhfguard_guard_locked_profile_baseline_trigger
before update on public.profiles
for each row
execute function public.myhfguard_guard_locked_profile_baseline();

commit;

-- Optional verification query (safe; it does not change the text):
-- update public.profiles
-- set current_medication = current_medication
-- where user_id = auth.uid();
