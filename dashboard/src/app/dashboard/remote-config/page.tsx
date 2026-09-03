"use client";
import { useEffect, useState, useCallback } from "react";
import { Settings2, Search, ChevronDown, Upload, CheckCircle2, Loader2, Palette, Radio, Smartphone, Wrench, Globe } from "lucide-react";
import { PageHeader, Toggle } from "@/components/ui";

interface RCParam {
  key: string; defaultValue: string; valueType: string; description: string;
}

// Canonical source origins — keep in sync with the MangaSource enum and the
// FirebaseRemoteConfigManager `source_<id>_base_url` defaults. Lets admins
// follow domain moves (e.g. starz → starzmanga.com) without an app update.
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

const DOMAIN_DESC = "النطاق الأساسي للمصدر — يُستخدم عند انتقال الدومين. اترك القيمة الافتراضية ما لم يتوقف المصدر عن العمل.";
function assignGroup(key: string): string {
  const k = key.toLowerCase();
  if (k.includes("source") || k.includes("scraper") || k.includes("manga")) return "sources";
  if (k.includes("ui") || k.includes("theme") || k.includes("color") || k.includes("dark")) return "ui";
  if (k.includes("network") || k.includes("timeout") || k.includes("retry")) return "network";
  if (k.includes("app") || k.includes("version") || k.includes("update") || k.includes("force")) return "app";
  return "general";
}

const GROUP_META: Record<string, { label: string; icon: React.ComponentType<any> }> = {
  sources: { label: "المصادر",        icon: Radio       },
  ui:      { label: "الواجهة",        icon: Palette     },
  network: { label: "الشبكة",         icon: Globe       },
  app:     { label: "إعدادات التطبيق", icon: Smartphone  },
  general: { label: "عام",            icon: Settings2   },
};

const TYPE_COLOR: Record<string,{bg:string;text:string}> = {
  BOOLEAN: { bg:"rgba(59,130,246,0.1)",  text:"#3b82f6" },
  NUMBER:  { bg:"rgba(245,158,11,0.1)", text:"#f59e0b"  },
  STRING:  { bg:"rgba(16,185,129,0.1)", text:"#10b981"  },
  JSON:    { bg:"rgba(139,92,246,0.1)", text:"#8b5cf6"  },
};

