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
  "/dashboard/remote-config": "الإعدادات遥远",
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

export function Header({ onToggleSidebar }: { onToggleSidebar?: () => void }) {
  const pathname = usePathname();
  const title = pageTitles[pathname] || "لوحة التحكم";

  return (
    <header className="h-16 border-b border-[var(--border)] bg-[var(--card)] flex items-center justify-between px-4 md:px-6 shrink-0">
      <div className="flex items-center gap-3">
        <button
          onClick={onToggleSidebar}
          className="lg:hidden p-2 rounded-lg hover:bg-[var(--accent)] transition"
          aria-label="Toggle sidebar"
        >
          <svg className="w-5 h-5" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M4 6h16M4 12h16M4 18h16" />
          </svg>
        </button>
        <h2 className="text-lg font-semibold text-[var(--foreground)]">{title}</h2>
      </div>
      <div className="flex items-center gap-4">
        <span className="text-xs text-[var(--muted-foreground)] hidden md:block">
          {new Date().toLocaleDateString("ar-SA", {
            weekday: "long",
            year: "numeric",
            month: "long",
            day: "numeric",
          })}
        </span>
      </div>
    </header>
  );
}
