"use client";

import { useEffect, useState } from "react";
import { Star, Trash2 } from "lucide-react";
import { PageHeader, EmptyState, SkeletonTable, ConfirmDialog } from "@/components/ui";
import { formatRelative, truncate } from "@/lib/utils";

interface Review {
  id:        string;
  userId:    string;
  userName?: string;
  mangaId:   string;
  rating:    number;
  text:      string;
  createdAt: string | number;
}

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
  const [reviews,       setReviews]       = useState<Review[]>([]);
  const [loading,       setLoading]       = useState(true);
  const [deleteId,      setDeleteId]      = useState<string | null>(null);
  const [deleteLoading, setDeleteLoading] = useState(false);

  useEffect(() => {
    fetch("/api/community/reviews")
      .then((r) => r.json())
      .then((d) => { setReviews(d.reviews ?? d.data ?? []); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const handleDelete = async () => {
    if (!deleteId) return;
    setDeleteLoading(true);
    try {
      await fetch(`/api/community/reviews?id=${deleteId}`, { method: "DELETE" });
      setReviews((p) => p.filter((r) => r.id !== deleteId));
    } finally { setDeleteLoading(false); setDeleteId(null); }
  };

  const avgRating = reviews.length
    ? (reviews.reduce((a, r) => a + r.rating, 0) / reviews.length).toFixed(1)
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
        {loading ? (
          <SkeletonTable rows={8} cols={5} />
        ) : reviews.length === 0 ? (
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
                      <p className="text-sm font-medium">{r.userName || "مجهول"}</p>
                      <p className="text-xs font-mono" style={{ color: "var(--muted-foreground)" }} dir="ltr">
                        {r.userId.slice(0, 8)}…
                      </p>
                    </td>
                    <td>
                      <StarRating rating={r.rating} />
                    </td>
                    <td className="max-w-[250px]">
                      <p className="text-sm">{truncate(r.text || "—", 60)}</p>
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
                      <button onClick={() => setDeleteId(r.id)}
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
        open={!!deleteId}
        title="حذف المراجعة"
        description="هل تريد حذف هذه المراجعة نهائياً؟"
        confirmLabel="حذف"
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteId(null)}
        loading={deleteLoading}
      />
    </div>
  );
}
