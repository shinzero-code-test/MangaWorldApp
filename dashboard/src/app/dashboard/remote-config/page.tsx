"use client";

import { useEffect, useState } from "react";

const PARAM_LABELS: Record<string, string> = {
  source_olympus_enabled: "Olympus Staff",
  source_azora_enabled: "Azora Moon",
  source_starz_enabled: "Manga Starz",
  source_mangasid_enabled: "Manga Sid",
  source_meshmanga_enabled: "Meshmanga",
  scraper_selector_overrides: "CSS Selectors Override",
  scraper_connect_timeout_seconds: "Connect Timeout (s)",
  scraper_read_timeout_seconds: "Read Timeout (s)",
  scraper_write_timeout_seconds: "Write Timeout (s)",
  scraper_retry_count: "Retry Count",
  home_layout_variant: "Home Layout Variant",
  community_banned_keywords: "Banned Keywords",
  remote_alert_message: "Remote Alert Message",
};

export default function RemoteConfigPage() {
  const [params, setParams] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetch("/api/remote-config")
      .then((r) => r.json())
      .then((data) => { setParams(data.parameters || {}); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const updateParam = (key: string, value: any) => {
    setParams({ ...params, [key]: { ...params[key], defaultValue: { value: String(value) } } });
  };

  const save = async () => {
    setSaving(true);
    const updates: Record<string, any> = {};
    for (const [key, param] of Object.entries(params)) {
      updates[key] = param.defaultValue?.value || "";
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
        <h3 className="text-lg font-semibold">Firebase Remote Config</h3>
        <button onClick={save} disabled={saving} className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm hover:opacity-90 disabled:opacity-50">
          {saving ? "جاري الحفظ..." : "نشر التغييرات"}
        </button>
      </div>

      <div className="space-y-3">
        {Object.entries(params).map(([key, param]) => {
          const val = param.defaultValue?.value || "";
          const isBool = val === "true" || val === "false";
          const label = PARAM_LABELS[key] || key;

          return (
            <div key={key} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
              <label className="text-sm font-medium block mb-2">{label}</label>
              <p className="text-xs text-[var(--muted-foreground)] mb-2 font-mono">{key}</p>
              {isBool ? (
                <button
                  onClick={() => updateParam(key, val === "true" ? "false" : "true")}
                  className={`px-3 py-1.5 rounded-lg text-sm font-medium ${
                    val === "true" ? "bg-green-500/10 text-green-500" : "bg-red-500/10 text-red-500"
                  }`}
                >
                  {val === "true" ? "مفعّل" : "معطّل"}
                </button>
              ) : key.includes("overrides") || key.includes("keywords") ? (
                <textarea
                  value={val}
                  onChange={(e) => updateParam(key, e.target.value)}
                  className="w-full h-24 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm font-mono"
                  dir="ltr"
                />
              ) : (
                <input
                  type={typeof val === "string" && !isNaN(Number(val)) ? "number" : "text"}
                  value={val}
                  onChange={(e) => updateParam(key, e.target.value)}
                  className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
                  dir="ltr"
                />
              )}
            </div>
          );
        })}
      </div>
    </div>
  );
}
