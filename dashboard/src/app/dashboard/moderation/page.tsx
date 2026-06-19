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

  const load = () => {
    fetch("/api/moderation/reports")
      .then((r) => r.json())
      .then((data) => { setReports(data.reports || []); setLoading(false); })
      .catch(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const resolve = async (id: string, status: string) => {
    await fetch("/api/moderation/reports", {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reportId: id, status }),
    });
    load();
  };

  const openReports = reports.filter((r) => r.status === "open");

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">التقارير المفتوحة ({openReports.length})</h3>
        <button onClick={load} className="text-sm text-[var(--primary)] hover:underline">تحديث</button>
      </div>

      {loading ? (
        <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>
      ) : openReports.length === 0 ? (
        <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
          لا توجد تقارير مفتوحة 🎉
        </div>
      ) : (
        <div className="space-y-3">
          {openReports.map((report) => (
            <div key={report.id} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
              <div className="flex items-start justify-between">
                <div className="flex-1">
                  <p className="text-sm font-medium">{report.reason || "بدون سبب"}</p>
                  <p className="text-xs text-[var(--muted-foreground)] mt-1">
                    mangaId: {report.mangaId} • المُبلّغ: {report.reporterUid?.slice(0, 8)}...
                  </p>
                  <p className="text-xs text-[var(--muted-foreground)]">
                    {report.createdAt ? new Date(report.createdAt).toLocaleString("ar-SA") : ""}
                  </p>
                </div>
                <div className="flex gap-2">
                  <button
                    onClick={() => resolve(report.id, "resolved")}
                    className="px-3 py-1 text-xs rounded-lg bg-green-500/10 text-green-500 hover:bg-green-500/20"
                  >
                    حل
                  </button>
                  <button
                    onClick={() => resolve(report.id, "dismissed")}
                    className="px-3 py-1 text-xs rounded-lg bg-gray-500/10 text-gray-500 hover:bg-gray-500/20"
                  >
                    تجاهل
                  </button>
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
