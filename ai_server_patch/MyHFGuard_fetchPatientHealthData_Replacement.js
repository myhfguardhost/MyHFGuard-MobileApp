// MyHFGuard health-data context replacement
// Replace the existing fetchPatientHealthData(patientId) function with this one.
// It adds water/salt, appointments/reminders, target steps, dates, and rule-based alerts.

async function safeSupabaseData(label, query) {
  try {
    const result = await query

    if (result?.error) {
      console.warn(`[MyChat data] ${label}: ${result.error.message}`)
      return []
    }

    return result?.data || []
  } catch (error) {
    console.warn(`[MyChat data] ${label}: ${error?.message || error}`)
    return []
  }
}

function firstRow(data) {
  return Array.isArray(data) && data.length ? data[0] : null
}

function finiteNumber(value) {
  const number = Number(value)
  return Number.isFinite(number) ? number : null
}

function dateOnly(value) {
  if (!value) return null
  return String(value).slice(0, 10)
}

function formatValue(value, suffix = '') {
  return value === null || value === undefined || value === ''
    ? 'N/A'
    : `${value}${suffix}`
}

function formatUpcomingReminders(rows) {
  if (!rows.length) return 'No upcoming appointment or reminder found'

  return rows
    .slice(0, 5)
    .map((row) => {
      const due = row.due_ts || row.date || 'Date unavailable'
      const title = row.title || row.type || 'Reminder'
      const location = row.notes ? `, ${row.notes}` : ''
      return `${title} on ${due}${location}`
    })
    .join('; ')
}

function buildHealthAlerts({ latestHr, latestBp, latestSpO2, weightChange }) {
  const alerts = []

  const hr = finiteNumber(latestHr?.hr_avg)
  const spo2 = finiteNumber(latestSpO2?.spo2_avg)
  const systolic = finiteNumber(latestBp?.systolic)
  const diastolic = finiteNumber(latestBp?.diastolic)
  const pulse = finiteNumber(latestBp?.pulse)

  if (spo2 !== null && spo2 < 90) {
    alerts.push(`CRITICAL: Latest SpO2 is ${spo2}%`)
  } else if (spo2 !== null && spo2 < 95) {
    alerts.push(`WARNING: Latest SpO2 is ${spo2}%`)
  }

  if (
    (systolic !== null && systolic >= 180) ||
    (diastolic !== null && diastolic >= 120)
  ) {
    alerts.push(
      `CRITICAL: Latest blood pressure is ${systolic ?? '?'} / ${diastolic ?? '?'} mmHg`
    )
  } else if (
    (systolic !== null && systolic >= 140) ||
    (diastolic !== null && diastolic >= 90)
  ) {
    alerts.push(
      `WARNING: Latest blood pressure is ${systolic ?? '?'} / ${diastolic ?? '?'} mmHg`
    )
  }

  const heartValue = pulse ?? hr

  if (heartValue !== null && (heartValue < 50 || heartValue > 150)) {
    alerts.push(`CRITICAL: Latest heart rate/pulse is ${heartValue} bpm`)
  } else if (heartValue !== null && (heartValue < 60 || heartValue > 100)) {
    alerts.push(`WARNING: Latest heart rate/pulse is ${heartValue} bpm`)
  }

  if (weightChange !== null && weightChange >= 3) {
    alerts.push(`WARNING: Weight increased by ${weightChange.toFixed(1)} kg over the available period`)
  }

  return alerts.length ? alerts.join('; ') : 'No automatic critical alert identified from the available latest readings'
}

