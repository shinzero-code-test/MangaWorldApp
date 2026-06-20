"use client";

import { useEffect, useState, useCallback } from "react";
import Link from "next/link";

interface User {
  id: string;
  username: string;
  role: string;
  email: string;
  disabled: boolean;
  providers: string[];
  lastSignIn: string;
  updatedAt: number;
}

export default function UsersPage() {
  const [users, setUsers] = useState<User[]>([]);
  const [search, setSearch] = useState("");
  const [roleFilter, setRoleFilter] = useState("");
  const [providerFilter, setProviderFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);
  const [total, setTotal] = useState(0);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), limit: "20" });
      if (search) params.set("search", search);
      if (roleFilter) params.set("role", roleFilter);
      if (providerFilter) params.set("provider", providerFilter);
      const res = await fetch(`/api/users?${params}`);
      const data = await res.json();
      setUsers(data.users || []);
      setHasMore(data.hasMore || false);
      setTotal(data.total || 0);
    } catch { setUsers([]); }
    setLoading(false);
  }, [page, search, roleFilter, providerFilter]);

  useEffect(() => { loadUsers(); }, [loadUsers]);
  useEffect(() => { setPage(1); }, [search, roleFilter, providerFilter]);

  const roleCounts = {
    all: total,
    "super-admin": users.filter(u => u.role === "super-admin").length,
    moderator: users.filter(u => u.role === "moderator").length,
    viewer: users.filter(u => u.role === "viewer").length,
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div>
          <h3 className="text-lg font-semibold">المستخدمون</h3>
          <p className="text-sm text-[var(--muted-foreground)]">{total} مستخدم مسجل</p>
        </div>
      </div>

      {/* Filters */}
      <div className="flex items-center gap-3 flex-wrap">
        <input type="text" placeholder="بحث بالاسم أو البريد..." value={search} onChange={e => setSearch(e.target.value)}
          className="w-64 px-4 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm" dir="ltr" />
        <select value={roleFilter} onChange={e => setRoleFilter(e.target.value)}
          className="px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm">
          <option value="">جميع الأدوار</option>
          <option value="super-admin">مدير عام</option>
          <option value="moderator">مشرف</option>
          <option value="viewer">مشاهد</option>
        </select>
        <select value={providerFilter} onChange={e => setProviderFilter(e.target.value)}
          className="px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm">
          <option value="">جميع الطرق</option>
          <option value="google.com">Google</option>
          <option value="password">بريد إلكتروني</option>
          <option value="anonymous">مجهول</option>
        </select>
      </div>

      {/* Users Table */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] overflow-hidden">
        <table className="w-full">
          <thead>
            <tr className="border-b border-[var(--border)] bg-[var(--accent)]/50">
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">المستخدم</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">الدور</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">الحالة</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">آخر دخول</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">إجراءات</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={i} className="border-b border-[var(--border)]">
                  <td colSpan={5} className="px-4 py-3"><div className="h-4 bg-[var(--muted)] rounded animate-pulse" /></td>
                </tr>
              ))
            ) : users.length === 0 ? (
              <tr><td colSpan={5} className="px-4 py-12 text-center text-[var(--muted-foreground)]">
                <span className="text-3xl block mb-2">👤</span>لا يوجد مستخدمون
              </td></tr>
            ) : (
              users.map(user => (
                <tr key={user.id} className="border-b border-[var(--border)] last:border-0 hover:bg-[var(--accent)]/50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-sm font-medium text-[var(--primary)]">
                        {user.username?.[0]?.toUpperCase() || "?"}
                      </div>
                      <div>
                        <div className="font-medium text-sm">{user.username || user.id.slice(0, 8)}</div>
                        <div className="text-xs text-[var(--muted-foreground)]" dir="ltr">{user.email || user.id.slice(0, 16)}</div>
                      </div>
                    </div>
                  </td>
                  <td className="px-4 py-3">
                    <span className={`inline-block px-2.5 py-0.5 rounded-full text-xs font-medium ${
                      user.role === "super-admin" ? "bg-red-100 text-red-700" :
                      user.role === "moderator" ? "bg-blue-100 text-blue-700" :
                      "bg-gray-100 text-gray-700"
                    }`}>
                      {user.role === "super-admin" ? "مدير" : user.role === "moderator" ? "مشرف" : "مشاهد"}
                    </span>
                  </td>
                  <td className="px-4 py-3">
                    {user.disabled ? (
                      <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-700">محظور</span>
                    ) : (
                      <span className="text-xs px-2 py-0.5 rounded-full bg-green-100 text-green-700">نشط</span>
                    )}
                  </td>
                  <td className="px-4 py-3 text-sm text-[var(--muted-foreground)]">
                    {user.lastSignIn ? new Date(user.lastSignIn).toLocaleDateString("ar-SA") : "—"}
                  </td>
                  <td className="px-4 py-3">
                    <Link href={`/dashboard/users/${user.id}`} className="text-[var(--primary)] text-sm hover:underline">تفاصيل →</Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex items-center justify-center gap-2">
        <button onClick={() => setPage(p => Math.max(1, p - 1))} disabled={page === 1}
          className="px-3 py-1.5 rounded-lg border border-[var(--border)] text-sm disabled:opacity-50 hover:bg-[var(--accent)]">السابق</button>
        <span className="text-sm text-[var(--muted-foreground)]">صفحة {page}</span>
        <button onClick={() => setPage(p => p + 1)} disabled={!hasMore}
          className="px-3 py-1.5 rounded-lg border border-[var(--border)] text-sm disabled:opacity-50 hover:bg-[var(--accent)]">التالي</button>
      </div>
    </div>
  );
}
