"use client";

import type { LucideIcon } from "lucide-react";
import { Loader2 } from "lucide-react";

// ─── StatusBadge ─────────────────────────────────────
const statusMap: Record<string,{bg:string;text:string;dot:string;label:string}> = {
  active:        {bg:"rgba(16,185,129,0.12)",  text:"#10b981", dot:"#10b981", label:"نشط"},
  inactive:      {bg:"rgba(107,114,128,0.12)", text:"#9ca3af", dot:"#9ca3af", label:"غير نشط"},
  banned:        {bg:"rgba(239,68,68,0.12)",   text:"#ef4444", dot:"#ef4444", label:"محظور"},
  open:          {bg:"rgba(245,158,11,0.12)",  text:"#f59e0b", dot:"#f59e0b", label:"مفتوح"},
  resolved:      {bg:"rgba(16,185,129,0.12)",  text:"#10b981", dot:"#10b981", label:"تم الحل"},
  dismissed:     {bg:"rgba(107,114,128,0.12)", text:"#9ca3af", dot:"#9ca3af", label:"مُتجاهَل"},
  "super-admin": {bg:"rgba(239,68,68,0.12)",   text:"#ef4444", dot:"#ef4444", label:"مدير عام"},
  moderator:     {bg:"rgba(59,130,246,0.12)",  text:"#3b82f6", dot:"#3b82f6", label:"مشرف"},
  viewer:        {bg:"rgba(107,114,128,0.12)", text:"#9ca3af", dot:"#9ca3af", label:"مشاهد"},
  critical:      {bg:"rgba(239,68,68,0.12)",   text:"#ef4444", dot:"#ef4444", label:"حرج"},
  warning:       {bg:"rgba(245,158,11,0.12)",  text:"#f59e0b", dot:"#f59e0b", label:"تحذير"},
  info:          {bg:"rgba(59,130,246,0.12)",  text:"#3b82f6", dot:"#3b82f6", label:"معلومة"},
};

interface BadgeProps { status:string; label?:string; size?:"sm"|"md"; }

