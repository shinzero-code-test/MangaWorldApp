"use client";

import { useEffect, useState } from "react";
import {
  Trophy, FileText, BookOpen, Target, Star, CheckCircle2,
  Flame, Zap, Crown, Award
} from "lucide-react";
import { PageHeader, SkeletonCard, EmptyState } from "@/components/ui";
import { formatAr } from "@/lib/utils";

interface Achievement {
  id:          string;
  title:       string;
  description: string;
  type:        string;
  isUnlocked:  boolean;
  unlockedAt?: string;
  count?:      number;
}

interface Goal {
  id:       string;
  label:    string;
  current:  number;
  target:   number;
  unit:     string;
}

interface AchievementsData {
  totalPagesRead:      number;
  totalChapters:       number;
  unlockedAchievements:number;
  activeGoals:         number;
  achievements:        Achievement[];
  goals:               Goal[];
}

const ACHIEVEMENT_ICONS: Record<string, React.ComponentType<any>> = {
  reading: BookOpen,
  streak:  Flame,
  speed:   Zap,
  crown:   Crown,
  star:    Star,
  award:   Award,
  default: Trophy,
};

export default function AchievementsPage() {
  const [data,    setData]    = useState<AchievementsData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/achievements")
      .then((r) => r.json())
      .then((d) => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const achievements = data?.achievements ?? [];
  const goals        = data?.goals        ?? [];

  return (
    <div className="space-y-6">
      <PageHeader
        title="الإنجازات"
        subtitle="تتبع إنجازات المستخدمين وأهدافهم"
        icon={Trophy}
      />

      {/* KPI row */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {loading
          ? Array.from({ length: 4 }).map((_, i) => <SkeletonCard key={i} />)
          : [
              { label: "إجمالي الصفحات",  val: data?.totalPagesRead      ?? 0, icon: FileText,  color: "var(--primary)" },
              { label: "إجمالي الفصول",   val: data?.totalChapters       ?? 0, icon: BookOpen,  color: "#10b981" },
              { label: "إنجازات مكتملة",  val: data?.unlockedAchievements ?? 0, icon: Trophy,   color: "#f59e0b" },
              { label: "أهداف نشطة",      val: data?.activeGoals         ?? 0, icon: Target,    color: "#3b82f6" },
            ].map((s) => {
              const Icon = s.icon;
              return (
                <div
                  key={s.label}
                  className="p-5 rounded-[var(--radius-xl)] border"
                  style={{ background: "var(--card)", borderColor: "var(--border)" }}
                >
                  <div
                    className="w-10 h-10 rounded-xl flex items-center justify-center mb-4"
                    style={{ background: `${s.color}15` }}
                  >
                    <Icon size={18} style={{ color: s.color }} />
                  </div>
                  <p className="text-sm mb-1" style={{ color: "var(--muted-foreground)" }}>
                    {s.label}
                  </p>
                  <p className="text-3xl font-bold">{formatAr(s.val)}</p>
                </div>
              );
            })}
      </div>

      {/* Achievements grid */}
      <div
        className="rounded-[var(--radius-lg)] border overflow-hidden"
        style={{ background: "var(--card)", borderColor: "var(--border)" }}
      >
        <div
          className="px-5 py-4 border-b flex items-center gap-2.5"
          style={{ borderColor: "var(--border)" }}
        >
          <div
            className="w-8 h-8 rounded-lg flex items-center justify-center"
            style={{ background: "var(--accent)" }}
          >
            <Trophy size={16} style={{ color: "var(--primary)" }} />
          </div>
          <h3 className="font-semibold text-sm">الإنجازات</h3>
        </div>
        <div className="p-5">
          {loading ? (
            <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
              {Array.from({ length: 6 }).map((_, i) => <SkeletonCard key={i} />)}
            </div>
          ) : achievements.length === 0 ? (
            <EmptyState
              icon={Trophy}
              title="لا توجد إنجازات"
              description="لم يُحقق أي مستخدم إنجازات بعد"
            />
          ) : (
            <div className="grid grid-cols-2 md:grid-cols-3 gap-4">
              {achievements.map((ach) => {
                const Icon = ACHIEVEMENT_ICONS[ach.type] ?? ACHIEVEMENT_ICONS.default;
                return (
                  <div
                    key={ach.id}
                    className="p-4 rounded-[var(--radius-lg)] border transition-all"
                    style={{
                      background:  ach.isUnlocked
                        ? "color-mix(in srgb, var(--primary) 5%, var(--card))"
                        : "var(--card)",
                      borderColor: ach.isUnlocked
                        ? "color-mix(in srgb, var(--primary) 30%, transparent)"
                        : "var(--border)",
                      opacity:    ach.isUnlocked ? 1 : 0.55,
                      filter:     ach.isUnlocked ? "none" : "grayscale(0.6)",
                    }}
                  >
                    <div
                      className="w-12 h-12 rounded-xl mb-3 flex items-center justify-center"
                      style={{ background: "var(--accent)" }}
                    >
                      <Icon
                        size={22}
                        style={{
                          color: ach.isUnlocked ? "var(--primary)" : "var(--muted-foreground)",
                        }}
                      />
                    </div>
                    <p className="font-semibold text-sm">{ach.title}</p>
                    <p
                      className="text-xs mt-1 line-clamp-2"
                      style={{ color: "var(--muted-foreground)" }}
                    >
                      {ach.description}
                    </p>
                    {ach.isUnlocked && (
                      <span
                        className="mt-2 inline-flex items-center gap-1 text-xs"
                        style={{ color: "var(--success)" }}
                      >
                        <CheckCircle2 size={12} />
                        مكتمل
                      </span>
                    )}
                    {ach.count !== undefined && (
                      <p
                        className="text-xs mt-1 font-mono"
                        style={{ color: "var(--muted-foreground)" }}
                      >
                        {formatAr(ach.count)} مستخدم
                      </p>
                    )}
                  </div>
                );
              })}
            </div>
          )}
        </div>
      </div>

      {/* Goals */}
      {goals.length > 0 && (
        <div
          className="rounded-[var(--radius-lg)] border overflow-hidden"
          style={{ background: "var(--card)", borderColor: "var(--border)" }}
        >
          <div
            className="px-5 py-4 border-b flex items-center gap-2.5"
            style={{ borderColor: "var(--border)" }}
          >
            <div
              className="w-8 h-8 rounded-lg flex items-center justify-center"
              style={{ background: "var(--accent)" }}
            >
              <Target size={16} style={{ color: "var(--primary)" }} />
            </div>
            <h3 className="font-semibold text-sm">الأهداف النشطة</h3>
          </div>
          <div className="divide-y" style={{ borderColor: "var(--border)" }}>
            {goals.map((goal) => {
              const pct = Math.min(100, (goal.current / goal.target) * 100);
              return (
                <div key={goal.id} className="px-5 py-4">
                  <div className="flex items-center justify-between mb-2">
                    <p className="font-medium text-sm">{goal.label}</p>
                    <span className="text-xs font-mono" style={{ color: "var(--muted-foreground)" }}>
                      {formatAr(goal.current)} / {formatAr(goal.target)} {goal.unit}
                    </span>
                  </div>
                  <div
                    className="h-2 rounded-full overflow-hidden"
                    style={{ background: "var(--muted)" }}
                  >
                    <div
                      className="h-full rounded-full transition-all duration-700"
                      style={{
                        width:      `${pct}%`,
                        background: pct >= 100 ? "var(--success)" : "var(--primary)",
                      }}
                    />
                  </div>
                  <p className="text-xs mt-1 font-mono" style={{ color: "var(--muted-foreground)" }}>
                    {pct.toFixed(0)}%
                  </p>
                </div>
              );
            })}
          </div>
        </div>
      )}
    </div>
  );
}
