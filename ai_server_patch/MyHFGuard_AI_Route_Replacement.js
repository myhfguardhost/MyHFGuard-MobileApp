// MyHFGuard AI chatbot route replacement
// Replace the existing app.post('/api/chat/symptoms', ...) block with this block.
// It expects these existing functions/variables in server.js:
//   - app
//   - fetchPatientHealthData(patientId)
//   - generateWithRetry(prompt, systemInstruction)

function cleanChatHistory(history) {
  if (!Array.isArray(history)) return []

  return history
    .slice(-10)
    .map((item) => ({
      role: item?.role === 'assistant' ? 'assistant' : 'user',
      content: String(item?.content ?? item?.message ?? '').trim().slice(0, 1200),
    }))
    .filter((item) => item.content)
}


function cleanClientContext(context) {
  if (!context || typeof context !== 'object' || Array.isArray(context)) return null

  const allowedKeys = [
    'steps', 'heartRate', 'spo2', 'weight', 'weightTrendKg', 'bp', 'pulse',
    'medication', 'symptomScore', 'symptomTrendDelta', 'waterMl',
    'waterLimitMl', 'saltScore', 'nextAppointmentTitle',
    'nextAppointmentDate', 'nextAppointmentTime', 'targetSteps'
  ]

  const cleaned = {}
  for (const key of allowedKeys) {
    const value = context[key]
    if (typeof value === 'number' && Number.isFinite(value)) cleaned[key] = value
    if (typeof value === 'string' && value.trim()) cleaned[key] = value.trim().slice(0, 500)
  }

  return Object.keys(cleaned).length ? cleaned : null
}

function formatChatHistory(history) {
  if (!history.length) return 'No earlier conversation.'

  return history
    .map((item) => `${item.role === 'assistant' ? 'Assistant' : 'Patient'}: ${item.content}`)
    .join('\n')
}



function languageNameFromCode(code) {
  const value = String(code || '').toLowerCase()
  if (value === 'ms') return 'Bahasa Melayu'
  if (value === 'rojak') return 'natural Malaysian English-Bahasa Melayu Rojak'
  if (value === 'zh') return 'Mandarin Chinese'
  if (value === 'ta') return 'Tamil'
  if (value === 'en') return 'English'
  return null
}

function detectRequestedLanguage(message) {
  const text = String(message || '')
  if (/[\u4E00-\u9FFF]/u.test(text)) return 'Mandarin Chinese'
  if (/[\u0B80-\u0BFF]/u.test(text)) return 'Tamil'

  const lower = text.toLowerCase()
  const malayWords = [
    'apa', 'adakah', 'bagaimana', 'macam mana', 'kenapa', 'boleh', 'saya',
    'nak', 'dah', 'belum', 'tak', 'bacaan', 'ubat', 'garam', 'air', 'berat',
    'langkah', 'temujanji', 'simptom', 'jantung', 'pening', 'bengkak', 'sesak'
  ]
  const englishWords = [
    'what', 'how', 'why', 'can', 'should', 'my', 'reading', 'medicine',
    'water', 'weight', 'steps', 'appointment', 'symptom', 'heart', 'exercise'
  ]
  const score = (words) => words.filter((word) => lower.includes(word)).length
  const malayScore = score(malayWords)
  const englishScore = score(englishWords)

  if (malayScore > 0 && englishScore > 0) {
    return 'natural Malaysian English-Bahasa Melayu Rojak'
  }
  if (malayScore > englishScore) return 'Bahasa Melayu'
  return 'English'
}

