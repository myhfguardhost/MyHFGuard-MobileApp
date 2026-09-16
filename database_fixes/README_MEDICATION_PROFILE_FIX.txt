MYHFGUARD - CURRENT MEDICATION SAVE FIX
======================================

Problem seen in the app
-----------------------
HTTP 400 / PostgreSQL P0001:
"Profile is locked and cannot be edited"

Why it happened
---------------
The Android screen correctly leaves Current Medication editable after the
profile is locked, but an older Supabase trigger locks the ENTIRE profiles row.
Therefore PATCH profiles.current_medication is rejected by PostgreSQL.

What was changed in this project
--------------------------------
1. The Android app now saves editable medicine records to the dedicated
   `medication` table first.
2. Profile, Dashboard, AI and medicine reminders use the editable medication
   table when records are available, with profiles.current_medication kept as a
   backwards-compatible fallback.
3. The app still tries to synchronize profiles.current_medication, but an old
   lock-trigger rejection no longer makes the user's save fail.
4. A Supabase SQL fix is included:
   ALLOW_LOCKED_PROFILE_MEDICATION_UPDATE.sql

Recommended one-time database step
----------------------------------
Supabase Dashboard -> SQL Editor -> New query
Paste/run ALLOW_LOCKED_PROFILE_MEDICATION_UPDATE.sql

That SQL keeps personal information and the health baseline locked while
allowing Current Medication and other intended post-lock fields to update.
