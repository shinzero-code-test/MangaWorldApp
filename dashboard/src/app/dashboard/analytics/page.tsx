"use client";

import { useEffect, useState } from "react";

export default function AnalyticsPage() {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<"7d" | "30d" | "90d">("7d");

  useEffect(() => {
    fetch(`/api/analytics/summary?period=${period}`)
      .then(r => r.json())
      .then(d => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, [period]);

  const kpis = [
    { label: "إجمالي المستخدمين", value: data?.overview?.totalUsers || 0, icon: "👥", color: "from-blue-500/20 to-blue-600/5", change: `+${data?.overview?.recentSignUps || 0}` },
    { label: "القوائم", value: data?.overview?.totalLists || 0, icon: "📋", color: "from-green-500/20 to-green-600/5", change: "" },
    { label: "الftime", value: data?.overview?.openReports || 0, icon: "🛡️", color: "from-red-500/20 to-red-600/5", change: "" },
    { label: "المستخدمون الجدد", value: data?.overview?.recentSignUps || 0, icon: "🆕", color: "from-purple-500/20 to-purple-600/5", change: "7 أيام" },
  ];

  const dailyActive = data?.engagement?.dailyActive || [];
  const maxVal = Math.max(...dailyActive.map((d: any) => d.users), 1);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">التحليلات</h3>
        <div className="flex gap-1 bg-[var(--card)] p-1 rounded-lg border border-[var(--border)]">
          {(["7d", "30d", "90d"] as const).map(p => (
            <button key={p} onClick={() => setPeriod(p)}
              className={`px-3 py-1 rounded-md text-xs font-medium transition ${period === p ? "bg-[var(--primary)] text-[var(--primary-foreground)]" : "text-[var(--muted-foreground)]"}`}>
              {p === "7d" ? "7 أيام" : p === "30d" ? "30 يوم" : "90 يوم"}
            </button>
          ))}
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {kpis.map(kpi => (
          <div key={kpi.label} className={`p-5 rounded-xl bg-gradient-to-br ${kpi.color} border border-[var(--border)]`}>
            <div className="flex items-center justify-between mb-2">
              <span className="text-2xl">{kpi.icon}</span>
              {kpi.change && <span className="text-xs font-medium text-green-500">{kpi.change}</span>}
            </div>
            <p className="text-sm text-[var(--muted-foreground)]">{kpi.label}</p>
            <p className={`text-3xl font-bold mt-1 ${loading ? "animate-pulse" : ""}`}>
              {loading ? "—" : kpi.value.toLocaleString("ar-SA")}
            </p>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Daily Active Users Chart */}
        <div className="p-6 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <h4 className="font-medium mb-4">النشاط اليومي</h4>
          <div className="flex items-end gap-2 h-48">
            {dailyActive.map((d: any, i: number) => (
              <div key={i} className="flex-1 flex flex-col items-center gap-1">
                <span className="text-xs text-[var(--muted-foreground)]">{d.users}</span>
                <div className="w-full bg-[var(--primary)]/30 rounded-t-md hover:bg-[var(--primary)]/50 transition-all"
                  style={{ height: `${(d.users / maxVal) * 100}%` }} />
                <span className="text-[10px] text-[var(--muted-foreground)]">{d.date}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Source Usage */}
        <div className="p-6 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <h4 className="font-medium mb-4">استخدام المصادر</h4>
          <div className="space-y-3">
            {(data?.engagement?.sourceUsage || []).map((s: any, i: number) => (
              <div key={i}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span>{s.name}</span>
                  <span className="text-[var(--muted-foreground)]">{s.value}%</span>
                </div>
                <div className="h-2 bg-[var(--accent)] rounded-full overflow-hidden">
                  <div className="h-full rounded-full transition-all duration-700"
                    style={{ width: `${s.value}%`, backgroundColor: s.color }} />
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>

      {/* Engagement Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-blue-500/10 flex items-center justify-center text-lg">📖</div>
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">متوسط وقت القراءة</p>
              <p className="text-xl font-bold">{data?.engagement?.avgReadingTime || 0} دقيقة</p>
            </div>
          </div>
        </div>
        <div className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-green-500/10 flex items-center justify-center text-lg">📊</div>
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">معدل الاحتفاظ</p>
              <p className="text-xl font-bold">{data?.engagement?.retentionRate || 0}%</p>
            </div>
          </div>
        </div>
        <div className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-purple-500/10 flex items-center justify-center text-lg">📄</div>
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">صفحات/جلسة</p>
              <p className="text-xl font-bold">{data?.engagement?.avgPagesPerSession || 0}</p>
            </div>
          </div>
        </div>
      </div>

      {/* Role Distribution */}
      {data?.overview?.roleDistribution && (
        <div className="p-6 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <h4 className="font-medium mb-4">توزيع الأدوار</h4>
          <div className="grid grid-cols-3 gap-4">
            {Object.entries(data.overview.roleDistribution).map(([role, count]: [string, any]) => (
              <div key={role} className="text-center p-4 bg-[var(--background)] rounded-lg">
                <p className="text-2xl font-bold">{count}</p>
                <p className="text-xs text-[var(--muted-foreground)]">
                  {role === "super-admin" ? "مدير عام" : role === "moderator" ? "مشرف" : "مشاهد"}
                </p>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}
