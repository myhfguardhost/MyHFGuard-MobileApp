# Admin target-steps patch

The uploaded ZIP contains the Android project only, so the admin web page could not be edited in-place.

1. Run `../SUPABASE_EXERCISE_ENHANCEMENT_0716.sql` in Supabase.
2. Copy `PatientTargetStepsControl.tsx` into the admin web project.
3. Render it on the patient detail/settings page:

```tsx
<PatientTargetStepsControl
  patientId={patient.user_id}
  initialTarget={patient.profile?.target_steps}
/>
```

The Android app reads `profiles.target_steps` automatically and falls back to 3000 when it is missing.
