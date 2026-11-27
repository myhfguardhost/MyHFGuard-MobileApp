-- MyHFGuard: add date of birth to patient profiles
-- Run this ONCE in Supabase Dashboard -> SQL Editor -> New query -> Run.
--
-- The Profile page now asks for the date of birth (calendar) instead of age.
-- The app works out the age from this date, so the age shown goes up by
-- itself every year. The old "age" column is kept and still filled at
-- submission, so existing pages that read it keep working.
--
-- Safe to run more than once. It only adds a new empty column; no existing
-- data is changed or deleted.

alter table public.profiles
  add column if not exists date_of_birth date;

-- Optional check (shows whether the column now exists):
-- select column_name, data_type
-- from information_schema.columns
-- where table_schema = 'public' and table_name = 'profiles' and column_name = 'date_of_birth';
