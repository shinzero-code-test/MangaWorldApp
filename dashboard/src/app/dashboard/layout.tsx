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
  const [sidebarOpen, setSidebarOpen] = useState(false);
  const [sidebarCollapsed, setSidebarCollapsed] = useState(false);
  const router = useRouter();

  useEffect(() => {
    fetch("/api/auth/me")
      .then((res) => {
        if (!res.ok) throw new Error("Unauthorized");
        return res.json();
      })
      .then((data) => {
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