export function StatusBadge({ status, label, size="md" }: BadgeProps) {
  const c = statusMap[status] ?? statusMap.inactive;
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full font-medium ${size==="sm"?"px-2 py-0.5 text-[10px]":"px-2.5 py-0.5 text-xs"}`}
      style={{ background:c.bg, color:c.text }}
    >
      <span className="rounded-full shrink-0" style={{ width:size==="sm"?4:6, height:size==="sm"?4:6, background:c.dot }} />
      {label ?? c.label}
    </span>
  );
}

// ─── Spinner ──────────────────────────────────────────
export function Spinner({ size=18, className="" }: { size?:number; className?:string }) {
  return <Loader2 size={size} className={`animate-spin ${className}`} style={{ color:"var(--primary)" }} />;
}

// ─── Skeleton ─────────────────────────────────────────
export function Skeleton({ className="", style }: { className?:string; style?:React.CSSProperties }) {
  return <div className={`skeleton-shimmer rounded-lg ${className}`} style={style} />;
}

export function SkeletonCard() {
  return (
    <div className="p-5 rounded-[var(--radius-xl)] border" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
      <div className="flex items-start justify-between mb-4">
        <Skeleton className="w-10 h-10 rounded-xl" />
        <Skeleton className="w-14 h-5 rounded-full" />
      </div>
      <Skeleton className="h-4 w-24 mb-2" />
      <Skeleton className="h-8 w-32" />
      <Skeleton className="h-8 w-full mt-3" />
    </div>
  );
}

export function SkeletonTable({ rows=5, cols=4 }: { rows?:number; cols?:number }) {
  return (
    <div className="space-y-0">
      {Array.from({length:rows}).map((_,i) => (
        <div key={i} className="flex items-center gap-4 px-4 py-3 border-b" style={{ borderColor:"var(--border)" }}>
          {Array.from({length:cols}).map((_,j) => (
            <Skeleton key={j} className="h-4" style={{ flex:j===0?"2":"1" }} />
          ))}
        </div>
      ))}
    </div>
  );
}

// ─── EmptyState ───────────────────────────────────────
interface EmptyStateProps {
  icon:    LucideIcon;
  title:   string;
  description?:  string;
  action?: { label:string; onClick:()=>void };
  color?:  string;
}
export function EmptyState({ icon:Icon, title, description, action, color="var(--muted-foreground)" }: EmptyStateProps) {
  return (
    <div className="flex flex-col items-center justify-center py-16 text-center gap-3">
      <div className="w-16 h-16 rounded-2xl flex items-center justify-center" style={{ background:"var(--accent)" }}>
        <Icon size={28} color={color} />
      </div>
      <div>
        <p className="font-semibold">{title}</p>
        {description && <p className="text-sm mt-1" style={{ color:"var(--muted-foreground)" }}>{description}</p>}
      </div>
      {action && (
        <button onClick={action.onClick}
          className="mt-1 px-4 py-2 rounded-lg text-sm font-medium transition"
          style={{ background:"var(--primary)", color:"var(--primary-foreground)" }}>
          {action.label}
        </button>
      )}
    </div>
  );
}

// ─── Toggle ───────────────────────────────────────────
interface ToggleProps { enabled:boolean; onChange:(v:boolean)=>void; disabled?:boolean; ariaLabel?:string; }
export function Toggle({ enabled, onChange, disabled, ariaLabel }: ToggleProps) {
  return (
    <button
      role="switch" aria-checked={enabled} aria-label={ariaLabel}
      disabled={disabled}
      onClick={() => onChange(!enabled)}
      className="relative inline-flex h-6 w-11 items-center rounded-full transition-all duration-200 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-offset-2 disabled:opacity-50 disabled:cursor-not-allowed"
      style={{
        background:   enabled ? "var(--primary)" : "var(--muted)",
        outlineColor: "var(--primary)",
      }}
    >
      <span
        className="inline-block h-4 w-4 rounded-full bg-white shadow-sm transition-transform duration-200"
        style={{ transform: enabled ? "translateX(-22px)" : "translateX(-4px)" }}
      />
    </button>
  );
}

// ─── PageHeader ───────────────────────────────────────
interface PageHeaderProps { title:string; subtitle?:string; icon:LucideIcon; actions?:React.ReactNode; }
export function PageHeader({ title, subtitle, icon:Icon, actions }: PageHeaderProps) {
  return (
    <div className="flex items-start justify-between gap-4 mb-6">
      <div className="flex items-center gap-3">
        <div className="w-10 h-10 rounded-xl flex items-center justify-center" style={{ background:"var(--accent)" }}>
          <Icon size={20} color="var(--primary)" />
        </div>
        <div>
          <h1 className="text-xl font-bold">{title}</h1>
          {subtitle && <p className="text-sm mt-0.5" style={{ color:"var(--muted-foreground)" }}>{subtitle}</p>}
        </div>
      </div>
      {actions && <div className="flex items-center gap-2">{actions}</div>}
    </div>
  );
}

// ─── ConfirmDialog ────────────────────────────────────
interface ConfirmDialogProps {
  open:boolean; title:string; description:string;
  confirmLabel?:string; cancelLabel?:string;
  variant?:"danger"|"warning";
  onConfirm:()=>void; onCancel:()=>void; loading?:boolean;
}
export function ConfirmDialog({ open, title, description, confirmLabel="تأكيد", cancelLabel="إلغاء", variant="danger", onConfirm, onCancel, loading }: ConfirmDialogProps) {
  if (!open) return null;
  const bg = variant==="danger" ? "var(--destructive)" : "var(--warning)";
  return (
    <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
      <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={onCancel} />
      <dialog open aria-modal="true"
        className="relative w-full max-w-sm rounded-2xl border shadow-2xl p-6 z-10"
        style={{ background:"var(--card)", borderColor:"var(--border)", color:"var(--foreground)" }}>
        <h2 className="text-base font-semibold mb-2">{title}</h2>
        <p className="text-sm mb-5" style={{ color:"var(--muted-foreground)" }}>{description}</p>
        <div className="flex gap-3 justify-end">
          <button onClick={onCancel}
            className="px-4 py-2 rounded-lg text-sm font-medium border transition hover:bg-[var(--accent)]"
            style={{ borderColor:"var(--border)" }}>
            {cancelLabel}
          </button>
          <button onClick={onConfirm} disabled={loading}
            className="px-4 py-2 rounded-lg text-sm font-medium text-white transition disabled:opacity-60 flex items-center gap-2"
            style={{ background:bg }}>
            {loading && <Spinner size={14} className="!text-white" />}
            {confirmLabel}
          </button>
        </div>
      </dialog>
    </div>
  );
}
