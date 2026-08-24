"use client";

import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import {
  BarChart3, Users, Shield, MessageSquare, TrendingUp,
  Zap, Bug, Trophy, Settings2, Bell, Database,
  HardDrive, Smartphone, Package, ShieldAlert, Star,
  LogOut, Sun, Moon, PanelLeftOpen
} from "lucide-react";
import { useTheme } from "@/components/providers/theme-provider";
import type { LucideIcon } from "lucide-react";

interface NavItem {
  href:    string;
  label:   string;
  icon:    LucideIcon;
  minRole?:string;
}

interface NavGroup {
  label: string;
  items: NavItem[];
}

const navGroups: NavGroup[] = [
  {
    label:"عام",
    items:[ { href:"/dashboard", label:"نظرة عامة", icon:BarChart3 } ],
  },
  {
    label:"إدارة المستخدمين",
    items:[
      { href:"/dashboard/users",              label:"المستخدمون", icon:Users,         minRole:"moderator" },
      { href:"/dashboard/moderation",         label:"الإشراف",    icon:Shield,        minRole:"moderator" },
      { href:"/dashboard/community/comments", label:"المجتمع",    icon:MessageSquare, minRole:"moderator" },
      { href:"/dashboard/community/reviews",  label:"المراجعات",  icon:Star,          minRole:"moderator" },
      { href:"/dashboard/moderation/banned-keywords", label:"الكلمات المحظورة", icon:ShieldAlert, minRole:"moderator" },
    ],
  },
  {
    label:"تحليلات",
    items:[
      { href:"/dashboard/analytics",   label:"التحليلات", icon:TrendingUp },
      { href:"/dashboard/analytics/engagement", label:"التفاعل", icon:TrendingUp },
      { href:"/dashboard/analytics/events",     label:"الأحداث",  icon:TrendingUp, minRole:"super-admin" },
      { href:"/dashboard/performance", label:"الأداء",    icon:Zap,       minRole:"super-admin" },
      { href:"/dashboard/crashlytics", label:"الأعطال",   icon:Bug,       minRole:"super-admin" },
      { href:"/dashboard/achievements",label:"الإنجازات", icon:Trophy },
    ],
  },
  {
    label:"النظام",
    items:[
      { href:"/dashboard/remote-config",  label:"Remote Config",    icon:Settings2, minRole:"super-admin" },
      { href:"/dashboard/notifications",  label:"الإشعارات",        icon:Bell,      minRole:"super-admin" },
      { href:"/dashboard/data",           label:"متصفح البيانات",   icon:Database,  minRole:"super-admin" },
      { href:"/dashboard/storage",        label:"التخزين",          icon:HardDrive, minRole:"super-admin" },
      { href:"/dashboard/settings",       label:"إعدادات التطبيق",  icon:Smartphone,minRole:"super-admin" },
      { href:"/dashboard/releases",       label:"الإصدارات",        icon:Package,   minRole:"super-admin" },
    ],
  },
];

const ROLE_RANK: Record<string,number> = { viewer:0, moderator:1, "super-admin":2 };
const canAccess = (userRole:string, minRole?:string) =>
  !minRole || (ROLE_RANK[userRole]??0) >= (ROLE_RANK[minRole]??0);

interface SidebarProps {
  userRole:   string;
  userEmail?: string;
  collapsed?: boolean;
  onToggleCollapse?: () => void;
  onNavItemClick?: () => void;
}

