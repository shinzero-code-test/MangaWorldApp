"use client";

import { useEffect, useState } from "react";
import { usePathname } from "next/navigation";
import Link from "next/link";
import {
  Menu, Bell, Sun, Moon, ChevronLeft,
  User, LogOut, Settings2
} from "lucide-react";
import { useTheme } from "@/components/providers/theme-provider";
import { useRouter } from "next/navigation";

const routeLabels: Record<string, string> = {
  "/dashboard": "نظرة عامة",
  "/dashboard/users": "المستخدمون",
  "/dashboard/moderation": "الإشراف",
  "/dashboard/community/comments": "التعليقات",
  "/dashboard/community/reviews": "المراجعات",
  "/dashboard/community/chat": "المحادثات",
  "/dashboard/community/lists": "القوائم",
  "/dashboard/analytics": "التحليلات",
  "/dashboard/performance": "الأداء",
  "/dashboard/crashlytics": "الأعطال",
  "/dashboard/achievements": "الإنجازات",
  "/dashboard/remote-config": "Remote Config",
  "/dashboard/notifications": "الإشعارات",
  "/dashboard/data": "متصفح البيانات",
  "/dashboard/storage": "التخزين",
  "/dashboard/settings": "الإعدادات",
  "/dashboard/releases": "الإصدارات",
};

interface HeaderProps {
  onToggleSidebar?: () => void;
  userEmail?: string;
  userRole?: string;
}

export function Header({ onToggleSidebar, userEmail, userRole }: HeaderProps) {
  const pathname = usePathname();
  const router = useRouter();
  const { theme, toggleTheme } = useTheme();
  const [time, setTime] = useState("");
  const [userMenuOpen, setUserMenuOpen] = useState(false);

  useEffect(() => {
    const update = () => {
      setTime(
        new Date().toLocaleTimeString("ar-SA", {
          hour: "2-digit",
          minute: "2-digit",
        })
      );
    };
    update();
    const timer = setInterval(update, 60_000);
    return () => clearInterval(timer);
  }, []);

  const pageLabel = routeLabels[pathname] || "لوحة التحكم";

  const handleLogout = async () => {
    await fetch("/api/auth/login", { method: "DELETE" }).catch(() => {});
    router.push("/login");
  };

  return (
    <header
      className="sticky top-0 z-30 h-14 flex items-center gap-4 px-4 md:px-6 border-b"
      style={{
        background: "var(--background)",
        borderColor: "var(--border)",
      }}
    >
      {/* Hamburger */}
      <button
        onClick={onToggleSidebar}
        className="p-2 rounded-lg transition hover:bg-[var(--accent)] lg:hidden"
        aria-label="فتح القائمة"
      >
        <Menu size={18} />
      </button>

      {/* Page title */}
      <div className="flex items-center gap-2 flex-1">
        <span
          className="text-sm font-semibold"
          style={{ color: "var(--foreground)" }}
        >
          {pageLabel}
        </span>
      </div>

      {/* Right actions */}
      <div className="flex items-center gap-2">
        {/* Clock */}
        <span
          className="hidden md:block text-sm font-mono tabular-nums"
          style={{ color: "var(--muted-foreground)" }}
        >
          {time}
        </span>

        {/* Notifications */}
        <Link
          href="/dashboard/notifications"
          className="relative p-2 rounded-lg transition hover:bg-[var(--accent)]"
          aria-label="الإشعارات"
        >
          <Bell size={18} />
        </Link>

        {/* Theme toggle */}
        <button
          onClick={toggleTheme}
          className="p-2 rounded-lg transition hover:bg-[var(--accent)]"
          aria-label="تبديل المظهر"
        >
          {theme === "dark" ? <Sun size={18} /> : <Moon size={18} />}
        </button>

        {/* User menu */}
        <div className="relative">
          <button
            onClick={() => setUserMenuOpen(!userMenuOpen)}
            className="flex items-center gap-2 p-1.5 rounded-lg transition hover:bg-[var(--accent)]"
            aria-label="قائمة المستخدم"
          >
            <div
              className="w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold"
              style={{ background: "var(--primary)", color: "var(--primary-foreground)" }}
            >
              {userEmail?.[0]?.toUpperCase() ?? "A"}
            </div>
          </button>

          {userMenuOpen && (
            <>
              <div
                className="fixed inset-0 z-40"
                onClick={() => setUserMenuOpen(false)}
              />
              <div
                className="absolute end-0 top-full mt-2 w-56 rounded-xl border shadow-xl z-50 py-1"
                style={{
                  background: "var(--card)",
                  borderColor: "var(--border)",
                }}
              >
                <div className="px-3 py-2.5 border-b" style={{ borderColor: "var(--border)" }}>
                  <p className="text-sm font-medium truncate">{userEmail}</p>
                  <p className="text-xs mt-0.5" style={{ color: "var(--muted-foreground)" }}>
                    {userRole === "super-admin" ? "مدير عام" : userRole === "moderator" ? "مشرف" : "مشاهد"}
                  </p>
                </div>
                <button
                  onClick={() => { setUserMenuOpen(false); handleLogout(); }}
                  className="w-full flex items-center gap-2.5 px-3 py-2.5 text-sm transition hover:bg-[var(--accent)] text-red-400"
                >
                  <LogOut size={14} />
                  تسجيل الخروج
                </button>
              </div>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
