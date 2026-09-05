"use client";
import { useEffect, useState } from "react";
import { Radio, Save, Loader2, CheckCircle2 } from "lucide-react";
import { PageHeader } from "@/components/ui";

const SOURCE_DOMAINS: { id: string; label: string; def: string }[] = [
  { id: "olympus",       label: "تيم اكس",            def: "https://olympustaff.com" },
  { id: "azora",         label: "ازورا مانجا",        def: "https://azorafly.com" },
  { id: "starz",         label: "مانجا ستارز",        def: "https://starzmanga.com" },
  { id: "mangasid",      label: "مانجا سيد",          def: "https://mangasid.com" },
  { id: "meshmanga",     label: "مانجا سوات",         def: "https://meshmanga.com" },
  { id: "asq3",          label: "مانجا العاشق",       def: "https://3asq.online" },
  { id: "lekmanga",      label: "مانجا ليك",          def: "https://mangalik.net" },
  { id: "lekmangaonline",label: "مانجا ليك اونلاين",  def: "https://lekmanga.online" },
  { id: "likemanga",     label: "مانجا لايك",         def: "https://like-manga.net" },
  { id: "linkmanga",     label: "مانجا لينك",         def: "https://link-manga.net" },
  { id: "mangaleko",     label: "مانجا ليكو",         def: "https://manga-leko.site" },
  { id: "mangalionz",    label: "مانجا ليونز",        def: "https://manga-lionz.org" },
  { id: "areascans",     label: "آريا مانجا",         def: "https://ar.kenmanga.com" },
  { id: "hijala",        label: "حجالة مانجا",        def: "https://hijala.com" },
  { id: "lavascans",     label: "لاڤا سكانز",         def: "https://lavascans.com" },
  { id: "stellarsaber",  label: "StellarSaber",       def: "https://stellarsaber.pro" },
  { id: "procomic",      label: "ProChan",            def: "https://procomic.pro" },
  { id: "rockmanga",     label: "روكس مانجا",         def: "https://rocksmanga.com" },
];

function isValidHttpUrl(raw: string): boolean {
  try {
    const url = new URL(raw.trim());
    return url.protocol === "https:" && url.hostname.includes(".");
  } catch {
    return false;
  }
}

export default function SourcesConfigPage() {
  const [urls, setUrls] = useState<Record<string, string>>(
    Object.fromEntries(SOURCE_DOMAINS.map((s) => [`source_${s.id}_base_url`, s.def])),
  );
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState(false);
  const [saveError, setSaveError] = useState("");

  useEffect(() => {
    fetch("/api/settings")
      .then((r) => r.json())
      .then((d) => {
        const s = (d.settings ?? d ?? {}) as Record<string, unknown>;
        setUrls((prev) => {
          const next = { ...prev };
          for (const key of Object.keys(prev)) {
            if (typeof s[key] === "string" && (s[key] as string).trim()) next[key] = s[key] as string;
          }
          return next;
        });
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const handleSave = async () => {
    for (const s of SOURCE_DOMAINS) {
      const key = `source_${s.id}_base_url`;
      if (!isValidHttpUrl(urls[key] ?? "")) {
        setSaveError(`رابط غير صالح للمصدر: ${s.label}`);
        return;
      }
    }
    setSaving(true);
    try {
      // Merge with the live template first so unrelated keys are preserved.
      const current = await fetch("/api/settings").then((r) => r.json());
      const merged = { ...((current.settings ?? {}) as Record<string, unknown>), ...urls };
      const res = await fetch("/api/settings", {
        method: "PUT",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ settings: merged }),
      });
      if (!res.ok) { setSaveError("فشل الحفظ — لم يتم تطبيق التغييرات."); return; }
      setSaveError("");
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch {
      setSaveError("خطأ في الاتصال أثناء الحفظ.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="space-y-5 pb-24">
      <PageHeader title="نطاقات المصادر" subtitle="النطاق الأساسي لكل مصدر — يُستخدم عند انتقال الدومين" icon={Radio} />
      <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background: "var(--card)", borderColor: "var(--border)" }}>
        <div className="divide-y" style={{ borderColor: "var(--border)" }}>
          {SOURCE_DOMAINS.map((s) => {
            const key = `source_${s.id}_base_url`;
            return (
              <div key={s.id} className="flex items-center gap-4 px-5 py-4 flex-wrap">
                <div className="flex-1 min-w-[140px]">
                  <p className="font-medium text-sm">{s.label}</p>
                  <p className="text-xs mt-0.5 font-mono" style={{ color: "var(--muted-foreground)" }} dir="ltr">{key}</p>
                </div>
                <input
                  type="url" dir="ltr" disabled={loading}
                  value={urls[key] ?? s.def}
                  onChange={(e) => setUrls((p) => ({ ...p, [key]: e.target.value }))}
                  placeholder={s.def}
                  className="w-64 text-xs font-mono"
                />
              </div>
            );
          })}
        </div>
      </div>
      <div className="fixed bottom-0 start-0 end-0 z-20 flex items-center justify-end gap-3 px-6 py-4 border-t"
        style={{ background: "var(--card)", borderColor: "var(--border)" }}>
        {saveError && (
          <span className="text-sm" style={{ color: "var(--destructive)" }}>{saveError}</span>
        )}
        <button onClick={handleSave} disabled={saving || loading}
          className="flex items-center gap-2 px-5 py-2.5 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-60"
          style={{ background: "var(--primary)", color: "var(--primary-foreground)" }}>
          {saving ? <Loader2 size={16} className="animate-spin" /> : saved ? <CheckCircle2 size={16} /> : <Save size={16} />}
          {saving ? "جاري الحفظ..." : saved ? "تم الحفظ!" : "حفظ النطاقات"}
        </button>
      </div>
    </div>
  );
}
