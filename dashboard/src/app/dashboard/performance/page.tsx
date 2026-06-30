"use client";

import { useEffect, useState } from "react";
import { Zap, Clock, TrendingUp, Monitor } from "lucide-react";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from "recharts";
import { PageHeader, SkeletonCard, Skeleton } from "@/components/ui";
import { formatDuration, formatAr, formatRelative } from "@/lib/utils";

interface TraceItem {
  name:      string;
  avgMs:     number;
  p50:       number;
  p95:       number;
  p99:       number;
  status:    "good" | "warning" | "critical";
  device?:   string;
  os?:       string;
  timestamp: string | number;
}

interface PerformanceData {
  traces:       TraceItem[];
  screenMetrics:{ screen: string; renderMs: number }[];
  summary: {
    avgStartup: number;
    avgNetwork: number;
    avgRender:  number;
    totalTraces:number;
  };
}

const durationColor = (ms: number) =>
  ms < 500 ? "var(--success)" : ms < 1000 ? "var(--warning)" : "var(--destructive)";

export default function PerformancePage() {
  const [data,    setData]    = useState<PerformanceData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/performance")
      .then((r) => r.json())
      .then((d) => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const traces       = data?.traces ?? [];
  const screenMetrics= data?.screenMetrics ?? [];
  const summary      = data?.summary ?? { avgStartup: 0, avgNetwork: 0, avgRender: 0, totalTraces: 0 };

  return (
    <div className="space-y-6">
      <PageHeader
        title="الأداء"
        subtitle="Firebase Performance Monitoring"
        icon={Zap}
      />

      {/* Summary cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {loading
          ? Array.from({ length: 4 }).map((_, i) => <SkeletonCard key={i} />)
          : [
              { label: "متوسط وقت الإقلاع",   val: summary.avgStartup, icon: Zap },
              { label: "متوسط الشبكة",        val: summary.avgNetwork,  icon: TrendingUp },
              { label: "متوسط الرسم",         val: summary.avgRender,   icon: Monitor },
              { label: "إجمالي التتبعات",     val: null, rawVal: formatAr(summary.totalTraces), icon: Clock },
            ].map((s) => {
              const Icon = s.icon;
              return (
                <div
                  key={s.label}
                  className="p-5 rounded-[var(--radius-xl)] border"
                  style={{ background: "var(--card)", borderColor: "var(--border)" }}
                >
                  <div
                    className="w-10 h-10 rounded-xl flex items-center justify-center mb-3"
                    style={{ background: "var(--accent)" }}
                  >
                    <Icon size={18} style={{ color: "var(--primary)" }} />
                  </div>
                  <p className="text-sm mb-1" style={{ color: "var(--muted-foreground)" }}>
                    {s.label}
                  </p>
                  <p className="text-2xl font-bold font-mono">
                    {s.val !== null ? formatDuration(s.val ?? 0) : s.rawVal}
                  </p>
                </div>
              );
            })}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Traces table */}
        <div
          className="rounded-[var(--radius-lg)] border overflow-hidden"
          style={{ background: "var(--card)", borderColor: "var(--border)" }}
        >
          <div
            className="px-5 py-4 border-b flex items-center gap-2.5"
            style={{ borderColor: "var(--border)" }}
          >
            <div
              className="w-8 h-8 rounded-lg flex items-center justify-center"
              style={{ background: "var(--accent)" }}
            >
              <Clock size={16} style={{ color: "var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">التتبعات</h3>
          </div>
          <div className="overflow-x-auto">
            {loading ? (
              <Skeleton className="h-48 m-4" />
            ) : traces.length === 0 ? (
              <p className="p-6 text-sm text-center" style={{ color: "var(--muted-foreground)" }}>
                لا توجد بيانات
              </p>
            ) : (
              <table aria-label="جدول التتبعات">
                <thead>
                  <tr>
                    <th scope="col">الاسم</th>
                    <th scope="col">متوسط</th>
                    <th scope="col">p95</th>
                    <th scope="col">الوقت</th>
                  </tr>
                </thead>
                <tbody>
                  {traces.map((t, i) => (
                    <tr key={i}>
                      <td>
                        <span className="text-xs font-mono" dir="ltr">{t.name}</span>
                      </td>
                      <td>
                        <span
                          className="text-sm font-mono font-semibold"
                          style={{ color: durationColor(t.avgMs) }}
                        >
                          {formatDuration(t.avgMs)}
                        </span>
                      </td>
                      <td>
                        <span className="text-sm font-mono" style={{ color: durationColor(t.p95) }}>
                          {formatDuration(t.p95)}
                        </span>
                      </td>
                      <td>
                        <span className="text-xs" style={{ color: "var(--muted-foreground)" }}>
                          {formatRelative(t.timestamp)}
                        </span>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}
          </div>
        </div>

        {/* Screen metrics chart */}
        <div
          className="rounded-[var(--radius-lg)] border overflow-hidden"
          style={{ background: "var(--card)", borderColor: "var(--border)" }}
        >
          <div
            className="px-5 py-4 border-b flex items-center gap-2.5"
            style={{ borderColor: "var(--border)" }}
          >
            <div
              className="w-8 h-8 rounded-lg flex items-center justify-center"
              style={{ background: "var(--accent)" }}
            >
              <Monitor size={16} style={{ color: "var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">وقت رسم الشاشات</h3>
          </div>
          <div className="p-5">
            {loading ? (
              <Skeleton className="h-[200px] w-full" />
            ) : screenMetrics.length === 0 ? (
              <p className="py-8 text-sm text-center" style={{ color: "var(--muted-foreground)" }}>
                لا توجد بيانات
              </p>
            ) : (
              <ResponsiveContainer width="100%" height={220}>
                <BarChart
                  layout="vertical"
                  data={screenMetrics}
                  margin={{ top: 0, right: 10, bottom: 0, left: 80 }}
                >
                  <CartesianGrid
                    strokeDasharray="3 3"
                    horizontal={false}
                    stroke="var(--border)"
                  />
                  <XAxis
                    type="number"
                    unit="ms"
                    tick={{ fill: "var(--muted-foreground)", fontSize: 11 }}
                    axisLine={false}
                    tickLine={false}
                  />
                  <YAxis
                    type="category"
                    dataKey="screen"
                    tick={{ fill: "var(--muted-foreground)", fontSize: 10 }}
                    axisLine={false}
                    tickLine={false}
                    width={75}
                    orientation="right"
                  />
                  <Tooltip
                    contentStyle={{
                      background:   "var(--card)",
                      border:       "1px solid var(--border)",
                      borderRadius: 8,
                    }}
                    formatter={(v: any) => [`${v}ms`, "وقت الرسم"]}
                  />
                  <Bar
                    dataKey="renderMs"
                    fill="var(--primary)"
                    radius={[0, 4, 4, 0]}
                    name="وقت الرسم"
                  />
                </BarChart>
              </ResponsiveContainer>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}
