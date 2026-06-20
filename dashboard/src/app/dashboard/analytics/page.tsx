"use client";

import { useEffect, useState } from "react";

interface SummaryData {
  totalUsers: number;
  totalComments: number;
  totalReviews: number;
  openReports: number;
}

interface ChartData {
  name: string;
  value: number;
}

export default function AnalyticsPage() {
  const [summary, setSummary] = useState<SummaryData | null>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<"7d" | "30d" | "90d">("7d");

  useEffect(() => {
    fetch("/api/analytics/summary")
      .then((r) => r.json())
      .then((data) => { setSummary(data); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const kpis = [
    { label: "إجمالي المستخدمين", value: summary?.totalUsers || 0, icon: "👥", color: "from-blue-500/20 to-blue-600/5", change: "+12%", positive: true },
    { label: "التعليقات", value: summary?.totalComments || 0, icon: "💬", color: "from-green-500/20 to-green-600/5", change: "+8%", positive: true },
    { label: "المراجعات", value: summary?.totalReviews || 0, icon: "⭐", color: "from-yellow-500/20 to-yellow-600/5", change: "+5%", positive: true },
    { label: "تقارير مفتوحة", value: summary?.openReports || 0, icon: "🛡️", color: "from-red-500/20 to-red-600/5", change: "-3%", positive: false },
  ];

  // Mock chart data for demonstration
  const weeklyData: ChartData[] = [
    { name: "السبت", value: 120 },
    { name: "الأحد", value: 98 },
    { name: "الاثنين", value: 145 },
    { name: "الثلاثاء", value: 132 },
    { name: "الأربعاء", value: 165 },
    { name: "الخميس", value: 189 },
    { name: "الجمعة", value: 156 },
  ];

  const sourceData: ChartData[] = [
    { name: "Olympus", value: 35 },
    { name: "Azora", value: 25 },
    { name: "Starz", value: 20 },
    { name: "MangaSid", value: 12 },
    { name: "Meshmanga", value: 8 },
  ];

  const maxBarValue = Math.max(...weeklyData.map((d) => d.value));

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">التحليلات</h3>
        <div className="flex gap-1 bg-[var(--card)] p-1 rounded-lg border border-[var(--border)]">
          {(["7d", "30d", "90d"] as const).map((p) => (
            <button
              key={p}
              onClick={() => setPeriod(p)}
              className={`px-3 py-1 rounded-md text-xs font-medium transition ${
                period === p ? "bg-[var(--primary)] text-[var(--primary-foreground)]" : "text-[var(--muted-foreground)]"
              }`}
            >
              {p === "7d" ? "7 أيام" : p === "30d" ? "30 يوم" : "90 يوم"}
            </button>
          ))}
        </div>
      </div>

      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {kpis.map((kpi) => (
          <div
            key={kpi.label}
            className={`p-5 rounded-xl bg-gradient-to-br ${kpi.color} border border-[var(--border)]`}
          >
            <div className="flex items-center justify-between mb-3">
              <span className="text-2xl">{kpi.icon}</span>
              <span className={`text-xs font-medium ${kpi.positive ? "text-green-500" : "text-red-500"}`}>
                {kpi.change}
              </span>
            </div>
            <p className="text-sm text-[var(--muted-foreground)]">{kpi.label}</p>
            <p className={`text-3xl font-bold mt-1 ${loading ? "animate-pulse" : ""}`}>
              {loading ? "—" : kpi.value.toLocaleString("ar-SA")}
            </p>
          </div>
        ))}
      </div>

      {/* Charts Row */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Weekly Activity Chart */}
        <div className="p-6 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <h4 className="font-medium mb-4">نشاط القراءة الأسبوعي</h4>
          <div className="flex items-end gap-2 h-48">
            {weeklyData.map((d, i) => (
              <div key={i} className="flex-1 flex flex-col items-center gap-1">
                <span className="text-xs text-[var(--muted-foreground)]">{d.value}</span>
                <div
                  className="w-full bg-[var(--primary)]/30 rounded-t-md transition-all duration-500 hover:bg-[var(--primary)]/50"
                  style={{ height: `${(d.value / maxBarValue) * 100}%` }}
                />
                <span className="text-[10px] text-[var(--muted-foreground)]">{d.name}</span>
              </div>
            ))}
          </div>
        </div>

        {/* Source Usage Chart */}
        <div className="p-6 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <h4 className="font-medium mb-4">استخدام المصادر</h4>
          <div className="space-y-3">
            {sourceData.map((d, i) => {
              const colors = ["bg-blue-500", "bg-green-500", "bg-yellow-500", "bg-purple-500", "bg-red-500"];
              return (
                <div key={i}>
                  <div className="flex items-center justify-between text-sm mb-1">
                    <span>{d.name}</span>
                    <span className="text-[var(--muted-foreground)]">{d.value}%</span>
                  </div>
                  <div className="h-2 bg-[var(--accent)] rounded-full overflow-hidden">
                    <div
                      className={`h-full ${colors[i]} rounded-full transition-all duration-700`}
                      style={{ width: `${d.value}%` }}
                    />
                  </div>
                </div>
              );
            })}
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
              <p className="text-xl font-bold">23 دقيقة/يوم</p>
            </div>
          </div>
        </div>
        <div className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-green-500/10 flex items-center justify-center text-lg">📊</div>
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">معدل الاحتفاظ</p>
              <p className="text-xl font-bold">68%</p>
            </div>
          </div>
        </div>
        <div className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center gap-3">
            <div className="w-10 h-10 rounded-lg bg-purple-500/10 flex items-center justify-center text-lg">📱</div>
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">الجلسات النشطة</p>
              <p className="text-xl font-bold">45</p>
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
