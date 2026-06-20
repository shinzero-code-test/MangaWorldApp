"use client";

import { useEffect, useState } from "react";

interface StorageStats {
  profiles: number;
  history: number;
  favorites: number;
  total: number;
  profileCount: number;
  historyCount: number;
  favsCount: number;
}

export default function StoragePage() {
  const [stats, setStats] = useState<StorageStats | null>(null);
  const [bucket, setBucket] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch("/api/storage")
      .then(r => r.json())
      .then(data => { setStats(data.stats); setBucket(data.bucket); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  if (loading) return (
    <div className="space-y-4">
      {Array.from({ length: 3 }).map((_, i) => (
        <div key={i} className="h-24 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      ))}
    </div>
  );

  const storageItems = stats ? [
    { label: "الملفات العامة", value: stats.profiles, icon: "👤", count: stats.profileCount, color: "from-blue-500/20" },
    { label: "سجل القراءة", value: stats.history, icon: "📖", count: stats.historyCount, color: "from-green-500/20" },
    { label: "المفضلة", value: stats.favorites, icon: "❤️", count: stats.favsCount, color: "from-red-500/20" },
  ] : [];

  return (
    <div className="space-y-6">
      <div>
        <h3 className="text-lg font-semibold">التخزين — Storage</h3>
        <p className="text-sm text-[var(--muted-foreground)] mt-1">bucket: {bucket}</p>
      </div>

      {/* Total Storage */}
      <div className="p-6 bg-gradient-to-br from-[var(--primary)]/10 to-[var(--primary)]/5 rounded-xl border border-[var(--border)]">
        <div className="flex items-center justify-between">
          <div>
            <p className="text-sm text-[var(--muted-foreground)]">إجمالي التخزين المقدر</p>
            <p className="text-4xl font-bold mt-1">{stats?.total?.toFixed(1) || 0} KB</p>
          </div>
          <span className="text-5xl">💾</span>
        </div>
      </div>

      {/* Storage Breakdown */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {storageItems.map(item => (
          <div key={item.label} className={`p-5 bg-gradient-to-br ${item.color} to-transparent rounded-xl border border-[var(--border)]`}>
            <div className="flex items-center justify-between mb-2">
              <span className="text-2xl">{item.icon}</span>
              <span className="text-xs text-[var(--muted-foreground)]">{item.count} عنصر</span>
            </div>
            <p className="text-sm text-[var(--muted-foreground)]">{item.label}</p>
            <p className="text-2xl font-bold mt-1">{item.value.toFixed(1)} KB</p>
          </div>
        ))}
      </div>

      {/* Storage Bar */}
      {stats && stats.total > 0 && (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
          <h4 className="font-medium mb-4">توزيع التخزين</h4>
          <div className="flex h-6 rounded-full overflow-hidden">
            {storageItems.map(item => (
              <div key={item.label} className="transition-all"
                style={{
                  width: `${(item.value / stats.total) * 100}%`,
                  backgroundColor: item.label.includes("عام") ? "#6366f1" : item.label.includes("قراءة") ? "#22c55e" : "#ef4444",
                }} />
            ))}
          </div>
          <div className="flex justify-between mt-3 text-xs text-[var(--muted-foreground)]">
            {storageItems.map(item => (
              <div key={item.label} className="flex items-center gap-1">
                <span className="w-2 h-2 rounded-full" style={{ backgroundColor: item.label.includes("عام") ? "#6366f1" : item.label.includes("قراءة") ? "#22c55e" : "#ef4444" }} />
                <span>{item.label}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      {/* Storage Rules Info */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <h4 className="font-medium mb-3">قواعد التخزين</h4>
        <div className="space-y-2 text-sm">
          <div className="flex items-center gap-2">
            <span className="text-green-500">✓</span>
            <span>الملفات مقيدة بـ 5 ميجابايت كحد أقصى</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-green-500">✓</span>
            <span>الأنواع المسموحة: صور فقط (image/*)</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-green-500">✓</span>
            <span>المالكون فقط يمكنهم القراءة والكتابة</span>
          </div>
          <div className="flex items-center gap-2">
            <span className="text-yellow-500">⚠</span>
            <span>الملفات العامة يمكن قراءتها من الجميع</span>
          </div>
        </div>
      </div>
    </div>
  );
}
