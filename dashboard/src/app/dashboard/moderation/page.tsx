"use client";

import { useEffect, useState } from "react";

interface Report {
  id: string;
  commentId: string;
  mangaId: string;
  reportedUid: string;
  reporterUid: string;
  reason: string;
  status: string;
  createdAt: number;
}

export default function ModerationPage() {
  const [reports, setReports] = useState<Report[]>([]);
  const [loading, setLoading] = useState(true);
  const [filter, setFilter] = useState<"all" | "open" | "resolved" | "dismissed">("open");
  const [processingId, setProcessingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/moderation/reports");
      const data = await res.json();
      setReports(data.reports || []);
    } catch {
      setReports([]);
    }
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const resolve = async (id: string, status: string) => {
    setProcessingId(id);
    try {
      await fetch("/api/moderation/reports", {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reportId: id, status }),
      });
      setReports((prev) =>
        prev.map((r) => (r.id === id ? { ...r, status } : r))
      );
    } catch {}
    setProcessingId(null);
  };

  const filtered = filter === "all" ? reports : reports.filter((r) => r.status === filter);
  const counts = {
    all: reports.length,
    open: reports.filter((r) => r.status === "open").length,
    resolved: reports.filter((r) => r.status === "resolved").length,
    dismissed: reports.filter((r) => r.status === "dismissed").length,
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">التقارير والإشراف</h3>
        <button onClick={load} className="text-sm text-[var(--primary)] hover:underline">تحديث</button>
      </div>

      {/* Filter Tabs */}
      <div className="flex gap-2 bg-[var(--card)] p-1 rounded-xl border border-[var(--border)]">
        {(["all", "open", "resolved", "dismissed"] as const).map((tab) => (
          <button
            key={tab}
            onClick={() => setFilter(tab)}
            className={`flex-1 px-4 py-2 rounded-lg text-sm font-medium transition ${
              filter === tab
                ? "bg-[var(--primary)] text-[var(--primary-foreground)]"
                : "text-[var(--muted-foreground)] hover:bg-[var(--accent)]"
            }`}
          >
            {tab === "all" ? "الكل" : tab === "open" ? "مفتوح" : tab === "resolved" ? "تم الحل" : "تم التجاهل"}
            <span className="mr-1.5 text-xs opacity-70">({counts[tab]})</span>
          </button>
        ))}
      </div>

      {/* Reports List */}
      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse">
              <div className="h-4 bg-[var(--muted)] rounded w-3/4 mb-2" />
              <div className="h-3 bg-[var(--muted)] rounded w-1/2" />
            </div>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className="p-12 text-center bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <span className="text-4xl block mb-3">
            {filter === "open" ? "🎉" : "📋"}
          </span>
          <p className="text-[var(--muted-foreground)]">
            {filter === "open"
              ? "لا توجد تقارير مفتوحة"
              : "لا توجد تقارير في هذا التصنيف"}
          </p>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((report) => (
            <div
              key={report.id}
              className={`p-5 bg-[var(--card)] rounded-xl border transition ${
                report.status === "open"
                  ? "border-yellow-500/30"
                  : report.status === "resolved"
                  ? "border-green-500/20"
                  : "border-[var(--border)]"
              }`}
            >
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-2">
                    <span className={`px-2 py-0.5 rounded-full text-xs font-medium ${
                      report.status === "open" ? "bg-yellow-100 text-yellow-700" :
                      report.status === "resolved" ? "bg-green-100 text-green-700" :
                      "bg-gray-100 text-gray-600"
                    }`}>
                      {report.status === "open" ? "مفتوح" : report.status === "resolved" ? "تم الحل" : "تم التجاهل"}
                    </span>
                    <span className="text-xs text-[var(--muted-foreground)]">
                      {report.createdAt ? new Date(report.createdAt).toLocaleString("ar-SA") : ""}
                    </span>
                  </div>
                  <p className="text-sm font-medium mb-1">{report.reason || "بدون سبب محدد"}</p>
                  <div className="flex flex-wrap gap-3 text-xs text-[var(--muted-foreground)]">
                    <span>المانجا: <code className="bg-[var(--accent)] px-1 rounded">{report.mangaId?.slice(0, 24)}</code></span>
                    <span>المُبلّغ: <code className="bg-[var(--accent)] px-1 rounded">{report.reporterUid?.slice(0, 12)}</code></span>
                    <span>المُبلّغ عنه: <code className="bg-[var(--accent)] px-1 rounded">{report.reportedUid?.slice(0, 12)}</code></span>
                  </div>
                </div>

                {report.status === "open" && (
                  <div className="flex gap-2 shrink-0">
                    <button
                      onClick={() => resolve(report.id, "resolved")}
                      disabled={processingId === report.id}
                      className="px-3 py-1.5 text-xs rounded-lg bg-green-500/10 text-green-600 hover:bg-green-500/20 font-medium disabled:opacity-50 transition"
                    >
                      {processingId === report.id ? "..." : "حل"}
                    </button>
                    <button
                      onClick={() => resolve(report.id, "dismissed")}
                      disabled={processingId === report.id}
                      className="px-3 py-1.5 text-xs rounded-lg bg-gray-500/10 text-gray-600 hover:bg-gray-500/20 font-medium disabled:opacity-50 transition"
                    >
                      {processingId === report.id ? "..." : "تجاهل"}
                    </button>
                  </div>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
