"use client";

import { useEffect, useState } from "react";

export default function ReviewsPage() {
  const [reviews, setReviews] = useState<any[]>([]);
  const [loading, setLoading] = useState(true);

  const load = () => {
    fetch("/api/community/reviews")
      .then((r) => r.json())
      .then((data) => { setReviews(data.reviews || []); setLoading(false); })
      .catch(() => setLoading(false));
  };

  useEffect(() => { load(); }, []);

  const deleteReview = async (id: string, mangaId: string) => {
    if (!confirm("هل أنت متأكد من حذف هذه المراجعة؟")) return;
    await fetch("/api/community/reviews", {
      method: "DELETE",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ reviewId: id, mangaId }),
    });
    load();
  };

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">المراجعات ({reviews.length})</h3>
        <button onClick={load} className="text-sm text-[var(--primary)] hover:underline">تحديث</button>
      </div>
      {loading ? (
        <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>
      ) : reviews.length === 0 ? (
        <div className="p-8 text-center text-[var(--muted-foreground)] bg-[var(--card)] rounded-xl border border-[var(--border)]">لا توجد مراجعات</div>
      ) : (
        <div className="space-y-2">
          {reviews.map((r) => (
            <div key={r.id} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] flex items-start justify-between">
              <div className="flex-1">
                <div className="flex items-center gap-2">
                  <span className="text-yellow-500">{"★".repeat(r.rating || 0)}{"☆".repeat(5 - (r.rating || 0))}</span>
                  <span className="text-sm font-medium">{r.title || "بدون عنوان"}</span>
                </div>
                <p className="text-sm text-[var(--muted-foreground)] mt-1">{r.body?.slice(0, 200)}</p>
                <p className="text-xs text-[var(--muted-foreground)] mt-1">
                  {r.authorName || r.authorUid?.slice(0, 8)} • manga: {r.mangaId?.slice(0, 20)}
                </p>
              </div>
              <button onClick={() => deleteReview(r.id, r.mangaId)} className="text-xs text-red-500 hover:underline mr-4">حذف</button>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
