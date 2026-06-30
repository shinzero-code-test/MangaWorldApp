"use client";
import { useEffect, useState } from "react";
import { Smartphone, Globe, BookOpen, Shield, Bell, Palette, Save, CheckCircle2, Loader2 } from "lucide-react";
import { PageHeader, Toggle } from "@/components/ui";

interface AppSettings {
  appName?: string; defaultLanguage?: string; maintenanceMode?: boolean;
  maxSourcesPerManga?: number; enableAds?: boolean; adFrequency?: number;
  autoModeration?: boolean; reportThreshold?: number; banOnHighPriority?: boolean;
  enablePushNotif?: boolean; notifFrequency?: string;
  defaultTheme?: string; enableRTL?: boolean; showRatings?: boolean; showComments?: boolean;
  [key: string]: any;
}

const DEFAULT: AppSettings = {
  appName:"MangaWorld", defaultLanguage:"ar", maintenanceMode:false,
  maxSourcesPerManga:5, enableAds:false, adFrequency:3,
  autoModeration:true, reportThreshold:5, banOnHighPriority:false,
  enablePushNotif:true, notifFrequency:"daily",
  defaultTheme:"dark", enableRTL:true, showRatings:true, showComments:true,
};

const SECTIONS = [
  { id:"general", label:"الإعدادات العامة", icon:Globe, fields:[
    { key:"appName", label:"اسم التطبيق", desc:"الاسم الظاهر للمستخدمين", type:"text" },
    { key:"defaultLanguage", label:"اللغة الافتراضية", desc:"اللغة الرئيسية للتطبيق", type:"select", options:[{v:"ar",l:"العربية"},{v:"en",l:"English"}] },
    { key:"maintenanceMode", label:"وضع الصيانة", desc:"إيقاف التطبيق مؤقتاً", type:"toggle" },
  ]},
  { id:"content", label:"إعدادات المحتوى", icon:BookOpen, fields:[
    { key:"maxSourcesPerManga", label:"الحد الأقصى للمصادر", desc:"عدد المصادر لكل مانجا", type:"number" },
    { key:"enableAds", label:"تفعيل الإعلانات", desc:"عرض إعلانات للمستخدمين", type:"toggle" },
    { key:"adFrequency", label:"تكرار الإعلانات", desc:"عدد الفصول بين كل إعلان", type:"number" },
  ]},
  { id:"moderation", label:"إعدادات الإشراف", icon:Shield, fields:[
    { key:"autoModeration", label:"الإشراف التلقائي", desc:"حجب المحتوى المخالف تلقائياً", type:"toggle" },
    { key:"reportThreshold", label:"حد التقارير", desc:"عدد التقارير قبل الإجراء التلقائي", type:"number" },
    { key:"banOnHighPriority", label:"حظر تلقائي", desc:"حظر المستخدم عند تقرير عالي الأولوية", type:"toggle" },
  ]},
  { id:"notifications", label:"الإشعارات", icon:Bell, fields:[
    { key:"enablePushNotif", label:"إشعارات Push", desc:"إرسال إشعارات للمستخدمين", type:"toggle" },
    { key:"notifFrequency", label:"تكرار الإشعارات", desc:"مدى تكرار إشعارات التحديثات", type:"select", options:[{v:"realtime",l:"فوري"},{v:"daily",l:"يومي"},{v:"weekly",l:"أسبوعي"}] },
  ]},
  { id:"ui", label:"إعدادات الواجهة", icon:Palette, fields:[
    { key:"defaultTheme", label:"الثيم الافتراضي", desc:"الثيم الأولي عند التثبيت", type:"select", options:[{v:"dark",l:"داكن"},{v:"light",l:"فاتح"},{v:"system",l:"النظام"}] },
    { key:"enableRTL", label:"دعم RTL", desc:"تفعيل الاتجاه من اليمين لليسار", type:"toggle" },
    { key:"showRatings", label:"إظهار التقييمات", desc:"عرض نجوم التقييم في القوائم", type:"toggle" },
    { key:"showComments", label:"إظهار التعليقات", desc:"تفعيل قسم التعليقات", type:"toggle" },
  ]},
];

export default function SettingsPage() {
  const [settings, setSettings] = useState<AppSettings>(DEFAULT);
  const [loading,  setLoading]  = useState(true);
  const [saving,   setSaving]   = useState(false);
  const [saved,    setSaved]    = useState(false);

  useEffect(() => {
    fetch("/api/settings")
      .then(r => r.json())
      .then(d => {
        // API returns { settings: {...} }
        const s = d.settings ?? d ?? {};
        setSettings({ ...DEFAULT, ...s });
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const update = (key: string, val: any) => setSettings(p => ({ ...p, [key]: val }));

  const handleSave = async () => {
    setSaving(true);
    try {
      await fetch("/api/settings", {
        method:"PUT",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ settings }),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } finally { setSaving(false); }
  };

  return (
    <div className="space-y-5 pb-24">
      <PageHeader title="إعدادات التطبيق" subtitle="ضبط إعدادات تطبيق مانجا وورلد" icon={Smartphone} />

      {SECTIONS.map(section => {
        const Icon = section.icon;
        return (
          <div key={section.id} className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
            <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
              <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
                <Icon size={16} style={{ color:"var(--primary)" }} />
              </div>
              <h3 className="font-semibold text-sm">{section.label}</h3>
            </div>
            <div className="divide-y" style={{ borderColor:"var(--border)" }}>
              {section.fields.map(field => {
                const val = settings[field.key];
                return (
                  <div key={field.key} className="flex items-center gap-4 px-5 py-4 flex-wrap">
                    <div className="flex-1 min-w-[180px]">
                      <p className="font-medium text-sm">{field.label}</p>
                      <p className="text-xs mt-0.5" style={{ color:"var(--muted-foreground)" }}>{field.desc}</p>
                    </div>
                    <div className="shrink-0">
                      {field.type === "toggle" ? (
                        <Toggle enabled={!!val} onChange={v => update(field.key, v)} disabled={loading} ariaLabel={field.label} />
                      ) : field.type === "select" ? (
                        <select value={String(val ?? "")} onChange={e => update(field.key, e.target.value)} disabled={loading} className="min-w-[130px]">
                          {field.options?.map(o => <option key={o.v} value={o.v}>{o.l}</option>)}
                        </select>
                      ) : field.type === "number" ? (
                        <input type="number" value={Number(val ?? 0)} dir="ltr" min={0}
                          onChange={e => update(field.key, Number(e.target.value))} disabled={loading}
                          className="w-24 text-end font-mono text-sm" />
                      ) : (
                        <input type="text" value={String(val ?? "")}
                          onChange={e => update(field.key, e.target.value)} disabled={loading}
                          className="w-48 text-sm" />
                      )}
                    </div>
                  </div>
                );
              })}
            </div>
          </div>
        );
      })}

      <div className="fixed bottom-0 start-0 end-0 z-20 flex items-center justify-end gap-3 px-6 py-4 border-t"
        style={{ background:"var(--card)", borderColor:"var(--border)" }}>
        <button onClick={handleSave} disabled={saving}
          className="flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-60"
          style={{ background:"var(--primary)", color:"var(--primary-foreground)" }}>
          {saving ? <Loader2 size={16} className="animate-spin" /> : saved ? <CheckCircle2 size={16} /> : <Save size={16} />}
          {saving ? "جاري الحفظ..." : saved ? "تم الحفظ!" : "حفظ الإعدادات"}
        </button>
      </div>
    </div>
  );
}