export function Sidebar({ userRole, userEmail, collapsed=false, onToggleCollapse, onNavItemClick }: SidebarProps) {
  const pathname = usePathname();
  const router   = useRouter();
  const { theme, toggleTheme } = useTheme();

  const handleLogout = async () => {
    await fetch("/api/auth/login", { method:"DELETE" }).catch(()=>{});
    router.push("/login");
  };

  const isActive = (href:string) =>
    href === "/dashboard" ? pathname === "/dashboard" : pathname.startsWith(href);

  return (
    <aside
      className={`flex flex-col h-screen transition-all duration-300 border-s border-white/5 ${collapsed?"w-[60px]":"w-[240px]"}`}
      style={{ background:"var(--sidebar-bg)" }}
    >
      {/* Logo */}
      <div className="flex items-center justify-between px-4 py-5 border-b border-white/5">
        {!collapsed && (
          <div className="flex items-center gap-2.5">
            <div className="w-8 h-8 rounded-lg flex items-center justify-center"
              style={{ background:"var(--sidebar-active)" }}>
              <img src="/logo.png" alt="Logo" className="w-4 h-4 object-contain" />
            </div>
            <div>
              <p className="text-sm font-bold text-white leading-none">MangaWorld</p>
              <p className="text-[10px] mt-0.5" style={{ color:"var(--sidebar-text)" }}>لوحة التحكم</p>
            </div>
          </div>
        )}
        {collapsed && (
          <div className="w-8 h-8 rounded-lg flex items-center justify-center mx-auto"
            style={{ background:"var(--sidebar-active)" }}>
            <img src="/logo.png" alt="Logo" className="w-4 h-4 object-contain" />
          </div>
        )}
        {!collapsed && onToggleCollapse && (
          <button onClick={onToggleCollapse}
            className="p-1.5 rounded-lg transition hover:bg-white/10"
            style={{ color:"var(--sidebar-text)" }}
            aria-label="طي الشريط الجانبي">
            <PanelLeftOpen size={16} />
          </button>
        )}
      </div>

      {/* Nav */}
      <nav className="flex-1 overflow-y-auto py-3 px-2 space-y-1">
        {navGroups.map(group => {
          const visibleItems = group.items.filter(item => canAccess(userRole, item.minRole));
          if (!visibleItems.length) return null;
          return (
            <div key={group.label} className="mb-1">
              {!collapsed && (
                <p className="text-[10px] font-semibold tracking-wider uppercase px-3 py-2"
                  style={{ color:"var(--sidebar-text)", opacity:0.5 }}>
                  {group.label}
                </p>
              )}
              {visibleItems.map(item => {
                const active = isActive(item.href);
                const Icon   = item.icon;
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    onClick={onNavItemClick}
                    className={`sidebar-item flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm font-medium transition-all duration-150 group relative ${collapsed?"justify-center":""}`}
                    style={{
                      background: active
                        ? "linear-gradient(90deg,color-mix(in srgb,var(--sidebar-active) 20%,transparent),transparent)"
                        : undefined,
                      color: active ? "white" : "var(--sidebar-text)",
                    }}
                    title={collapsed ? item.label : undefined}
                  >
                    {/* active indicator */}
                    {active && !collapsed && (
                      <span className="absolute start-0 top-1/2 -translate-y-1/2 h-5 w-0.5 rounded-full"
                        style={{ background:"var(--sidebar-active)" }} />
                    )}
                    <Icon
                      size={16}
                      className="shrink-0"
                      color={active ? "var(--sidebar-active)" : undefined}
                    />
                    {!collapsed && <span className="truncate">{item.label}</span>}
                    {collapsed && (
                      <span className="absolute start-full ms-2 px-2 py-1 bg-gray-900 text-white text-xs rounded-md opacity-0 group-hover:opacity-100 pointer-events-none whitespace-nowrap transition-opacity z-50">
                        {item.label}
                      </span>
                    )}
                  </Link>
                );
              })}
            </div>
          );
        })}
      </nav>

      {/* Bottom */}
      <div className="border-t border-white/5 p-3 space-y-1">
        <button onClick={toggleTheme}
          className={`sidebar-item w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition hover:bg-white/5 ${collapsed?"justify-center":""}`}
          style={{ color:"var(--sidebar-text)" }}
          aria-label="تبديل المظهر">
          {theme === "dark" ? <Sun size={16} /> : <Moon size={16} />}
          {!collapsed && <span>{theme==="dark"?"الوضع الفاتح":"الوضع الداكن"}</span>}
        </button>

        {!collapsed && (
          <div className="flex items-center gap-2.5 px-3 py-2.5 rounded-lg"
            style={{ background:"rgba(255,255,255,0.03)" }}>
            <div className="w-7 h-7 rounded-full flex items-center justify-center text-xs font-bold shrink-0 text-white"
              style={{ background:"var(--sidebar-active)" }}>
              {userEmail?.[0]?.toUpperCase() ?? "A"}
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-xs font-medium text-white truncate">{userEmail || "admin"}</p>
              <p className="text-[10px]" style={{ color:"var(--sidebar-text)", opacity:0.7 }}>
                {userRole==="super-admin"?"مدير عام":userRole==="moderator"?"مشرف":"مشاهد"}
              </p>
            </div>
          </div>
        )}

        <button onClick={handleLogout}
          className={`sidebar-item w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition hover:bg-red-500/10 ${collapsed?"justify-center":""}`}
          style={{ color:"#f87171" }}
          aria-label="تسجيل الخروج">
          <LogOut size={16} />
          {!collapsed && <span>تسجيل الخروج</span>}
        </button>
      </div>
    </aside>
  );
}
