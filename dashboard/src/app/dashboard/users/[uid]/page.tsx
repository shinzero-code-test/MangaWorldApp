"use client";

import { useEffect, useState } from "react";
import { useParams, useRouter } from "next/navigation";

export default function UserDetailPage() {
  const { uid } = useParams();
  const router = useRouter();
  const [user, setUser] = useState<any>(null);
  const [loading, setLoading] = useState(true);
  const [role, setRole] = useState("");

  useEffect(() => {
    fetch(`/api/users/${uid}`)
      .then((r) => r.json())
      .then((data) => { setUser(data); setRole(data.role || "viewer"); setLoading(false); })
      .catch(() => setLoading(false));
  }, [uid]);

  const updateRole = async (newRole: string) => {
    await fetch(`/api/users/${uid}`, {
      method: "PATCH",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ role: newRole }),
    });
    setRole(newRole);
    setUser({ ...user, role: newRole });
  };

  if (loading) return <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>;
  if (!user) return <div className="text-[var(--muted-foreground)]">المستخدم غير موجود</div>;

  return (
    <div className="space-y-6">
      <button onClick={() => router.back()} className="text-[var(--primary)] text-sm hover:underline">← رجوع</button>

      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <div className="flex items-start justify-between">
          <div>
            <h2 className="text-xl font-bold">{user.username || "بدون اسم"}</h2>
            <p className="text-sm text-[var(--muted-foreground)] mt-1">UID: {uid}</p>
            {user.bio && <p className="text-sm mt-2">{user.bio}</p>}
          </div>
          <div className="flex items-center gap-2">
            <select
              value={role}
              onChange={(e) => updateRole(e.target.value)}
              className="px-3 py-1.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
            >
              <option value="viewer">مشاهد</option>
              <option value="moderator">مشرف</option>
              <option value="super-admin">مدير عام</option>
            </select>
          </div>
        </div>
      </div>

      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p className="text-sm text-[var(--muted-foreground)]">المفضلة</p>
          <p className="text-2xl font-bold mt-1">{user.favoriteCount || 0}</p>
        </div>
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p className="text-sm text-[var(--muted-foreground)]">سجل القراءة</p>
          <p className="text-2xl font-bold mt-1">{user.historyCount || 0}</p>
        </div>
        <div className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <p className="text-sm text-[var(--muted-foreground)]">تاريخ الإنشاء</p>
          <p className="text-2xl font-bold mt-1">{user.updatedAt ? new Date(user.updatedAt).toLocaleDateString("ar-SA") : "—"}</p>
        </div>
      </div>
    </div>
  );
}
