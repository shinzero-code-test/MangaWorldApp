"use client";

import { MANGA_SOURCES } from "@/lib/constants";
import { useEffect, useState } from "react";

export default function SourcesPage() {
  const [params, setParams] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetch("/api/remote-config")
      .then((r) => r.json())
      .then((data) => { setParams(data.parameters || {}); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const toggleSource = (sourceId: string) => {
    const key = `source_${sourceId}_enabled`;
    const current = params[key]?.defaultValue?.value || "true";
    setParams({ ...params, [key]: { defaultValue: { value: current === "true" ? "false" : "true" } } });
  };

  const save = async () => {
    setSaving(true);
    const updates: Record<string, any> = {};
    for (const [key, param] of Object.entries(params)) {
      if (key.startsWith("source_")) updates[key] = param.defaultValue?.value || "";
    }
    await fetch("/api/remote-config", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ parameters: updates }),
    });
    setSaving(false);
  };

  if (loading) return <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>;

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">إدارة المصادر</h3>
        <button onClick={save} disabled={saving} className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm hover:opacity-90 disabled:opacity-50">
          {saving ? "جاري الحفظ..." : "حفظ"}
        </button>
      </div>

      <div className="space-y-3">
        {MANGA_SOURCES.map((source) => {
          const enabled = params[`source_${source.id}_enabled`]?.defaultValue?.value !== "false";
          return (
            <div key={source.id} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] flex items-center justify-between">
              <div>
                <h4 className="font-medium">{source.name}</h4>
                <p className="text-xs text-[var(--muted-foreground)]">{source.baseUrl}</p>
                {source.requiresCloudflare && (
                  <span className="text-xs text-yellow-500">يتطلب Cloudflare</span>
                )}
              </div>
              <button
                onClick={() => toggleSource(source.id)}
                className={`px-4 py-2 rounded-lg text-sm font-medium transition ${
                  enabled ? "bg-green-500/10 text-green-500" : "bg-red-500/10 text-red-500"
                }`}
              >
                {enabled ? "مفعّل" : "معطّل"}
              </button>
            </div>
          );
        })}
      </div>
    </div>
  );
}
