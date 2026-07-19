"use client";

import { useEffect, useState } from "react";
import { useRouter } from "next/navigation";
import { Sidebar } from "@/components/layout/sidebar";
import { Header } from "@/components/layout/header";
import { ErrorBoundary } from "@/components/shared/error-boundary";
import { ThemeProvider } from "@/components/providers/theme-provider";
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
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const router = useRouter();

  useEffect(() => {
    fetch("/api/auth/me")
      .then((res) => {
        if (res.status === 403) {
          setAccessDenied(true);
          setLoading(false);
          return null;
        }
        if (!res.ok) throw new Error("Unauthorized");
        return res.json();
      })
      .then(async (data) => {
        if (!data) return;
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
        setUser(data);
        setLoading(false);
      })
      .catch(() => {
        router.push("/login");
        setLoading(false);
      });
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

  if (!user) return null;

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
              className="w-16 h-16 rounded-full flex items-center justify-center text-3xl"
              style={{ background: "rgba(239,68,68,0.15)" }}
            >
              🚫
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

        {/* Sidebar */}
        <div
          className={`fixed end-0 top-0 z-50 h-screen lg:sticky transition-transform duration-300 ${
            sidebarOpen ? "translate-x-0" : "translate-x-full lg:translate-x-0"
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
