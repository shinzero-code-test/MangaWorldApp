"use client";
import { Radio } from "lucide-react";
import { PageHeader } from "@/components/ui";

export default function SourcesConfigPage() {
  return (
    <div className="space-y-5">
      <PageHeader title="إعدادات المصادر" subtitle="ضبط مصادر المانجا" icon={Radio} />
      <div className="flex flex-col items-center justify-center py-24 gap-4 rounded-[var(--radius-lg)] border"
        style={{ background: "var(--card)", borderColor: "var(--border)" }}>
        <div className="w-16 h-16 rounded-2xl flex items-center justify-center" style={{ background: "var(--accent)" }}>
          <Radio size={28} style={{ color: "var(--primary)" }} />
        </div>
        <div className="text-center">
          <p className="font-semibold">إعدادات المصادر</p>
          <p className="text-sm mt-1" style={{ color: "var(--muted-foreground)" }}>
            استخدم Remote Config لإدارة مصادر المانجا
          </p>
        </div>
      </div>
    </div>
  );
}
