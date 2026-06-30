"use client";

import { useEffect, useState, useRef } from "react";
import Link from "next/link";
import {
  Users, MessageSquare, Star, Shield, TrendingUp, Bell,
  Trophy, Settings2, Activity, ArrowUpRight
} from "lucide-react";
import { AreaChart, Area, ResponsiveContainer } from "recharts";
import { SkeletonCard, StatusBadge } from "@/components/ui";
import { formatAr } from "@/lib/utils";

interface KPIData {
  totalUsers: number; totalComments: number; totalReviews: number;
  openReports: number; recentSignUps: number; roleCounts: Record<string,number>;
}

const SERVICES = [
  { name:"Firebase Auth", key:"auth" },{ name:"Firestore", key:"db" },
  { name:"Remote Config", key:"rc" },{ name:"FCM", key:"fcm" },
  { name:"Crashlytics", key:"crash" },
];

const QUICK_ACTIONS = [
  { label:"المستخدمون", icon:Users, href:"/dashboard/users", color:"var(--primary)", bg:"rgba(139,92,246,0.1)" },
  { label:"الإشراف", icon:Shield, href:"/dashboard/moderation", color:"#f59e0b", bg:"rgba(245,158,11,0.1)" },
  { label:"Remote Config", icon:Settings2, href:"/dashboard/remote-config", color:"#8b5cf6", bg:"rgba(139,92,246,0.08)" },
  { label:"التحليلات", icon:TrendingUp, href:"/dashboard/analytics", color:"#10b981", bg:"rgba(16,185,129,0.1)" },
  { label:"الإشعارات", icon:Bell, href:"/dashboard/notifications", color:"#3b82f6", bg:"rgba(59,130,246,0.1)" },
  { label:"الإنجازات", icon:Trophy, href:"/dashboard/achievements", color:"#f59e0b", bg:"rgba(245,158,11,0.1)" },
];

function buildSparkline(value: number, points = 8) {
  const base = Math.max(value, 1);
  return Array.from({ length: points }, (_, i) => ({ v: base * (0.6 + (i / points) * 0.4) }));
}