function localisedTemporaryError(language) {
  if (language === 'Mandarin Chinese') {
    return '我暂时无法分析您的健康数据。请稍后再试。若出现胸痛、严重呼吸困难、晕厥、意识混乱、嘴唇发蓝或其他紧急症状，请立即寻求紧急医疗帮助。'
  }
  if (language === 'Tamil') {
    return 'உங்கள் உடல்நலத் தரவை இப்போது பகுப்பாய்வு செய்ய முடியவில்லை. சிறிது நேரத்தில் மீண்டும் முயற்சிக்கவும். நெஞ்சுவலி, கடுமையான மூச்சுத்திணறல், மயக்கம், குழப்பம், உதடு நீலமாகுதல் அல்லது வேறு அவசர அறிகுறி இருந்தால் உடனடி மருத்துவ உதவி பெறவும்.'
  }
  if (language === 'Bahasa Melayu' || language.includes('Rojak')) {
    return 'Saya tidak dapat menganalisis data kesihatan anda buat masa ini. Sila cuba sebentar lagi. Jika ada sakit dada, sesak nafas teruk, pengsan, keliru, bibir kebiruan atau simptom kecemasan lain, dapatkan bantuan perubatan segera.'
  }
  return 'I could not analyse your health data just now. Please try again shortly. If you have chest pain, severe breathing difficulty, fainting, confusion, blue lips, or another emergency symptom, seek emergency help immediately.'
}

