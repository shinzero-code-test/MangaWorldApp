"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

interface KPIData {
  totalUsers: number;
  totalComments: number;
  totalReviews: number;
  openReports: number;
}

interface ActivityItem {
  id: string;
  type: string;
  text: string;
  time: string;
}

export default function DashboardOverview() {
  const [kpis, setKpis] = useState<KPIData>({ totalUsers: 0, totalComments: 0, totalReviews: 0, openReports: 0 });
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/analytics/summary")
      .then((r) => r.json())
      .then((data) => {
        setKpis({
          totalUsers: data?.overview?.totalUsers ?? data?.totalUsers ?? 0,
          totalComments: data?.overview?.totalComments ?? data?.totalComments ?? 0,
          totalReviews: data?.overview?.totalReviews ?? data?.totalReviews ?? 0,
          openReports: data?.overview?.openReports ?? data?.openReports ?? 0,
        });
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const cards = [
    { label: "إجمالي المستخدمين", value: kpis.totalUsers, icon: "👥", color: "from-blue-500/20 to-blue-600/5", href: "/dashboard/users" },
    { label: "التعليقات", value: kpis.totalComments, icon: "💬", color: "from-green-500/20 to-green-600/5", href: "/dashboard/community/comments" },
    { label: "المراجعات", value: kpis.totalReviews, icon: "⭐", color: "from-yellow-500/20 to-yellow-600/5", href: "/dashboard/community/reviews" },
    { label: "تقارير مفتوحة", value: kpis.openReports, icon: "🛡️", color: "from-red-500/20 to-red-600/5", href: "/dashboard/moderation" },
  ];

  const quickActions = [
    { label: "المستخدمون", icon: "👥", href: "/dashboard/users", color: "bg-blue-500/10 text-blue-500" },
    { label: "الإشراف", icon: "🛡️", href: "/dashboard/moderation", color: "bg-yellow-500/10 text-yellow-500" },
    { label: "الإعدادات", icon: "⚙️", href: "/dashboard/remote-config", color: "bg-purple-500/10 text-purple-500" },
    { label: "التحليلات", icon: "📊", href: "/dashboard/analytics", color: "bg-green-500/10 text-green-500" },
    { label: "الإشعارات", icon: "🔔", href: "/dashboard/notifications", color: "bg-red-500/10 text-red-500" },
    { label: "الإنجازات", icon: "🏆", href: "/dashboard/achievements", color: "bg-orange-500/10 text-orange-500" },
  ];

  return (
    <div className="space-y-6">
      {/* KPI Cards */}
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {cards.map((card) => (
          <Link
            key={card.label}
            href={card.href}
            className={`p-5 rounded-xl bg-gradient-to-br ${card.color} border border-[var(--border)] hover:shadow-lg transition-all group`}
          >
            <div className="flex items-center justify-between mb-3">
              <span className="text-2xl group-hover:scale-110 transition-transform">{card.icon}</span>
            </div>
            <p className="text-sm text-[var(--muted-foreground)]">{card.label}</p>
            <p className={`text-3xl font-bold mt-1 ${loading ? "animate-pulse" : ""}`}>
              {loading ? "—" : (card.value || 0).toLocaleString("ar-SA")}
            </p>
          </Link>
        ))}
      </div>

      {/* Quick Actions Grid */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <h3 className="text-lg font-semibold mb-4">الوصول السريع</h3>
        <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3">
          {quickActions.map((action) => (
            <Link
              key={action.href}
              href={action.href}
              className="flex flex-col items-center gap-2 p-4 rounded-xl bg-[var(--background)] hover:bg-[var(--accent)] transition-colors group"
            >
              <span className={`w-12 h-12 rounded-xl ${action.color} flex items-center justify-center text-xl group-hover:scale-110 transition-transform`}>
                {action.icon}
              </span>
              <span className="text-sm font-medium text-[var(--foreground)]">{action.label}</span>
            </Link>
          ))}
        </div>
      </div>

      {/* Two Column Layout */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* System Status */}
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
          <h3 className="text-lg font-semibold mb-4">حالة النظام</h3>
          <div className="space-y-3">
            {[
              { name: "Firebase Auth", status: "نشط", ok: true },
              { name: "Firestore", status: "نشط", ok: true },
              { name: "Remote Config", status: "نشط", ok: true },
              { name: "FCM", status: "نشط", ok: true },
              { name: "Crashlytics", status: "يتطلب إعداد", ok: false },
            ].map((item) => (
              <div key={item.name} className="flex items-center justify-between py-2 border-b border-[var(--border)] last:border-0">
                <span className="text-sm">{item.name}</span>
                <span className={`text-xs font-medium px-2 py-0.5 rounded-full ${item.ok ? "bg-green-100 text-green-700" : "bg-yellow-100 text-yellow-700"}`}>
                  {item.status}
                </span>
              </div>
            ))}
          </div>
        </div>

        {/* Recent Activity */}
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
          <h3 className="text-lg font-semibold mb-4">النشاط الأخير</h3>
          <div className="space-y-3">
            {[
              { text: "تم إضافة مستخدم جديد", time: "منذ 5 دقائق", icon: "👤" },
              { text: "تم حل تقرير إشرافي", time: "منذ 15 دقيقة", icon: "✅" },
              { text: "تم تحديث Remote Config", time: "منذ ساعة", icon: "⚙️" },
              { text: "تم إرسال إشعار", time: "منذ ساعتين", icon: "🔔" },
            ].map((activity, i) => (
              <div key={i} className="flex items-center gap-3 py-2 border-b border-[var(--border)] last:border-0">
                <span className="text-lg">{activity.icon}</span>
                <div className="flex-1">
                  <p className="text-sm">{activity.text}</p>
                  <p className="text-xs text-[var(--muted-foreground)]">{activity.time}</p>
                </div>
              </div>
            ))}
          </div>
        </div>
      </div>
    </div>
  );
}
