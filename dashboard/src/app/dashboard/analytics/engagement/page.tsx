"use client";

import { useEffect, useState } from "react";

export default function EngagementPage() {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<"7d" | "30d" | "90d">("7d");

  useEffect(() => {
    fetch("/api/analytics/summary")
      .then((r) => r.json())
      .then((d) => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  // Mock engagement data for demonstration
  const retentionData = [
    { day: "السبت", users: 85, pct: 85 },
    { day: "الأحد", users: 72, pct: 72 },
    { day: "الاثنين", users: 91, pct: 91 },
    { day: "الثلاثاء", users: 68, pct: 68 },
    { day: "الأربعاء", users: 79, pct: 79 },
    { day: "الخميس", users: 95, pct: 95 },
    { day: "الجمعة", users: 88, pct: 88 },
  ];

  const readingStats = [
    { label: "متوسط صفحات/جلسة", value: "42", icon: "📄" },
    { label: "متوسط وقت القراءة", value: "23 دقيقة", icon: "⏱️" },
    { label: "معدل إتمام الفصل", value: "78%", icon: "✅" },
    { label: "معدل العودة يومياً", value: "65%", icon: "🔄" },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">التفاعل والاحتفاظ</h3>
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

      {/* Reading Stats */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {readingStats.map((stat) => (
          <div key={stat.label} className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <div className="flex items-center gap-3">
              <span className="text-2xl">{stat.icon}</span>
              <div>
                <p className="text-sm text-[var(--muted-foreground)]">{stat.label}</p>
                <p className="text-xl font-bold">{stat.value}</p>
              </div>
            </div>
          </div>
        ))}
      </div>

      {/* Retention Chart */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <h4 className="font-medium mb-4">معدل الاحتفاظ اليومي</h4>
        <div className="flex items-end gap-2 h-48">
          {retentionData.map((d, i) => (
            <div key={i} className="flex-1 flex flex-col items-center gap-1">
              <span className="text-xs text-[var(--muted-foreground)]">{d.users}</span>
              <div
                className={`w-full rounded-t-md transition-all duration-500 ${
                  d.pct >= 80 ? "bg-green-500/50" : d.pct >= 60 ? "bg-yellow-500/50" : "bg-red-500/50"
                }`}
                style={{ height: `${d.pct}%` }}
              />
              <span className="text-[10px] text-[var(--muted-foreground)]">{d.day}</span>
            </div>
          ))}
        </div>
        <div className="flex items-center justify-center gap-6 mt-4 text-xs text-[var(--muted-foreground)]">
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 rounded bg-green-500/50" />
            <span>≥ 80%</span>
          </div>
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 rounded bg-yellow-500/50" />
            <span>60-79%</span>
          </div>
          <div className="flex items-center gap-1">
            <span className="w-3 h-3 rounded bg-red-500/50" />
            <span>&lt; 60%</span>
          </div>
        </div>
      </div>

      {/* Reading Time Distribution */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
          <h4 className="font-medium mb-4">توزيع أوقات القراءة</h4>
          <div className="space-y-3">
            {[
              { time: "6 صباحاً - 12 ظهراً", pct: 15 },
              { time: "12 ظهراً - 6 مساءً", pct: 35 },
              { time: "6 مساءً - 12 ليلاً", pct: 40 },
              { time: "12 ليلاً - 6 صباحاً", pct: 10 },
            ].map((t) => (
              <div key={t.time}>
                <div className="flex items-center justify-between text-sm mb-1">
                  <span>{t.time}</span>
                  <span className="text-[var(--muted-foreground)]">{t.pct}%</span>
                </div>
                <div className="h-2 bg-[var(--accent)] rounded-full overflow-hidden">
                  <div
                    className="h-full bg-[var(--primary)] rounded-full transition-all duration-700"
                    style={{ width: `${t.pct}%` }}
                  />
                </div>
              </div>
            ))}
          </div>
        </div>

        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
          <h4 className="font-medium mb-4">أكثر المانجا قراءة</h4>
          <div className="space-y-3">
            {[
              { name: "One Piece", reads: 245 },
              { name: "Naruto", reads: 189 },
              { name: "Attack on Titan", reads: 156 },
              { name: "Jujutsu Kaisen", reads: 134 },
              { name: "Demon Slayer", reads: 98 },
            ].map((m, i) => (
              <div key={m.name} className="flex items-center gap-3">
                <span className="text-sm font-bold text-[var(--muted-foreground)] w-5">{i + 1}</span>
                <div className="flex-1">
                  <div className="flex items-center justify-between text-sm mb-1">
                    <span>{m.name}</span>
                    <span className="text-[var(--muted-foreground)]">{m.reads}</span>
                  </div>
                  <div className="h-1.5 bg-[var(--accent)] rounded-full overflow-hidden">
                    <div
                      className="h-full bg-[var(--primary)]/50 rounded-full"
                      style={{ width: `${(m.reads / 245) * 100}%` }}
                    />
                  </div>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
