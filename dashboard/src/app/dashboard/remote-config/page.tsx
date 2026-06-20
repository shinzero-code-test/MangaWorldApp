"use client";

import { useEffect, useState } from "react";

const PARAM_CONFIG: Record<string, { label: string; type: "boolean" | "number" | "text" | "json"; description: string; group: string }> = {
  source_olympus_enabled: { label: "Olympus Staff", type: "boolean", description: "تفعيل/تعطيل مصدر Olympus", group: "المصادر" },
  source_azora_enabled: { label: "Azora Moon", type: "boolean", description: "تفعيل/تعطيل مصدر Azora", group: "المصادر" },
  source_starz_enabled: { label: "Manga Starz", type: "boolean", description: "تفعيل/تعطيل مصدر Starz", group: "المصادر" },
  source_mangasid_enabled: { label: "Manga Sid", type: "boolean", description: "تفعيل/تعطيل مصدر MangaSid", group: "المصادر" },
  source_meshmanga_enabled: { label: "Meshmanga", type: "boolean", description: "تفعيل/تعطيل مصدر Meshmanga", group: "المصادر" },
  scraper_selector_overrides: { label: "CSS Selectors Override", type: "json", description: "ترجمة CSS selectors مخصصة لكل مصدر", group: "السكريبر" },
  scraper_connect_timeout_seconds: { label: "Connect Timeout", type: "number", description: "مهلة الاتصال بالثواني (5-90)", group: "السكريبر" },
  scraper_read_timeout_seconds: { label: "Read Timeout", type: "number", description: "مهلة القراءة بالثواني (5-120)", group: "السكريبر" },
  scraper_write_timeout_seconds: { label: "Write Timeout", type: "number", description: "مهلة الكتابة بالثواني (5-90)", group: "السكريبر" },
  scraper_retry_count: { label: "Retry Count", type: "number", description: "عدد المحاولات الإضافية (0-3)", group: "السكريبر" },
  home_layout_variant: { label: "Home Layout", type: "text", description: "نمط تخطيط الصفحة الرئيسية", group: "الواجهة" },
  community_banned_keywords: { label: "Banned Keywords", type: "json", description: "الكلمات المحظورة في التعليقات", group: "المجتمع" },
  remote_alert_message: { label: "Alert Message", type: "text", description: "رسالة تنبيه عامة للمستخدمين", group: "الواجهة" },
};