export default function DashboardOverview() {
  const [kpis, setKpis] = useState<KPIData | null>(null);
  const [loading, setLoading] = useState(true);
  const [svcs, setSvcs] = useState<Record<string,boolean>>({});
  const mounted = useRef(true);
  useEffect(() => { mounted.current = true; return () => { mounted.current = false; }; }, []);

  useEffect(() => {
    fetch("/api/dashboard")
      .then(r => r.json())
      .then(d => {
        if (!mounted.current) return;
        setKpis({
          totalUsers: d.totalUsers ?? 0, totalComments: d.totalComments ?? 0,
          totalReviews: d.totalReviews ?? 0, openReports: d.openReports ?? 0,
          recentSignUps: d.recentSignUps ?? 0, roleCounts: d.roleCounts ?? {},
        });
        setSvcs({ auth:true, db:true, rc:true, fcm:true, crash:false });
        setLoading(false);
      })
      .catch(() => { if (mounted.current) setLoading(false); });
  }, []);

  const kpiConfig = [
    { key:"totalUsers" as keyof KPIData, label:"إجمالي المستخدمين", icon:Users, color:"var(--primary)", bg:"rgba(139,92,246,0.1)", href:"/dashboard/users" },
    { key:"totalComments" as keyof KPIData, label:"التعليقات", icon:MessageSquare, color:"#10b981", bg:"rgba(16,185,129,0.1)", href:"/dashboard/community/comments" },
    { key:"totalReviews" as keyof KPIData, label:"المراجعات", icon:Star, color:"#f59e0b", bg:"rgba(245,158,11,0.1)", href:"/dashboard/community/reviews" },
    { key:"openReports" as keyof KPIData, label:"تقارير مفتوحة", icon:Shield, color:"#ef4444", bg:"rgba(239,68,68,0.1)", href:"/dashboard/moderation" },
  ];

  return (
    <div className="space-y-6">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {loading ? Array.from({length:4}).map((_,i) => <SkeletonCard key={i} />) :
          kpiConfig.map((cfg, idx) => {
            const value = (kpis?.[cfg.key] as number) ?? 0;
            const spark = buildSparkline(value);
            const Icon = cfg.icon;
            return (
              <Link key={cfg.key} href={cfg.href}
                className="card-enter relative overflow-hidden p-5 rounded-[var(--radius-xl)] border transition-all duration-200 group block hover:shadow-lg"
                style={{ background:"var(--card)", borderColor:"var(--border)", animationDelay:`${idx*60}ms` }}>
                <div className="absolute -top-10 -end-10 w-24 h-24 rounded-full opacity-40 group-hover:opacity-70 transition-opacity"
                  style={{ background:cfg.bg }} />
                <div className="relative flex items-start justify-between mb-4">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{ background:cfg.bg }}>
                    <Icon size={18} style={{ color:cfg.color }} />
                  </div>
                  {cfg.key==="totalUsers" && (kpis?.recentSignUps ?? 0) > 0 && (
                    <span className="text-xs font-semibold px-2 py-0.5 rounded-full"
                      style={{ background:"rgba(16,185,129,0.1)", color:"#10b981" }}>
                      +{formatAr(kpis!.recentSignUps)} هذا الأسبوع
                    </span>
                  )}
                </div>
                <p className="text-sm mb-1" style={{ color:"var(--muted-foreground)" }}>{cfg.label}</p>
                <p className="text-3xl font-bold tracking-tight">{formatAr(value)}</p>
                <div className="mt-3 h-8 opacity-50">
                  <ResponsiveContainer width="100%" height="100%">
                    <AreaChart data={spark}>
                      <Area dataKey="v" stroke={cfg.color} fill={cfg.color} fillOpacity={0.15} strokeWidth={1.5} dot={false} isAnimationActive />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              </Link>
            );
          })}
      </div>

      <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
        <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
          <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
            <ArrowUpRight size={16} style={{ color:"var(--primary)" }} />
          </div>
          <h3 className="font-semibold text-sm">الوصول السريع</h3>
        </div>
        <div className="p-5">
          <div className="grid grid-cols-3 md:grid-cols-6 gap-3">
            {QUICK_ACTIONS.map((action) => {
              const Icon = action.icon;
              return (
                <Link key={action.href} href={action.href}
                  className="flex flex-col items-center gap-2 p-4 rounded-xl transition-all duration-150 group hover:-translate-y-0.5 hover:shadow-md"
                  style={{ background:"var(--muted)" }}>
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center transition-transform group-hover:scale-110"
                    style={{ background:action.bg }}>
                    <Icon size={18} style={{ color:action.color }} />
                  </div>
                  <span className="text-xs font-medium text-center">{action.label}</span>
                </Link>
              );
            })}
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
              <Activity size={16} style={{ color:"var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">حالة النظام</h3>
          </div>
          <div className="divide-y" style={{ borderColor:"var(--border)" }}>
            {SERVICES.map(svc => {
              const ok = loading ? null : (svcs[svc.key] ?? false);
              return (
                <div key={svc.key} className="flex items-center justify-between px-5 py-3">
                  <div className="flex items-center gap-2.5">
                    <div className="w-2 h-2 rounded-full status-pulse"
                      style={{ background: ok===null?"var(--border)":ok?"var(--success)":"var(--warning)" }} />
                    <span className="text-sm">{svc.name}</span>
                  </div>
                  <StatusBadge status={ok===null?"dismissed":ok?"active":"open"}
                    label={ok===null?"جاري التحقق":ok?"نشط":"يتطلب إعداد"} size="sm" />
                </div>
              );
            })}
          </div>
        </div>

        <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
              <Users size={16} style={{ color:"var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">توزيع الأدوار</h3>
          </div>
          <div className="divide-y" style={{ borderColor:"var(--border)" }}>
            {loading ? Array.from({length:3}).map((_,i) => (
              <div key={i} className="flex items-center gap-4 px-5 py-3">
                <div className="flex-1 h-3 rounded skeleton-shimmer" />
              </div>
            )) : [
              { key:"super-admin", label:"مدير عام", color:"#ef4444" },
              { key:"moderator", label:"مشرف", color:"#3b82f6" },
              { key:"viewer", label:"مشاهد", color:"#6b7280" },
            ].map(role => {
              const count = kpis?.roleCounts?.[role.key] ?? 0;
              const total = Math.max(kpis?.totalUsers ?? 1, 1);
              const pct   = Math.round((count / total) * 100);
              return (
                <div key={role.key} className="flex items-center gap-4 px-5 py-3.5">
                  <div className="w-2.5 h-2.5 rounded-sm shrink-0" style={{ background:role.color }} />
                  <span className="text-sm flex-1">{role.label}</span>
                  <span className="font-mono text-sm font-semibold">{formatAr(count)}</span>
                  <div className="w-24 h-1.5 rounded-full overflow-hidden" style={{ background:"var(--muted)" }}>
                    <div className="h-full rounded-full" style={{ width:`${pct}%`, background:role.color }} />
                  </div>
                  <span className="text-xs w-10 text-end font-mono" style={{ color:"var(--muted-foreground)" }}>{pct}%</span>
                </div>
              );
            })}
          </div>
          {!loading && (
            <div className="px-5 py-3 border-t" style={{ borderColor:"var(--border)" }}>
              <Link href="/dashboard/analytics" className="text-xs font-medium flex items-center gap-1 hover:opacity-70 transition" style={{ color:"var(--primary)" }}>
                عرض التحليلات الكاملة <ArrowUpRight size={12} />
              </Link>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}
