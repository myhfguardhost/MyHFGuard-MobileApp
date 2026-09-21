# MyHFGuard Gemini Health Agent + Smart OCR Backend

This FastAPI service powers the Android app's Gemini chatbot and Gemini Vision OCR fallback. The Gemini API key stays on the server and is never placed in Kotlin, `BuildConfig`, GitHub, or the APK.

## What is improved

- A staged conversation flow: intent detection, emergency screening, symptom/emotion recognition, patient-context grounding, Gemini response, then response-quality validation and retry.
- Natural greetings and follow-up conversation instead of rejecting “hello”.
- Turn-by-turn conversation memory: short answers such as `1 time`, `yes`, `no`, `sekali`, `一次`, and `ஒரு முறை` are treated as answers to the previous question instead of new questions.
- A no-repeat validator rejects Gemini replies that ask an already answered vomiting-frequency question again.
- Symptom-aware support for nausea/vomiting, dizziness, tiredness, cough, swelling, poor appetite, palpitations, pain and breathlessness.
- Emotional acknowledgement for sadness, fear, anxiety, loneliness, stress and frustration.
- English, Bahasa Melayu, Malaysian Rojak, Simplified Chinese and Tamil.
- Per-user Supabase token verification; chat history stays in the signed-in user's Android storage rather than global server memory.
- `/api/ocr/read` Gemini Vision endpoint for weight scales and blood-pressure monitors.
- Local ML Kit OCR remains the fast first pass; Gemini cross-checks or recovers uncertain scans.

## Required environment variables

Copy `.env.example` to `.env` for local development, then set:

- `GEMINI_API_KEY`: your real Gemini API key.
- `SUPABASE_URL`: the same Supabase project used by the Android app.
- `SUPABASE_ANON_KEY`: the Supabase anon key used to verify login tokens.
- `REQUIRE_AUTH=true` for deployment.
- Optional: `GEMINI_MODEL` and `GEMINI_OCR_MODEL` (default: `gemini-2.5-flash`).

Do not send the API key to the mobile app. A key embedded in an APK can be extracted.

## Run locally

```bash
cd fastapi_ai_backend
pip install -r requirements.txt
uvicorn main:app --host 0.0.0.0 --port 8000 --reload
```

For the Android emulator, build with:

```bash
./gradlew assembleDebug -PAI_API_BASE_URL=http://10.0.2.2:8000
```

A physical phone must use your computer's LAN IP, and the phone and computer must be on the same network. Local HTTP may also require a debug network-security configuration; HTTPS is recommended.

## Deploy on Render

Create the service from `fastapi_ai_backend/render.yaml`, then enter the secret values in Render's Environment page. The included service name is `myhfguard-ai`, so its expected URL is normally:

`https://myhfguard-ai.onrender.com`

Confirm the actual URL shown by Render and open `/health`. It should show `geminiConfigured: true`.

The Android project defaults to that service URL. To use a different deployed URL without editing source code:

```bash
./gradlew assembleDebug -PAI_API_BASE_URL=https://YOUR-AI-SERVICE.onrender.com
```

## API routes

- `GET /health`: service and Gemini configuration status.
- `POST /api/chat/symptoms`: context-aware Gemini conversation.
- `POST /api/ocr/read`: authenticated Gemini Vision reading for `weight` or `blood_pressure` images.

Both POST routes require the Android app's Supabase bearer token when `REQUIRE_AUTH=true`, and the token user ID must match `patientId`.

## Test checklist

1. Send `Hello` and confirm a greeting plus a question about how the user feels.
2. Send `I feel like vomiting` and confirm symptom-specific advice and one follow-up question.
3. When it asks how many times, send `1 time`. Confirm it replies that you vomited once and moves on to whether fluids/medicines stay down. It must not ask the count again.
4. Reply `no` to the fluid question. Confirm it advises prompt contact with the healthcare team and does not repeat either earlier question.
5. Repeat the same flow using `sekali`, `一次`, and `ஒரு முறை`.
6. Photograph a weight scale and a BP monitor; verify the form fills, then visually confirm the numbers before saving.
7. Save vitals twice on the same date; verify the existing record is updated rather than duplicated.
8. Complete an education video, claim its coin, then tap **Watch Again** and replay it.
