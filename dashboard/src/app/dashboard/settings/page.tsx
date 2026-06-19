"use client";

import { useEffect, useState } from "react";

export default function SettingsPage() {
  const [settings, setSettings] = useState<Record<string, any>>({});
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetch("/api/settings")
      .then((r) => r.json())
      .then((data) => { setSettings(data.settings || {}); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const update = (key: string, value: any) => setSettings({ ...settings, [key]: value });

  const save = async () => {
    setSaving(true);
    await fetch("/api/settings", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ settings }),
    });
    setSaving(false);
  };

  if (loading) return <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>;

  return (
    <div className="space-y-6 max-w-3xl">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">إعدادات التطبيق الافتراضية</h3>
        <button onClick={save} disabled={saving} className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm hover:opacity-90 disabled:opacity-50">
          {saving ? "جاري الحفظ..." : "حفظ"}
        </button>
      </div>

      <div className="space-y-4">
        {[
          { key: "downloadOnWifiOnly", label: "التنزيل عبر الواي فاي فقط", type: "boolean" },
          { key: "autoDownloadNewChapters", label: "تنزيل الفصول الجديدة تلقائياً", type: "boolean" },
          { key: "enableNotifications", label: "تفعيل الإشعارات", type: "boolean" },
          { key: "secureReaderEnabled", label: "تأمين القارئ", type: "boolean" },
          { key: "autoCleanupReadDownloads", label: "حذف التنزيلات المقروءة تلقائياً", type: "boolean" },
          { key: "cleanupAfterHours", label: "ساعات الحذف التلقائي", type: "number" },
          { key: "imageCacheLimitMb", label: "حد ذاكرة التخزين المؤقت (MB)", type: "number" },
        ].map((field) => (
          <div key={field.key} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] flex items-center justify-between">
            <label className="text-sm font-medium">{field.label}</label>
            {field.type === "boolean" ? (
              <button
                onClick={() => update(field.key, !settings[field.key])}
                className={`px-3 py-1.5 rounded-lg text-sm font-medium ${
                  settings[field.key] ? "bg-green-500/10 text-green-500" : "bg-red-500/10 text-red-500"
                }`}
              >
                {settings[field.key] ? "مفعّل" : "معطّل"}
              </button>
            ) : (
              <input
                type="number"
                value={settings[field.key] || ""}
                onChange={(e) => update(field.key, parseInt(e.target.value) || 0)}
                className="w-24 px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm text-center"
              />
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
