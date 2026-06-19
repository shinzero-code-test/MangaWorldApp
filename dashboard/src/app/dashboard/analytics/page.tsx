"use client";

import { useEffect, useState } from "react";

export default function AnalyticsPage() {
  const [summary, setSummary] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/analytics/summary")
      .then((r) => r.json())
      .then((data) => { setSummary(data); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">نظرة عامة على التحليلات</h3>
      {loading ? (
        <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>
      ) : (
        <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-sm text-[var(--muted-foreground)]">إجمالي المستخدمين</p>
            <p className="text-2xl font-bold mt-1">{summary?.totalUsers || 0}</p>
          </div>
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-sm text-[var(--muted-foreground)]">التعليقات</p>
            <p className="text-2xl font-bold mt-1">{summary?.totalComments || 0}</p>
          </div>
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-sm text-[var(--muted-foreground)]">المراجعات</p>
            <p className="text-2xl font-bold mt-1">{summary?.totalReviews || 0}</p>
          </div>
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-sm text-[var(--muted-foreground)]">تقارير مفتوحة</p>
            <p className="text-2xl font-bold mt-1">{summary?.openReports || 0}</p>
          </div>
        </div>
      )}
    </div>
  );
}
