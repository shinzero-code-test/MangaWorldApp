"use client";

import { useEffect, useState } from "react";
import Link from "next/link";

interface User {
  id: string;
  username: string;
  role: string;
  bio?: string;
  updatedAt: number;
}

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [search, setSearch] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetch(`/api/users?search=${search}`)
      .then((r) => r.json())
      .then((data) => { setUsers(data.users || []); setLoading(false); })
      .catch(() => setLoading(false));
  }, [search]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <input
          type="text"
          placeholder="بحث عن مستخدم..."
          value={search}
          onChange={(e) => setSearch(e.target.value)}
          className="w-80 px-4 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-[var(--foreground)] text-sm"
          dir="ltr"
        />
        <span className="text-sm text-[var(--muted-foreground)]">{users.length} مستخدم</span>
      </div>

      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] overflow-hidden">
        <table className="w-full">
          <thead>
            <tr className="border-b border-[var(--border)]">
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">المستخدم</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">الدور</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">آخر نشاط</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">إجراءات</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[var(--muted-foreground)]">جاري التحميل...</td></tr>
            ) : users.length === 0 ? (
              <tr><td colSpan={4} className="px-4 py-8 text-center text-[var(--muted-foreground)]">لا يوجد مستخدمون</td></tr>
            ) : (
              users.map((user) => (
                <tr key={user.id} className="border-b border-[var(--border)] last:border-0 hover:bg-[var(--accent)]">
                  <td className="px-4 py-3">
                    <div className="font-medium text-sm">{user.username || user.id.slice(0, 8)}</div>
                    <div className="text-xs text-[var(--muted-foreground)]">{user.id.slice(0, 16)}...</div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-block px-2 py-0.5 rounded-full text-xs font-medium ${
                      user.role === "super-admin" ? "bg-red-100 text-red-700" :
                      user.role === "moderator" ? "bg-blue-100 text-blue-700" :
                      "bg-gray-100 text-gray-700"
                    }`}>
                      {user.role === "super-admin" ? "مدير" : user.role === "moderator" ? "مشرف" : "مشاهد"}
                    </span>
                  </td>
                  <td className="px-4 py-3 text-sm text-[var(--muted-foreground)]">
                    {user.updatedAt ? new Date(user.updatedAt).toLocaleDateString("ar-SA") : "—"}
                  </td>
                  <td className="px-4 py-3">
                    <Link href={`/dashboard/users/${user.id}`} className="text-[var(--primary)] text-sm hover:underline">
                      تفاصيل
                    </Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </div>
  );
}
