"use client";

export default function CrashlyticsPage() {
  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">مراقبة الأعطال</h3>
      <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
        <p>تتطلب Firebase Crashlytics API</p>
        <p className="text-xs mt-2">قم بتفعيل Firebase Crashlytics API</p>
      </div>
    </div>
  );
}
