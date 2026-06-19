"use client";

import { useEffect, useState } from "react";

export default function AchievementsPage() {
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/achievements")
      .then((r) => r.json())
      .then((d) => { setData(d.achievements); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  if (loading) return <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>;

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">الإنجازات والإحصائيات</h3>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p className="text-sm text-[var(--muted-foreground)]">إجمالي الصفحات</p>
          <p className="text-2xl font-bold mt-1">{data?.totalPagesRead || 0}</p>
        </div>
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p className="text-sm text-[var(--muted-foreground)]">إجمالي الفصول</p>
          <p className="text-2xl font-bold mt-1">{data?.totalChaptersRead || 0}</p>
        </div>
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p className="text-sm text-[var(--muted-foreground)]">آخر تحديث</p>
          <p className="text-2xl font-bold mt-1">
            {data?.lastUpdated ? new Date(data.lastUpdated).toLocaleDateString("ar-SA") : "—"}
          </p>
        </div>
      </div>

      {data?.achievements && (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-4">
          <h4 className="font-medium mb-3">الإنجازات</h4>
          <pre className="text-xs text-[var(--muted-foreground)] font-mono whitespace-pre-wrap overflow-auto max-h-64">
            {JSON.stringify(JSON.parse(data.achievements || "[]"), null, 2)}
          </pre>
        </div>
      )}

      {data?.goals && (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-4">
          <h4 className="font-medium mb-3">الأهداف</h4>
          <pre className="text-xs text-[var(--muted-foreground)] font-mono whitespace-pre-wrap overflow-auto max-h-64">
            {JSON.stringify(JSON.parse(data.goals || "[]"), null, 2)}
          </pre>
        </div>
      )}
    </div>
  );
}
