"use client";
import { useEffect, useState } from "react";
import {
  TrendingUp, Sparkles, BookOpen, Clock, Star, Users
} from "lucide-react";
import {
  BarChart, Bar, XAxis, YAxis, CartesianGrid, Tooltip, ResponsiveContainer,
  AreaChart, Area, PieChart, Pie, Cell
} from "recharts";
import { PageHeader, SkeletonCard, Skeleton } from "@/components/ui";
import { formatAr } from "@/lib/utils";

const PERIODS = [
  { id:"7d", label:"٧ أيام" },
  { id:"30d", label:"٣٠ يوم" },
  { id:"90d", label:"٩٠ يوم" },
];

const ROLE_COLORS: Record<string,string> = {
  "super-admin":"#ef4444", moderator:"#3b82f6", viewer:"#6b7280",
};

export default function AnalyticsPage() {
  const [period, setPeriod] = useState("30d");
  const [data, setData] = useState<any>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    setLoading(true);
    fetch(`/api/analytics/summary?period=${period}`)
      .then(r => r.json())
      .then(d => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, [period]);

  // API shape: { overview: {totalUsers, openReports, totalLists, recentSignUps, roleDistribution},
  //              engagement: {dailyActive, sourceUsage, avgReadingTime, retentionRate, avgPagesPerSession} }
  const overview   = data?.overview   ?? {};
  const engagement = data?.engagement ?? {};

  const dailyData   = engagement.dailyActive   ?? [];
  const sourceData  = engagement.sourceUsage   ?? [];

  const roleDistribution = overview.roleDistribution
    ? Object.entries(overview.roleDistribution as Record<string,number>).map(([role, count]) => ({
        role, count, label: role === "super-admin" ? "مدير عام" : role === "moderator" ? "مشرف" : "مشاهد",
      }))
    : [];
  const roleTotal = roleDistribution.reduce((a,r) => a + r.count, 0);

  const kpiCards = [
    { label:"إجمالي المستخدمين", value: overview.totalUsers        ?? 0,   icon:Users,    color:"var(--primary)", isNum:true },
    { label:"مستخدمون جدد هذا الأسبوع", value: overview.recentSignUps ?? 0, icon:Sparkles, color:"#8b5cf6",        isNum:true },
    { label:"معدل الاحتفاظ",     value: engagement.retentionRate    ?? 0,   icon:Star,     color:"#f59e0b",        isNum:false, suffix:"%" },
    { label:"متوسط وقت القراءة", value: engagement.avgReadingTime   ?? 0,   icon:Clock,    color:"#10b981",        isNum:true, suffix:" دقيقة" },
  ];

  return (
    <div className="space-y-6">
      <PageHeader
        title="التحليلات"
        subtitle="تحليل نشاط المستخدمين والمصادر"
        icon={TrendingUp}
        actions={
          <div className="flex gap-1 p-1 rounded-lg" style={{ background:"var(--muted)" }}>
            {PERIODS.map(p => (
              <button key={p.id} onClick={() => setPeriod(p.id)}
                className="px-3 py-1.5 rounded-md text-sm font-medium transition-all"
                style={{
                  background: period===p.id ? "var(--card)" : "transparent",
                  color:      period===p.id ? "var(--foreground)" : "var(--muted-foreground)",
                  boxShadow:  period===p.id ? "0 1px 3px rgba(0,0,0,0.1)" : undefined,
                }}>{p.label}</button>
            ))}
          </div>
        }
      />

      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {loading ? Array.from({length:4}).map((_,i) => <SkeletonCard key={i} />) :
          kpiCards.map((card, i) => {
            const Icon = card.icon;
            return (
              <div key={i} className="relative overflow-hidden p-5 rounded-[var(--radius-xl)] border"
                style={{ background:"var(--card)", borderColor:"var(--border)" }}>
                <div className="absolute -top-10 -end-10 w-24 h-24 rounded-full opacity-30"
                  style={{ background:card.color }} />
                <div className="relative">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center mb-4"
                    style={{ background:`${card.color}18` }}>
                    <Icon size={18} style={{ color:card.color }} />
                  </div>
                  <p className="text-sm mb-1" style={{ color:"var(--muted-foreground)" }}>{card.label}</p>
                  <p className="text-2xl font-bold">
                    {card.isNum ? formatAr(card.value) : card.value}{card.suffix ?? ""}
                  </p>
                </div>
              </div>
            );
          })}
      </div>

      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Daily Active */}
        <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
              <Users size={16} style={{ color:"var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">المستخدمون النشطون يومياً</h3>
          </div>
          <div className="p-5">
            {loading ? <Skeleton className="h-[200px] w-full" /> :
              dailyData.length === 0 ? (
                <div className="h-[200px] flex items-center justify-center text-sm" style={{ color:"var(--muted-foreground)" }}>
                  لا توجد بيانات يومية
                </div>
              ) : (
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart data={dailyData} barSize={8}>
                    <CartesianGrid strokeDasharray="3 3" vertical={false} stroke="var(--border)" />
                    <XAxis dataKey="date" tick={{ fill:"var(--muted-foreground)", fontSize:11 }} axisLine={false} tickLine={false} />
                    <YAxis tick={{ fill:"var(--muted-foreground)", fontSize:11 }} axisLine={false} tickLine={false} width={35} />
                    <Tooltip contentStyle={{ background:"var(--card)", border:"1px solid var(--border)", borderRadius:8, color:"var(--foreground)" }} cursor={{ fill:"rgba(139,92,246,0.08)" }} />
                    <Bar dataKey="users" fill="var(--primary)" radius={[4,4,0,0]} name="المستخدمون" />
                  </BarChart>
                </ResponsiveContainer>
              )}
          </div>
        </div>

        {/* Source Usage */}
        <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
              <BookOpen size={16} style={{ color:"var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">استخدام المصادر</h3>
          </div>
          <div className="p-5">
            {loading ? <Skeleton className="h-[200px] w-full" /> :
              sourceData.length === 0 ? (
                <div className="h-[200px] flex items-center justify-center text-sm" style={{ color:"var(--muted-foreground)" }}>
                  لا توجد بيانات مصادر
                </div>
              ) : (
                <ResponsiveContainer width="100%" height={200}>
                  <BarChart layout="vertical" data={sourceData} margin={{ top:0, right:0, bottom:0, left:60 }}>
                    <CartesianGrid strokeDasharray="3 3" horizontal={false} stroke="var(--border)" />
                    <XAxis type="number" tick={{ fill:"var(--muted-foreground)", fontSize:11 }} axisLine={false} tickLine={false} />
                    <YAxis type="category" dataKey="name" tick={{ fill:"var(--muted-foreground)", fontSize:11 }} axisLine={false} tickLine={false} width={55} orientation="right" />
                    <Tooltip contentStyle={{ background:"var(--card)", border:"1px solid var(--border)", borderRadius:8, color:"var(--foreground)" }} />
                    <Bar dataKey="value" fill="var(--primary)" radius={[0,4,4,0]} name="الاستخدام" />
                  </BarChart>
                </ResponsiveContainer>
              )}
          </div>
        </div>
      </div>

      {/* Role Distribution */}
      {!loading && roleDistribution.length > 0 && (
        <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          <div className="px-5 py-4 border-b flex items-center gap-2.5" style={{ borderColor:"var(--border)" }}>
            <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:"var(--accent)" }}>
              <Users size={16} style={{ color:"var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">توزيع الأدوار</h3>
          </div>
          <div className="p-5 flex flex-col sm:flex-row items-center gap-8">
            <div className="shrink-0">
              <ResponsiveContainer width={200} height={200}>
                <PieChart>
                  <Pie data={roleDistribution} dataKey="count" nameKey="label" cx="50%" cy="50%" innerRadius="55%" outerRadius="80%" paddingAngle={3}>
                    {roleDistribution.map((entry) => (
                      <Cell key={entry.role} fill={ROLE_COLORS[entry.role] ?? "#6b7280"} />
                    ))}
                  </Pie>
                  <Tooltip contentStyle={{ background:"var(--card)", border:"1px solid var(--border)", borderRadius:8, color:"var(--foreground)" }} />
                </PieChart>
              </ResponsiveContainer>
            </div>
            <div className="flex flex-col gap-3 flex-1">
              {roleDistribution.map(r => {
                const pct = roleTotal > 0 ? ((r.count / roleTotal) * 100).toFixed(1) : "0";
                return (
                  <div key={r.role} className="flex items-center gap-3">
                    <div className="w-3 h-3 rounded-sm shrink-0" style={{ background:ROLE_COLORS[r.role]??"#6b7280" }} />
                    <span className="text-sm flex-1">{r.label}</span>
                    <span className="font-mono text-sm font-semibold">{formatAr(r.count)}</span>
                    <span className="text-xs w-12 text-end" style={{ color:"var(--muted-foreground)" }}>{pct}%</span>
                  </div>
                );
              })}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
