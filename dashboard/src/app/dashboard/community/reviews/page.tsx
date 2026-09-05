"use client";

import { useEffect, useState } from "react";
import { Star, Trash2 } from "lucide-react";
import { PageHeader, EmptyState, SkeletonTable, ConfirmDialog } from "@/components/ui";
import { formatRelative, truncate } from "@/lib/utils";
import type { MangaReview } from "@/types/community";

function StarRating({ rating, max = 5 }: { rating: number; max?: number }) {
  return (
    <span className="inline-flex gap-0.5" aria-label={`${rating} من ${max} نجوم`}>
      {Array.from({ length: max }).map((_, i) => (
        <Star
          key={i}
          size={13}
          style={{
            color:  i < rating ? "#f59e0b" : "var(--border)",
            fill:   i < rating ? "#f59e0b" : "none",
          }}
        />
      ))}
    </span>
  );
}

export default function ReviewsPage() {
  const [reviews,       setReviews]       = useState<MangaReview[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [error,         setError]         = useState("");
  const [deleteTarget,  setDeleteTarget]  = useState<MangaReview | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  useEffect(() => {
    fetch("/api/community/reviews")
      .then((r) => {
        if (!r.ok) throw new Error(r.status === 403 ? "forbidden" : "failed");
        return r.json();
      })
      .then((d) => {
        // Defensive: never render soft-deleted rows even if the API regresses.
        setReviews((Array.isArray(d.reviews) ? d.reviews : []).filter((rv: MangaReview) => !rv.isDeleted));
        setLoading(false);
      })
      .catch((e: Error) => {
        setError(e.message === "forbidden" ? "ليست لديك صلاحية عرض المراجعات" : "فشل تحميل المراجعات");
        setLoading(false);
      });
  }, []);

  const handleDelete = async () => {
    if (!deleteTarget) return;
    const target = deleteTarget;
    setDeleteLoading(true);
    try {
      const response = await fetch("/api/community/reviews", {
        method: "DELETE",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ reviewId: target.id, mangaId: target.mangaId }),
      });
      if (response.ok) {
        setReviews((previous) => previous.filter((review) =>
          review.id !== target.id || review.mangaId !== target.mangaId,
        ));
      }
    } finally { setDeleteLoading(false); setDeleteTarget(null); }
  };

  const activeReviews = reviews.filter((r) => !r.isDeleted);
  const avgRating = activeReviews.length
    ? (activeReviews.reduce((a, r) => a + r.rating, 0) / activeReviews.length).toFixed(1)
    : "—";

  return (
    <div className="space-y-5">
      <PageHeader
        title="المراجعات"
        subtitle={`${reviews.length} مراجعة — متوسط التقييم: ${avgRating}`}
        icon={Star}
      />

      <div className="rounded-[var(--radius-lg)] border overflow-hidden"
        style={{ background: "var(--card)", borderColor: "var(--border)" }}>
        {error && (
          <div className="p-3 text-sm" style={{ color: "var(--destructive)" }}>{error}</div>
        )}
        {loading ? (
          <SkeletonTable rows={8} cols={5} />
        ) : !error && reviews.length === 0 ? (
          <EmptyState icon={Star} title="لا توجد مراجعات" description="لم يُضف أي مستخدم مراجعة بعد" />
        ) : (
          <div className="overflow-x-auto">
            <table aria-label="جدول المراجعات">
              <thead>
                <tr>
                  <th scope="col">المستخدم</th>
                  <th scope="col">التقييم</th>
                  <th scope="col">النص</th>
                  <th scope="col">المانجا</th>
                  <th scope="col">التاريخ</th>
                  <th scope="col" className="w-16"></th>
                </tr>
              </thead>
              <tbody>
                {reviews.map((r) => (
                  <tr key={r.id}>
                    <td>
                       <p className="text-sm font-medium">{r.authorName || r.authorUsername || "مجهول"}</p>
                       <p className="text-xs font-mono" style={{ color: "var(--muted-foreground)" }} dir="ltr">
                         {(r.authorUid ?? "").slice(0, 8)}…
                      </p>
                    </td>
                    <td>
                      <StarRating rating={r.rating} />
                    </td>
                    <td className="max-w-[250px]">
                       <p className="text-sm">{truncate(r.title || r.body || "—", 60)}</p>
                    </td>
                    <td>
                      <code className="text-xs px-1.5 py-0.5 rounded"
                        style={{ background: "var(--muted)", color: "var(--muted-foreground)" }} dir="ltr">
                        {r.mangaId.slice(0, 12)}
                      </code>
                    </td>
                    <td>
                      <span className="text-sm" style={{ color: "var(--muted-foreground)" }}>
                        {formatRelative(r.createdAt)}
                      </span>
                    </td>
                    <td>
                       <button onClick={() => setDeleteTarget(r)}
                        className="p-1.5 rounded-lg transition hover:bg-red-500/10"
                        aria-label="حذف المراجعة">
                        <Trash2 size={14} style={{ color: "var(--destructive)" }} />
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>

      <ConfirmDialog
         open={deleteTarget !== null}
        title="حذف المراجعة"
         description="سيُخفى محتوى المراجعة مع الإبقاء على الردود المتصلة بها."
        confirmLabel="حذف"
        variant="danger"
        onConfirm={handleDelete}
         onCancel={() => setDeleteTarget(null)}
        loading={deleteLoading}
      />
    </div>
  );
}
