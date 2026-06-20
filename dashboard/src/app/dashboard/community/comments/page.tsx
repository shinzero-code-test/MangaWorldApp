"use client";

import { useEffect, useState } from "react";

interface Comment {
  id: string;
  mangaId: string;
  chapterId: string;
  authorUid: string;
  authorName: string;
  text: string;
  spoiler: boolean;
  reportedCount: number;
  createdAt: number;
}

export default function CommentsPage() {
  const [comments, setComments] = useState<Comment[]>([]);
  const [loading, setLoading] = useState(true);
  const [search, setSearch] = useState("");
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const params = new URLSearchParams();
      if (search) params.set("mangaId", search);
      const res = await fetch(`/api/community/comments?${params}`);
      const data = await res.json();
      setComments(data.comments || []);
    } catch {
      setComments([]);
    }
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const deleteComment = async (id: string, mangaId: string, chapterId: string) => {
    if (!confirm("هل أنت متأكد من حذف هذا التعليق؟")) return;
    setDeletingId(id);
    try {
      await fetch("/api/community/comments", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ commentId: id, mangaId, chapterId }),
      });
      setComments((prev) => prev.filter((c) => c.id !== id));
    } catch {}
    setDeletingId(null);
  };

  const filtered = search
    ? comments.filter(
        (c) =>
          c.mangaId?.toLowerCase().includes(search.toLowerCase()) ||
          c.text?.toLowerCase().includes(search.toLowerCase()) ||
          c.authorName?.toLowerCase().includes(search.toLowerCase())
      )
    : comments;

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between flex-wrap gap-4">
        <h3 className="text-lg font-semibold">التعليقات ({filtered.length})</h3>
        <div className="flex items-center gap-3">
          <input
            type="text"
            placeholder="بحث في التعليقات..."
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            className="w-64 px-4 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
            dir="ltr"
          />
          <button onClick={load} className="text-sm text-[var(--primary)] hover:underline">تحديث</button>
        </div>
      </div>

      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 5 }).map((_, i) => (
            <div key={i} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse">
              <div className="h-4 bg-[var(--muted)] rounded w-3/4 mb-2" />
              <div className="h-3 bg-[var(--muted)] rounded w-1/2" />
            </div>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className="p-12 text-center bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <span className="text-4xl block mb-3">💬</span>
          <p className="text-[var(--muted-foreground)]">لا توجد تعليقات</p>
          {search && <p className="text-xs mt-1">جرب تغيير كلمات البحث</p>}
        </div>
      ) : (
        <div className="space-y-2">
          {filtered.map((comment) => (
            <div key={comment.id} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] hover:bg-[var(--accent)]/50 transition">
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2 mb-1">
                    <span className="text-sm font-medium">{comment.authorName || comment.authorUid?.slice(0, 8)}</span>
                    {comment.spoiler && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-yellow-100 text-yellow-700 font-medium">سبويْلر</span>
                    )}
                    {comment.reportedCount > 0 && (
                      <span className="px-1.5 py-0.5 rounded text-[10px] bg-red-100 text-red-700 font-medium">
                        {comment.reportedCount} بلاغ
                      </span>
                    )}
                  </div>
                  <p className="text-sm text-[var(--foreground)] line-clamp-2">{comment.text}</p>
                  <div className="flex items-center gap-3 mt-2 text-xs text-[var(--muted-foreground)]">
                    <span>المانجا: {comment.mangaId?.slice(0, 24)}</span>
                    <span>•</span>
                    <span>{comment.createdAt ? new Date(comment.createdAt).toLocaleDateString("ar-SA") : ""}</span>
                  </div>
                </div>
                <button
                  onClick={() => deleteComment(comment.id, comment.mangaId, comment.chapterId)}
                  disabled={deletingId === comment.id}
                  className="px-3 py-1.5 text-xs rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 font-medium disabled:opacity-50 transition shrink-0"
                >
                  {deletingId === comment.id ? "..." : "حذف"}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
