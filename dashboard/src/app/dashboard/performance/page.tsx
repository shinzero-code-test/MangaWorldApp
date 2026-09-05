"use client";

import { useEffect, useState } from "react";
import { Zap, Clock, TrendingUp, Monitor } from "lucide-react";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer
} from "recharts";
import { PageHeader, SkeletonCard, Skeleton } from "@/components/ui";
import { formatDuration, formatAr } from "@/lib/utils";

interface TraceItem {
  name:      string;
  eventType: string;
  count:     number;
  avgMs:     number;
  p50Ms:     number;
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
  bigquery?: { available: boolean; table?: string; reason?: string; hint?: string };
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
  const bq           = data?.bigquery;

  return (
    <div className="space-y-6">
      <PageHeader
        title="الأداء"
        subtitle="Firebase Performance Monitoring"
        icon={Zap}
      />

      {bq && !bq.available && (
        <div className="p-4 rounded-xl border text-sm leading-relaxed"
          style={{ background: "rgba(245,158,11,0.08)", borderColor: "rgba(245,158,11,0.3)", color: "var(--warning)" }}>
          {bq.reason === "permission-denied"
            ? "لا تملك الخدمة صلاحية BigQuery — امنح حساب الخدمة دوري Job User و Data Viewer."
            : "لا توجد بيانات مصدّرة في BigQuery بعد — تظهر التتبعات تلقائياً بعد أول تصدير يومي يحتوي أحداث أداء."}
        </div>
      )}

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
                    <th scope="col">النوع</th>
                    <th scope="col">العدد</th>
                    <th scope="col">متوسط</th>
                    <th scope="col">p50</th>
                  </tr>
                </thead>
                <tbody>
                  {traces.map((t, i) => (
                    <tr key={`${t.eventType}-${t.name}-${i}`}>
                      <td>
                        <span className="text-xs font-mono" dir="ltr">{t.name}</span>
                      </td>
                      <td>
                        <span className="text-xs font-mono" style={{ color: "var(--muted-foreground)" }} dir="ltr">{t.eventType}</span>
                      </td>
                      <td>
                        <span className="text-sm font-mono">{formatAr(t.count ?? 0)}</span>
                      </td>
                      <td>
                        <span
                          className="text-sm font-mono font-semibold"
                          style={{ color: durationColor(t.avgMs ?? 0) }}
                        >
                          {formatDuration(t.avgMs ?? 0)}
                        </span>
                      </td>
                      <td>
                        <span className="text-sm font-mono" style={{ color: durationColor(t.p50Ms ?? 0) }}>
                          {formatDuration(t.p50Ms ?? 0)}
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