// AI MyChat Route — health-data-aware and able to understand broad health questions
app.post('/api/chat/symptoms', async (req, res) => {
  console.log('[MyChat] Request received', {
    patientId: req.body?.patientId,
    messageLength: String(req.body?.message || '').length,
  })

  try {
    const message = String(req.body?.message || '').trim()
    const patientId = String(req.body?.patientId || '').trim()
    const history = cleanChatHistory(req.body?.history)
    const clientContext = cleanClientContext(req.body?.context)
    const requestedLanguage = languageNameFromCode(req.body?.language) || detectRequestedLanguage(message)

    if (!message) {
      return res.status(400).json({ error: 'Message is required' })
    }

    if (!patientId) {
      return res.status(400).json({ error: 'Patient ID is required' })
    }

    /*
      IMPORTANT:
      Do not reject messages using a health-keyword whitelist.

      Questions such as:
      - "How is my health?"
      - "Am I getting better?"
      - "What changed since yesterday?"
      - "Why do I feel like this?"
      are valid health questions even when they do not contain a specific
      keyword such as BP, SpO2, weight, or medicine.
    */

    const healthData = await fetchPatientHealthData(patientId)

    const systemInstruction = `You are MyHFGuard AI, a health-support assistant for a patient who may have heart failure.

SCOPE
- Answer questions about the patient's health data, health condition, symptoms, blood pressure, pulse, heart rate, SpO2, weight, activity, steps, exercise, water/fluid, salt/sodium, diet, medicines, appointments, reminders, and heart-failure self-care.
- Also answer broad health questions such as "How is my health?", "Am I improving?", "What should I pay attention to?", and follow-up questions that depend on the conversation.
- For clearly unrelated questions, briefly say that you can help with health and MyHFGuard information, then invite a health-related question.

PATIENT-SAFETY RULES
- Do not claim to diagnose a disease.
- Do not say the patient is definitely safe or definitely healthy.
- Do not prescribe a new medicine, stop a medicine, or change a dose.
- Explain that app data is supportive information and does not replace a doctor.
- Never invent a reading. Clearly say when data is missing or old.
- Distinguish the patient's recorded data from general health information.
- When the patient asks for an overall assessment, summarize the available readings, symptoms, trends, missing data, and the most important next action.
- Use the dates and values supplied in PATIENT DATA.
- Ask one useful follow-up question only when the available information is not enough.

URGENT WARNING SIGNS
Advise immediate emergency help when the patient reports chest pain/tightness, severe difficulty breathing, fainting/collapse, confusion, blue lips, stroke-like symptoms, SpO2 below 90%, blood pressure at or above 180/120, or a very fast/slow heart rate together with serious symptoms.

NON-EMERGENCY WARNING SIGNS
Advise contacting the clinic promptly for worsening breathlessness, increasing leg/ankle swelling, needing more pillows to sleep, rapid weight gain, SpO2 below the patient's target, repeatedly abnormal BP/heart rate, or symptoms that are becoming worse.

LANGUAGE AND STYLE
- The required response language for this request is: ${requestedLanguage}.
- This selected language is authoritative even if the patient code-switches or uses loanwords from another language.
- Reply fully in that language and keep the same writing script throughout. Support English, Bahasa Melayu, Simplified Mandarin Chinese, and Tamil.
- Do not switch to English when the required language is Mandarin Chinese, Tamil, or Bahasa Melayu. Understand mixed input, but keep output locked to ${requestedLanguage}.
- If the patient mixes English and Bahasa Melayu, a natural Malaysian Rojak reply is acceptable.
- Use simple, elderly-friendly wording.
- Give a direct answer first.
- Make the reply pleasant and easy to scan. For normal health questions, use this compact format:
  🩺 **What I see**
  • Mention the relevant saved reading, recent trend, symptom, medicine, or missing data.
  ✅ **What to do**
  • Give 1 to 3 practical next steps.
  🚩 **Get help now** (only when a relevant urgent warning applies).
- Use only a few helpful emojis (normally 2 or 3), never an emoji on every line.
- Use short bullet points with •. Do not use a large paragraph when bullets are clearer.
- When patient data is available, refer to at least one relevant value or trend from the data below. Example: "Your latest BP is 128/78" or "No weight was recorded today." Never invent a reading, a trend, or a date.
- When the needed data is missing, say so clearly and explain the useful next action instead of repeatedly telling the patient to log every measurement.
- Tailor advice to the question: a medicine question should discuss the medicine record; a symptom question should discuss symptoms and red flags; a progress question should summarise the available readings and trends.
- End with 1 to 3 practical actions.
- Keep normal answers around 80 to 180 words, unless more detail is requested.
- Use calm wording. Do not unnecessarily frighten the patient.

PATIENT DATA FROM MYHFGUARD
Summary: ${healthData.summary}
Heart rate: ${healthData.hr}
Blood pressure: ${healthData.bp}
SpO2: ${healthData.spo2}
Weight: ${healthData.weight}
Steps/activity: ${healthData.steps}
Recent symptoms: ${healthData.symptoms}
Current medicines: ${healthData.medications}
Water and salt: ${healthData.waterSalt || 'Not available'}
Upcoming appointments/reminders: ${healthData.appointments || 'Not available'}
Target steps: ${healthData.targetSteps || 'Not available'}
Automatic app alerts: ${healthData.automaticAlerts || 'Not available'}
Available data coverage: ${healthData.dataCoverage || 'Not available'}

LATEST MOBILE CONTEXT SENT BY THE APP
${clientContext ? JSON.stringify(clientContext, null, 2) : 'Not available'}

CONTEXT PRIORITY
- Treat server-fetched PATIENT DATA as authoritative.
- Use LATEST MOBILE CONTEXT to fill gaps and discuss recent weight/symptom direction.
- If the two sources conflict, state that the data appears inconsistent and advise the patient to refresh/sync instead of inventing a conclusion.`

    const prompt = `RECENT CONVERSATION
${formatChatHistory(history)}

CURRENT PATIENT QUESTION
${message}

Answer the current question using the patient data and safety rules. Respond in ${requestedLanguage}. For an overall-health question, do not give a generic refusal: provide a useful summary of the available data and clearly identify any missing data. For a follow-up such as "why?" or "what should I do?", use RECENT CONVERSATION to identify what the patient means.`

    const reply = String(
      await generateWithRetry(prompt, systemInstruction)
    ).trim()

    if (!reply) {
      throw new Error('AI returned an empty response')
    }

    return res.status(200).json({
      reply,
      timestamp: new Date().toISOString(),
    })
  } catch (error) {
    console.error('[MyChat] Error:', error?.message || error)

    return res.status(200).json({
      reply: localisedTemporaryError(
        (languageNameFromCode(req.body?.language) || detectRequestedLanguage(req.body?.message))
      ),
      timestamp: new Date().toISOString(),
    })
  }
})
