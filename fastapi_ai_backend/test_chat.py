import json
import os
import urllib.request

base_url = os.getenv("AI_BASE_URL", "http://127.0.0.1:8000").rstrip("/")
token = os.getenv("SUPABASE_ACCESS_TOKEN", "")
patient_id = os.getenv("PATIENT_ID", "test-patient")

payload = {
    "patientId": patient_id,
    "message": "How is my health today?",
    "history": [],
    "language": "en",
    "context": {
        "steps": 1800,
        "targetSteps": 3000,
        "bp": "128/78",
        "spo2": 97,
        "heartRate": 76,
        "loaded": True,
    },
}
headers = {"Content-Type": "application/json"}
if token:
    headers["Authorization"] = f"Bearer {token}"
request = urllib.request.Request(
    f"{base_url}/api/chat/symptoms",
    data=json.dumps(payload).encode("utf-8"),
    headers=headers,
    method="POST",
)
with urllib.request.urlopen(request, timeout=90) as response:
    print(response.read().decode("utf-8"))
