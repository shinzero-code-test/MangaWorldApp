"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";

interface UserData {
  uid: string;
  email: string;
  emailVerified: boolean;
  disabled: boolean;
  lastSignIn: string;
  createdAt: string;
  providers: { providerId: string; email: string; displayName: string }[];
  customClaims: Record<string, any>;
  phoneNumber: string;
  username: string;
  avatarUrl: string;
  role: string;
  bio: string;
  isPublic: boolean;
  favoriteCount: number;
  historyCount: number;
  annotationCount: number;
  deviceCount: number;
  recentHistory: any[];
  lists: any[];
}

export default function UserDetailPage() {
  const { uid } = useParams();
  const router = useRouter();
  const [user, setUser] = useState<UserData | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState<"overview" | "activity" | "devices" | "settings">("overview");
  const [editMode, setEditMode] = useState(false);
  const [editData, setEditData] = useState({ username: "", bio: "", role: "" });

  const loadUser = async () => {
    try {
      const res = await fetch(`/api/users/${uid}`);
      const data = await res.json();
      setUser(data);
      setEditData({ username: data.username || "", bio: data.bio || "", role: data.role || "viewer" });
    } catch {}
    setLoading(false);
  };

  useEffect(() => { loadUser(); }, [uid]);

  const saveUser = async () => {
    setSaving(true);
    try {
      await fetch(`/api/users/${uid}`, {
        method: "PATCH",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify(editData),
      });
      await loadUser();
      setEditMode(false);
    } catch {}
    setSaving(false);
  };

  const toggleBan = async () => {
    if (!confirm(user?.disabled ? "فك الحظر؟" : "حظر المستخدم؟")) return;
    setSaving(true);
    await fetch(`/api/users/${uid}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ disabled: !user?.disabled }),
    });
    await loadUser();
    setSaving(false);
  };

  const deleteUser = async () => {
    if (!confirm("⚠️ حذف المستخدم نهائياً؟ لا يمكن التراجع عن هذا الإجراء.")) return;
    if (!confirm("هل أنت متأكد？ سيتم حذف جميع بيانات المستخدم.")) return;
    setSaving(true);
    const res = await fetch("/api/users", {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ uid }),
    });
    if (res.ok) router.push("/dashboard/users");
    setSaving(false);
  };

  if (loading) return (
    <div className="space-y-4 animate-pulse">
      <div className="h-8 w-32 bg-[var(--muted)] rounded" />
      <div className="h-40 bg-[var(--card)] rounded-xl border border-[var(--border)]" />
      <div className="grid grid-cols-4 gap-4">
        {[1,2,3,4].map(i => <div key={i} className="h-24 bg-[var(--card)] rounded-xl border border-[var(--border)]" />)}
      </div>
    </div>
  );

  if (!user) return <div className="text-center py-12 text-[var(--muted-foreground)]">المستخدم غير موجود</div>;

  const tabs = [
    { id: "overview" as const, label: "نظرة عامة", icon: "📋" },
    { id: "activity" as const, label: "النشاط", icon: "📊" },
    { id: "devices" as const, label: "الأجهزة", icon: "📱" },
    { id: "settings" as const, label: "الإعدادات", icon: "⚙️" },
  ];

  const providerLabels: Record<string, string> = {
    "google.com": "Google",
    "password": "البريد الإلكتروني",
    "anonymous": "مجهول",
    "phone": "الهاتف",
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <button onClick={() => router.back()} className="text-[var(--primary)] text-sm hover:underline">← رجوع</button>
        <div className="flex items-center gap-2">
          <button onClick={() => setEditMode(!editMode)} className="px-3 py-1.5 text-sm rounded-lg border border-[var(--border)] hover:bg-[var(--accent)]">
            {editMode ? "إلغاء" : "تعديل"}
          </button>
          <button onClick={toggleBan} disabled={saving} className={`px-3 py-1.5 text-sm rounded-lg font-medium ${user.disabled ? "bg-green-500/10 text-green-500" : "bg-red-500/10 text-red-500"}`}>
            {user.disabled ? "فك الحظر" : "حظر"}
          </button>
          <button onClick={deleteUser} disabled={saving} className="px-3 py-1.5 text-sm rounded-lg bg-red-600 text-white font-medium disabled:opacity-50">
            حذف المستخدم
          </button>
        </div>
      </div>

      {/* Profile Header */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <div className="flex items-start gap-4">
          <div className="w-16 h-16 rounded-full bg-gradient-to-br from-[var(--primary)]/30 to-[var(--primary)]/10 flex items-center justify-center text-2xl font-bold text-[var(--primary)]">
            {user.avatarUrl ? (
              <img src={user.avatarUrl} alt="" className="w-16 h-16 rounded-full" />
            ) : (
              user.username?.[0]?.toUpperCase() || "?"
            )}
          </div>
          <div className="flex-1">
            {editMode ? (
              <div className="space-y-2">
                <input value={editData.username} onChange={e => setEditData({...editData, username: e.target.value})} className="w-full px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm" placeholder="اسم المستخدم" />
                <textarea value={editData.bio} onChange={e => setEditData({...editData, bio: e.target.value})} className="w-full px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm h-16" placeholder="النبذة التعريفية" />
                <div className="flex items-center gap-2">
                  <select value={editData.role} onChange={e => setEditData({...editData, role: e.target.value})} className="px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm">
                    <option value="viewer">مشاهد</option>
                    <option value="moderator">مشرف</option>
                    <option value="super-admin">مدير عام</option>
                  </select>
                  <button onClick={saveUser} disabled={saving} className="px-4 py-1.5 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium disabled:opacity-50">
                    {saving ? "..." : "حفظ"}
                  </button>
                </div>
              </div>
            ) : (
              <>
                <h2 className="text-xl font-bold">{user.username || "بدون اسم"}</h2>
                <p className="text-sm text-[var(--muted-foreground)]" dir="ltr">{user.email || "لا بريد إلكتروني"}</p>
                {user.bio && <p className="text-sm mt-1">{user.bio}</p>}
              </>
            )}
          </div>
          <div className="text-left space-y-2">
            <span className={`inline-block px-3 py-1 rounded-full text-sm font-medium ${
              user.role === "super-admin" ? "bg-red-100 text-red-700" :
              user.role === "moderator" ? "bg-blue-100 text-blue-700" :
              "bg-gray-100 text-gray-700"
            }`}>
              {user.role === "super-admin" ? "مدير عام" : user.role === "moderator" ? "مشرف" : "مشاهد"}
            </span>
            {user.disabled && <span className="block px-2 py-0.5 rounded text-xs bg-red-500/10 text-red-500">محظور</span>}
          </div>
        </div>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-2 md:grid-cols-4 gap-4">
        {[
          { label: "المفضلة", value: user.favoriteCount, icon: "❤️" },
          { label: "سجل القراءة", value: user.historyCount, icon: "📖" },
          { label: "الملاحظات", value: user.annotationCount, icon: "📝" },
          { label: "الأجهزة", value: user.deviceCount, icon: "📱" },
        ].map(stat => (
          <div key={stat.label} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
            <div className="flex items-center justify-between mb-2">
              <span className="text-xl">{stat.icon}</span>
            </div>
            <p className="text-sm text-[var(--muted-foreground)]">{stat.label}</p>
            <p className="text-2xl font-bold">{stat.value}</p>
          </div>
        ))}
      </div>

      {/* Tabs */}
      <div className="border-b border-[var(--border)]">
        <div className="flex gap-4">
          {tabs.map(tab => (
            <button key={tab.id} onClick={() => setActiveTab(tab.id)}
              className={`pb-3 px-1 text-sm font-medium border-b-2 transition ${activeTab === tab.id ? "border-[var(--primary)] text-[var(--primary)]" : "border-transparent text-[var(--muted-foreground)] hover:text-[var(--foreground)]"}`}>
              {tab.icon} {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Tab Content */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        {activeTab === "overview" && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div><p className="text-xs text-[var(--muted-foreground)]">تاريخ الإنشاء</p><p className="text-sm font-medium">{user.createdAt ? new Date(user.createdAt).toLocaleString("ar-SA") : "—"}</p></div>
              <div><p className="text-xs text-[var(--muted-foreground)]">آخر دخول</p><p className="text-sm font-medium">{user.lastSignIn ? new Date(user.lastSignIn).toLocaleString("ar-SA") : "—"}</p></div>
              <div><p className="text-xs text-[var(--muted-foreground)]">البريد موثق</p><p className="text-sm font-medium">{user.emailVerified ? "✓ نعم" : "✗ لا"}</p></div>
              <div><p className="text-xs text-[var(--muted-foreground)]">الحالة</p><p className="text-sm font-medium">{user.disabled ? "محظور" : "نشط"}</p></div>
            </div>
            <div>
              <p className="text-xs text-[var(--muted-foreground)] mb-2">طرق تسجيل الدخول</p>
              <div className="flex gap-2 flex-wrap">
                {user.providers.map((p, i) => (
                  <span key={i} className="px-2 py-1 rounded-lg bg-[var(--accent)] text-xs font-medium">
                    {providerLabels[p.providerId] || p.providerId}
                  </span>
                ))}
              </div>
            </div>
            {Object.keys(user.customClaims).length > 0 && (
              <div>
                <p className="text-xs text-[var(--muted-foreground)] mb-2">Custom Claims</p>
                <pre className="p-3 bg-[var(--background)] rounded-lg text-xs font-mono overflow-auto">{JSON.stringify(user.customClaims, null, 2)}</pre>
              </div>
            )}
          </div>
        )}

        {activeTab === "activity" && (
          <div className="space-y-3">
            <h4 className="font-medium">آخر نشاط قراءة</h4>
            {user.recentHistory.length === 0 ? (
              <p className="text-sm text-[var(--muted-foreground)]">لا يوجد نشاط</p>
            ) : (
              user.recentHistory.map((h: any, i: number) => (
                <div key={i} className="flex items-center gap-3 p-3 bg-[var(--background)] rounded-lg">
                  <span className="text-lg">📖</span>
                  <div className="flex-1">
                    <p className="text-sm font-medium">{h.title || h.mangaId}</p>
                    <p className="text-xs text-[var(--muted-foreground)]">
                      فصل {h.lastChapterNumber} • {h.lastReadAt ? new Date(h.lastReadAt).toLocaleDateString("ar-SA") : ""}
                    </p>
                  </div>
                </div>
              ))
            )}
          </div>
        )}

        {activeTab === "devices" && (
          <div className="space-y-3">
            <h4 className="font-medium">الأجهزة المسجلة ({user.deviceCount})</h4>
            <p className="text-sm text-[var(--muted-foreground)]">عرض أجهزة المستخدم المسجلة عبر FCM</p>
          </div>
        )}

        {activeTab === "settings" && (
          <div className="space-y-4">
            <h4 className="font-medium">إعدادات الحساب</h4>
            <div className="space-y-3">
              <div className="flex items-center justify-between p-3 bg-[var(--background)] rounded-lg">
                <span className="text-sm">الملف العام</span>
                <span className="text-sm">{user.isPublic ? "عام" : "خاص"}</span>
              </div>
              <div className="flex items-center justify-between p-3 bg-[var(--background)] rounded-lg">
                <span className="text-sm">الحظر</span>
                <button onClick={toggleBan} className={`px-3 py-1 rounded-lg text-xs font-medium ${user.disabled ? "bg-green-500/10 text-green-500" : "bg-red-500/10 text-red-500"}`}>
                  {user.disabled ? "فك الحظر" : "حظر"}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
