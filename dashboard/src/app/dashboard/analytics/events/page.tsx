"use client";

import { useEffect, useState } from "react";

interface Event {
  id: string;
  name: string;
  params: Record<string, any>;
  userId: string;
  timestamp: number;
}

interface TopEvent {
  name: string;
  count: number;
}

interface EventsData {
  events: Event[];
  topEvents: TopEvent[];
  totalEvents: number;
  note?: string;
}

export default function EventsPage() {
  const [data, setData] = useState<EventsData | null>(null);
  const [loading, setLoading] = useState(true);
  const [fetchError, setFetchError] = useState("");

  useEffect(() => {
    fetch("/api/analytics/events")
      .then((r) => r.json())
      .then((d) => {
        // API may return { error } on failure — normalize to empty lists
        // so `.length` / `.slice` never crash (Cannot read properties of undefined).
        setData({
          events: Array.isArray(d?.events) ? d.events : [],
          topEvents: Array.isArray(d?.topEvents) ? d.topEvents : [],
          totalEvents: typeof d?.totalEvents === "number" ? d.totalEvents : 0,
          note: typeof d?.note === "string" ? d.note : undefined,
        });
        if (typeof d?.error === "string" && !Array.isArray(d?.events)) {
          setFetchError(d.error);
        }
        setLoading(false);
      })
      .catch(() => { setData({ events: [], topEvents: [], totalEvents: 0 }); setLoading(false); });
  }, []);

  if (loading) {
    return (
      <div className="flex items-center justify-center h-64">
        <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">سجل الأحداث</h3>

      {(data?.topEvents?.length ?? 0) > 0 ? (
        <div className="grid grid-cols-1 md:grid-cols-2 gap-6">
          {/* Top Events */}
          <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
            <h4 className="font-medium mb-4">أكثر الأحداث تكراراً</h4>
            <div className="space-y-3">
              {(data?.topEvents ?? []).map((e) => (
                <div key={e.name} className="flex items-center justify-between">
                  <span className="text-sm">{e.name}</span>
                  <span className="text-sm font-bold text-[var(--muted-foreground)]">{e.count}</span>
                </div>
              ))}
            </div>
          </div>

          {/* Recent Events */}
          <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
            <h4 className="font-medium mb-4">أحدث الأحداث</h4>
            <div className="space-y-3 max-h-96 overflow-y-auto">
              {(data?.events ?? []).slice(0, 20).map((e) => (
                <div key={e.id} className="p-3 rounded-lg" style={{ background: "var(--background)" }}>
                  <div className="flex items-center justify-between">
                    <span className="text-sm font-medium">{e.name}</span>
                    <span className="text-xs text-[var(--muted-foreground)]">
                      {Number.isFinite(new Date(e.timestamp).getTime())
                        ? new Date(e.timestamp).toLocaleString("ar-EG")
                        : "—"}
                    </span>
                  </div>
                  {e.params && Object.keys(e.params).length > 0 && (
                    <div className="mt-1 text-xs text-[var(--muted-foreground)]">
                      {Object.entries(e.params).slice(0, 3).map(([k, v]) => (
                        <span key={k} className="mr-2">{k}: {String(v).slice(0, 30)}</span>
                      ))}
                    </div>
                  )}
                </div>
              ))}
            </div>
          </div>
        </div>
      ) : (
        <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p>لا توجد أحداث مسجلة بعد</p>
          <p className="text-xs mt-2">{fetchError || data?.note || "سيتم عرض الأحداث هنا عندما يسجل التطبيق أحداث تحليلات"}</p>
        </div>
      )}
    </div>
  );
}
