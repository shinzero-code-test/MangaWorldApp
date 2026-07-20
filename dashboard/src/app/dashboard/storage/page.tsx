"use client";

import { useEffect, useState } from "react";
import { HardDrive, Image, FileText, Database, Folder } from "lucide-react";
import { PieChart, Pie, Cell, ResponsiveContainer, Tooltip } from "recharts";
import { PageHeader, SkeletonCard } from "@/components/ui";
import { formatBytes, formatAr } from "@/lib/utils";

interface StorageBreakdown {
  id:        string;
  label:     string;
  icon:      React.ComponentType<any>;
  bytes:     number;
  fileCount: number;
  color:     string;
}

interface StorageData {
  totalBytes:    number;
  bucketName:    string;
  breakdown:     { id: string; label: string; bytes: number; fileCount: number }[];
}

const ICONS: Record<string, React.ComponentType<any>> = {
  images:    Image,
  documents: FileText,
  cache:     Database,
  other:     Folder,
};

const COLORS = ["#8b5cf6", "#3b82f6", "#10b981", "#f59e0b", "#ef4444"];

export default function StoragePage() {
  const [data,    setData]    = useState<StorageData | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/storage")
      .then((r) => r.json())
      .then((d) => { setData(d); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const breakdown: StorageBreakdown[] = (data?.breakdown ?? [
    { id: "images",    label: "الصور",         bytes: 0, fileCount: 0 },
    { id: "documents", label: "المستندات",     bytes: 0, fileCount: 0 },
    { id: "cache",     label: "الذاكرة المؤقتة",bytes: 0, fileCount: 0 },
    { id: "other",     label: "أخرى",          bytes: 0, fileCount: 0 },
  ]).map((b: any, i: number) => ({
    ...b,
    icon:  ICONS[b.id] ?? Folder,
    color: COLORS[i % COLORS.length],
  }));

  const totalBytes = data?.totalBytes ?? breakdown.reduce((a, b) => a + b.bytes, 0);
  const bucketName = data?.bucketName ?? "manga-world.appspot.com";

  return (
    <div className="space-y-6">
      <PageHeader
        title="التخزين"
        subtitle="Firebase Storage — إحصاءات مساحة التخزين"
        icon={HardDrive}
      />

      {/* Hero card */}
      <div
        className="relative overflow-hidden rounded-[var(--radius-xl)] border p-6"
        style={{
          background:  "linear-gradient(135deg, color-mix(in srgb, var(--primary) 15%, var(--card)), var(--card))",
          borderColor: "var(--border)",
        }}
      >
        <div
          className="absolute -top-16 -end-16 w-40 h-40 rounded-full opacity-10"
          style={{ background: "var(--primary)" }}
        />
        <div className="relative flex flex-col sm:flex-row items-start sm:items-center justify-between gap-6">
          <div>
            <div className="flex items-center gap-2 mb-2">
              <HardDrive size={20} style={{ color: "var(--primary)" }} />
              <p className="font-semibold">إجمالي التخزين</p>
            </div>
            <p className="text-4xl font-bold">
              {loading ? "—" : formatBytes(totalBytes)}
            </p>
            <p
              className="text-sm mt-1 font-mono"
              style={{ color: "var(--muted-foreground)" }}
              dir="ltr"
            >
              {bucketName}
            </p>
          </div>

          {/* Donut chart */}
          {!loading && (
            <div className="shrink-0">
              <ResponsiveContainer width={160} height={160}>
                <PieChart>
                  <Pie
                    data={breakdown}
                    dataKey="bytes"
                    nameKey="label"
                    cx="50%"
                    cy="50%"
                    innerRadius="55%"
                    outerRadius="80%"
                    paddingAngle={3}
                  >
                    {breakdown.map((b, i) => (
                      <Cell key={b.id} fill={b.color} />
                    ))}
                  </Pie>
                  <Tooltip
                    contentStyle={{
                      background:   "var(--card)",
                      border:       "1px solid var(--border)",
                      borderRadius: 8,
                      color:        "var(--foreground)",
                    }}
                    formatter={(v: any) => formatBytes(v)}
                  />
                </PieChart>
              </ResponsiveContainer>
            </div>
          )}
        </div>
      </div>

      {/* Breakdown cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {loading
          ? Array.from({ length: 4 }).map((_, i) => <SkeletonCard key={i} />)
          : breakdown.map((b) => {
              const Icon = b.icon;
              const pct  = totalBytes > 0 ? (b.bytes / totalBytes) * 100 : 0;
              return (
                <div
                  key={b.id}
                  className="p-5 rounded-[var(--radius-xl)] border"
                  style={{ background: "var(--card)", borderColor: "var(--border)" }}
                >
                  <div
                    className="w-10 h-10 rounded-xl flex items-center justify-center mb-4"
                    style={{ background: `${b.color}15` }}
                  >
                    <Icon size={18} style={{ color: b.color }} />
                  </div>
                  <p className="text-sm mb-0.5" style={{ color: "var(--muted-foreground)" }}>
                    {b.label}
                  </p>
                  <p className="text-2xl font-bold">{formatBytes(b.bytes)}</p>
                  <p className="text-xs mt-1" style={{ color: "var(--muted-foreground)" }}>
                    {formatAr(b.fileCount)} ملف
                  </p>

                  {/* Progress bar */}
                  <div
                    className="mt-3 h-1.5 rounded-full overflow-hidden"
                    style={{ background: "var(--muted)" }}
                  >
                    <div
                      className="h-full rounded-full transition-all duration-500"
                      style={{ width: `${pct}%`, background: b.color }}
                    />
                  </div>
                  <p className="text-xs mt-1 font-mono" style={{ color: "var(--muted-foreground)" }}>
                    {pct.toFixed(1)}%
                  </p>
                </div>
              );
            })}
      </div>
    </div>
  );
}