export default function RemoteConfigPage() {
  const [params, setParams] = useState<Record<string, any>>({});
  const [template, setTemplate] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [error, setError] = useState("");
  const [search, setSearch] = useState("");
  const [expandedGroups, setExpandedGroups] = useState<Set<string>>(new Set(["المصادر"]));

  useEffect(() => {
    fetch("/api/remote-config")
      .then(r => r.json())
      .then(data => { setParams(data.parameters || {}); setTemplate(data.template); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const updateParam = (key: string, value: any) => {
    setParams(p => ({ ...p, [key]: { ...p[key], defaultValue: { value: String(value) } } }));
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
      const data = await res.json();
      setTemplate((t: any) => ({ ...t, etag: data.etag }));
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch (err: any) { setError(err.message); }
    setSaving(false);
  };

  // Group parameters
  const groups: Record<string, [string, any][]> = {};
  for (const [key, param] of Object.entries(params)) {
    const config = PARAM_CONFIG[key];
    const group = config?.group || "أخرى";
    if (!groups[group]) groups[group] = [];
    if (!search || key.toLowerCase().includes(search.toLowerCase()) || config?.label.toLowerCase().includes(search.toLowerCase())) {
      groups[group].push([key, param]);
    }
  }

  const toggleGroup = (g: string) => {
    const next = new Set(expandedGroups);
    next.has(g) ? next.delete(g) : next.add(g);
    setExpandedGroups(next);
  };

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-20 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  return (
    <div className="space-y-6">
      {/* Header */}
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h3 className="text-lg font-semibold">Firebase Remote Config</h3>
          <div className="flex items-center gap-3 mt-1 text-xs text-[var(--muted-foreground)]">
            {template && (
              <>
                <span>{template.parameterCount} معامل</span>
                <span>•</span>
                <span>{template.conditionCount} شرط</span>
                <span>•</span>
                <span className="font-mono">etag: {template.etag?.slice(0, 12)}...</span>
              </>
            )}
          </div>
        </div>
        <div className="flex items-center gap-3">
          {saved && <span className="text-sm text-green-500 font-medium animate-pulse">✓ تم الحفظ</span>}
          {error && <span className="text-sm text-red-500">{error}</span>}
          <button onClick={save} disabled={saving}
            className="px-5 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium hover:opacity-90 disabled:opacity-50 transition">
            {saving ? "جاري الحفظ..." : "نشر التغييرات"}
          </button>
        </div>
      </div>

      {/* Search */}
      <input type="text" placeholder="بحث في الإعدادات..." value={search} onChange={e => setSearch(e.target.value)}
        className="w-full px-4 py-2.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm" dir="ltr" />

      {/* Parameter Groups */}
      <div className="space-y-4">
        {Object.entries(groups).map(([groupName, items]) => (
          <div key={groupName} className="bg-[var(--card)] rounded-xl border border-[var(--border)] overflow-hidden">
            <button onClick={() => toggleGroup(groupName)}
              className="w-full flex items-center justify-between px-5 py-4 hover:bg-[var(--accent)]/50 transition">
              <div className="flex items-center gap-2">
                <span className="text-lg">{groupName === "المصادر" ? "📡" : groupName === "السكريبر" ? "🔧" : groupName === "الواجهة" ? "🎨" : groupName === "المجتمع" ? "💬" : "⚙️"}</span>
                <span className="font-medium">{groupName}</span>
                <span className="text-xs text-[var(--muted-foreground)] bg-[var(--accent)] px-2 py-0.5 rounded-full">{items.length}</span>
              </div>
              <span className={`transition-transform ${expandedGroups.has(groupName) ? "rotate-180" : ""}`}>▾</span>
            </button>

            {expandedGroups.has(groupName) && (
              <div className="border-t border-[var(--border)] divide-y divide-[var(--border)]">
                {items.map(([key, param]) => {
                  const val = param.defaultValue?.value || "";
                  const config = PARAM_CONFIG[key] || { label: key, type: "text" as const, description: "" };
                  const isBool = val === "true" || val === "false";

                  return (
                    <div key={key} className="px-5 py-4 flex items-center justify-between gap-4">
                      <div className="flex-1 min-w-0">
                        <div className="flex items-center gap-2 mb-0.5">
                          <span className="text-sm font-medium">{config.label}</span>
                          <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--accent)] text-[var(--muted-foreground)] font-mono">{config.type}</span>
                        </div>
                        <p className="text-xs text-[var(--muted-foreground)]">{config.description}</p>
                        <p className="text-[10px] text-[var(--muted-foreground)] font-mono opacity-50 mt-0.5">{key}</p>
                      </div>
                      <div className="shrink-0">
                        {config.type === "boolean" || isBool ? (
                          <button onClick={() => updateParam(key, val === "true" ? "false" : "true")}
                            className={`relative w-12 h-6 rounded-full transition-colors ${val === "true" ? "bg-green-500" : "bg-gray-300"}`}>
                            <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${val === "true" ? "right-0.5" : "right-[26px]"}`} />
                          </button>
                        ) : config.type === "json" ? (
                          <textarea value={val} onChange={e => updateParam(key, e.target.value)}
                            className="w-56 h-16 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-xs font-mono" dir="ltr" />
                        ) : config.type === "number" ? (
                          <input type="number" value={val} onChange={e => updateParam(key, e.target.value)}
                            className="w-24 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm text-center" />
                        ) : (
                          <input type="text" value={val} onChange={e => updateParam(key, e.target.value)}
                            className="w-48 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm" />
                        )}
                      </div>
                    </div>
                  );
                })}
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
