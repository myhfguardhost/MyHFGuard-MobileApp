import { useEffect, useState } from "react";
import { supabase } from "../lib/supabase";

type PatientTargetStepsControlProps = {
  patientId: string;
  initialTarget?: number | null;
};

export default function PatientTargetStepsControl({
  patientId,
  initialTarget = 3000,
}: PatientTargetStepsControlProps) {
  const [target, setTarget] = useState(initialTarget ?? 3000);
  const [saving, setSaving] = useState(false);
  const [message, setMessage] = useState("");

  useEffect(() => {
    setTarget(initialTarget ?? 3000);
  }, [initialTarget, patientId]);

  async function saveTarget() {
    const safeTarget = Math.max(500, Math.min(50000, Math.round(target)));
    setSaving(true);
    setMessage("");

    const { error } = await supabase
      .from("profiles")
      .update({ target_steps: safeTarget })
      .eq("user_id", patientId);

    setSaving(false);
    setMessage(error ? error.message : "Daily step target updated successfully.");
    if (!error) setTarget(safeTarget);
  }

  return (
    <section className="rounded-2xl border bg-white p-4 shadow-sm">
      <h3 className="text-lg font-semibold">Daily Step Target</h3>
      <p className="mt-1 text-sm text-slate-500">
        Set the target shown on the patient&apos;s My Exercise page.
      </p>

      <div className="mt-4 flex flex-col gap-3 sm:flex-row sm:items-end">
        <label className="flex-1 text-sm font-medium">
          Target steps
          <input
            type="number"
            min={500}
            max={50000}
            step={100}
            value={target}
            onChange={(event) => setTarget(Number(event.target.value))}
            className="mt-1 w-full rounded-xl border px-3 py-2"
          />
        </label>
        <button
          type="button"
          disabled={saving || target < 500 || target > 50000}
          onClick={saveTarget}
          className="rounded-xl bg-emerald-600 px-5 py-2 font-semibold text-white disabled:opacity-50"
        >
          {saving ? "Saving…" : "Update Target"}
        </button>
      </div>

      {message && <p className="mt-3 text-sm">{message}</p>}
    </section>
  );
}