async function fetchPatientHealthData(patientId) {
  try {
    const today = new Date()
    const sevenDaysAgo = new Date(today)
    sevenDaysAgo.setDate(today.getDate() - 7)

    const startDate = sevenDaysAgo.toISOString().slice(0, 10)
    const nowIso = today.toISOString()

    const [
      hrRows,
      bpRows,
      spo2Rows,
      weightRows,
      stepsRows,
      symptomRows,
      medicationRows,
      reminderRows,
      profileRows,
    ] = await Promise.all([
      safeSupabaseData(
        'hr_day',
        supabase
          .from('hr_day')
          .select('date,hr_min,hr_max,hr_avg')
          .eq('patient_id', patientId)
          .gte('date', startDate)
          .order('date', { ascending: false })
          .limit(7)
      ),

      safeSupabaseData(
        'bp_readings',
        supabase
          .from('bp_readings')
          .select('reading_date,reading_time,systolic,diastolic,pulse')
          .eq('patient_id', patientId)
          .order('reading_date', { ascending: false })
          .order('reading_time', { ascending: false })
          .limit(7)
      ),

      safeSupabaseData(
        'spo2_day',
        supabase
          .from('spo2_day')
          .select('date,spo2_min,spo2_max,spo2_avg')
          .eq('patient_id', patientId)
          .gte('date', startDate)
          .order('date', { ascending: false })
          .limit(7)
      ),

      safeSupabaseData(
        'weight_sample',
        supabase
          .from('weight_sample')
          .select('time_ts,kg')
          .eq('patient_id', patientId)
          .gte('time_ts', `${startDate}T00:00:00`)
          .order('time_ts', { ascending: false })
          .limit(7)
      ),

      safeSupabaseData(
        'steps_day',
        supabase
          .from('steps_day')
          .select('date,steps_total')
          .eq('patient_id', patientId)
          .gte('date', startDate)
          .order('date', { ascending: false })
          .limit(7)
      ),

      safeSupabaseData(
        'symptom_log',
        supabase
          .from('symptom_log')
          .select('*')
          .eq('patient_id', patientId)
          .gte('date', startDate)
          .order('date', { ascending: false })
          .limit(7)
      ),

      safeSupabaseData(
        'medication',
        supabase
          .from('medication')
          .select('*')
          .eq('patient_id', patientId)
          .eq('active', true)
          .limit(50)
      ),

      safeSupabaseData(
        'reminders',
        supabase
          .from('reminders')
          .select('id,title,type,due_ts,notes,status')
          .eq('patient_id', patientId)
          .gte('due_ts', nowIso)
          .order('due_ts', { ascending: true })
          .limit(5)
      ),

      safeSupabaseData(
        'profiles',
        supabase
          .from('profiles')
          .select('target_steps,current_medication')
          .eq('user_id', patientId)
          .limit(1)
      ),
    ])

    // The project has used both "date" and "entry_date" in water_salt_logs.
    let waterSaltRows = await safeSupabaseData(
      'water_salt_logs.date',
      supabase
        .from('water_salt_logs')
        .select('*')
        .eq('patient_id', patientId)
        .gte('date', startDate)
        .order('date', { ascending: false })
        .limit(7)
    )

    if (!waterSaltRows.length) {
      waterSaltRows = await safeSupabaseData(
        'water_salt_logs.entry_date',
        supabase
          .from('water_salt_logs')
          .select('*')
          .eq('patient_id', patientId)
          .gte('entry_date', startDate)
          .order('entry_date', { ascending: false })
          .limit(7)
      )
    }

    const latestHr = firstRow(hrRows)
    const latestBp = firstRow(bpRows)
    const latestSpO2 = firstRow(spo2Rows)
    const latestWeight = firstRow(weightRows)
    const oldestWeight = weightRows.length ? weightRows[weightRows.length - 1] : null
    const latestSteps = firstRow(stepsRows)
    const latestSymptoms = firstRow(symptomRows)
    const latestWaterSalt = firstRow(waterSaltRows)
    const profile = firstRow(profileRows)

    const latestWeightValue = finiteNumber(latestWeight?.kg)
    const oldestWeightValue = finiteNumber(oldestWeight?.kg)
    const weightChange =
      latestWeightValue !== null && oldestWeightValue !== null
        ? latestWeightValue - oldestWeightValue
        : null

    const averageSteps = stepsRows.length
      ? Math.round(
          stepsRows.reduce(
            (sum, row) => sum + (finiteNumber(row.steps_total) || 0),
            0
          ) / stepsRows.length
        )
      : null

    const targetSteps =
      finiteNumber(profile?.target_steps) &&
      finiteNumber(profile?.target_steps) > 0
        ? finiteNumber(profile.target_steps)
        : 3000

    const symptomValues = latestSymptoms
      ? [
          ['Breathlessness', latestSymptoms.sob_activity],
          ['Leg/feet swelling', latestSymptoms.leg_swelling],
          ['Cough', latestSymptoms.cough],
          ['Difficulty lying flat', latestSymptoms.orthopnea],
          ['Abdominal discomfort', latestSymptoms.abd_discomfort],
          ['Sudden weight gain symptom', latestSymptoms.sudden_weight_gain],
        ]
          .filter(([, value]) => finiteNumber(value) !== null && finiteNumber(value) > 0)
          .map(([name, value]) => `${name} ${value}/5`)
      : []

    if (latestSymptoms?.notes) {
      symptomValues.push(`Notes: ${latestSymptoms.notes}`)
    }

    const medicines = medicationRows.length
      ? medicationRows
          .map((row) => {
            const name = row.name || row.class || 'Medicine'
            const medicineClass =
              row.name && row.class ? ` (${row.class})` : ''
            return `${name}${medicineClass}`
          })
          .join('; ')
      : profile?.current_medication || 'No active medicine found'

    const waterValue =
      latestWaterSalt?.water_intake ??
      latestWaterSalt?.water_ml ??
      latestWaterSalt?.water

    const saltValue =
      latestWaterSalt?.salt_intake ??
      latestWaterSalt?.salt_level ??
      latestWaterSalt?.salt

    return {
      summary:
        'Patient health context from the most recent MyHFGuard records. This is monitoring information, not a medical diagnosis.',

      hr: latestHr
        ? `${formatValue(latestHr.hr_avg, ' bpm')} on ${dateOnly(latestHr.date)}; range ${formatValue(latestHr.hr_min)}-${formatValue(latestHr.hr_max)} bpm`
        : 'No recent heart-rate data',

      bp: latestBp
        ? `${formatValue(latestBp.systolic)}/${formatValue(latestBp.diastolic)} mmHg, pulse ${formatValue(latestBp.pulse, ' bpm')} on ${dateOnly(latestBp.reading_date)} ${latestBp.reading_time || ''}`.trim()
        : 'No recent blood-pressure data',

      spo2: latestSpO2
        ? `${formatValue(latestSpO2.spo2_avg, '%')} on ${dateOnly(latestSpO2.date)}; range ${formatValue(latestSpO2.spo2_min)}-${formatValue(latestSpO2.spo2_max)}%`
        : 'No recent SpO2 data',

      weight: latestWeight
        ? `${formatValue(latestWeight.kg, ' kg')} on ${dateOnly(latestWeight.time_ts)}${
            weightChange === null
              ? ''
              : `; change across available records ${weightChange >= 0 ? '+' : ''}${weightChange.toFixed(1)} kg`
          }`
        : 'No recent weight data',

      steps: latestSteps
        ? `${formatValue(latestSteps.steps_total, ' steps')} on ${dateOnly(latestSteps.date)}; 7-day average ${formatValue(averageSteps, ' steps/day')}; target ${targetSteps} steps/day`
        : `No recent step data; target ${targetSteps} steps/day`,

      symptoms: latestSymptoms
        ? `${symptomValues.length ? symptomValues.join('; ') : 'No significant symptom selected'} on ${dateOnly(latestSymptoms.date)}`
        : 'No recent symptom log',

      medications: medicines,

      waterSalt: latestWaterSalt
        ? `Latest water: ${formatValue(waterValue)}; latest salt: ${formatValue(saltValue)}; recorded on ${dateOnly(latestWaterSalt.date || latestWaterSalt.entry_date)}`
        : 'No recent water/salt log',

      appointments: formatUpcomingReminders(reminderRows),

      targetSteps,

      automaticAlerts: buildHealthAlerts({
        latestHr,
        latestBp,
        latestSpO2,
        weightChange,
      }),

      dataCoverage: [
        hrRows.length ? 'heart rate' : null,
        bpRows.length ? 'blood pressure' : null,
        spo2Rows.length ? 'SpO2' : null,
        weightRows.length ? 'weight' : null,
        stepsRows.length ? 'steps' : null,
        symptomRows.length ? 'symptoms' : null,
        waterSaltRows.length ? 'water/salt' : null,
        medicationRows.length || profile?.current_medication ? 'medicines' : null,
        reminderRows.length ? 'appointments/reminders' : null,
      ]
        .filter(Boolean)
        .join(', ') || 'No recent health records',
    }
  } catch (error) {
    console.error(
      '[Helper fetchPatientHealthData] Error:',
      error?.message || error
    )

    return {
      summary: 'Unable to fetch patient health data',
      hr: 'N/A',
      bp: 'N/A',
      spo2: 'N/A',
      weight: 'N/A',
      steps: 'N/A',
      symptoms: 'N/A',
      medications: 'N/A',
      waterSalt: 'N/A',
      appointments: 'N/A',
      targetSteps: 3000,
      automaticAlerts: 'Unable to calculate alerts',
      dataCoverage: 'Unavailable',
    }
  }
}
