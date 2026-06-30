"use client";
import { useEffect, useState, useCallback } from "react";
import Link from "next/link";
import { Users, Search, ExternalLink, Shield, Eye, ChevronLeft, ChevronRight, UserCheck } from "lucide-react";
import { StatusBadge, SkeletonTable, EmptyState, PageHeader } from "@/components/ui";
import { formatAr, formatRelative, formatDate, avatarColor, getInitials } from "@/lib/utils";

interface User {
  id: string; email: string; username?: string; role: string;
  disabled: boolean; lastSignIn?: string; createdAt?: string;
  providers?: string[];
}

const PAGE_SIZE = 20;

export default function UsersPage() {
  const [users,     setUsers]     = useState<User[]>([]);
  const [total,     setTotal]     = useState(0);
  const [loading,   setLoading]   = useState(true);
  const [page,      setPage]      = useState(1);
  const [search,    setSearch]    = useState("");
  const [roleFilter,setRoleFilter]= useState("");
  const [provFilter,setProvFilter]= useState("");

  const fetchUsers = useCallback(async () => {
    setLoading(true);
    const params = new URLSearchParams({
      page: String(page), limit: String(PAGE_SIZE),
      ...(search     && { search }),
      ...(roleFilter && { role: roleFilter }),
      ...(provFilter && { provider: provFilter }),
    });
    try {
      const res  = await fetch(`/api/users?${params}`);
      const data = await res.json();
      setUsers(data.users ?? []);
      setTotal(data.total ?? 0);
    } catch { setUsers([]); }
    finally  { setLoading(false); }
  }, [page, search, roleFilter, provFilter]);

  useEffect(() => { fetchUsers(); }, [fetchUsers]);

  // Count by role from current page (approximate)
  const roleCounts = users.reduce((acc, u) => {
    acc[u.role] = (acc[u.role] ?? 0) + 1; return acc;
  }, {} as Record<string, number>);

  const totalPages = Math.ceil(total / PAGE_SIZE);
  const hasFilters = !!(search || roleFilter || provFilter);

  const statChips = [
    { id:"", label:"الكل", count: total, icon: Users },
    { id:"super-admin", label:"مدير عام", count: roleCounts["super-admin"] ?? 0, icon: Shield },
    { id:"moderator",   label:"مشرف",     count: roleCounts["moderator"]   ?? 0, icon: UserCheck },
    { id:"viewer",      label:"مشاهد",    count: roleCounts["viewer"]      ?? 0, icon: Eye },
  ];

  return (
    <div className="space-y-5">
      <PageHeader title="المستخدمون" subtitle={`${formatAr(total)} مستخدم مسجّل`} icon={Users} />

      <div className="flex gap-2 flex-wrap">
        {statChips.map(chip => {
          const Icon   = chip.icon;
          const active = roleFilter === chip.id;
          return (
            <button key={chip.id} onClick={() => { setRoleFilter(chip.id); setPage(1); }}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-lg border text-sm transition-all"
              style={{
                background:  active ? "color-mix(in srgb, var(--primary) 10%, transparent)" : "var(--card)",
                borderColor: active ? "color-mix(in srgb, var(--primary) 30%, transparent)" : "var(--border)",
                color:       active ? "var(--primary)" : "var(--muted-foreground)",
              }} aria-pressed={active}>
              <Icon size={13} />
              <span>{chip.label}</span>
              <span className="font-mono text-xs px-1.5 py-0.5 rounded" style={{ background:"var(--muted)" }}>
                {formatAr(chip.count)}
              </span>
            </button>
          );
        })}
      </div>

      <div className="flex flex-wrap gap-3 p-4 rounded-[var(--radius-lg)] border"
        style={{ background:"var(--card)", borderColor:"var(--border)" }}>
        <div className="relative flex-1 min-w-[200px]">
          <Search size={15} className="absolute end-3 top-1/2 -translate-y-1/2" style={{ color:"var(--muted-foreground)" }} />
          <input type="text" placeholder="بحث باسم أو إيميل..." value={search}
            onChange={e => { setSearch(e.target.value); setPage(1); }} className="w-full pe-9" dir="rtl" />
        </div>
        <select value={roleFilter} onChange={e => { setRoleFilter(e.target.value); setPage(1); }} className="min-w-[130px]">
          <option value="">كل الأدوار</option>
          <option value="super-admin">مدير عام</option>
          <option value="moderator">مشرف</option>
          <option value="viewer">مشاهد</option>
        </select>
        <select value={provFilter} onChange={e => { setProvFilter(e.target.value); setPage(1); }} className="min-w-[130px]">
          <option value="">كل المزودين</option>
          <option value="google.com">Google</option>
          <option value="password">بريد إلكتروني</option>
        </select>
        {hasFilters && (
          <button onClick={() => { setSearch(""); setRoleFilter(""); setProvFilter(""); setPage(1); }}
            className="px-3 py-1.5 rounded-lg text-sm transition hover:bg-[var(--accent)]"
            style={{ color:"var(--muted-foreground)" }}>
            مسح الفلاتر
          </button>
        )}
      </div>

      <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
        <div className="overflow-x-auto">
          <table aria-label="جدول المستخدمين" aria-busy={loading}>
            <thead>
              <tr>
                <th scope="col">المستخدم</th>
                <th scope="col">الدور</th>
                <th scope="col">الحالة</th>
                <th scope="col">المزود</th>
                <th scope="col">آخر دخول</th>
                <th scope="col" className="w-12"></th>
              </tr>
            </thead>
            <tbody>
              {loading ? (
                <tr><td colSpan={6} className="p-0"><SkeletonTable rows={PAGE_SIZE} cols={5} /></td></tr>
              ) : users.length === 0 ? (
                <tr><td colSpan={6}><EmptyState icon={Users} title="لا يوجد مستخدمون" description="جرّب تغيير فلاتر البحث" /></td></tr>
              ) : (
                users.map(user => {
                  const initials = getInitials(user.username, user.email);
                  const bgColor  = avatarColor(user.id);
                  const provider = user.providers?.[0] ?? "password";
                  return (
                    <tr key={user.id} className="cursor-pointer">
                      <td>
                        <div className="flex items-center gap-3">
                          <div className="w-8 h-8 rounded-full flex items-center justify-center text-xs font-bold text-white shrink-0"
                            style={{ background:bgColor }}>{initials}</div>
                          <div className="min-w-0">
                            <p className="font-medium text-sm truncate">{user.username || "—"}</p>
                            <p className="text-xs font-mono truncate" style={{ color:"var(--muted-foreground)" }} dir="ltr">{user.email}</p>
                          </div>
                        </div>
                      </td>
                      <td><StatusBadge status={user.role} /></td>
                      <td><StatusBadge status={user.disabled ? "banned" : "active"} /></td>
                      <td>
                        <span className="text-xs font-mono px-2 py-0.5 rounded"
                          style={{ background:"var(--muted)", color:"var(--muted-foreground)" }} dir="ltr">
                          {provider === "google.com" ? "Google" : "Email"}
                        </span>
                      </td>
                      <td>
                        <span className="text-sm" title={user.lastSignIn ? formatDate(user.lastSignIn) : "—"}>
                          {user.lastSignIn ? formatRelative(user.lastSignIn) : "—"}
                        </span>
                      </td>
                      <td>
                        <Link href={`/dashboard/users/${user.id}`}
                          className="p-1.5 rounded-lg transition hover:bg-[var(--accent)] inline-flex"
                          aria-label={`عرض ${user.email}`}>
                          <ExternalLink size={14} style={{ color:"var(--muted-foreground)" }} />
                        </Link>
                      </td>
                    </tr>
                  );
                })
              )}
            </tbody>
          </table>
        </div>
        {!loading && users.length > 0 && (
          <div className="flex items-center justify-between px-5 py-3 border-t" style={{ borderColor:"var(--border)" }}>
            <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>
              عرض {formatAr(users.length)} من أصل {formatAr(total)}
            </span>
            <div className="flex items-center gap-1">
              <button onClick={() => setPage(p => Math.max(1,p-1))} disabled={page===1}
                className="p-1.5 rounded-lg transition hover:bg-[var(--accent)] disabled:opacity-40" aria-label="السابقة">
                <ChevronRight size={16} />
              </button>
              <span className="text-sm px-3">{formatAr(page)} / {formatAr(totalPages||1)}</span>
              <button onClick={() => setPage(p => Math.min(totalPages,p+1))} disabled={page>=totalPages}
                className="p-1.5 rounded-lg transition hover:bg-[var(--accent)] disabled:opacity-40" aria-label="التالية">
                <ChevronLeft size={16} />
              </button>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
