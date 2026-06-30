"use client";
import { useEffect, useState } from "react";
import { Shield, CheckCircle2, XCircle, Clock } from "lucide-react";
import { PageHeader, StatusBadge, EmptyState, ConfirmDialog } from "@/components/ui";
import { formatRelative } from "@/lib/utils";

interface Report {
  id: string; reporterId: string; reportedId: string; mangaId?: string;
  reason: string; status: "open"|"resolved"|"dismissed"; priority?: string;
  createdAt: string|number;
}

const TABS = [
  { id:"open",      label:"مفتوحة",    icon: Clock,        color:"var(--warning)"  },
  { id:"resolved",  label:"محلولة",    icon: CheckCircle2, color:"var(--success)"  },
  { id:"dismissed", label:"مُتجاهَلة", icon: XCircle,      color:"var(--muted-foreground)" },
];

const priorityColor: Record<string,string> = {
  high:"var(--destructive)", medium:"var(--warning)", low:"var(--muted-foreground)"
};

export default function ModerationPage() {
  const [allReports, setAllReports] = useState<Report[]>([]);
  const [activeTab,  setActiveTab]  = useState("open");
  const [loading,    setLoading]    = useState(true);
  const [actionId,   setActionId]   = useState<string|null>(null);
  const [actionType, setActionType] = useState<"resolve"|"dismiss"|null>(null);
  const [actionLoad, setActionLoad] = useState(false);

  useEffect(() => {
    setLoading(true);
    fetch("/api/moderation/reports")
      .then(r => r.json())
      .then(d => { setAllReports(d.reports ?? []); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const counts = {
    total:     allReports.length,
    open:      allReports.filter(r => r.status === "open").length,
    resolved:  allReports.filter(r => r.status === "resolved").length,
    dismissed: allReports.filter(r => r.status === "dismissed").length,
  };

  const filtered = allReports.filter(r => r.status === activeTab);

  const handleAction = async () => {
    if (!actionId || !actionType) return;
    setActionLoad(true);
    try {
      await fetch("/api/moderation/reports", {
        method:"PATCH",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({
          reportId: actionId,
          status:   actionType === "resolve" ? "resolved" : "dismissed",
        }),
      });
      setAllReports(prev =>
        prev.map(r => r.id === actionId ? { ...r, status: actionType==="resolve"?"resolved":"dismissed" } : r)
      );
    } finally {
      setActionLoad(false); setActionId(null); setActionType(null);
    }
  };

  const countForTab = (id: string) => id==="open"?counts.open:id==="resolved"?counts.resolved:counts.dismissed;

  return (
    <div className="space-y-6">
      <PageHeader title="الإشراف" subtitle="إدارة تقارير المخالفات" icon={Shield} />

      <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
        {[
          { label:"الكل",     val:counts.total,     color:"var(--foreground)",      bg:"var(--muted)" },
          { label:"مفتوحة",   val:counts.open,      color:"var(--warning)",         bg:"rgba(245,158,11,0.08)" },
          { label:"محلولة",   val:counts.resolved,  color:"var(--success)",         bg:"rgba(16,185,129,0.08)" },
          { label:"مُتجاهَلة",val:counts.dismissed, color:"var(--muted-foreground)", bg:"var(--muted)" },
        ].map(s => (
          <div key={s.label} className="p-3 rounded-[var(--radius-lg)] border flex items-center justify-between"
            style={{ background:s.bg, borderColor:"var(--border)" }}>
            <span className="text-sm" style={{ color:"var(--muted-foreground)" }}>{s.label}</span>
            <span className="text-lg font-bold" style={{ color:s.color }}>{s.val}</span>
          </div>
        ))}
      </div>

      <div className="flex gap-1 p-1 rounded-[var(--radius-lg)]" style={{ background:"var(--muted)" }}>
        {TABS.map(tab => {
          const active = activeTab === tab.id;
          const Icon   = tab.icon;
          return (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className="flex-1 flex items-center justify-center gap-2 px-3 py-2 rounded-[var(--radius-md)] text-sm font-medium transition-all"
              style={{
                background: active ? "var(--card)" : "transparent",
                color:      active ? "var(--foreground)" : "var(--muted-foreground)",
                boxShadow:  active ? "0 1px 3px rgba(0,0,0,0.15)" : undefined,
              }}>
              <Icon size={14} style={{ color: active ? tab.color : undefined }} />
              {tab.label}
              <span className="text-xs px-1.5 py-0.5 rounded-full"
                style={{
                  background: active ? "color-mix(in srgb, var(--primary) 10%, transparent)" : "var(--border)",
                  color:      active ? "var(--primary)" : "var(--muted-foreground)",
                }}>
                {countForTab(tab.id)}
              </span>
            </button>
          );
        })}
      </div>

      {loading ? (
        <div className="space-y-3">
          {[1,2,3].map(i => (
            <div key={i} className="h-28 rounded-[var(--radius-lg)] border skeleton-shimmer" style={{ borderColor:"var(--border)" }} />
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className="rounded-[var(--radius-lg)] border" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <EmptyState
            icon={activeTab==="open" ? CheckCircle2 : Shield}
            title={activeTab==="open" ? "لا توجد تقارير مفتوحة" : "لا توجد تقارير"}
            description={activeTab==="open" ? "رائع! تم حل جميع البلاغات" : "لا توجد بيانات بهذا الفلتر"}
            color={activeTab==="open" ? "var(--success)" : undefined}
          />
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map(report => {
            const statusAccent = report.status==="open"?"var(--warning)":report.status==="resolved"?"var(--success)":"var(--border)";
            return (
              <div key={report.id} className="rounded-[var(--radius-lg)] border overflow-hidden"
                style={{ background:"var(--card)", borderColor:"var(--border)", borderInlineStartWidth:4, borderInlineStartColor:statusAccent }}>
                <div className="p-4">
                  <div className="flex items-start justify-between gap-3 flex-wrap">
                    <div className="flex items-center gap-2 flex-wrap">
                      <StatusBadge status={report.status} size="sm" />
                      {report.priority && (
                        <span className="text-[10px] font-semibold px-2 py-0.5 rounded-full uppercase tracking-wide"
                          style={{ color:priorityColor[report.priority]??"var(--muted-foreground)", background:`${priorityColor[report.priority]??"#6b7280"}18` }}>
                          {report.priority==="high"?"عالي":report.priority==="medium"?"متوسط":"منخفض"}
                        </span>
                      )}
                    </div>
                    <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>{formatRelative(report.createdAt)}</span>
                  </div>
                  <p className="font-semibold text-sm mt-3">{report.reason}</p>
                  <div className="flex flex-wrap gap-2 mt-2.5">
                    {report.mangaId && (
                      <code className="text-xs px-2 py-0.5 rounded" style={{ background:"var(--muted)", color:"var(--muted-foreground)" }} dir="ltr">
                        manga: {report.mangaId.slice(0,12)}
                      </code>
                    )}
                    <code className="text-xs px-2 py-0.5 rounded" style={{ background:"var(--muted)", color:"var(--muted-foreground)" }} dir="ltr">
                      reporter: {report.reporterId.slice(0,8)}…
                    </code>
                    <code className="text-xs px-2 py-0.5 rounded" style={{ background:"var(--muted)", color:"var(--muted-foreground)" }} dir="ltr">
                      reported: {report.reportedId.slice(0,8)}…
                    </code>
                  </div>
                  {report.status === "open" && (
                    <div className="flex gap-2 mt-3">
                      <button onClick={() => { setActionId(report.id); setActionType("resolve"); }}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition hover:opacity-80"
                        style={{ background:"rgba(16,185,129,0.12)", color:"var(--success)" }}>
                        <CheckCircle2 size={14} /> حل
                      </button>
                      <button onClick={() => { setActionId(report.id); setActionType("dismiss"); }}
                        className="flex items-center gap-1.5 px-3 py-1.5 rounded-lg text-sm font-medium transition hover:bg-[var(--accent)]"
                        style={{ color:"var(--muted-foreground)", border:"1px solid var(--border)" }}>
                        <XCircle size={14} /> تجاهل
                      </button>
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}

      <ConfirmDialog
        open={!!actionId}
        title={actionType==="resolve" ? "حل التقرير" : "تجاهل التقرير"}
        description={actionType==="resolve" ? "هل تريد وضع علامة 'تم الحل' على هذا التقرير؟" : "هل تريد تجاهل هذا التقرير وإغلاقه؟"}
        confirmLabel={actionType==="resolve" ? "حل" : "تجاهل"}
        variant="warning"
        onConfirm={handleAction}
        onCancel={() => { setActionId(null); setActionType(null); }}
        loading={actionLoad}
      />
    </div>
  );
}
