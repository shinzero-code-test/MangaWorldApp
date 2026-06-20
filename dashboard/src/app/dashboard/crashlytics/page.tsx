"use client";

import { useEffect, useState } from "react";

interface CrashIssue {
  id: string;
  title: string;
  subtitle: string;
  state: string;
  count: number;
  users: number;
  firstOccurrence: string;
  lastOccurrence: string;
  appVersions: string[];
  osVersions: string[];
  devices: string[];
}

interface CrashStats {
  crashFreeRate: number;
  crashFreeRateDelta: number;
  totalIssues: number;
  openIssues: number;
  totalCrashes: number;
  affectedUsers: number;
}

export default function CrashlyticsPage() {
  const [issues, setIssues] = useState<CrashIssue[]>([]);
  const [stats, setStats] = useState<CrashStats | null>(null);
  const [loading, setLoading] = useState(true);
  const [selectedIssue, setSelectedIssue] = useState<CrashIssue | null>(null);
  const [filter, setFilter] = useState<"all" | "open" | "resolved">("all");

  useEffect(() => {
    fetch("/api/crashlytics")
      .then(r => r.json())
      .then(data => { setIssues(data.issues || []); setStats(data.stats); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const filtered = filter === "all" ? issues : issues.filter(i => i.state === filter);

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-20 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">مراقبة الأعطال — Crashlytics</h3>

      {/* Stats Cards */}
      {stats && (
        <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-xs text-[var(--muted-foreground)]">نسبة بدون أخطاء</p>
            <p className="text-2xl font-bold mt-1">{stats.crashFreeRate}%</p>
            <p className={`text-xs mt-1 ${stats.crashFreeRateDelta >= 0 ? "text-green-500" : "text-red-500"}`}>
              {stats.crashFreeRateDelta >= 0 ? "+" : ""}{stats.crashFreeRateDelta}%
            </p>
          </div>
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-xs text-[var(--muted-foreground)]">إجمالي الأعطال</p>
            <p className="text-2xl font-bold mt-1">{stats.totalCrashes}</p>
          </div>
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-xs text-[var(--muted-foreground)]">مشاكل مفتوحة</p>
            <p className="text-2xl font-bold mt-1 text-red-500">{stats.openIssues}</p>
          </div>
          <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <p className="text-xs text-[var(--muted-foreground)]">مستخدمون متأثرون</p>
            <p className="text-2xl font-bold mt-1">{stats.affectedUsers}</p>
          </div>
        </div>
      )}

      {/* Filter */}
      <div className="flex gap-2">
        {(["all", "open", "resolved"] as const).map(f => (
          <button key={f} onClick={() => setFilter(f)}
            className={`px-4 py-2 rounded-lg text-sm font-medium transition ${filter === f ? "bg-[var(--primary)] text-[var(--primary-foreground)]" : "bg-[var(--card)] border border-[var(--border)] hover:bg-[var(--accent)]"}`}>
            {f === "all" ? "الكل" : f === "open" ? "مفتوح" : "تم الحل"}
            <span className="mr-1.5 text-xs opacity-70">
              ({f === "all" ? issues.length : issues.filter(i => i.state === f).length})
            </span>
          </button>
        ))}
      </div>

      {/* Issues List */}
      <div className="space-y-3">
        {filtered.map(issue => (
          <div key={issue.id}
            className={`p-5 bg-[var(--card)] rounded-xl border cursor-pointer transition hover:shadow-md ${
              selectedIssue?.id === issue.id ? "border-[var(--primary)]" :
              issue.state === "open" ? "border-yellow-500/30" : "border-[var(--border)]"
            }`}
            onClick={() => setSelectedIssue(selectedIssue?.id === issue.id ? null : issue)}>
            <div className="flex items-start justify-between gap-4">
              <div className="flex-1">
                <div className="flex items-center gap-2 mb-1">
                  <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${issue.state === "open" ? "bg-red-100 text-red-700" : "bg-green-100 text-green-700"}`}>
                    {issue.state === "open" ? "مفتوح" : "تم الحل"}
                  </span>
                  <span className="text-xs text-[var(--muted-foreground)]">
                    {issue.count} حدث • {issue.users} مستخدم
                  </span>
                </div>
                <h4 className="font-medium text-sm">{issue.title}</h4>
                <p className="text-xs text-[var(--muted-foreground)] font-mono mt-0.5">{issue.subtitle}</p>
              </div>
              <div className="text-left text-xs text-[var(--muted-foreground)]">
                <p>الأحدث: {new Date(issue.lastOccurrence).toLocaleDateString("ar-SA")}</p>
                <p>الأقدم: {new Date(issue.firstOccurrence).toLocaleDateString("ar-SA")}</p>
              </div>
            </div>

            {/* Expanded Detail */}
            {selectedIssue?.id === issue.id && (
              <div className="mt-4 pt-4 border-t border-[var(--border)] grid grid-cols-3 gap-4 text-xs">
                <div>
                  <p className="text-[var(--muted-foreground)] mb-1">إصدارات التطبيق</p>
                  <div className="flex flex-wrap gap-1">
                    {issue.appVersions.map(v => <span key={v} className="px-2 py-0.5 bg-[var(--accent)] rounded">{v}</span>)}
                  </div>
                </div>
                <div>
                  <p className="text-[var(--muted-foreground)] mb-1">إصدارات النظام</p>
                  <div className="flex flex-wrap gap-1">
                    {issue.osVersions.map(v => <span key={v} className="px-2 py-0.5 bg-[var(--accent)] rounded">{v}</span>)}
                  </div>
                </div>
                <div>
                  <p className="text-[var(--muted-foreground)] mb-1">الأجهزة</p>
                  <div className="flex flex-wrap gap-1">
                    {issue.devices.map(d => <span key={d} className="px-2 py-0.5 bg-[var(--accent)] rounded">{d}</span>)}
                  </div>
                </div>
              </div>
            )}
          </div>
        ))}
      </div>
    </div>
  );
}
