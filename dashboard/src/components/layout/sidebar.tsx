"use client";

import { usePathname } from "next/navigation";
import Link from "next/link";
import { cn } from "@/lib/utils";

const navItems = [
  { href: "/dashboard", label: "لوحة التحكم", icon: "📊" },
  { href: "/dashboard/users", label: "المستخدمون", icon: "👥", minRole: "moderator" },
  { href: "/dashboard/moderation", label: "الإشراف", icon: "🛡️", minRole: "moderator" },
  { href: "/dashboard/community/comments", label: "المجتمع", icon: "💬", minRole: "moderator" },
  { href: "/dashboard/remote-config", label: "الإعدادات遥远", icon: "⚙️", minRole: "super-admin" },
  { href: "/dashboard/analytics", label: "التحليلات", icon: "📈" },
  { href: "/dashboard/crashlytics", label: "الأعطال", icon: "🐛", minRole: "super-admin" },
  { href: "/dashboard/notifications", label: "الإشعارات", icon: "🔔", minRole: "super-admin" },
  { href: "/dashboard/settings", label: "إعدادات التطبيق", icon: "📱", minRole: "super-admin" },
  { href: "/dashboard/achievements", label: "الإنجازات", icon: "🏆" },
  { href: "/dashboard/releases", label: "الإصدارات", icon: "📦", minRole: "super-admin" },
];

const subItems: Record<string, { href: string; label: string }[]> = {
  "/dashboard/moderation": [
    { href: "/dashboard/moderation", label: "التقارير" },
    { href: "/dashboard/moderation/banned-keywords", label: "الكلمات المحظورة" },
  ],
  "/dashboard/community/comments": [
    { href: "/dashboard/community/comments", label: "التعليقات" },
    { href: "/dashboard/community/reviews", label: "المراجعات" },
    { href: "/dashboard/community/chat", label: "المحادثات" },
    { href: "/dashboard/community/lists", label: "القوائم" },
  ],
  "/dashboard/remote-config": [
    { href: "/dashboard/remote-config", label: "الإعدادات" },
    { href: "/dashboard/remote-config/sources", label: "المصادر" },
  ],
  "/dashboard/analytics": [
    { href: "/dashboard/analytics", label: "نظرة عامة" },
    { href: "/dashboard/analytics/events", label: "الأحداث" },
    { href: "/dashboard/analytics/engagement", label: "التفاعل" },
  ],
  "/dashboard/notifications": [
    { href: "/dashboard/notifications", label: "إرسال" },
    { href: "/dashboard/notifications/history", label: "السجل" },
  ],
};

export function Sidebar({ userRole }: { userRole: string }) {
  const pathname = usePathname();

  const hierarchy: Record<string, number> = {
    viewer: 0,
    moderator: 1,
    "super-admin": 2,
  };

  const hasAccess = (minRole?: string) => {
    if (!minRole) return true;
    return (hierarchy[userRole] || 0) >= (hierarchy[minRole] || 0);
  };

  return (
    <aside className="w-64 h-screen bg-[var(--card)] border-l border-[var(--border)] flex flex-col overflow-y-auto">
      <div className="p-6 border-b border-[var(--border)]">
        <h1 className="text-xl font-bold text-[var(--primary)]">MangaWorld</h1>
        <p className="text-xs text-[var(--muted-foreground)] mt-1">لوحة التحكم</p>
      </div>

      <nav className="flex-1 p-3 space-y-1">
        {navItems
          .filter((item) => hasAccess(item.minRole))
          .map((item) => {
            const isActive =
              pathname === item.href ||
              (item.href !== "/dashboard" && pathname.startsWith(item.href));
            const subs = subItems[item.href];

            return (
              <div key={item.href}>
                <Link
                  href={item.href}
                  className={cn(
                    "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-colors",
                    isActive
                      ? "bg-[var(--primary)]/10 text-[var(--primary)] font-medium"
                      : "text-[var(--muted-foreground)] hover:bg-[var(--accent)] hover:text-[var(--foreground)]"
                  )}
                >
                  <span className="text-lg">{item.icon}</span>
                  <span>{item.label}</span>
                </Link>
                {subs && isActive && (
                  <div className="mr-10 mt-1 space-y-0.5">
                    {subs.map((sub) => (
                      <Link
                        key={sub.href}
                        href={sub.href}
                        className={cn(
                          "block px-3 py-1.5 rounded-md text-xs transition-colors",
                          pathname === sub.href
                            ? "bg-[var(--primary)]/5 text-[var(--primary)]"
                            : "text-[var(--muted-foreground)] hover:text-[var(--foreground)]"
                        )}
                      >
                        {sub.label}
                      </Link>
                    ))}
                  </div>
                )}
              </div>
            );
          })}
      </nav>

      <div className="p-4 border-t border-[var(--border)]">
        <div className="flex items-center gap-3">
          <div className="w-8 h-8 rounded-full bg-[var(--primary)]/20 flex items-center justify-center text-sm font-medium text-[var(--primary)]">
            {userRole === "super-admin" ? "A" : userRole === "moderator" ? "M" : "V"}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-xs text-[var(--muted-foreground)] truncate">{userRole}</p>
          </div>
          <button
            onClick={async () => {
              await fetch("/api/auth/login", { method: "DELETE" });
              window.location.href = "/login";
            }}
            className="text-xs text-[var(--muted-foreground)] hover:text-[var(--destructive)]"
          >
            خروج
          </button>
        </div>
      </div>
    </aside>
  );
}
