"use client";

import { useEffect, useState } from "react";
import { MessageSquare, Trash2, ChevronDown, ChevronUp, Search } from "lucide-react";
import { PageHeader, EmptyState, SkeletonTable, ConfirmDialog } from "@/components/ui";
import { formatRelative, truncate } from "@/lib/utils";
import type { CommunityComment } from "@/types/community";

export default function CommentsPage() {
  const [comments,      setComments]      = useState<CommunityComment[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState("");
  const [search,        setSearch]        = useState("");
  const [expanded,      setExpanded]      = useState<Set<string>>(new Set());
  const [deleteTarget,  setDeleteTarget]  = useState<CommunityComment | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  // Debounced + aborted so per-keystroke fetches cannot race out of order.
  useEffect(() => {
    const controller = new AbortController();
    const timer = setTimeout(async () => {
      setLoading(true);
      setError("");
      try {
        const p   = search ? `?search=${encodeURIComponent(search)}` : "";
        const res = await fetch(`/api/community/comments${p}`, { signal: controller.signal });
        if (!res.ok) {
          setComments([]);
          setError(res.status === 403 ? "ليست لديك صلاحية عرض التعليقات" : "فشل تحميل التعليقات");
          return;
        }
        const d   = await res.json();
        // Defensive: never render soft-deleted rows even if the API regresses.
        setComments((Array.isArray(d.comments) ? d.comments : []).filter((c: CommunityComment) => !c.isDeleted));
      } catch (e) {
        if ((e as Error).name !== "AbortError") {
          setComments([]);
          setError("فشل تحميل التعليقات");
        }
      } finally { setLoading(false); }
    }, 300);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [search]);

  const toggleExpand = (id: string) =>
    setExpanded((p) => { const n = new Set(p); n.has(id) ? n.delete(id) : n.add(id); return n; });

  const handleDelete = async () => {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleteLoading(true);
    try {
      const response = await fetch("/api/community/comments", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({
          commentId: target.id,
          mangaId: target.mangaId,
          chapterUrl: target.chapterUrl,
        }),
      });
      if (response.ok) {
        setComments((previous) => previous.filter((comment) =>
          comment.id !== target.id ||
          comment.mangaId !== target.mangaId ||
          comment.chapterUrl !== target.chapterUrl,
        ));
      }
    } finally { setDeleteLoading(false); setDeleteTarget(null); }
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

      {/* Error / access state */}
      {error && (
        <div className="p-3 rounded-[var(--radius-lg)] border text-sm"
          style={{ background: "rgba(239,68,68,0.08)", borderColor: "rgba(239,68,68,0.25)", color: "var(--destructive)" }}>
          {error}
        </div>
      )}

      {/* Table */}
      <div className="rounded-[var(--radius-lg)] border overflow-hidden"
        style={{ background: "var(--card)", borderColor: "var(--border)" }}>
        {loading ? (
          <SkeletonTable rows={8} cols={4} />
        ) : error ? null : comments.length === 0 ? (
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
                        <p className="text-sm font-medium">{c.authorName || c.authorUsername || "مجهول"}</p>
                        <p className="text-xs font-mono" style={{ color: "var(--muted-foreground)" }} dir="ltr">
                           {c.authorUid.slice(0, 8)}…
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
                           onClick={() => setDeleteTarget(c)}
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
         open={deleteTarget !== null}
        title="حذف التعليق"
         description="سيُخفى التعليق مع الإبقاء على الردود المتصلة به."
        confirmLabel="حذف"
        variant="danger"
        onConfirm={handleDelete}
         onCancel={() => setDeleteTarget(null)}
        loading={deleteLoading}
      />
    </div>
  );
}
