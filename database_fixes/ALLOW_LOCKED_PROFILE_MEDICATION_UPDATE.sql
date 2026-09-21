-- MyHFGuard database fix
-- Purpose: keep personal/health baseline fields locked after first submission,
-- while allowing fields that are intentionally editable later, especially
-- current_medication.
--
-- Run this ONCE in Supabase Dashboard -> SQL Editor -> New query -> Run.

begin;

-- Remove the older trigger that raises:
-- "Profile is locked and cannot be edited"
-- We identify it by the exception text so this works even if the old trigger
-- was given a different name in an earlier database setup.
do $$
declare
    r record;
begin
    for r in
        select t.tgname
        from pg_trigger t
        join pg_class c on c.oid = t.tgrelid
        join pg_namespace n on n.oid = c.relnamespace
        join pg_proc p on p.oid = t.tgfoid
        where not t.tgisinternal
          and n.nspname = 'public'
          and c.relname = 'profiles'
          and pg_get_functiondef(p.oid) ilike '%Profile is locked and cannot be edited%'
    loop
        execute format('drop trigger if exists %I on public.profiles', r.tgname);
    end loop;
end
$$;

create or replace function public.myhfguard_guard_locked_profile_baseline()
returns trigger
language plpgsql
as $$
begin
    if old.baseline_locked is true then
        -- These are the one-time personal information / health baseline fields.
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

        -- Intentionally allowed after locking:
        --   current_medication
        --   language
        --   target_steps
        --   coins
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

-- Optional quick check after running:
-- update public.profiles
-- set current_medication = current_medication
-- where baseline_locked is true;
-- This should complete without the old P0001 exception.
