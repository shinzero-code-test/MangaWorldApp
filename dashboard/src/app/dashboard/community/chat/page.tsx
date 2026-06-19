"use client";

export default function ChatPage() {
  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">المحادثات المباشرة</h3>
      <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
        <p>عرض رسائل المحادثة من غرفة الدردشة العامة</p>
        <p className="text-xs mt-2">يتطلب اتصال Realtime Database في المتصفح</p>
      </div>
    </div>
  );
}
