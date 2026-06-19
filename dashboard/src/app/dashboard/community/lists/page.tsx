"use client";

export default function ListsPage() {
  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">قوائم المستخدمين</h3>
      <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
        <p>عرض القوائم العامة للمستخدمين</p>
        <p className="text-xs mt-2">قريباً</p>
      </div>
    </div>
  );
}
