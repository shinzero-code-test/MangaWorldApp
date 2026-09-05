// ─── Number formatters ──────────────────────────────
export const formatAr = (n: number) => n.toLocaleString("ar-SA");

// ─── Date formatters ────────────────────────────────
export const formatDate = (ts: number | string | Date) =>
  new Date(ts).toLocaleDateString("ar-SA", { year:"numeric", month:"short", day:"numeric" });

export const formatDateFull = (ts: number | string | Date) =>
  new Date(ts).toLocaleString("ar-SA", { year:"numeric", month:"short", day:"numeric", hour:"2-digit", minute:"2-digit" });

// ─── Relative time ───────────────────────────────────
// Uses Arabic-Indic digits and proper plural forms (singular/dual/plural).
export const formatRelative = (ts: number | string | Date): string => {
  const diff = Date.now() - new Date(ts).getTime();
  if (diff < 60_000)     return "الآن";
  if (diff < 3_600_000)  return `منذ ${arabicCount(Math.floor(diff / 60_000), "دقيقة", "دقيقتين", "دقائق")}`;
  if (diff < 86_400_000) return `منذ ${arabicCount(Math.floor(diff / 3_600_000), "ساعة", "ساعتين", "ساعات")}`;
  if (diff < 604_800_000)return `منذ ${arabicCount(Math.floor(diff / 86_400_000), "يوم", "يومين", "أيام")}`;
  return formatDate(ts);
};

/** Formats a count with the correct Arabic noun form: 1 دقيقة / 2 دقيقتين / 3-10 دقائق / 11+ دقيقة. */
function arabicCount(n: number, singular: string, dual: string, plural: string): string {
  const digits = formatAr(n);
  if (n === 1) return `${digits} ${singular}`;
  if (n === 2) return dual;
  if (n >= 3 && n <= 10) return `${digits} ${plural}`;
  return `${digits} ${singular}`;
}

// ─── Duration ────────────────────────────────────────
export const formatDuration = (ms: number): string => {
  if (!Number.isFinite(ms) || ms < 0) return "—";
  if (ms < 1000) return `${formatAr(Math.round(ms))} م.ث`;
  return `${formatAr(Number((ms / 1000).toFixed(1)))} ث`;
};

// ─── Avatar ──────────────────────────────────────────
const AVATAR_COLORS = [
  "#7c3aed","#2563eb","#059669","#d97706",
  "#dc2626","#0891b2","#9333ea","#0d9488",
];
export const avatarColor = (uid?: string | null): string => {
  if (!uid) return AVATAR_COLORS[0];
  let h = 0;
  for (let i = 0; i < uid.length; i++) h = uid.charCodeAt(i) + ((h << 5) - h);
  return AVATAR_COLORS[Math.abs(h) % AVATAR_COLORS.length];
};

// ─── Initials ─────────────────────────────────────────
export const getInitials = (name?: string | null, email?: string | null): string => {
  const str  = name || email || "?";
  if (str === "?") return "?";
  const parts = str.split(/[\s@._-]+/).filter(Boolean);
  if (parts.length >= 2) return (parts[0][0] + parts[1][0]).toUpperCase();
  return str.slice(0, 2).toUpperCase();
};

// ─── Class join ──────────────────────────────────────
export const cn = (...c: (string | boolean | undefined | null)[]) => c.filter(Boolean).join(" ");

// ─── Bytes ────────────────────────────────────────────
export const formatBytes = (b: number): string => {
  if (!Number.isFinite(b) || b <= 0) return b === 0 ? "0 B" : "—";
  const k = 1024, sizes = ["B","KB","MB","GB","TB"];
  const i = Math.min(sizes.length - 1, Math.floor(Math.log(b) / Math.log(k)));
  return `${formatAr(Number((b / Math.pow(k, i)).toFixed(1)))} ${sizes[i]}`;
};

// ─── Truncate ─────────────────────────────────────────
export const truncate = (str?: string | null, n: number = 60) => {
  if (!str) return "";
  return str.length > n ? str.slice(0, n) + "…" : str;
};

// ─── Role label ───────────────────────────────────────
export const roleLabel = (r: string) =>
  r === "super-admin" ? "مدير عام" : r === "moderator" ? "مشرف" : "مشاهد";
