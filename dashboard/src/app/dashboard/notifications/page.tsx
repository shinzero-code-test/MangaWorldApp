"use client";

import { useState, useEffect } from "react";

interface NotificationHistory {
  id: string;
  title: string;
  body: string;
  topic?: string;
  sentAt: number;
  sentBy: string;
}

export default function NotificationsPage() {
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [topic, setTopic] = useState("all");
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<{ success: boolean; message: string } | null>(null);
  const [tokenCount, setTokenCount] = useState(0);

  useEffect(() => {
    fetch("/api/notifications/tokens")
      .then((r) => r.json())
      .then((data) => setTokenCount(data.tokens?.length || 0))
      .catch(() => {});
  }, []);

  const send = async () => {
    if (!title.trim() || !body.trim()) return;
    setSending(true);
    setResult(null);
    try {
      const res = await fetch("/api/notifications/send", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ title: title.trim(), body: body.trim(), topic }),
      });
      const data = await res.json();
      if (data.success) {
        setResult({ success: true, message: `تم الإرسال بنجاح! (${data.sent || 1} مستلم)` });
        setTitle("");
        setBody("");
      } else {
        setResult({ success: false, message: data.error || "خطأ غير معروف" });
      }
    } catch {
      setResult({ success: false, message: "خطأ في الاتصال بالخادم" });
    }
    setSending(false);
  };

  const topics = [
    { value: "all", label: "جميع المستخدمين", description: "إرسال للجميع" },
    { value: "new_chapters", label: "الفصول الجديدة", description: "إشعار بفصل جديد" },
    { value: "updates", label: "التحديثات", description: "إشعار بتحديث التطبيق" },
  ];

  return (
    <div className="space-y-6 max-w-3xl">
      <div>
        <h3 className="text-lg font-semibold">إرسال إشعار</h3>
        <p className="text-sm text-[var(--muted-foreground)] mt-1">
          أرسل إشعارات للمستخدمين عبر FCM
          <span className="mr-2 inline-flex items-center gap-1 text-xs bg-[var(--accent)] px-2 py-0.5 rounded-full">
            📱 {tokenCount} جهاز مسجل
          </span>
        </p>
      </div>

      {/* Notification Form */}
      <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6 space-y-5">
        {/* Topic Selection */}
        <div>
          <label className="block text-sm font-medium mb-2">المستلمون</label>
          <div className="grid grid-cols-1 md:grid-cols-3 gap-2">
            {topics.map((t) => (
              <button
                key={t.value}
                onClick={() => setTopic(t.value)}
                className={`p-3 rounded-lg border text-right transition ${
                  topic === t.value
                    ? "border-[var(--primary)] bg-[var(--primary)]/5"
                    : "border-[var(--border)] hover:bg-[var(--accent)]"
                }`}
              >
                <p className="text-sm font-medium">{t.label}</p>
                <p className="text-xs text-[var(--muted-foreground)]">{t.description}</p>
              </button>
            ))}
          </div>
        </div>

        {/* Title */}
        <div>
          <label className="block text-sm font-medium mb-1">العنوان *</label>
          <input
            value={title}
            onChange={(e) => setTitle(e.target.value)}
            className="w-full px-4 py-2.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm"
            placeholder="عنوان الإشعار"
            maxLength={100}
          />
          <p className="text-xs text-[var(--muted-foreground)] mt-1">{title.length}/100</p>
        </div>

        {/* Body */}
        <div>
          <label className="block text-sm font-medium mb-1">النص *</label>
          <textarea
            value={body}
            onChange={(e) => setBody(e.target.value)}
            className="w-full h-32 px-4 py-2.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm resize-none"
            placeholder="نص الإشعار التفصيلي"
            maxLength={500}
          />
          <p className="text-xs text-[var(--muted-foreground)] mt-1">{body.length}/500</p>
        </div>

        {/* Preview */}
        {(title || body) && (
          <div className="p-4 bg-[var(--background)] rounded-lg border border-[var(--border)]">
            <p className="text-xs text-[var(--muted-foreground)] mb-2">معاينة الإشعار:</p>
            <div className="flex items-start gap-3">
              <div className="w-10 h-10 rounded-lg bg-[var(--primary)]/10 flex items-center justify-center text-lg shrink-0">
                📱
              </div>
              <div>
                <p className="text-sm font-medium">{title || "عنوان الإشعار"}</p>
                <p className="text-xs text-[var(--muted-foreground)] mt-0.5">{body || "نص الإشعار"}</p>
              </div>
            </div>
          </div>
        )}

        {/* Result */}
        {result && (
          <div className={`p-3 rounded-lg text-sm font-medium ${
            result.success ? "bg-green-500/10 text-green-600 border border-green-500/20" : "bg-red-500/10 text-red-600 border border-red-500/20"
          }`}>
            {result.success ? "✓ " : "✗ "}{result.message}
          </div>
        )}

        {/* Send Button */}
        <button
          onClick={send}
          disabled={sending || !title.trim() || !body.trim()}
          className="w-full py-3 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] font-medium hover:opacity-90 disabled:opacity-50 transition"
        >
          {sending ? (
            <span className="flex items-center justify-center gap-2">
              <span className="w-4 h-4 border-2 border-current border-t-transparent rounded-full animate-spin" />
              جاري الإرسال...
            </span>
          ) : (
            "إرسال الإشعار"
          )}
        </button>
      </div>
    </div>
  );
}
