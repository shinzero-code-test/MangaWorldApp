"use client";

import { useEffect, useState } from "react";
import { Package, CheckCircle2, Clock, Download } from "lucide-react";
import { PageHeader, StatusBadge, EmptyState } from "@/components/ui";
import { formatDate, formatAr } from "@/lib/utils";

interface Release {
  id:          string;
  version:     string;
  buildNumber: number;
  platform:    "android" | "ios" | "all";
  notes:       string;
  publishedAt: string | number;
  status:      "active" | "deprecated" | "draft";
  downloads:   number;
}

export default function ReleasesPage() {
  const [releases, setReleases] = useState<Release[]>([]);
  const [loading,  setLoading]  = useState(true);

  useEffect(() => {
    // Real releases from the GitHub release pipeline (APKs/AABs per tag).
    fetch("/api/releases")
      .then((r) => r.json())
      .then((d) => {
        const list = Array.isArray(d.releases) ? d.releases : [];
        setReleases(list);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const platforms: Record<string, string> = {
    android: "Android",
    ios:     "iOS",
    all:     "الكل",
  };

  return (
    <div className="space-y-5">
      <PageHeader
        title="الإصدارات"
        subtitle="إدارة إصدارات التطبيق"
        icon={Package}
      />

      {loading ? (
        <div className="space-y-3">
          {[1, 2, 3].map((i) => (
            <div key={i} className="h-20 rounded-[var(--radius-lg)] border skeleton-shimmer"
              style={{ borderColor: "var(--border)" }} />
          ))}
        </div>
      ) : releases.length === 0 ? (
        <div className="rounded-[var(--radius-lg)] border"
          style={{ background: "var(--card)", borderColor: "var(--border)" }}>
          <EmptyState
            icon={Package}
            title="لا توجد إصدارات"
            description="لم يتم نشر أي إصدار بعد"
          />
        </div>
      ) : (
        <div className="space-y-3">
          {releases.map((rel) => (
            <div key={rel.id}
              className="p-5 rounded-[var(--radius-lg)] border flex items-start gap-4 flex-wrap"
              style={{ background: "var(--card)", borderColor: "var(--border)" }}>
              <div className="w-10 h-10 rounded-xl flex items-center justify-center shrink-0"
                style={{ background: "var(--accent)" }}>
                <Package size={18} style={{ color: "var(--primary)" }} />
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <p className="font-bold text-sm font-mono" dir="ltr">v{rel.version}</p>
                  <span className="text-xs px-2 py-0.5 rounded font-mono"
                    style={{ background: "var(--muted)", color: "var(--muted-foreground)" }}>
                    Build #{rel.buildNumber}
                  </span>
                  <span className="text-xs px-2 py-0.5 rounded"
                    style={{ background: "var(--muted)", color: "var(--muted-foreground)" }}>
                    {platforms[rel.platform] ?? rel.platform}
                  </span>
                  <StatusBadge
                    status={rel.status === "active" ? "active" : rel.status === "draft" ? "open" : "dismissed"}
                    label={rel.status === "active" ? "نشط" : rel.status === "draft" ? "مسودة" : "منتهي"}
                    size="sm"
                  />
                </div>
                <p className="text-sm mt-1.5" style={{ color: "var(--muted-foreground)" }}>
                  {rel.notes || "بدون ملاحظات"}
                </p>
                <div className="flex items-center gap-4 mt-2">
                  {rel.publishedAt && (
                    <span className="flex items-center gap-1 text-xs" style={{ color: "var(--muted-foreground)" }}>
                      <Clock size={11} />
                      {formatDate(rel.publishedAt)}
                    </span>
                  )}
                  {rel.downloads !== undefined && (
                    <span className="flex items-center gap-1 text-xs" style={{ color: "var(--muted-foreground)" }}>
                      <Download size={11} />
                      {formatAr(rel.downloads)} تنزيل
                    </span>
                  )}
                </div>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
