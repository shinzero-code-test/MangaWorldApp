"use client";

export default function EventsPage() {
  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">سجل الأحداث</h3>
      <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
        <p>يتطلب Firebase Analytics Data API</p>
        <p className="text-xs mt-2">قم بتفعيل Google Analytics API في مشروع Firebase</p>
      </div>
    </div>
  );
}
