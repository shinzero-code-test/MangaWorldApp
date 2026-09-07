"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Sidebar } from "@/components/layout/sidebar";
import { Header } from "@/components/layout/header";
import { ErrorBoundary } from "@/components/shared/error-boundary";
import { ThemeProvider } from "@/components/providers/theme-provider";
import { Ban } from "lucide-react";
import { Spinner } from "@/components/ui";

interface UserInfo {
  uid: string;
  email: string;
  role: string;
}

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const [user, setUser] = useState<UserInfo | null>(null);
  const [loading, setLoading] = useState(true);
  const [accessDenied, setAccessDenied] = useState(false);
  const [authError, setAuthError] = useState(false);
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const router = useRouter();

  useEffect(() => {
    let cancelled = false;
    const loadMe = async () => {
      try {
        const res = await fetch("/api/auth/me");
        if (cancelled) return;
        if (res.status === 403) {
          setAccessDenied(true);
          setLoading(false);
          return;
        }
        if (res.status === 401) {
          router.push("/login");
          return;
        }
        if (!res.ok) throw new Error(`me:${res.status}`);
        const data = await res.json();
        // Check 2FA status
        try {
          const tfaRes = await fetch("/api/auth/2fa/status");
          if (tfaRes.ok) {
            const tfa = await tfaRes.json();
            if (tfa.needsSetup || tfa.needsValidation) {
              router.push("/2fa");
              return;
            }
          }
        } catch { /* proceed if 2FA check fails */ }
        if (cancelled) return;
        setUser(data);
        setAuthError(false);
        setLoading(false);
      } catch {
        // Network blip: retryable error state, not a blind login push.
        if (!cancelled) {
          setAuthError(true);
          setLoading(false);
        }
      }
    };
    loadMe();
    return () => { cancelled = true; };
  }, [router]);

  if (loading) {
    return (
      <ThemeProvider>
        <div
          className="min-h-screen flex items-center justify-center"
          style={{ background: "var(--background)" }}
        >
          <div className="flex flex-col items-center gap-3">
            <div
              className="w-12 h-12 rounded-2xl flex items-center justify-center"
              style={{ background: "var(--accent)" }}
            >
              <Spinner size={22} />
            </div>
            <p className="text-sm" style={{ color: "var(--muted-foreground)" }}>
              جاري التحميل...
            </p>
          </div>
        </div>
      </ThemeProvider>
    );
  }

  if (authError && !user) {
    return (
      <ThemeProvider>
        <div
          className="min-h-screen flex items-center justify-center"
          style={{ background: "var(--background)" }}
        >
          <div className="flex flex-col items-center gap-3">
            <p className="text-sm" style={{ color: "var(--muted-foreground)" }}>
              تعذر الاتصال بالخادم
            </p>
            <button
              onClick={() => { setAuthError(false); setLoading(true); window.location.reload(); }}
              className="px-4 py-2 rounded-lg text-sm font-semibold"
              style={{ background: "var(--accent)", color: "var(--accent-foreground)" }}
            >
              إعادة المحاولة
            </button>
          </div>
        </div>
      </ThemeProvider>
    );
  }

  // Denied-before-null: on 403 `user` stays null, so this branch must win.
  if (accessDenied) {
    return (
      <ThemeProvider>
        <div
          className="min-h-screen flex items-center justify-center"
          style={{ background: "var(--background)" }}
        >
          <div
            className="flex flex-col items-center gap-4 p-8 rounded-2xl text-center max-w-md"
            style={{ background: "var(--card)", border: "1px solid var(--border)" }}
          >
            <div
              className="w-16 h-16 rounded-full flex items-center justify-center"
              style={{ background: "rgba(239,68,68,0.15)", color: "#ef4444" }}
            >
              <Ban size={30} />
            </div>
            <h1
              className="text-xl font-bold"
              style={{ color: "var(--foreground)" }}
            >
              صلاحية مرفوضة
            </h1>
            <p
              className="text-sm leading-relaxed"
              style={{ color: "var(--muted-foreground)" }}
            >
              ليس لديك صلاحية الوصول إلى لوحة التحكم. هذه اللوحة مخصصة للمشرفين والمديرين فقط.
              <br />
              إذا كنت تعتقد أن هذا خطأ، تواصل مع مدير النظام.
            </p>
            <button
              onClick={() => {
                // Sign out and redirect to login
                fetch("/api/auth/login", { method: "DELETE" }).catch(() => {});
                router.push("/login");
              }}
              className="px-6 py-2.5 rounded-lg text-sm font-medium transition-all"
              style={{ background: "var(--accent)", color: "var(--accent-foreground)" }}
            >
              تسجيل الخروج
            </button>
          </div>
        </div>
      </ThemeProvider>
    );
  }

  if (!user) return null;

  return (
    <ThemeProvider>
      <div
        className="flex min-h-screen"
        dir="rtl"
        style={{ background: "var(--background)" }}
      >
        {/* Mobile overlay */}
        {sidebarOpen && (
          <div
            className="fixed inset-0 bg-black/60 z-40 lg:hidden backdrop-blur-sm"
            onClick={() => setSidebarOpen(false)}
          />
        )}

        {/* Sidebar — note: physical -translate-x-full is correct here because the
            panel is end-0 in an always-RTL shell. If dir ever flips to LTR,
            switch to logical rtl:/ltr: translate variants. */}
        <div
          className={`fixed end-0 top-0 z-50 h-screen lg:sticky transition-transform duration-300 ${
            sidebarOpen ? "translate-x-0" : "-translate-x-full lg:translate-x-0"
          }`}
        >
          <Sidebar
            userRole={user.role}
            userEmail={user.email}
            collapsed={sidebarCollapsed}
            onToggleCollapse={() => setSidebarCollapsed(!sidebarCollapsed)}
            onNavItemClick={() => setSidebarOpen(false)}
          />
        </div>

        {/* Main */}
        <div className="flex-1 flex flex-col min-w-0 overflow-hidden">
          <Header
            onToggleSidebar={() => setSidebarOpen(!sidebarOpen)}
            userEmail={user.email}
            userRole={user.role}
          />
          <main className="flex-1 p-4 md:p-6 overflow-auto">
            <ErrorBoundary>
              <div className="page-enter">{children}</div>
            </ErrorBoundary>
          </main>
        </div>
      </div>
    </ThemeProvider>
  );
}
