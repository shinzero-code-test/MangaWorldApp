"use client";

import { useEffect, useState } from "react";

interface AchievementData {
  totalPagesRead: number;
  totalChaptersRead: number;
  lastUpdated: number;
  goals: string;
  achievements: string;
}

interface Achievement {
  id: string;
  title: string;
  description: string;
  icon: string;
  isUnlocked: boolean;
}

interface Goal {
  id: string;
  type: string;
  targetValue: number;
  currentValue: number;
  period: string;
}

export default function AchievementsPage() {
  const [data, setData] = useState<AchievementData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/achievements")
      .then((r) => r.json())
      .then((d) => { setData(d.achievements); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const achievements: Achievement[] = data?.achievements ? (() => {
    try { return JSON.parse(data.achievements); }
    catch { return []; }
  })() : [];

  const goals: Goal[] = data?.goals ? (() => {
    try { return JSON.parse(data.goals); }
    catch { return []; }
  })() : [];

  const unlockedCount = achievements.filter((a) => a.isUnlocked).length;

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-32 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold">الإنجازات والإحصائيات</h3>
        <p className="text-sm text-[var(--muted-foreground)] mt-1">تتبع تقدم القراءة وإنجازات المستخدم</p>
      </div>

      {/* Stats Overview */}
      <div className="grid grid-cols-1 md:grid-cols-4 gap-4">
        <div className="p-5 bg-gradient-to-br from-blue-500/20 to-blue-600/5 rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between mb-2">
            <span className="text-2xl">📖</span>
          </div>
          <p className="text-sm text-[var(--muted-foreground)]">إجمالي الصفحات</p>
          <p className="text-3xl font-bold mt-1">{((data?.totalPagesRead) || 0).toLocaleString("ar-SA")}</p>
        </div>
        <div className="p-5 bg-gradient-to-br from-green-500/20 to-green-600/5 rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between mb-2">
            <span className="text-2xl">📚</span>
          </div>
          <p className="text-sm text-[var(--muted-foreground)]">إجمالي الفصول</p>
          <p className="text-3xl font-bold mt-1">{(data?.totalChaptersRead || 0).toLocaleString("ar-SA")}</p>
        </div>
        <div className="p-5 bg-gradient-to-br from-yellow-500/20 to-yellow-600/5 rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between mb-2">
            <span className="text-2xl">🏆</span>
          </div>
          <p className="text-sm text-[var(--muted-foreground)]">الإنجازات المحققة</p>
          <p className="text-3xl font-bold mt-1">{unlockedCount}/{achievements.length}</p>
        </div>
        <div className="p-5 bg-gradient-to-br from-purple-500/20 to-purple-600/5 rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between mb-2">
            <span className="text-2xl">🎯</span>
          </div>
          <p className="text-sm text-[var(--muted-foreground)]">الأهداف النشطة</p>
          <p className="text-3xl font-bold mt-1">{goals.length}</p>
        </div>
      </div>

      {/* Achievement Progress Bar */}
      {achievements.length > 0 && (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-5">
          <h4 className="font-medium mb-3">تقدم الإنجازات</h4>
          <div className="h-4 bg-[var(--accent)] rounded-full overflow-hidden">
            <div
              className="h-full bg-gradient-to-r from-yellow-400 to-yellow-600 rounded-full transition-all duration-1000"
              style={{ width: `${achievements.length > 0 ? (unlockedCount / achievements.length) * 100 : 0}%` }}
            />
          </div>
          <p className="text-xs text-[var(--muted-foreground)] mt-2">
            {unlockedCount} من {achievements.length} إنجاز محقق ({achievements.length > 0 ? Math.round((unlockedCount / achievements.length) * 100) : 0}%)
          </p>
        </div>
      )}

      {/* Achievements Grid */}
      {achievements.length > 0 && (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-5">
          <h4 className="font-medium mb-4">الإنجازات</h4>
          <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-3">
            {achievements.map((achievement) => (
              <div
                key={achievement.id}
                className={`p-4 rounded-xl border transition ${
                  achievement.isUnlocked
                    ? "border-yellow-500/30 bg-yellow-500/5"
                    : "border-[var(--border)] opacity-60"
                }`}
              >
                <div className="flex items-center gap-3">
                  <span className="text-2xl">{achievement.icon}</span>
                  <div className="flex-1 min-w-0">
                    <p className="text-sm font-medium">{achievement.title}</p>
                    <p className="text-xs text-[var(--muted-foreground)]">{achievement.description}</p>
                  </div>
                  {achievement.isUnlocked && (
                    <span className="text-yellow-500 text-lg">✓</span>
                  )}
                </div>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Goals */}
      {goals.length > 0 && (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-5">
          <h4 className="font-medium mb-4">الأهداف</h4>
          <div className="space-y-3">
            {goals.map((goal) => {
              const progress = goal.targetValue > 0 ? Math.min(100, (goal.currentValue / goal.targetValue) * 100) : 0;
              const isComplete = goal.currentValue >= goal.targetValue;
              const typeLabels: Record<string, string> = { PAGES_READ: "صفحات", CHAPTERS_READ: "فصول", READING_TIME: "وقت" };
              const periodLabels: Record<string, string> = { DAILY: "يومي", WEEKLY: "أسبوعي", MONTHLY: "شهري" };

              return (
                <div key={goal.id} className="p-4 bg-[var(--background)] rounded-lg">
                  <div className="flex items-center justify-between mb-2">
                    <div className="flex items-center gap-2">
                      <span className="text-lg">{isComplete ? "🎉" : "🎯"}</span>
                      <div>
                        <p className="text-sm font-medium">{typeLabels[goal.type] || goal.type}</p>
                        <p className="text-xs text-[var(--muted-foreground)]">{periodLabels[goal.period] || goal.period}</p>
                      </div>
                    </div>
                    <span className={`text-sm font-medium ${isComplete ? "text-green-500" : "text-[var(--muted-foreground)]"}`}>
                      {goal.currentValue}/{goal.targetValue}
                    </span>
                  </div>
                  <div className="h-2 bg-[var(--accent)] rounded-full overflow-hidden">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${
                        isComplete ? "bg-green-500" : "bg-[var(--primary)]"
                      }`}
                      style={{ width: `${progress}%` }}
                    />
                  </div>
                </div>
              );
            })}
          </div>
        </div>
      )}

      {/* Empty State */}
      {achievements.length === 0 && goals.length === 0 && (
        <div className="p-12 text-center bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <span className="text-4xl block mb-3">🏆</span>
          <p className="text-[var(--muted-foreground)]">لم يتم تسجيل أي إنجازات بعد</p>
          <p className="text-xs text-[var(--muted-foreground)] mt-1">ستظهر الإنجازات هنا عندما يقرأ المستخدمون الفصول</p>
        </div>
      )}
    </div>
  );
}
