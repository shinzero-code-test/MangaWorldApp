"use client";

import { useEffect, useState } from "react";

interface DailyActive {
  date: string;
  users: number;
}

interface TopManga {
  name: string;
  reads: number;
}

interface ReadingDistribution {
  time: string;
  pct: number;
}

interface EngagementData {
  dailyActive: DailyActive[];
  topManga: TopManga[];
  readingDistribution: ReadingDistribution[];
}

export default function EngagementPage() {
  const [data, setData] = useState<EngagementData | null>(null);
  const [loading, setLoading] = useState(true);
  const [period, setPeriod] = useState<"7d" | "30d" | "90d">("7d");

  useEffect(() => {
    setLoading(true);
    const days = period === "7d" ? 7 : period === "30d" ? 30 : 90;
    fetch(`/api/analytics/engagement?days=${days}`)
      .then((r) => r.json())
      .then((d) => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, [period]);

  if (loading || !data) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>
      </div>
    );
  }

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

      {/* Retention Chart */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <h4 className="font-medium mb-4">المستخدمون النشطون يومياً</h4>
        {data.dailyActive.length > 0 ? (
          <div className="flex items-end gap-2 h-48">
            {data.dailyActive.map((d, i) => {
              const maxUsers = Math.max(...data.dailyActive.map((x) => x.users), 1);
              const pct = Math.round((d.users / maxUsers) * 100);
              return (
                <div key={i} className="flex-1 flex flex-col items-center gap-1">
                  <span className="text-xs text-[var(--muted-foreground)]">{d.users}</span>
                  <div
                    className={`w-full rounded-t-md transition-all duration-500 ${
                      pct >= 80 ? "bg-green-500/50" : pct >= 60 ? "bg-yellow-500/50" : "bg-red-500/50"
                    }`}
                    style={{ height: `${pct}%` }}
                  />
                  <span className="text-[10px] text-[var(--muted-foreground)]">{d.date}</span>
                </div>
              );
            })}
          </div>
        ) : (
          <p className="text-sm text-[var(--muted-foreground)]">لا توجد بيانات متاحة</p>
        )}
      </div>

      {/* Reading Time Distribution */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
          <h4 className="font-medium mb-4">توزيع أوقات القراءة</h4>
          <div className="space-y-3">
            {data.readingDistribution.map((t) => (
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
          <h4 className="font-medium mb-4">أكثر المانجا تفاعلاً</h4>
          {data.topManga.length > 0 ? (
            <div className="space-y-3">
              {data.topManga.map((m, i) => (
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
                        style={{ width: `${data.topManga[0]?.reads ? (m.reads / data.topManga[0].reads) * 100 : 0}%` }}
                      />
                    </div>
                  </div>
                </div>
              ))}
            </div>
          ) : (
            <p className="text-sm text-[var(--muted-foreground)]">لا توجد بيانات متاحة</p>
          )}
        </div>
      </div>
    </div>
  );
}
