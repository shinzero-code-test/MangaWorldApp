"use client";

import { useEffect, useState, useCallback } from "react";
import { MessageSquare, Trash2, ChevronDown, ChevronUp, Search } from "lucide-react";
import { PageHeader, EmptyState, SkeletonTable, ConfirmDialog } from "@/components/ui";
import { formatRelative, truncate } from "@/lib/utils";

interface Comment {
  id:        string;
  userId:    string;
  userName?: string;
  mangaId:   string;
  text:      string;
  createdAt: string | number;
}

export default function CommentsPage() {
  const [comments,      setComments]      = useState<Comment[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [search,        setSearch]        = useState("");
  const [expanded,      setExpanded]      = useState<Set<string>>(new Set());
  const [deleteId,      setDeleteId]      = useState<string | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  const fetch_ = useCallback(async () => {
    setLoading(true);
    try {
      const p   = search ? `?search=${encodeURIComponent(search)}` : "";
      const res = await fetch(`/api/community/comments${p}`);
      const d   = await res.json();
      setComments(d.comments ?? d.data ?? []);
    } catch { setComments([]); }
    finally  { setLoading(false); }
  }, [search]);

  useEffect(() => { fetch_(); }, [fetch_]);

  const toggleExpand = (id: string) =>
    setExpanded((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });

  const handleDelete = async () => {
    if (!deleteId) return;
    setDeleteLoading(true);
    try {
      await fetch(`/api/community/comments?id=${deleteId}`, { method: "DELETE" });
      setComments((p) => p.filter((c) => c.id !== deleteId));
    } finally { setDeleteLoading(false); setDeleteId(null); }
  };

  return (
    <div className="space-y-5">
      <PageHeader
        title="التعليقات"
        subtitle={`${comments.length} تعليق`}
        icon={MessageSquare}
      />

      {/* Search */}
      <div className="relative">
        <Search size={15} className="absolute end-3 top-1/2 -translate-y-1/2"
          style={{ color: "var(--muted-foreground)" }} />
        <input
          type="text" value={search}
          onChange={(e) => setSearch(e.target.value)}
          placeholder="بحث في التعليقات..."
          className="w-full pe-9"
        />
      </div>

      {/* Table */}
      <div className="rounded-[var(--radius-lg)] border overflow-hidden"
        style={{ background: "var(--card)", borderColor: "var(--border)" }}>
        {loading ? (
          <SkeletonTable rows={8} cols={4} />
        ) : comments.length === 0 ? (
          <EmptyState icon={MessageSquare} title="لا توجد تعليقات" description="لم يُضف أي مستخدم تعليقات بعد" />
        ) : (
          <div className="overflow-x-auto">
            <table aria-label="جدول التعليقات">
              <thead>
                <tr>
                  <th scope="col">المستخدم</th>
                  <th scope="col">التعليق</th>
                  <th scope="col">المانجا</th>
                  <th scope="col">التاريخ</th>
                  <th scope="col" className="w-16"></th>
                </tr>
              </thead>
              <tbody>
                {comments.map((c) => {
                  const isExp = expanded.has(c.id);
                  return (
                    <tr key={c.id}>
                      <td>
                        <p className="text-sm font-medium">{c.userName || "مجهول"}</p>
                        <p className="text-xs font-mono" style={{ color: "var(--muted-foreground)" }} dir="ltr">
                          {c.userId.slice(0, 8)}…
                        </p>
                      </td>
                      <td className="max-w-[300px]">
                        <button
                          onClick={() => toggleExpand(c.id)}
                          className="text-sm text-start w-full flex items-start gap-1"
                        >
                          <span className="flex-1">
                            {isExp ? c.text : truncate(c.text, 60)}
                          </span>
                          {c.text.length > 60 && (
                            isExp
                              ? <ChevronUp size={14} className="shrink-0 mt-0.5" style={{ color: "var(--muted-foreground)" }} />
                              : <ChevronDown size={14} className="shrink-0 mt-0.5" style={{ color: "var(--muted-foreground)" }} />
                          )}
                        </button>
                      </td>
                      <td>
                        <code className="text-xs px-1.5 py-0.5 rounded"
                          style={{ background: "var(--muted)", color: "var(--muted-foreground)" }} dir="ltr">
                          {c.mangaId.slice(0, 12)}
                        </code>
                      </td>
                      <td>
                        <span className="text-sm" style={{ color: "var(--muted-foreground)" }}>
                          {formatRelative(c.createdAt)}
                        </span>
                      </td>
                      <td>
                        <button
                          onClick={() => setDeleteId(c.id)}
                          className="p-1.5 rounded-lg transition hover:bg-red-500/10"
                          aria-label="حذف التعليق"
                        >
                          <Trash2 size={14} style={{ color: "var(--destructive)" }} />
                        </button>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ConfirmDialog
        open={!!deleteId}
        title="حذف التعليق"
        description="هل تريد حذف هذا التعليق نهائياً؟"
        confirmLabel="حذف"
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteId(null)}
        loading={deleteLoading}
      />
    </div>
  );
}
