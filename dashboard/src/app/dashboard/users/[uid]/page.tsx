"use client";
import { useEffect, useState } from "react";
import { useRouter, useParams } from "next/navigation";
import { User, ChevronRight, ShieldOff, Shield, Save, Loader2, Mail, Calendar, Key, Globe, BookOpen, Heart, MessageSquare, PenLine, Star } from "lucide-react";
import { StatusBadge, ConfirmDialog, Skeleton } from "@/components/ui";
import { formatDate, formatRelative, avatarColor, getInitials } from "@/lib/utils";

interface UserDetail {
  id: string; email: string | null; username?: string; displayName?: string | null; role: string;
  disabled: boolean; lastSignIn?: string; createdAt?: string;
  emailVerified?: boolean; providers?: { providerId: string; email?: string }[];
  bio?: string; avatarUrl?: string;
  favoriteCount?: number; historyCount?: number; annotationCount?: number; deviceCount?: number;
  commentsCount?: number; reviewsCount?: number;
  customClaims?: Record<string, any>;
}

export default function UserDetailPage() {
  const { uid }           = useParams<{ uid: string }>();
  const router            = useRouter();
  const [user, setUser]   = useState<UserDetail | null>(null);
  const [loading,setLoading]     = useState(true);
  const [role, setRole]          = useState("");
  const [roleLoading,setRoleLoading] = useState(false);
  const [roleSaved,setRoleSaved] = useState(false);
  const [banOpen,setBanOpen]     = useState(false);
  const [banLoading,setBanLoading] = useState(false);
  const [canManage,setCanManage]   = useState(false);
  const [roleError,setRoleError]   = useState("");

  useEffect(() => {
    if (!uid) return;
    fetch(`/api/users/${uid}`)
      .then(r => { if (!r.ok) throw new Error("failed"); return r.json(); })
      .then(d => { setUser(d); setRole(d.role ?? "viewer"); setLoading(false); })
      .catch(() => { setLoading(false); router.back(); });
    // Resolve viewer's own role to gate privileged panels.
    fetch("/api/auth/me")
      .then(r => (r.ok ? r.json() : null))
      .then(u => setCanManage(u?.role === "moderator" || u?.role === "super-admin"))
      .catch(() => setCanManage(false));
  }, [uid, router]);

  const handleRoleSave = async () => {
    if (!user) return;
    setRoleLoading(true);
    try {
      const res = await fetch(`/api/users/${uid}`, {
        method:"PATCH",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ role }),
      });
      if (!res.ok) { setRoleError("تعذر حفظ الدور — تحقق من صلاحياتك."); return; }
      setUser(p => p ? { ...p, role } : p);
      setRoleSaved(true);
      setTimeout(() => setRoleSaved(false), 2000);
    } catch {
      setRoleError("خطأ في الاتصال أثناء حفظ الدور.");
    } finally { setRoleLoading(false); }
  };

  const handleBanToggle = async () => {
    if (!user) return;
    setBanLoading(true);
    try {
      const res = await fetch(`/api/users/${uid}/ban`, {
        method:"POST",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ banned: !user.disabled }),
      });
      // Flip local state only when the server accepted the change.
      if (res.ok) setUser(p => p ? { ...p, disabled: !p.disabled } : p);
    } finally { setBanLoading(false); setBanOpen(false); }
  };

  if (loading) return (
    <div className="space-y-5">
      <Skeleton className="h-8 w-40" />
      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        <div className="lg:col-span-2 space-y-4"><Skeleton className="h-48 w-full" /><Skeleton className="h-32 w-full" /></div>
        <Skeleton className="h-48 w-full" />
      </div>
    </div>
  );
  if (!user) return null;

  const initials  = getInitials(user.username, user.email ?? undefined);
  const avatarBg  = avatarColor(user.id);
  const providerIds = user.providers?.map(p => p.providerId) ?? [];

  const stats = [
    { label:"المفضلة", val: user.favoriteCount ?? 0, icon: Heart },
    { label:"سجل القراءة", val: user.historyCount ?? 0, icon: BookOpen },
    { label:"التعليقات", val: user.commentsCount ?? 0, icon: MessageSquare },
    { label:"المراجعات", val: user.reviewsCount ?? 0, icon: Star },
    { label:"الأجهزة", val: user.deviceCount ?? 0, icon: Globe },
    { label:"تعليقات توضيحية", val: user.annotationCount ?? 0, icon: PenLine },
  ];

  return (
    <div className="space-y-5">
      <button onClick={() => router.back()}
        className="flex items-center gap-1.5 text-sm transition hover:opacity-70"
        style={{ color:"var(--muted-foreground)" }}>
        <ChevronRight size={16} />
        العودة إلى المستخدمين
      </button>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-5">
        <div className="lg:col-span-2 space-y-4">
          {/* Main card */}
          <div className="rounded-[var(--radius-lg)] border p-6" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
            <div className="flex items-start gap-4">
              <div className="w-16 h-16 rounded-2xl flex items-center justify-center text-2xl font-bold text-white shrink-0"
                style={{ background: user.avatarUrl ? undefined : avatarBg }}>
                {user.avatarUrl ? (
                  <img src={user.avatarUrl} alt={initials} className="w-full h-full rounded-2xl object-cover" />
                ) : initials}
              </div>
              <div className="flex-1 min-w-0">
                <div className="flex items-center gap-2 flex-wrap">
                  <h2 className="text-xl font-bold">{user.displayName || user.username || "بدون اسم"}</h2>
                  <StatusBadge status={user.role} />
                  <StatusBadge status={user.disabled ? "banned" : "active"} />
                </div>
                <p className="text-sm font-mono mt-1" style={{ color:"var(--muted-foreground)" }} dir="ltr">
                  {user.email ?? "—"}
                </p>
                {user.bio && <p className="text-sm mt-2" style={{ color:"var(--muted-foreground)" }}>{user.bio}</p>}
              </div>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 gap-4 mt-6">
              {[
                { icon:Key,      label:"المعرف",          val: user.id,                                        mono:true },
                { icon:Mail,     label:"البريد",           val: user.email ?? "—",                              mono:true },
                { icon:Calendar, label:"تاريخ الإنشاء",   val: user.createdAt ? formatDate(user.createdAt) : "—",  mono:false },
                { icon:Globe,    label:"آخر دخول",         val: user.lastSignIn ? formatRelative(user.lastSignIn) : "—", mono:false },
              ].map(field => {
                const Icon = field.icon;
                return (
                  <div key={field.label} className="flex items-start gap-3">
                    <div className="w-8 h-8 rounded-lg flex items-center justify-center shrink-0 mt-0.5" style={{ background:"var(--accent)" }}>
                      <Icon size={14} style={{ color:"var(--primary)" }} />
                    </div>
                    <div>
                      <p className="text-xs" style={{ color:"var(--muted-foreground)" }}>{field.label}</p>
                      <p className={`text-sm font-medium mt-0.5 break-all ${field.mono ? "font-mono" : ""}`} dir={field.mono ? "ltr" : "auto"}>
                        {field.val}
                      </p>
                    </div>
                  </div>
                );
              })}
            </div>

            {providerIds.length > 0 && (
              <div className="mt-5 pt-4 border-t" style={{ borderColor:"var(--border)" }}>
                <p className="text-xs font-semibold mb-2" style={{ color:"var(--muted-foreground)" }}>مزودو المصادقة</p>
                <div className="flex gap-2 flex-wrap">
                  {providerIds.map(p => (
                    <span key={p} className="text-xs font-mono px-2.5 py-1 rounded-full" style={{ background:"var(--accent)", color:"var(--primary)" }} dir="ltr">{p}</span>
                  ))}
                </div>
              </div>
            )}
          </div>

          {/* Stats */}
          <div className="grid grid-cols-2 sm:grid-cols-4 gap-3">
            {stats.map(s => {
              const Icon = s.icon;
              return (
                <div key={s.label} className="p-4 rounded-[var(--radius-lg)] border text-center" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
                  <Icon size={16} className="mx-auto mb-2" style={{ color:"var(--primary)" }} />
                  <p className="text-xl font-bold">{s.val}</p>
                  <p className="text-xs mt-0.5" style={{ color:"var(--muted-foreground)" }}>{s.label}</p>
                </div>
              );
            })}
          </div>
        </div>

        {/* Right: actions — hidden from viewers; the API enforces the same rule server-side */}
        <div className="space-y-4">
          {canManage ? (
          <>
          <div className="rounded-[var(--radius-lg)] border p-5" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
            <p className="font-semibold text-sm mb-4">إدارة الصلاحيات</p>
            <div className="space-y-3">
              <div>
                <label className="text-xs font-medium block mb-1.5" style={{ color:"var(--muted-foreground)" }}>الدور</label>
                <select value={role} onChange={e => setRole(e.target.value)} className="w-full">
                  <option value="viewer">مشاهد</option>
                  <option value="moderator">مشرف</option>
                  <option value="super-admin">مدير عام</option>
                </select>
              </div>
              {roleError && (
                <p className="text-xs" style={{ color:"var(--destructive)" }}>{roleError}</p>
              )}
              <button onClick={handleRoleSave} disabled={roleLoading || role === user.role}
                className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-50"
                style={{ background:"var(--primary)", color:"var(--primary-foreground)" }}>
                {roleLoading ? <Loader2 size={15} className="animate-spin" /> : <Save size={15} />}
                {roleSaved ? "تم الحفظ!" : "حفظ الدور"}
              </button>
            </div>
          </div>

          <div className="rounded-[var(--radius-lg)] border p-5" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
            <p className="font-semibold text-sm mb-2">{user.disabled ? "إلغاء الحظر" : "حظر المستخدم"}</p>
            <p className="text-xs mb-4" style={{ color:"var(--muted-foreground)" }}>
              {user.disabled ? "المستخدم محظور حالياً. إلغاء الحظر سيتيح له الدخول مجدداً." : "حظر المستخدم يمنعه من تسجيل الدخول."}
            </p>
            <button onClick={() => setBanOpen(true)}
              className="w-full flex items-center justify-center gap-2 py-2.5 rounded-xl text-sm font-semibold border transition hover:opacity-90"
              style={user.disabled
                ? { background:"rgba(16,185,129,0.1)", borderColor:"rgba(16,185,129,0.3)", color:"var(--success)" }
                : { background:"rgba(239,68,68,0.1)", borderColor:"rgba(239,68,68,0.3)", color:"var(--destructive)" }}>
              {user.disabled ? <Shield size={15} /> : <ShieldOff size={15} />}
              {user.disabled ? "إلغاء الحظر" : "حظر المستخدم"}
            </button>
          </div>
          </>
          ) : (
            <div className="rounded-[var(--radius-lg)] border p-5 text-sm" style={{ background:"var(--card)", borderColor:"var(--border)", color:"var(--muted-foreground)" }}>
              إدارة الصلاحيات متاحة للمشرفين والمديرين فقط.
            </div>
          )}
        </div>
      </div>

      <ConfirmDialog
        open={banOpen}
        title={user.disabled ? "إلغاء حظر المستخدم" : "حظر المستخدم"}
        description={user.disabled ? `هل تريد إلغاء حظر "${user.email ?? "—"}"؟` : `هل تريد حظر "${user.email ?? "—"}"؟ لن يتمكن من الدخول حتى يتم رفع الحظر.`}
        confirmLabel={user.disabled ? "إلغاء الحظر" : "حظر"}
        variant="danger"
        onConfirm={handleBanToggle}
        onCancel={() => setBanOpen(false)}
        loading={banLoading}
      />
    </div>
  );
}
