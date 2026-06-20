"use client";

import { usePathname } from "next/navigation";
import Link from "next/link";
import { cn } from "@/lib/utils";
import { useTheme } from "@/components/providers/theme-provider";

interface NavItem {
  href: string;
  label: string;
  icon: string;
  minRole?: string;
}

const navItems: NavItem[] = [
  { href: "/dashboard", label: "نظرة عامة", icon: "📊" },
  { href: "/dashboard/users", label: "المستخدمون", icon: "👥", minRole: "moderator" },
  { href: "/dashboard/moderation", label: "الإشراف", icon: "🛡️", minRole: "moderator" },
  { href: "/dashboard/community/comments", label: "المجتمع", icon: "💬", minRole: "moderator" },
  { href: "/dashboard/data", label: "متصفح البيانات", icon: "🗄️", minRole: "super-admin" },
  { href: "/dashboard/remote-config", label: "Remote Config", icon: "⚙️", minRole: "super-admin" },
  { href: "/dashboard/analytics", label: "التحليلات", icon: "📈" },
  { href: "/dashboard/performance", label: "الأداء", icon: "⚡", minRole: "super-admin" },
  { href: "/dashboard/crashlytics", label: "الأعطال", icon: "🐛", minRole: "super-admin" },
  { href: "/dashboard/notifications", label: "الإشعارات", icon: "🔔", minRole: "super-admin" },
  { href: "/dashboard/storage", label: "التخزين", icon: "💾", minRole: "super-admin" },
  { href: "/dashboard/settings", label: "إعدادات التطبيق", icon: "📱", minRole: "super-admin" },
  { href: "/dashboard/achievements", label: "الإنجازات", icon: "🏆" },
  { href: "/dashboard/releases", label: "الإصدارات", icon: "📦", minRole: "super-admin" },
];

const ROLE_HIERARCHY: Record<string, number> = {
  viewer: 0,
  moderator: 1,
  "super-admin": 2,
};

export function Sidebar({ userRole }: { userRole: string }) {
  const pathname = usePathname();
  const { theme, toggleTheme } = useTheme();

  const hasAccess = (minRole?: string) => {
    if (!minRole) return true;
    return (ROLE_HIERARCHY[userRole] || 0) >= (ROLE_HIERARCHY[minRole] || 0);
  };

  return (
    <aside className="w-64 h-screen bg-[var(--card)] border-l border-[var(--border)] flex flex-col overflow-y-auto">
      {/* Logo */}
      <div className="p-5 border-b border-[var(--border)]">
        <Link href="/dashboard" className="block">
          <h1 className="text-xl font-bold text-[var(--primary)]">MangaWorld</h1>
          <p className="text-xs text-[var(--muted-foreground)] mt-0.5">لوحة التحكم</p>
        </Link>
      </div>

      {/* Navigation */}
      <nav className="flex-1 p-3 space-y-0.5 overflow-y-auto">
        {navItems
          .filter((item) => hasAccess(item.minRole))
          .map((item) => {
            const isActive =
              pathname === item.href ||
              (item.href !== "/dashboard" && pathname.startsWith(item.href));

            return (
              <Link
                key={item.href}
                href={item.href}
                className={cn(
                  "flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-all",
                  isActive
                    ? "bg-[var(--primary)]/10 text-[var(--primary)] font-medium"
                    : "text-[var(--muted-foreground)] hover:bg-[var(--accent)] hover:text-[var(--foreground)]"
                )}
              >
                <span className="text-lg w-6 text-center">{item.icon}</span>
                <span className="flex-1">{item.label}</span>
                {isActive && (
                  <span className="w-1.5 h-1.5 rounded-full bg-[var(--primary)]" />
                )}
              </Link>
            );
          })}
      </nav>

      {/* User Info */}
      <div className="p-4 border-t border-[var(--border)]">
        <div className="flex items-center gap-3">
          <div className="w-9 h-9 rounded-full bg-[var(--primary)]/20 flex items-center justify-center text-sm font-bold text-[var(--primary)]">
            {userRole === "super-admin" ? "A" : userRole === "moderator" ? "M" : "V"}
          </div>
          <div className="flex-1 min-w-0">
            <p className="text-sm font-medium truncate">
              {userRole === "super-admin" ? "مدير عام" : userRole === "moderator" ? "مشرف" : "مشاهد"}
            </p>
            <p className="text-xs text-[var(--muted-foreground)]">{userRole}</p>
          </div>
          <button onClick={toggleTheme}
            className="p-1.5 rounded-lg text-[var(--muted-foreground)] hover:bg-[var(--accent)] transition"
            title={theme === "dark" ? "الوضع الفاتح" : "الوضع الداكن"}>
            {theme === "dark" ? "☀️" : "🌙"}
          </button>
          <button
            onClick={async () => {
              document.cookie = "session=; path=/; expires=Thu, 01 Jan 1970 00:00:00 GMT";
              window.location.href = "/login";
            }}
            className="p-1.5 rounded-lg text-[var(--muted-foreground)] hover:bg-[var(--accent)] hover:text-[var(--destructive)] transition"
            title="تسجيل الخروج"
          >
            <svg className="w-4 h-4" fill="none" stroke="currentColor" viewBox="0 0 24 24">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M17 16l4-4m0 0l-4-4m4 4H7m6 4v1a3 3 0 01-3 3H6a3 3 0 01-3-3V7a3 3 0 013-3h4a3 3 0 013 3v1" />
            </svg>
          </button>
        </div>
      </div>
    </aside>
  );
}
