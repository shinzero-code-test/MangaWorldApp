"use client";

import { useEffect, useState } from "react";

interface Review {
  id: string;
  mangaId: string;
  authorUid: string;
  authorName: string;
  rating: number;
  title: string;
  body: string;
  createdAt: number;
}

export default function ReviewsPage() {
  const [reviews, setReviews] = useState<Review[]>([]);
  const [loading, setLoading] = useState(true);
  const [ratingFilter, setRatingFilter] = useState<number | null>(null);
  const [deletingId, setDeletingId] = useState<string | null>(null);

  const load = async () => {
    setLoading(true);
    try {
      const res = await fetch("/api/community/reviews");
      const data = await res.json();
      setReviews(data.reviews || []);
    } catch {
      setReviews([]);
    }
    setLoading(false);
  };

  useEffect(() => { load(); }, []);

  const deleteReview = async (id: string, mangaId: string) => {
    if (!confirm("هل أنت متأكد من حذف هذه المراجعة؟")) return;
    setDeletingId(id);
    try {
      await fetch("/api/community/reviews", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reviewId: id, mangaId }),
      });
      setReviews((prev) => prev.filter((r) => r.id !== id));
    } catch {}
    setDeletingId(null);
  };

  const filtered = ratingFilter !== null
    ? reviews.filter((r) => r.rating === ratingFilter)
    : reviews;

  const ratingCounts = [5, 4, 3, 2, 1].map((r) => ({
    rating: r,
    count: reviews.filter((rev) => rev.rating === r).length,
  }));

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between flex-wrap gap-4">
        <h3 className="text-lg font-semibold">المراجعات ({filtered.length})</h3>
        <button onClick={load} className="text-sm text-[var(--primary)] hover:underline">تحديث</button>
      </div>

      {/* Rating Filter */}
      <div className="flex gap-2 flex-wrap">
        <button
          onClick={() => setRatingFilter(null)}
          className={`px-3 py-1.5 rounded-lg text-sm font-medium transition ${
            ratingFilter === null ? "bg-[var(--primary)] text-[var(--primary-foreground)]" : "bg-[var(--card)] border border-[var(--border)] hover:bg-[var(--accent)]"
          }`}
        >
          الكل ({reviews.length})
        </button>
        {ratingCounts.map((rc) => (
          <button
            key={rc.rating}
            onClick={() => setRatingFilter(rc.rating)}
            className={`px-3 py-1.5 rounded-lg text-sm font-medium transition flex items-center gap-1 ${
              ratingFilter === rc.rating ? "bg-[var(--primary)] text-[var(--primary-foreground)]" : "bg-[var(--card)] border border-[var(--border)] hover:bg-[var(--accent)]"
            }`}
          >
            <span className="text-yellow-400">★</span>
            {rc.rating} ({rc.count})
          </button>
        ))}
      </div>

      {loading ? (
        <div className="space-y-3">
          {Array.from({ length: 3 }).map((_, i) => (
            <div key={i} className="p-4 bg-[var(--card)] rounded-xl border border-[var(--border)] animate-pulse">
              <div className="h-4 bg-[var(--muted)] rounded w-3/4 mb-2" />
              <div className="h-3 bg-[var(--muted)] rounded w-1/2" />
            </div>
          ))}
        </div>
      ) : filtered.length === 0 ? (
        <div className="p-12 text-center bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <span className="text-4xl block mb-3">⭐</span>
          <p className="text-[var(--muted-foreground)]">لا توجد مراجعات</p>
        </div>
      ) : (
        <div className="space-y-3">
          {filtered.map((review) => (
            <div key={review.id} className="p-5 bg-[var(--card)] rounded-xl border border-[var(--border)] hover:bg-[var(--accent)]/50 transition">
              <div className="flex items-start justify-between gap-4">
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-3 mb-2">
                    <div className="flex items-center gap-0.5">
                      {Array.from({ length: 5 }).map((_, i) => (
                        <span key={i} className={`text-lg ${i < review.rating ? "text-yellow-400" : "text-gray-300"}`}>
                          ★
                        </span>
                      ))}
                    </div>
                    <span className="text-sm font-medium">{review.title || "بدون عنوان"}</span>
                  </div>
                  <p className="text-sm text-[var(--muted-foreground)] line-clamp-3">{review.body}</p>
                  <div className="flex items-center gap-3 mt-3 text-xs text-[var(--muted-foreground)]">
                    <span>{review.authorName || review.authorUid?.slice(0, 8)}</span>
                    <span>•</span>
                    <span>{review.mangaId?.slice(0, 24)}</span>
                    <span>•</span>
                    <span>{review.createdAt ? new Date(review.createdAt).toLocaleDateString("ar-SA") : ""}</span>
                  </div>
                </div>
                <button
                  onClick={() => deleteReview(review.id, review.mangaId)}
                  disabled={deletingId === review.id}
                  className="px-3 py-1.5 text-xs rounded-lg bg-red-500/10 text-red-500 hover:bg-red-500/20 font-medium disabled:opacity-50 transition shrink-0"
                >
                  {deletingId === review.id ? "..." : "حذف"}
                </button>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
