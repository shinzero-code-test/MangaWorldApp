"use client";

import { useEffect, useState } from "react";

interface Settings {
  downloadOnWifiOnly: boolean;
  autoDownloadNewChapters: boolean;
  enableNotifications: boolean;
  secureReaderEnabled: boolean;
  autoCleanupReadDownloads: boolean;
  cleanupAfterHours: number;
  imageCacheLimitMb: number;
  spoilerCollapseDefault: boolean;
  notificationDeliveryMode: string;
}

const DEFAULT_SETTINGS: Settings = {
  downloadOnWifiOnly: true,
  autoDownloadNewChapters: false,
  enableNotifications: true,
  secureReaderEnabled: false,
  autoCleanupReadDownloads: false,
  cleanupAfterHours: 24,
  imageCacheLimitMb: 250,
  spoilerCollapseDefault: true,
  notificationDeliveryMode: "INSTANT",
};

export default function SettingsPage() {
  const [settings, setSettings] = useState<Settings>(DEFAULT_SETTINGS);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);

  useEffect(() => {
    fetch("/api/settings")
      .then((r) => r.json())
      .then((data) => { setSettings({ ...DEFAULT_SETTINGS, ...data.settings }); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const update = <K extends keyof Settings>(key: K, value: Settings[K]) => {
    setSettings({ ...settings, [key]: value });
    setSaved(false);
  };

  const save = async () => {
    setSaving(true);
    try {
      await fetch("/api/settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ settings }),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch {}
    setSaving(false);
  };

  const sections = [
    {
      title: "التنزيلات",
      icon: "📥",
      fields: [
        { key: "downloadOnWifiOnly" as const, label: "التنزيل عبر الواي فاي فقط", description: "يمنع التنزيل عبر البيانات المحمولة" },
        { key: "autoDownloadNewChapters" as const, label: "تنزيل الفصول الجديدة تلقائياً", description: "تنزيل 3 فصول غير مقروءة من المفضلة" },
        { key: "autoCleanupReadDownloads" as const, label: "حذف التنزيلات المقروءة تلقائياً", description: "حذف الفصول المقروءة بعد فترة" },
      ],
    },
    {
      title: "الإشعارات",
      icon: "🔔",
      fields: [
        { key: "enableNotifications" as const, label: "تفعيل الإشعارات", description: "استقبال إشعارات الفصول الجديدة" },
      ],
      selects: [
        {
          key: "notificationDeliveryMode" as const,
          label: "طريقة الإرسال",
          options: [
            { value: "INSTANT", label: "فوري" },
            { value: "DAILY_DIGEST", label: "ملخص يومي" },
            { value: "SILENT", label: "صامت" },
          ],
        },
      ],
    },
    {
      title: "القارئ",
      icon: "📖",
      fields: [
        { key: "secureReaderEnabled" as const, label: "تأمين القارئ", description: "منع لقطة الشاشة والتسجيل" },
        { key: "spoilerCollapseDefault" as const, label: "طي التعليقات الحساسة", description: "إخفاء التعليقات المُعلَّمة كسبويْلر" },
      ],
    },
    {
      title: "التخزين",
      icon: "💾",
      numbers: [
        { key: "cleanupAfterHours" as const, label: "ساعات الحذف التلقائي", min: 12, max: 168, step: 12 },
        { key: "imageCacheLimitMb" as const, label: "حد ذاكرة التخزين المؤقت (MB)", min: 64, max: 1024, step: 64 },
      ],
    },
  ];

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-32 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  return (
    <div className="space-y-6 max-w-3xl">
      {/* Header */}
      <div className="flex items-center justify-between">
        <div>
          <h3 className="text-lg font-semibold">إعدادات التطبيق</h3>
          <p className="text-sm text-[var(--muted-foreground)]">الإعدادات الافتراضية لجميع المستخدمين</p>
        </div>
        <div className="flex items-center gap-3">
          {saved && <span className="text-sm text-green-500 font-medium">✓ تم الحفظ</span>}
          <button
            onClick={save}
            disabled={saving}
            className="px-5 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium hover:opacity-90 disabled:opacity-50 transition"
          >
            {saving ? "جاري الحفظ..." : "حفظ"}
          </button>
        </div>
      </div>

      {/* Settings Sections */}
      {sections.map((section) => (
        <div key={section.title} className="bg-[var(--card)] rounded-xl border border-[var(--border)] overflow-hidden">
          <div className="px-5 py-4 border-b border-[var(--border)] flex items-center gap-2">
            <span className="text-lg">{section.icon}</span>
            <h4 className="font-medium">{section.title}</h4>
          </div>
          <div className="divide-y divide-[var(--border)]">
            {section.fields?.map((field) => (
              <div key={field.key} className="px-5 py-4 flex items-center justify-between gap-4">
                <div>
                  <p className="text-sm font-medium">{field.label}</p>
                  <p className="text-xs text-[var(--muted-foreground)] mt-0.5">{field.description}</p>
                </div>
                <button
                  onClick={() => update(field.key, !settings[field.key])}
                  className={`relative w-11 h-6 rounded-full transition-colors shrink-0 ${
                    settings[field.key] ? "bg-green-500" : "bg-gray-300"
                  }`}
                >
                  <span className={`absolute top-0.5 w-5 h-5 rounded-full bg-white shadow transition-transform ${
                    settings[field.key] ? "right-0.5" : "right-[22px]"
                  }`} />
                </button>
              </div>
            ))}

            {section.selects?.map((sel) => (
              <div key={sel.key} className="px-5 py-4 flex items-center justify-between gap-4">
                <p className="text-sm font-medium">{sel.label}</p>
                <select
                  value={settings[sel.key] as string}
                  onChange={(e) => update(sel.key, e.target.value as any)}
                  className="px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
                >
                  {sel.options.map((opt) => (
                    <option key={opt.value} value={opt.value}>{opt.label}</option>
                  ))}
                </select>
              </div>
            ))}

            {section.numbers?.map((num) => (
              <div key={num.key} className="px-5 py-4 flex items-center justify-between gap-4">
                <p className="text-sm font-medium">{num.label}</p>
                <div className="flex items-center gap-2">
                  <input
                    type="range"
                    min={num.min}
                    max={num.max}
                    step={num.step}
                    value={settings[num.key] as number}
                    onChange={(e) => update(num.key, parseInt(e.target.value) as any)}
                    className="w-32"
                  />
                  <span className="text-sm font-mono w-12 text-center">{settings[num.key]}</span>
                </div>
              </div>
            ))}
          </div>
        </div>
      ))}
    </div>
  );
}
