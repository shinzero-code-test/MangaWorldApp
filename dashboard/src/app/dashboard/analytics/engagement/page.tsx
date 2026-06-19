"use client";

export default function EngagementPage() {
  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">التفاعل والاحتفاظ</h3>
      <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
        <p>سيتم عرض مقاييس التفاعل هنا</p>
        <p className="text-xs mt-2">DAU، وقت القراءة، معدل الاحتفاظ</p>
      </div>
    </div>
  );
}
