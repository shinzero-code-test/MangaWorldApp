"use client";

import { useEffect, useState } from "react";

export default function CommentsPage() {
  const [comments, setComments] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = () => {
    fetch("/api/community/comments")
      .then((r) => r.json())
      .then((data) => { setComments(data.comments || []); setLoading(false); })
      .catch(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const deleteComment = async (id: string, mangaId: string, chapterId: string) => {
    if (!confirm("هل أنت متأكد من حذف هذا التعليق؟")) return;
    await fetch("/api/community/comments", {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ commentId: id, mangaId, chapterId }),
    });
    load();
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">التعليقات ({comments.length})</h3>
        <button onClick={load} className="text-sm text-[var(--primary)] hover:underline">تحديث</button>
      </div>

      {loading ? (
        <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>
      ) : comments.length === 0 ? (
        <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">
          لا توجد تعليقات
        </div>
      ) : (
        <div className="space-y-2">
          {comments.map((c) => (
            <div key={c.id} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] flex items-start justify-between">
              <div className="flex-1">
                <p className="text-sm">{c.text}</p>
                <p className="text-xs text-[var(--muted-foreground)] mt-1">
                  {c.authorName || c.authorUid?.slice(0, 8)} • manga: {c.mangaId?.slice(0, 20)}
                  {c.createdAt ? ` • ${new Date(c.createdAt).toLocaleDateString("ar-SA")}` : ""}
                </p>
              </div>
              <button
                onClick={() => deleteComment(c.id, c.mangaId, c.chapterId)}
                className="text-xs text-red-500 hover:underline mr-4"
              >
                حذف
              </button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
