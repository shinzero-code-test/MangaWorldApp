"use client";

import { useEffect, useState } from "react";

interface KPIData {
  totalUsers: number;
  totalComments: number;
  totalReviews: number;
  openReports: number;
}

export default function DashboardOverview() {
  const [kpis, setKpis] = useState<KPIData>({ totalUsers: 0, totalComments: 0, totalReviews: 0, openReports: 0 });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/analytics/summary")
      .then((r) => r.json())
      .then((data) => { setKpis(data); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const cards = [
    { label: "إجمالي المستخدمين", value: kpis.totalUsers, icon: "👥", color: "text-blue-500" },
    { label: "التعليقات", value: kpis.totalComments, icon: "💬", color: "text-green-500" },
    { label: "المراجعات", value: kpis.totalReviews, icon: "⭐", color: "text-yellow-500" },
    { label: "تقارير مفتوحة", value: kpis.openReports, icon: "🛡️", color: "text-red-500" },
  ];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {cards.map((card) => (
          <div
            key={card.label}
            className="p-6 rounded-xl bg-[var(--card)] border border-[var(--border)]"
          >
            <div className="flex items-center justify-between">
              <div>
                <p className="text-sm text-[var(--muted-foreground)]">{card.label}</p>
                <p className={`text-3xl font-bold mt-1 ${loading ? "animate-pulse" : ""}`}>
                  {loading ? "—" : card.value.toLocaleString("ar-SA")}
                </p>
              </div>
              <span className={`text-3xl ${card.color}`}>{card.icon}</span>
            </div>
          </div>
        ))}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="p-6 rounded-xl bg-[var(--card)] border border-[var(--border)]">
          <h3 className="text-lg font-semibold mb-4">النشاط الأخير</h3>
          <p className="text-[var(--muted-foreground)] text-sm">
            {loading ? "جاري التحميل..." : "سيتم عرض الرسم البياني للنشاط هنا"}
          </p>
        </div>
        <div className="p-6 rounded-xl bg-[var(--card)] border border-[var(--border)]">
          <h3 className="text-lg font-semibold mb-4">التقارير المفتوحة</h3>
          <p className="text-[var(--muted-foreground)] text-sm">
            {loading ? "جاري التحميل..." : "سيتم عرض التقارير الأخيرة هنا"}
          </p>
        </div>
      </div>
    </div>
  );
}
