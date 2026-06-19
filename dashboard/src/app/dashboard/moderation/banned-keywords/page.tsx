"use client";

import { useEffect, useState } from "react";

export default function BannedKeywordsPage() {
  const [keywords, setKeywords] = useState("");
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);

  useEffect(() => {
    fetch("/api/moderation/banned-keywords")
      .then((r) => r.json())
      .then((data) => { setKeywords(data.keywords || ""); setLoading(false); })
      .catch(() => setLoading(false));
  }, []);

  const save = async () => {
    setSaving(true);
    await fetch("/api/moderation/banned-keywords", {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ keywords }),
    });
    setSaving(false);
  };

  if (loading) return <div className="text-[var(--muted-foreground)]">جاري التحميل...</div>;

  return (
    <div className="space-y-6 max-w-2xl">
      <div>
        <h3 className="text-lg font-semibold mb-2">الكلمات المحظورة</h3>
        <p className="text-sm text-[var(--muted-foreground)]">كل سطر كلمة واحدة. سيتم حذف التعليقات التي تحتوي على هذه الكلمات.</p>
      </div>
      <textarea
        value={keywords}
        onChange={(e) => setKeywords(e.target.value)}
        className="w-full h-64 px-4 py-3 rounded-lg border border-[var(--border)] bg-[var(--background)] text-[var(--foreground)] text-sm font-mono"
        placeholder={"كلمة1\nكلمة2\nكلمة3"}
        dir="ltr"
      />
      <button
        onClick={save}
        disabled={saving}
        className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium hover:opacity-90 disabled:opacity-50"
      >
        {saving ? "جاري الحفظ..." : "حفظ"}
      </button>
    </div>
  );
}
