"use client";

import { useState } from "react";

export default function NotificationsPage() {
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [topic, setTopic] = useState("all");
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState("");

  const send = async () => {
    setSending(true);
    setResult("");
    try {
      const res = await fetch("/api/notifications/send", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title, body, topic }),
      });
      const data = await res.json();
      setResult(data.success ? "تم الإرسال بنجاح!" : `خطأ: ${data.error}`);
    } catch {
      setResult("خطأ في الاتصال");
    }
    setSending(false);
  };

  return (
    <div className="space-y-6 max-w-2xl">
      <h3 className="text-lg font-semibold">إرسال إشعار</h3>

      <div className="space-y-4 bg-[var(--card)] rounded-xl border border-[var(--border)] p-6">
        <div>
          <label className="block text-sm font-medium mb-1">العنوان</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
            placeholder="عنوان الإشعار"
          />
        </div>
        <div>
          <label className="block text-sm font-medium mb-1">النص</label>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            className="w-full h-24 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
            placeholder="نص الإشعار"
          />
        </div>
        <div>
          <label className="block text-sm font-medium mb-1">الموضوع (Topic)</label>
          <input
            value={topic}
            onChange={(e) => setTopic(e.target.value)}
            className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
            dir="ltr"
          />
        </div>
        <button
          onClick={send}
          disabled={sending || !title || !body}
          className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium hover:opacity-90 disabled:opacity-50"
        >
          {sending ? "جاري الإرسال..." : "إرسال"}
        </button>
        {result && (
          <p className={`text-sm ${result.includes("خطأ") ? "text-red-500" : "text-green-500"}`}>{result}</p>
        )}
      </div>
    </div>
  );
}
