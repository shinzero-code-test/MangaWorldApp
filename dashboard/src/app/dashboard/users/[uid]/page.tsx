"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";

interface UserData {
  id: string;
  username: string;
  role: string;
  bio: string;
  updatedAt: number;
  favoriteCount: number;
  historyCount: number;
  email?: string;
  disabled?: boolean;
}

export default function UserDetailPage() {
  const { uid } = useParams();
  const router = useRouter();
  const [user, setUser] = useState<UserData | null>(null);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [activeTab, setActiveTab] = useState<"profile" | "stats" | "activity">("profile");

  useEffect(() => {
    fetch(`/api/users/${uid}`)
      .then((r) => r.json())
      .then((data) => { setUser(data); setLoading(false); })
      .catch(() => setLoading(false));
  }, [uid]);

  const updateRole = async (newRole: string) => {
    setSaving(true);
    await fetch(`/api/users/${uid}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ role: newRole }),
    });
    setUser({ ...user!, role: newRole });
    setSaving(false);
  };

  const toggleBan = async () => {
    if (!confirm(user?.disabled ? "هل تريد فتح الحظر؟" : "هل تريد حظر هذا المستخدم؟")) return;
    setSaving(true);
    await fetch(`/api/users/${uid}/ban`, {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ banned: !user?.disabled }),
    });
    setUser({ ...user!, disabled: !user?.disabled });
    setSaving(false);
  };

  if (loading) return (
    <div className="space-y-4">
      <div className="h-8 w-32 bg-[var(--muted)] rounded animate-pulse" />
      <div className="h-48 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />
      <div className="grid grid-cols-3 gap-4">
        {[1, 2, 3].map((i) => <div key={i} className="h-24 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse" />)}
      </div>
    </div>
  );

  if (!user) return <div className="text-[var(--muted-foreground)] p-8 text-center">المستخدم غير موجود</div>;

  const tabs = [
    { id: "profile" as const, label: "الملف الشخصي" },
    { id: "stats" as const, label: "الإحصائيات" },
    { id: "activity" as const, label: "النشاط" },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <button onClick={() => router.back()} className="text-[var(--primary)] text-sm hover:underline flex items-center gap-1">
          ← رجوع
        </button>
        <div className="flex items-center gap-2">
          <select
            value={user.role}
            onChange={(e) => updateRole(e.target.value)}
            disabled={saving}
            className="px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm disabled:opacity-50"
          >
            <option value="viewer">مشاهد</option>
            <option value="moderator">مشرف</option>
            <option value="super-admin">مدير عام</option>
          </select>
          <button
            onClick={toggleBan}
            disabled={saving}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium disabled:opacity-50 ${
              user.disabled
                ? "bg-green-500/10 text-green-500 hover:bg-green-500/20"
                : "bg-red-500/10 text-red-500 hover:bg-red-500/20"
            }`}
          >
            {user.disabled ? "فك الحظر" : "حظر"}
          </button>
        </div>
      </div>

      {/* Profile Header */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <div className="flex items-center gap-4">
          <div className="w-16 h-16 rounded-full bg-[var(--primary)]/20 flex items-center justify-center text-2xl font-bold text-[var(--primary)]">
            {user.username?.[0]?.toUpperCase() || "?"}
          </div>
          <div className="flex-1">
            <h2 className="text-xl font-bold">{user.username || "بدون اسم"}</h2>
            <p className="text-sm text-[var(--muted-foreground)]">UID: {uid}</p>
            {user.bio && <p className="text-sm mt-1 text-[var(--muted-foreground)]">{user.bio}</p>}
          </div>
          <div className="text-left">
            <span className={`inline-block px-3 py-1 rounded-full text-sm font-medium ${
              user.role === "super-admin" ? "bg-red-100 text-red-700" :
              user.role === "moderator" ? "bg-blue-100 text-blue-700" :
              "bg-gray-100 text-gray-700"
            }`}>
              {user.role === "super-admin" ? "مدير عام" : user.role === "moderator" ? "مشرف" : "مشاهد"}
            </span>
            {user.disabled && (
              <span className="inline-block ml-2 px-2 py-0.5 rounded-full text-xs bg-red-500/10 text-red-500">
                محظور
              </span>
            )}
          </div>
        </div>
      </div>

      {/* Stats Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">المفضلة</p>
              <p className="text-2xl font-bold mt-1">{user.favoriteCount || 0}</p>
            </div>
            <span className="text-2xl">❤️</span>
          </div>
        </div>
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">سجل القراءة</p>
              <p className="text-2xl font-bold mt-1">{user.historyCount || 0}</p>
            </div>
            <span className="text-2xl">📖</span>
          </div>
        </div>
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="flex items-center justify-between">
            <div>
              <p className="text-sm text-[var(--muted-foreground)]">تاريخ الإنشاء</p>
              <p className="text-lg font-bold mt-1">
                {user.updatedAt ? new Date(user.updatedAt).toLocaleDateString("ar-SA") : "—"}
              </p>
            </div>
            <span className="text-2xl">📅</span>
          </div>
        </div>
      </div>

      {/* Tabs */}
      <div className="border-b border-[var(--border)]">
        <div className="flex gap-4">
          {tabs.map((tab) => (
            <button
              key={tab.id}
              onClick={() => setActiveTab(tab.id)}
              className={`pb-3 px-1 text-sm font-medium border-b-2 transition ${
                activeTab === tab.id
                  ? "border-[var(--primary)] text-[var(--primary)]"
                  : "border-transparent text-[var(--muted-foreground)] hover:text-[var(--foreground)]"
              }`}
            >
              {tab.label}
            </button>
          ))}
        </div>
      </div>

      {/* Tab Content */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        {activeTab === "profile" && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div>
                <label className="text-sm text-[var(--muted-foreground)]">اسم المستخدم</label>
                <p className="font-medium">{user.username || "—"}</p>
              </div>
              <div>
                <label className="text-sm text-[var(--muted-foreground)]">البريد الإلكتروني</label>
                <p className="font-medium" dir="ltr">{user.email || "—"}</p>
              </div>
              <div>
                <label className="text-sm text-[var(--muted-foreground)]">الدور</label>
                <p className="font-medium">{user.role === "super-admin" ? "مدير عام" : user.role === "moderator" ? "مشرف" : "مشاهد"}</p>
              </div>
              <div>
                <label className="text-sm text-[var(--muted-foreground)]">الحالة</label>
                <p className="font-medium">{user.disabled ? "محظور" : "نشط"}</p>
              </div>
            </div>
            {user.bio && (
              <div>
                <label className="text-sm text-[var(--muted-foreground)]">النبذة التعريفية</label>
                <p className="mt-1">{user.bio}</p>
              </div>
            )}
          </div>
        )}

        {activeTab === "stats" && (
          <div className="space-y-4">
            <div className="grid grid-cols-2 gap-4">
              <div className="p-4 bg-[var(--background)] rounded-lg">
                <p className="text-sm text-[var(--muted-foreground)]">المانجا المفضلة</p>
                <p className="text-3xl font-bold mt-1">{user.favoriteCount || 0}</p>
              </div>
              <div className="p-4 bg-[var(--background)] rounded-lg">
                <p className="text-sm text-[var(--muted-foreground)]">الفصول المقروءة</p>
                <p className="text-3xl font-bold mt-1">{user.historyCount || 0}</p>
              </div>
            </div>
            <p className="text-sm text-[var(--muted-foreground)]">
              للحصول على إحصائيات تفصيلية أكثر (وقت القراءة، السلسلة، إلخ)، قم بتفعيل Firebase Analytics Data API.
            </p>
          </div>
        )}

        {activeTab === "activity" && (
          <div className="space-y-4">
            <p className="text-sm text-[var(--muted-foreground)]">
              سجل النشاط الأخير للمستخدم. يظهر آخر التعليقات والمراجعات.
            </p>
            <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--background)] rounded-lg">
              <p>سيتم عرض النشاط هنا</p>
              <p className="text-xs mt-1">يتطلب Firebase Analytics Data API</p>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
