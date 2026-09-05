"use client";
import { useEffect, useState } from "react";
import { Smartphone, Globe, Shield, Palette, Save, CheckCircle2, Loader2, Zap, type LucideIcon } from "lucide-react";
import { PageHeader, Toggle } from "@/components/ui";

interface AppSettings {
  home_layout_variant?: string;
  remote_alert_message?: string;
  community_banned_keywords?: string;
  scraper_connect_timeout_seconds?: number;
  scraper_read_timeout_seconds?: number;
  scraper_write_timeout_seconds?: number;
  scraper_retry_count?: number;
  scraper_selector_overrides?: string;
  engagement_tier_warming_ms?: number;
  engagement_tier_active_ms?: number;
  engagement_tier_avid_ms?: number;
  [key: string]: unknown;
}

function isValidJsonObject(raw: string): boolean {
  try {
    const parsed: unknown = JSON.parse(raw);
    return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed);
  } catch {
    return false;
  }
}

interface SettingsField {
  key: string;
  label: string;
  desc: string;
  type: "toggle" | "select" | "number" | "text" | "json";
  options?: { v: string; l: string }[];
}

const SOURCE_TOGGLES: { key: string; label: string }[] = [
  { key: "source_olympus_enabled", label: "Olympus" },
  { key: "source_azora_enabled", label: "Azora" },
  { key: "source_starz_enabled", label: "Starz" },
  { key: "source_mangasid_enabled", label: "MangaSid" },
  { key: "source_meshmanga_enabled", label: "MeshManga" },
  { key: "source_asq3_enabled", label: "3asq" },
  { key: "source_lekmanga_enabled", label: "LekManga" },
  { key: "source_lekmangaonline_enabled", label: "LekMangaOnline" },
  { key: "source_likemanga_enabled", label: "LikeManga" },
  { key: "source_linkmanga_enabled", label: "LinkManga" },
  { key: "source_mangaleko_enabled", label: "MangaLeko" },
  { key: "source_mangalionz_enabled", label: "MangaLionz" },
  { key: "source_areascans_enabled", label: "AreaScans" },
  { key: "source_hijala_enabled", label: "Hijala" },
  { key: "source_lavascans_enabled", label: "LavaScans" },
  { key: "source_stellarsaber_enabled", label: "StellarSaber" },
  { key: "source_procomic_enabled", label: "ProComic" },
  { key: "source_rockmanga_enabled", label: "RockManga" },
];

const DEFAULT: AppSettings = {
  home_layout_variant: "default",
  community_banned_keywords: "",
  remote_alert_message: "",
  scraper_connect_timeout_seconds: 30,
  scraper_read_timeout_seconds: 30,
  scraper_write_timeout_seconds: 15,
  scraper_retry_count: 1,
  scraper_selector_overrides: "",
  engagement_tier_warming_ms: 900000,
  engagement_tier_active_ms: 3600000,
  engagement_tier_avid_ms: 36000000,
  ...Object.fromEntries(SOURCE_TOGGLES.map((s) => [s.key, true])),
};

const SECTIONS: { id: string; label: string; icon: LucideIcon; fields: SettingsField[] }[] = [
  { id:"ui", label:"واجهة التطبيق", icon:Palette, fields:[
    { key:"home_layout_variant", label:"تصميم الرئيسية", desc:"شكل تخطيط الصفحة الرئيسية", type:"select", options:[{v:"default",l:"الافتراضي"},{v:"modern",l:"عصري"},{v:"compact",l:"مضغوط"}] },
    { key:"remote_alert_message", label:"رسالة تنبيه عامة", desc:"تظهر لجميع المستخدمين في أعلى التطبيق (اتركها فارغة للإخفاء)", type:"text" },
  ]},
  { id:"sources", label:"المصادر", icon:Globe, fields:
    SOURCE_TOGGLES.map((s) => ({ key: s.key, label: s.label, desc: `تفعيل مصدر ${s.label}`, type: "toggle" as const })),
  },
  { id:"scrapers", label:"إعدادات الجلب (Scrapers)", icon:Zap, fields:[
    { key:"scraper_connect_timeout_seconds", label:"مهلة الاتصال (ثواني)", desc:"مهلة إنشاء الاتصال بالمصدر (5-90)", type:"number" },
    { key:"scraper_read_timeout_seconds", label:"مهلة القراءة (ثواني)", desc:"مهلة قراءة البيانات (5-120)", type:"number" },
    { key:"scraper_write_timeout_seconds", label:"مهلة الكتابة (ثواني)", desc:"مهلة الإرسال للمصدر (5-90)", type:"number" },
    { key:"scraper_retry_count", label:"عدد محاولات الإعادة", desc:"المحاولات عند الفشل (0-3)", type:"number" },
    { key:"scraper_selector_overrides", label:"تجاوزات المحددات (JSON)", desc:"إصلاحات ساخنة لمحددات المصادر — كائن JSON صالح أو فارغ", type:"json" },
  ]},
  { id:"engagement", label:"مستويات التفاعل", icon:Zap, fields:[
    { key:"engagement_tier_warming_ms", label:"عتبة الإحماء (مللي ثانية)", desc:"الافتراضي 900000 (15 دقيقة)", type:"number" },
    { key:"engagement_tier_active_ms", label:"عتبة النشط (مللي ثانية)", desc:"الافتراضي 3600000 (ساعة)", type:"number" },
    { key:"engagement_tier_avid_ms", label:"عتبة الشغوف (مللي ثانية)", desc:"الافتراضي 36000000 (10 ساعات)", type:"number" },
  ]},
  { id:"community", label:"المجتمع والإشراف", icon:Shield, fields:[
    { key:"community_banned_keywords", label:"الكلمات المحظورة", desc:"كلمات تمنع في التعليقات (مفصولة بفاصلة)", type:"text" },
  ]},
];

export default function SettingsPage() {
  const [settings, setSettings] = useState<AppSettings>(DEFAULT);
  const [loading,  setLoading]  = useState(true);
  const [saving,   setSaving]   = useState(false);

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

  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState("");

  const handleSave = async () => {
    // Validate JSON fields client-side before publishing to the live template.
    const rawOverrides = String(settings.scraper_selector_overrides ?? "").trim();
    if (rawOverrides && !isValidJsonObject(rawOverrides)) {
      setSaveError("تجاوزات المحددات ليست كائن JSON صالح.");
      return;
    }
    setSaving(true);
    try {
      const res = await fetch("/api/settings", {
        method:"PUT",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ settings }),
      });
      if (!res.ok) { setSaveError("فشل الحفظ — لم يتم تطبيق التغييرات."); return; }
      setSaveError("");
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch {
      setSaveError("خطأ في الاتصال أثناء الحفظ.");
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
                      ) : field.type === "json" ? (
                        <textarea value={String(val ?? "")} dir="ltr" rows={3}
                          onChange={e => update(field.key, e.target.value)} disabled={loading}
                          className="w-64 text-xs font-mono" placeholder="{}" />
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
        {saveError && (
          <span className="text-sm" style={{ color:"var(--destructive)" }}>{saveError}</span>
        )}
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
