"use client";
import { Construction } from "lucide-react";
import { PageHeader } from "@/components/ui";

export default function ListsPage() {
  return (
    <div className="space-y-5">
      <PageHeader title="قوائم القراءة" subtitle="قريباً" icon={Construction} />
      <div className="flex flex-col items-center justify-center py-24 gap-4"
        style={{ background: "var(--card)", borderRadius: "var(--radius-lg)", border: "1px solid var(--border)" }}>
        <div className="w-16 h-16 rounded-2xl flex items-center justify-center"
          style={{ background: "var(--accent)" }}>
          <Construction size={28} style={{ color: "var(--muted-foreground)" }} />
        </div>
        <div className="text-center">
          <p className="font-semibold">قيد التطوير</p>
          <p className="text-sm mt-1" style={{ color: "var(--muted-foreground)" }}>
            ستتوفر هذه الميزة قريباً
          </p>
        </div>
      </div>
    </div>
  );
}
