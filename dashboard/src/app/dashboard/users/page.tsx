"use client";

import { useEffect, useState, useCallback } from "react";
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
  const [roleFilter, setRoleFilter] = useState("");
  const [loading, setLoading] = useState(true);
  const [page, setPage] = useState(1);
  const [hasMore, setHasMore] = useState(false);

  const loadUsers = useCallback(async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams({ page: String(page), limit: "20" });
      if (search) params.set("search", search);
      if (roleFilter) params.set("role", roleFilter);
      const res = await fetch(`/api/users?${params}`);
      const data = await res.json();
      setUsers(data.users || []);
      setHasMore(data.hasMore || false);
    } catch {
      setUsers([]);
    }
    setLoading(false);
  }, [page, search, roleFilter]);

  useEffect(() => { loadUsers(); }, [loadUsers]);

  useEffect(() => {
    const timer = setTimeout(() => { setPage(1); }, 300);
    return () => clearTimeout(timer);
  }, [search, roleFilter]);

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between flex-wrap gap-4">
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder="بحث عن مستخدم..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-64 px-4 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-[var(--foreground)] text-sm"
            dir="ltr"
          />
          <select
            value={roleFilter}
            onChange={(e) => setRoleFilter(e.target.value)}
            className="px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
          >
            <option value="">جميع الأدوار</option>
            <option value="viewer">مشاهد</option>
            <option value="moderator">مشرف</option>
            <option value="super-admin">مدير عام</option>
          </select>
        </div>
        <span className="text-sm text-[var(--muted-foreground)]">{users.length} مستخدم</span>
      </div>

      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] overflow-hidden">
        <table className="w-full">
          <thead>
            <tr className="border-b border-[var(--border)] bg-[var(--accent)]/50">
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">المستخدم</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">الدور</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">آخر نشاط</th>
              <th className="px-4 py-3 text-right text-sm font-medium text-[var(--muted-foreground)]">إجراءات</th>
            </tr>
          </thead>
          <tbody>
            {loading ? (
              Array.from({ length: 5 }).map((_, i) => (
                <tr key={i} className="border-b border-[var(--border)]">
                  <td colSpan={4} className="px-4 py-3">
                    <div className="h-4 bg-[var(--muted)] rounded animate-pulse w-full" />
                  </td>
                </tr>
              ))
            ) : users.length === 0 ? (
              <tr>
                <td colSpan={4} className="px-4 py-12 text-center text-[var(--muted-foreground)]">
                  <div className="flex flex-col items-center gap-2">
                    <span className="text-3xl">👤</span>
                    <p>لا يوجد مستخدمون</p>
                    {search && <p className="text-xs">جرب تغيير كلمات البحث</p>}
                  </div>
                </td>
              </tr>
            ) : (
              users.map((user) => (
                <tr key={user.id} className="border-b border-[var(--border)] last:border-0 hover:bg-[var(--accent)]/50 transition-colors">
                  <td className="px-4 py-3">
                    <div className="flex items-center gap-3">
                      <div className="w-9 h-9 rounded-full bg-[var(--primary)]/10 flex items-center justify-center text-sm font-medium text-[var(--primary)]">
                        {user.username?.[0]?.toUpperCase() || "?"}
                      </div>
                      <div>
                        <div className="font-medium text-sm">{user.username || user.id.slice(0, 8)}</div>
                        <div className="text-xs text-[var(--muted-foreground)] font-mono">{user.id.slice(0, 16)}...</div>
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
                  <td className="px-4 py-3 text-sm text-[var(--muted-foreground)]">
                    {user.updatedAt ? new Date(user.updatedAt).toLocaleDateString("ar-SA", { year: "numeric", month: "short", day: "numeric" }) : "—"}
                  </td>
                  <td className="px-4 py-3">
                    <Link href={`/dashboard/users/${user.id}`} className="text-[var(--primary)] text-sm hover:underline">
                      تفاصيل →
                    </Link>
                  </td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      {/* Pagination */}
      <div className="flex items-center justify-center gap-2">
        <button
          onClick={() => setPage((p) => Math.max(1, p - 1))}
          disabled={page === 1}
          className="px-3 py-1.5 rounded-lg border border-[var(--border)] text-sm disabled:opacity-50 hover:bg-[var(--accent)]"
        >
          السابق
        </button>
        <span className="text-sm text-[var(--muted-foreground)]">صفحة {page}</span>
        <button
          onClick={() => setPage((p) => p + 1)}
          disabled={!hasMore}
          className="px-3 py-1.5 rounded-lg border border-[var(--border)] text-sm disabled:opacity-50 hover:bg-[var(--accent)]"
        >
          التالي
        </button>
      </div>
    </div>
  );
}
