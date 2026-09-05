"use client";
import { useEffect, useState } from "react";
import { Bug, ChevronDown, ChevronUp } from "lucide-react";
import { RadialBarChart, RadialBar, ResponsiveContainer } from "recharts";
import { PageHeader, StatusBadge, EmptyState, SkeletonCard, Skeleton } from "@/components/ui";
import { formatAr, formatRelative } from "@/lib/utils";

interface CrashIssue {
  id:string; title:string; subtitle?:string; state:"open"|"resolved";
  count:number; users:number; lastOccurrence:string; firstOccurrence?:string;
  appVersions?:string[]; osVersions?:string[]; devices?:string[];
}

interface Stats {
  crashFreeRate:number|null; crashFreeRateDelta?:number;
  totalIssues:number; openIssues:number; totalCrashes:number; affectedUsers:number;
}

interface BigQueryState {
  available: boolean;
  table?: string;
  reason?: string;
  hint?: string;
}

export default function CrashlyticsPage() {
  const [issues,  setIssues]  = useState<CrashIssue[]>([]);
  const [stats,   setStats]   = useState<Stats|null>(null);
  const [bq,      setBq]      = useState<BigQueryState|null>(null);
  const [loading, setLoading] = useState(true);
  const [filter,  setFilter]  = useState<"all"|"open"|"resolved">("all");
  const [expanded,setExpanded]= useState<Set<string>>(new Set());

  useEffect(() => {
    fetch("/api/crashlytics")
      .then(r => r.json())
      .then(d => {
        setIssues(d.issues ?? []);
        setStats(d.stats ?? null);
        setBq(d.bigquery ?? null);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const toggleExpand = (id:string) =>
    setExpanded(p => { const n=new Set(p); n.has(id)?n.delete(id):n.add(id); return n; });

  const filtered = issues.filter(i => filter==="all" ? true : i.state===filter);
  const cfr      = stats?.crashFreeRate ?? null;
  const cfrColor = cfr===null?"var(--muted-foreground)":cfr>=99?"var(--success)":cfr>=95?"var(--warning)":"var(--destructive)";

  const STAT_CARDS = [
    { label:"معدل خالٍ من الأعطال", custom: "gauge" },
    { label:"إجمالي الأعطال",        val: stats?.totalCrashes   ?? 0, color:"var(--destructive)" },
    { label:"مشاكل مفتوحة",          val: stats?.openIssues     ?? 0, color:"var(--warning)"     },
    { label:"مستخدمون متأثرون",      val: stats?.affectedUsers  ?? 0, color:"var(--info)"        },
  ];

  return (
    <div className="space-y-6">
      <PageHeader title="الأعطال" subtitle="Crashlytics — تتبع أعطال التطبيق" icon={Bug} />

      {bq && !bq.available && (
        <div className="p-4 rounded-xl border text-sm leading-relaxed"
          style={{ background: "rgba(245,158,11,0.08)", borderColor: "rgba(245,158,11,0.3)", color: "var(--warning)" }}>
          {bq.reason === "permission-denied"
            ? "لا تملك الخدمة صلاحية BigQuery — امنح حساب الخدمة دوري Job User و Data Viewer."
            : "لا توجد بيانات مصدّرة في BigQuery بعد — تظهر المشاكل تلقائياً بعد أول تصدير يومي يحتوي أحداث أعطال."}
        </div>
      )}

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {loading ? Array.from({length:4}).map((_,i) => <SkeletonCard key={i} />) : (
          <>
            {/* Gauge card */}
            <div className="p-5 rounded-[var(--radius-xl)] border flex flex-col items-center justify-center gap-2"
              style={{ background:"var(--card)", borderColor:"var(--border)" }}>
              <div className="relative w-24 h-24">
                <ResponsiveContainer width="100%" height="100%">
                  <RadialBarChart innerRadius="65%" outerRadius="85%" data={[{value:cfr ?? 0}]} startAngle={90} endAngle={-270}>
                    <RadialBar dataKey="value" fill={cfrColor} cornerRadius={4} background={{ fill:"var(--muted)" } as any} />
                  </RadialBarChart>
                </ResponsiveContainer>
                <div className="absolute inset-0 flex items-center justify-center">
                  <span className="text-sm font-bold" style={{ color:cfrColor }}>{cfr === null ? "—" : `${cfr.toFixed(1)}%`}</span>
                </div>
              </div>
              <p className="text-xs text-center" style={{ color:"var(--muted-foreground)" }}>خالٍ من الأعطال</p>
              {stats?.crashFreeRateDelta !== undefined && (
                <span className="text-xs font-semibold px-2 py-0.5 rounded-full"
                  style={{ background: stats.crashFreeRateDelta>=0?"rgba(16,185,129,0.1)":"rgba(239,68,68,0.1)", color: stats.crashFreeRateDelta>=0?"var(--success)":"var(--destructive)" }}>
                  {stats.crashFreeRateDelta>=0?"+":""}{stats.crashFreeRateDelta.toFixed(2)}%
                </span>
              )}
            </div>

            {STAT_CARDS.slice(1).map(s => (
              <div key={s.label} className="p-5 rounded-[var(--radius-xl)] border" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
                <p className="text-sm mb-2" style={{ color:"var(--muted-foreground)" }}>{s.label}</p>
                <p className="text-3xl font-bold" style={{ color: s.color }}>{formatAr(s.val ?? 0)}</p>
              </div>
            ))}
          </>
        )}
      </div>

      <div className="flex gap-2 flex-wrap">
        {[{id:"all" as const,label:"الكل"},{id:"open" as const,label:"مفتوحة"},{id:"resolved" as const,label:"محلولة"}].map(f => (
          <button key={f.id} onClick={() => setFilter(f.id)}
            className="px-3 py-1.5 rounded-lg border text-sm font-medium transition-all"
            style={{ background:filter===f.id?"color-mix(in srgb, var(--primary) 10%, transparent)":"var(--card)", borderColor:filter===f.id?"color-mix(in srgb, var(--primary) 30%, transparent)":"var(--border)", color:filter===f.id?"var(--primary)":"var(--muted-foreground)" }}>
            {f.label}
          </button>
        ))}
      </div>

      {loading ? (
        <div className="space-y-3">{[1,2,3].map(i => <Skeleton key={i} className="h-24 w-full" />)}</div>
      ) : filtered.length === 0 ? (
        <div className="rounded-[var(--radius-lg)] border" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <EmptyState icon={Bug} title="لا توجد مشاكل" description="تطبيقك يعمل بشكل مثالي!" color="var(--success)" />
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map(issue => {
            const isExp    = expanded.has(issue.id);
            const isOpen   = issue.state === "open";
            const bdrColor = isOpen ? "rgba(239,68,68,0.3)" : "rgba(16,185,129,0.2)";
            return (
              <div key={issue.id} className="rounded-[var(--radius-lg)] border overflow-hidden"
                style={{ background:"var(--card)", borderColor:"var(--border)", borderInlineStartWidth:4, borderInlineStartColor:bdrColor }}>
                <button onClick={() => toggleExpand(issue.id)}
                  className="w-full flex items-center gap-3 px-5 py-4 text-start hover:bg-[var(--accent)]/30 transition-colors">
                  <StatusBadge status={isOpen?"open":"resolved"} size="sm" />
                  <div className="flex-1 min-w-0">
                    <p className="font-mono text-sm font-semibold truncate" dir="ltr">{issue.title}</p>
                    {issue.subtitle && <p className="text-xs font-mono truncate mt-0.5" dir="ltr" style={{ color:"var(--muted-foreground)" }}>{issue.subtitle}</p>}
                    <div className="flex items-center gap-3 mt-1 flex-wrap">
                      <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>{formatAr(issue.count)} حدث</span>
                      <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>{formatAr(issue.users)} مستخدم</span>
                      <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>{formatRelative(issue.lastOccurrence)}</span>
                    </div>
                  </div>
                  {isExp ? <ChevronUp size={16} style={{ color:"var(--muted-foreground)" }} /> : <ChevronDown size={16} style={{ color:"var(--muted-foreground)" }} />}
                </button>

                {isExp && (
                  <div className="border-t px-5 py-4 grid grid-cols-1 sm:grid-cols-3 gap-4" style={{ borderColor:"var(--border)" }}>
                    {[
                      { label:"إصدارات التطبيق", items: issue.appVersions ?? [] },
                      { label:"إصدارات النظام",  items: issue.osVersions  ?? [] },
                      { label:"الأجهزة",          items: issue.devices     ?? [] },
                    ].map(col => (
                      <div key={col.label}>
                        <p className="text-xs font-semibold mb-2 uppercase tracking-wide" style={{ color:"var(--muted-foreground)" }}>{col.label}</p>
                        {col.items.length===0 ? <p className="text-xs" style={{ color:"var(--muted-foreground)" }}>—</p> : (
                          <div className="flex flex-wrap gap-1.5">
                            {col.items.map(item => (
                              <span key={item} className="text-xs px-2 py-0.5 rounded font-mono"
                                style={{ background:"var(--muted)", color:"var(--muted-foreground)" }} dir="ltr">{item}</span>
                            ))}
                          </div>
                        )}
                      </div>
                    ))}
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