export default function RemoteConfigPage() {
  const [params,   setParams]   = useState<RCParam[]>([]);
  const [values,   setValues]   = useState<Record<string,any>>({});
  const [search,   setSearch]   = useState("");
  const [expanded, setExpanded] = useState<Set<string>>(new Set(["general"]));
  const [loading,  setLoading]  = useState(true);
  const [saving,   setSaving]   = useState(false);
  const [saved,    setSaved]    = useState(false);
  const [publishError, setPublishError] = useState("");
  const [etag,     setEtag]     = useState("");
  const [paramCount,setParamCount] = useState(0);

  useEffect(() => {
    setLoading(true);
    fetch("/api/remote-config")
      .then(r => r.json())
      .then(d => {
        const raw = d.parameters ?? {};
        const etag = d.template?.etag ?? "";
        setEtag(etag);
        setParamCount(d.template?.parameterCount ?? 0);
        const list: RCParam[] = Object.entries(raw).map(([key, p]: [string, any]) => ({
          key,
          defaultValue: p.defaultValue ?? "",
          valueType:    p.valueType    ?? "STRING",
          description:  p.description  ?? "",
        }));
        // Source domains are editable even before they exist server-side: show
        // every known `source_<id>_base_url` key (template value or default).
        // Publishing writes them into the live template via the generic PUT.
        const have = new Set(list.map(p => p.key));
        for (const s of SOURCE_DOMAINS) {
          const key = `source_${s.id}_base_url`;
          if (!have.has(key)) {
            list.push({ key, defaultValue: s.def, valueType: "STRING", description: `${DOMAIN_DESC} (${s.label})` });
          }
        }
        setParams(list);
        // Init editable values
        const init: Record<string,any> = {};
        list.forEach(p => {
          const raw = p.defaultValue;
          if (p.valueType === "BOOLEAN") init[p.key] = raw === "true" || String(raw) === "true";
          else if (p.valueType === "NUMBER") init[p.key] = Number(raw) || 0;
          else init[p.key] = raw;
        });
        setValues(init);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const handlePublish = async () => {
    setSaving(true);
    try {
      const res = await fetch("/api/remote-config", {
        method:"PUT",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ parameters: values }),
      });
      // Never claim success for a failed publish — operators must not believe broken config shipped.
      if (!res.ok) { setPublishError("فشل نشر الإعدادات — لم يتم تطبيق أي تغييرات."); return; }
      setPublishError("");
      setSaved(true);
      setTimeout(() => setSaved(false), 3000);
    } catch {
      setPublishError("خطأ في الاتصال أثناء النشر.");
    } finally { setSaving(false); }
  };

  const toggleGroup = (id: string) =>
    setExpanded(prev => { const n = new Set(prev); n.has(id) ? n.delete(id) : n.add(id); return n; });

  // Group params
  const grouped: Record<string, RCParam[]> = {};
  params
    .filter(p => !search || p.key.toLowerCase().includes(search.toLowerCase()) || p.description.includes(search))
    .forEach(p => {
      const g = assignGroup(p.key);
      if (!grouped[g]) grouped[g] = [];
      grouped[g].push(p);
    });

  return (
    <div className="space-y-5">
      <PageHeader
        title="Remote Config"
        subtitle="إدارة إعدادات التطبيق عن بُعد"
        icon={Settings2}
        actions={
          <div className="flex items-center gap-2">
            {etag && (
              <span className="text-xs font-mono px-2 py-1 rounded-lg" style={{ background:"var(--muted)", color:"var(--muted-foreground)" }} dir="ltr">
                {paramCount} معامل • ETag: {etag.slice(0,10)}…
              </span>
            )}
            {publishError && (
              <span className="text-xs font-medium" style={{ color:"var(--destructive)" }}>{publishError}</span>
            )}
            <button onClick={handlePublish} disabled={saving}
              className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-60"
              style={{ background:"var(--primary)", color:"var(--primary-foreground)" }}>
              {saving ? <Loader2 size={15} className="animate-spin" /> : saved ? <CheckCircle2 size={15} /> : <Upload size={15} />}
              {saved ? "تم النشر!" : "نشر التغييرات"}
            </button>
          </div>
        }
      />

      <div className="relative">
        <Search size={15} className="absolute end-3 top-1/2 -translate-y-1/2" style={{ color:"var(--muted-foreground)" }} />
        <input type="text" value={search} onChange={e => setSearch(e.target.value)}
          placeholder="ابحث في المعاملات..." className="w-full pe-9" />
      </div>

      {loading ? (
        <div className="space-y-3">
          {[1,2,3].map(i => (
            <div key={i} className="h-16 rounded-[var(--radius-lg)] border skeleton-shimmer" style={{ borderColor:"var(--border)" }} />
          ))}
        </div>
      ) : Object.keys(grouped).length === 0 ? (
        <div className="py-12 text-center rounded-[var(--radius-lg)] border" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <p style={{ color:"var(--muted-foreground)" }}>
            {params.length === 0 ? "Remote Config فارغ أو غير مُعدّ" : "لا توجد معاملات بهذا البحث"}
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {Object.entries(grouped).map(([groupId, groupParams]) => {
            const meta   = GROUP_META[groupId] ?? GROUP_META.general;
            const isOpen = expanded.has(groupId);
            const Icon   = meta.icon;
            return (
              <div key={groupId} className="rounded-[var(--radius-lg)] border overflow-hidden"
                style={{ background:"var(--card)", borderColor:"var(--border)" }}>
                <button onClick={() => toggleGroup(groupId)}
                  className="w-full flex items-center gap-3 px-5 py-4 text-start hover:bg-[var(--accent)]/40 transition-colors">
                  <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0" style={{ background:"var(--accent)" }}>
                    <Icon size={16} style={{ color:"var(--primary)" }} />
                  </div>
                  <div className="flex-1">
                    <p className="font-semibold text-sm">{meta.label}</p>
                  </div>
                  <span className="text-xs px-2 py-0.5 rounded-full font-mono" style={{ background:"var(--muted)", color:"var(--muted-foreground)" }}>
                    {groupParams.length}
                  </span>
                  <ChevronDown size={16} style={{ color:"var(--muted-foreground)", transform:isOpen?"rotate(180deg)":undefined, transition:"transform 200ms" }} />
                </button>

                {isOpen && (
                  <div className="divide-y" style={{ borderColor:"var(--border)" }}>
                    {groupParams.map(param => {
                      const tc  = TYPE_COLOR[param.valueType] ?? TYPE_COLOR.STRING;
                      const val = values[param.key];
                      return (
                        <div key={param.key} className="flex items-center gap-4 px-5 py-4 flex-wrap">
                          <div className="flex-1 min-w-[200px]">
                            <div className="flex items-center gap-2 flex-wrap">
                              <p className="font-semibold text-sm">{param.description || param.key}</p>
                              <span className="text-[10px] font-mono px-1.5 py-0.5 rounded" style={{ background:tc.bg, color:tc.text }}>
                                {param.valueType.toLowerCase()}
                              </span>
                            </div>
                            <code className="text-[10px] mt-1 inline-block" style={{ color:"var(--muted-foreground)", opacity:0.6 }} dir="ltr">
                              {param.key}
                            </code>
                          </div>
                          <div className="shrink-0">
                            {param.valueType === "BOOLEAN" ? (
                              <Toggle enabled={!!val} onChange={v => setValues(p => ({...p,[param.key]:v}))} ariaLabel={param.key} />
                            ) : param.valueType === "NUMBER" ? (
                              <input type="number" value={val ?? ""} dir="ltr"
                                onChange={e => setValues(p => ({...p,[param.key]:Number(e.target.value)}))}
                                className="w-28 text-end font-mono text-sm" />
                            ) : param.valueType === "JSON" ? (
                              <textarea rows={3} dir="ltr"
                                value={typeof val === "object" ? JSON.stringify(val,null,2) : (val ?? "")}
                                onChange={e => { try { setValues(p => ({...p,[param.key]:JSON.parse(e.target.value)})); } catch { setValues(p => ({...p,[param.key]:e.target.value})); }}}
                                className="w-60 text-xs font-mono resize-none" />
                            ) : (
                              <input type="text" value={val ?? ""}
                                onChange={e => setValues(p => ({...p,[param.key]:e.target.value}))}
                                className="w-48 text-sm" />
                            )}
                          </div>
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
