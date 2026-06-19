"use client";

export default function ReleasesPage() {
  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">إصدارات التطبيق</h3>
      <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
        <p>عرض سجل الإصدارات من GitHub</p>
        <p className="text-xs mt-2">يتطلب GitHub API token</p>
      </div>
    </div>
  );
}
