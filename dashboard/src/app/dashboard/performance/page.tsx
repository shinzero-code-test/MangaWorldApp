"use client";

import { useEffect, useState } from "react";

interface Trace {
  id: string;
  name: string;
  duration: number;
  status: string;
  timestamp: number;
  device: string;
  os: string;
}

interface Metrics {
  appStartup: { avg: number; p50: number; p95: number; p99: number };
  pageLoad: { avg: number; p50: number; p95: number; p99: number };
  networkRequests: { avg: number; p50: number; p95: number; p99: number };
  imageLoad: { avg: number; p50: number; p95: number; p99: number };
}

interface ScreenMetric {
  screen: string;
  avgTime: number;
  renders: number;
}

export default function PerformancePage() {
  const [traces, setTraces] = useState<Trace[]>([]);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [screenMetrics, setScreenMetrics] = useState<ScreenMetric[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/performance")
      .then(r => r.json())
      .then(data => { setTraces(data.traces || []); setMetrics(data.metrics); setScreenMetrics(data.screenMetrics || []); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const formatDuration = (ms: number) => ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`;

  const metricCards = metrics ? [
    { label: "بداية التطبيق", icon: "🚀", data: metrics.appStartup },
    { label: "تحميل الصفحات", icon: "📄", data: metrics.pageLoad },
    { label: "الطلبات الشبكة", icon: "🌐", data: metrics.networkRequests },
    { label: "تحميل الصور", icon: "🖼️", data: metrics.imageLoad },
  ] : [];

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 4 }).map((_, i) => (
        <div key={i} className="h-24 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">مراقبة الأداء — Performance</h3>

      {/* Performance Metrics */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {metricCards.map(card => (
          <div key={card.label} className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <div className="flex items-center gap-2 mb-3">
              <span className="text-xl">{card.icon}</span>
              <span className="text-sm font-medium">{card.label}</span>
            </div>
            <div className="space-y-1">
              <div className="flex justify-between text-xs">
                <span className="text-[var(--muted-foreground)]">متوسط</span>
                <span className="font-medium">{formatDuration(card.data.avg)}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-[var(--muted-foreground)]">P50</span>
                <span className="font-medium">{formatDuration(card.data.p50)}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-[var(--muted-foreground)]">P95</span>
                <span className={`font-medium ${card.data.p95 > 2000 ? "text-red-500" : "text-green-500"}`}>{formatDuration(card.data.p95)}</span>
              </div>
              <div className="flex justify-between text-xs">
                <span className="text-[var(--muted-foreground)]">P99</span>
                <span className={`font-medium ${card.data.p99 > 3000 ? "text-red-500" : "text-green-500"}`}>{formatDuration(card.data.p99)}</span>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Screen Rendering Metrics */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <h4 className="font-medium mb-4">أداء الشاشات</h4>
        <div className="space-y-3">
          {screenMetrics.map(s => (
            <div key={s.screen} className="flex items-center gap-4">
              <div className="w-40 text-sm font-medium">{s.screen}</div>
              <div className="flex-1">
                <div className="h-2 bg-[var(--accent)] rounded-full overflow-hidden">
                  <div className="h-full rounded-full transition-all"
                    style={{
                      width: `${Math.min(100, (s.avgTime / 2000) * 100)}%`,
                      backgroundColor: s.avgTime > 1500 ? "#ef4444" : s.avgTime > 800 ? "#f59e0b" : "#22c55e",
                    }} />
                </div>
              </div>
              <div className="w-20 text-right text-sm">{formatDuration(s.avgTime)}</div>
              <div className="w-24 text-right text-xs text-[var(--muted-foreground)]">{s.renders.toLocaleString()} renders</div>
            </div>
          ))}
        </div>
      </div>

      {/* Recent Traces */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <h4 className="font-medium mb-4">آخر التتبعات</h4>
        <div className="space-y-2">
          {traces.map(t => (
            <div key={t.id} className="flex items-center gap-4 p-3 bg-[var(--background)] rounded-lg">
              <span className={`w-2 h-2 rounded-full ${t.status === "ok" ? "bg-green-500" : "bg-yellow-500"}`} />
              <div className="flex-1">
                <p className="text-sm font-medium font-mono">{t.name}</p>
                <p className="text-xs text-[var(--muted-foreground)]">{t.device} • {t.os}</p>
              </div>
              <div className="text-left">
                <span className={`text-sm font-medium ${t.duration > 2000 ? "text-red-500" : t.duration > 1000 ? "text-yellow-500" : "text-green-500"}`}>
                  {formatDuration(t.duration)}
                </span>
                <p className="text-[10px] text-[var(--muted-foreground)]">{new Date(t.timestamp).toLocaleTimeString("ar-SA")}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
