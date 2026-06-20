"use client";

import { useEffect, useState } from "react";

const PARAM_CONFIG: Record<string, { label: string; type: "boolean" | "number" | "text" | "json"; description: string }> = {
  source_olympus_enabled: { label: "Olympus Staff", type: "boolean", description: "تفعيل/تعطيل مصدر Olympus" },
  source_azora_enabled: { label: "Azora Moon", type: "boolean", description: "تفعيل/تعطيل مصدر Azora" },
  source_starz_enabled: { label: "Manga Starz", type: "boolean", description: "تفعيل/تعطيل مصدر Starz" },
  source_mangasid_enabled: { label: "Manga Sid", type: "boolean", description: "تفعيل/تعطيل مصدر MangaSid" },
  source_meshmanga_enabled: { label: "Meshmanga", type: "boolean", description: "تفعيل/تعطيل مصدر Meshmanga" },
  scraper_selector_overrides: { label: "CSS Selectors Override", type: "json", description: "ترجمة CSS selectors مخصصة لكل مصدر" },
  scraper_connect_timeout_seconds: { label: "Connect Timeout", type: "number", description: "مهلة الاتصال بالثواني (5-90)" },
  scraper_read_timeout_seconds: { label: "Read Timeout", type: "number", description: "مهلة القراءة بالثواني (5-120)" },
  scraper_write_timeout_seconds: { label: "Write Timeout", type: "number", description: "مهلة الكتابة بالثواني (5-90)" },
  scraper_retry_count: { label: "Retry Count", type: "number", description: "عدد المحاولات الإضافية (0-3)" },
  home_layout_variant: { label: "Home Layout", type: "text", description: "نمط تخطيط الصفحة الرئيسية" },
  community_banned_keywords: { label: "Banned Keywords", type: "json", description: "الكلمات المحظورة في التعليقات" },
  remote_alert_message: { label: "Alert Message", type: "text", description: "رسالة تنبيه عامة للمستخدمين" },
};

export default function RemoteConfigPage() {
  const [params, setParams] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");

  useEffect(() => {
    fetch("/api/remote-config")
      .then((r) => r.json())
      .then((data) => { setParams(data.parameters || {}); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const updateParam = (key: string, value: any) => {
    setParams({ ...params, [key]: { ...params[key], defaultValue: { value: String(value) } } });
    setSaved(false);
    setError("");
  };

  const save = async () => {
    setSaving(true);
    setError("");
    try {
      const updates: Record<string, any> = {};
      for (const [key, param] of Object.entries(params)) {
        updates[key] = param.defaultValue?.value || "";
      }
      const res = await fetch("/api/remote-config", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ parameters: updates }),
      });
      if (!res.ok) throw new Error("فشل الحفظ");
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch (err: any) {
      setError(err.message);
    }
    setSaving(false);
  };

  const filteredParams = Object.entries(params).filter(([key, _]) => {
    if (!search) return true;
    const config = PARAM_CONFIG[key];
    const s = search.toLowerCase();
    return key.toLowerCase().includes(s) || config?.label.toLowerCase().includes(s) || config?.description.toLowerCase().includes(s);
  });

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 5 }).map((_, i) => (
        <div key={i} className="h-24 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h3 className="text-lg font-semibold">Firebase Remote Config</h3>
          <p className="text-sm text-[var(--muted-foreground)]">إدارة جميع إعدادات التطبيق عن بُعد</p>
        </div>
        <div className="flex items-center gap-3">
          {saved && <span className="text-sm text-green-500 font-medium">✓ تم الحفظ</span>}
          {error && <span className="text-sm text-red-500">{error}</span>}
          <button
            onClick={save}
            disabled={saving}
            className="px-5 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium hover:opacity-90 disabled:opacity-50 transition"
          >
            {saving ? "جاري الحفظ..." : "نشر التغييرات"}
          </button>
        </div>
      </div>

      {/* Search */}
      <input
        type="text"
        placeholder="بحث في الإعدادات..."
        value={search}
        onChange={(e) => setSearch(e.target.value)}
        className="w-full px-4 py-2.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
        dir="ltr"
      />

      {/* Parameters */}
      <div className="space-y-3">
        {filteredParams.map(([key, param]) => {
          const val = param.defaultValue?.value || "";
          const config = PARAM_CONFIG[key] || { label: key, type: "text" as const, description: "" };
          const isBool = val === "true" || val === "false";

          return (
            <div key={key} className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <h4 className="font-medium text-sm">{config.label}</h4>
                    <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--accent)] text-[var(--muted-foreground)] font-mono">
                      {config.type}
                    </span>
                  </div>
                  <p className="text-xs text-[var(--muted-foreground)] mb-3">{config.description}</p>
                  <p className="text-[10px] text-[var(--muted-foreground)] font-mono opacity-50">{key}</p>
                </div>

                <div className="shrink-0">
                  {config.type === "boolean" || isBool ? (
                    <button
                      onClick={() => updateParam(key, val === "true" ? "false" : "true")}
                      className={`relative w-12 h-6 rounded-full transition-colors ${
                        val === "true" ? "bg-green-500" : "bg-gray-300"
                      }`}
                    >
                      <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${
                        val === "true" ? "right-0.5" : "right-[26px]"
                      }`} />
                    </button>
                  ) : config.type === "json" ? (
                    <textarea
                      value={val}
                      onChange={(e) => updateParam(key, e.target.value)}
                      className="w-64 h-20 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-xs font-mono"
                      dir="ltr"
                    />
                  ) : config.type === "number" ? (
                    <input
                      type="number"
                      value={val}
                      onChange={(e) => updateParam(key, e.target.value)}
                      className="w-24 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm text-center"
                    />
                  ) : (
                    <input
                      type="text"
                      value={val}
                      onChange={(e) => updateParam(key, e.target.value)}
                      className="w-48 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
                    />
                  )}
                </div>
              </div>
            </div>
          );
        })}
      </div>

      {filteredParams.length === 0 && (
        <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
          لا توجد نتائج للبحث
        </div>
      )}
    </div>
  );
}
