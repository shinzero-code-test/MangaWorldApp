"use client";

import { usePathname } from "next/navigation";

const pageTitles: Record<string, string> = {
  "/dashboard": "نظرة عامة",
  "/dashboard/users": "المستخدمون",
  "/dashboard/moderation": "الإشراف",
  "/dashboard/moderation/banned-keywords": "الكلمات المحظورة",
  "/dashboard/community/comments": "التعليقات",
  "/dashboard/community/reviews": "المراجعات",
  "/dashboard/community/chat": "المحادثات",
  "/dashboard/community/lists": "القوائم",
  "/dashboard/remote-config": "الإعدادات",
  "/dashboard/remote-config/sources": "المصادر",
  "/dashboard/analytics": "التحليلات",
  "/dashboard/analytics/events": "الأحداث",
  "/dashboard/analytics/engagement": "التفاعل",
  "/dashboard/crashlytics": "الأعطال",
  "/dashboard/notifications": "إرسال إشعار",
  "/dashboard/notifications/history": "سجل الإشعارات",
  "/dashboard/settings": "إعدادات التطبيق",
  "/dashboard/achievements": "الإنجازات",
  "/dashboard/releases": "الإصدارات",
};

export function Header() {
  const pathname = usePathname();
  const title = pageTitles[pathname] || "لوحة التحكم";

  return (
    <header className="h-16 border-b border-[var(--border)] bg-[var(--card)] flex items-center justify-between px-6">
      <h2 className="text-lg font-semibold text-[var(--foreground)]">{title}</h2>
      <div className="flex items-center gap-4">
        <span className="text-xs text-[var(--muted-foreground)]">
          {new Date().toLocaleDateString("ar-SA", { weekday: "long", year: "numeric", month: "long", day: "numeric" })}
        </span>
      </div>
    </header>
  );
}
