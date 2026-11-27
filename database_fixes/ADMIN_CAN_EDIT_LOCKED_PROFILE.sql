-- MyHFGuard: let the admin web edit a locked patient profile
-- Run this ONCE in Supabase Dashboard -> SQL Editor -> New query -> Run.
--
-- Before: once a patient submits the Profile, the lock trigger blocked
-- every change to the locked fields, for everyone (admin included).
--
-- After:
--   * Patients (the mobile app, signed in as the patient) are still locked.
--   * The admin web server (service_role key) and the Supabase Dashboard
--     can correct the locked fields.
--   * date_of_birth is now one of the locked fields too.
--   * current_medication stays editable by the patient, as before.
--
-- Safe to run more than once. No existing data is changed or deleted.

begin;

-- The Profile page now saves the date of birth (needed by the trigger below).
alter table public.profiles
  add column if not exists date_of_birth date;

create or replace function public.myhfguard_guard_locked_profile_baseline()
returns trigger
language plpgsql
security definer
set search_path = public
as $$
begin
  -- Only requests made with a patient (or anonymous) JWT are locked.
  -- auth.role() is 'service_role' for the admin server and empty for the
  -- Supabase Dashboard SQL editor, so both may correct the profile.
  if coalesce(auth.role(), '') not in ('authenticated', 'anon') then
    return new;
  end if;

  -- After first profile submission, lock the one-time identity and
  -- health-baseline fields. current_medication remains editable.
  if coalesce(old.baseline_locked, false) then
    if new.user_id is distinct from old.user_id
       or new.full_name is distinct from old.full_name
       or new.age is distinct from old.age
       or new.date_of_birth is distinct from old.date_of_birth
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
