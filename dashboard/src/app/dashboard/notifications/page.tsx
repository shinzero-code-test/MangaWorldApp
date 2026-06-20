"use client";

import { useState, useEffect } from "react";

interface NotificationHistory {
  id: string;
  title: string;
  body: string;
  topic?: string;
  sentAt: number;
  status: string;
}

export default function NotificationsPage() {
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [topic, setTopic] = useState("all");
  const [sending, setSending] = useState(false);
  const [result, setResult] = useState<{ success: boolean; message: string } | null>(null);
  const [tokenCount, setTokenCount] = useState(0);
  const [history, setHistory] = useState<NotificationHistory[]>([]);
  const [activeTab, setActiveTab] = useState<"send" | "history">("send");

  useEffect(() => {
    fetch("/api/notifications/tokens").then(r => r.json()).then(d => setTokenCount(d.tokens?.length || 0));
    fetch("/api/notifications/history").then(r => r.json()).then(d => setHistory(d.history || []));
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
        // Save to history
        await fetch("/api/notifications/history", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ title: title.trim(), body: body.trim(), topic }),
        });
        setResult({ success: true, message: `تم الإرسال بنجاح! (${data.sent || 1} مستلم)` });
        setTitle("");
        setBody("");
        // Refresh history
        fetch("/api/notifications/history").then(r => r.json()).then(d => setHistory(d.history || []));
      } else {
        setResult({ success: false, message: data.error || "خطأ" });
      }
    } catch { setResult({ success: false, message: "خطأ في الاتصال" }); }
    setSending(false);
  };

  const topics = [
    { value: "all", label: "جميع المستخدمين", desc: "إرسال للجميع", icon: "📢" },
    { value: "new_chapters", label: "الفصول الجديدة", desc: "إشعار بفصل جديد", icon: "📖" },
    { value: "updates", label: "التحديثات", desc: "إشعار بتحديث", icon: "🔄" },
  ];

  return (
    <div className="space-y-6">
      <div className="flex items-center justify-between">
        <h3 className="text-lg font-semibold">الإشعارات</h3>
        <span className="text-xs text-[var(--muted-foreground)]">📱 {tokenCount} جهاز مسجل</span>
      </div>

      {/* Tabs */}
      <div className="flex gap-2 bg-[var(--card)] p-1 rounded-xl border border-[var(--border)]">
        {(["send", "history"] as const).map(tab => (
          <button key={tab} onClick={() => setActiveTab(tab)}
            className={`flex-1 px-4 py-2 rounded-lg text-sm font-medium transition ${activeTab === tab ? "bg-[var(--primary)] text-[var(--primary-foreground)]" : "text-[var(--muted-foreground)]"}`}>
            {tab === "send" ? "إرسال" : "السجل"}
          </button>
        ))}
      </div>

      {activeTab === "send" ? (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6 space-y-5">
          {/* Topic Selection */}
          <div>
            <label className="block text-sm font-medium mb-2">المستلمون</label>
            <div className="grid grid-cols-3 gap-2">
              {topics.map(t => (
                <button key={t.value} onClick={() => setTopic(t.value)}
                  className={`p-3 rounded-lg border text-right transition ${topic === t.value ? "border-[var(--primary)] bg-[var(--primary)]/5" : "border-[var(--border)] hover:bg-[var(--accent)]"}`}>
                  <span className="text-lg block mb-1">{t.icon}</span>
                  <p className="text-sm font-medium">{t.label}</p>
                  <p className="text-xs text-[var(--muted-foreground)]">{t.desc}</p>
                </button>
              ))}
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">العنوان *</label>
            <input value={title} onChange={e => setTitle(e.target.value)} maxLength={100}
              className="w-full px-4 py-2.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm" placeholder="عنوان الإشعار" />
            <p className="text-xs text-[var(--muted-foreground)] mt-1">{title.length}/100</p>
          </div>

          <div>
            <label className="block text-sm font-medium mb-1">النص *</label>
            <textarea value={body} onChange={e => setBody(e.target.value)} maxLength={500} className="w-full h-32 px-4 py-2.5 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm resize-none" placeholder="نص الإشعار" />
            <p className="text-xs text-[var(--muted-foreground)] mt-1">{body.length}/500</p>
          </div>

          {(title || body) && (
            <div className="p-4 bg-[var(--background)] rounded-lg border border-[var(--border)]">
              <p className="text-xs text-[var(--muted-foreground)] mb-2">معاينة:</p>
              <div className="flex items-start gap-3">
                <div className="w-10 h-10 rounded-lg bg-[var(--primary)]/10 flex items-center justify-center text-lg shrink-0">📱</div>
                <div>
                  <p className="text-sm font-medium">{title || "عنوان الإشعار"}</p>
                  <p className="text-xs text-[var(--muted-foreground)] mt-0.5">{body || "نص الإشعار"}</p>
                </div>
              </div>
            </div>
          )}

          {result && (
            <div className={`p-3 rounded-lg text-sm font-medium ${result.success ? "bg-green-500/10 text-green-600 border border-green-500/20" : "bg-red-500/10 text-red-600 border border-red-500/20"}`}>
              {result.success ? "✓ " : "✗ "}{result.message}
            </div>
          )}

          <button onClick={send} disabled={sending || !title.trim() || !body.trim()}
            className="w-full py-3 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] font-medium hover:opacity-90 disabled:opacity-50 transition">
            {sending ? <span className="flex items-center justify-center gap-2"><span className="w-4 h-4 border-2 border-current border-t-transparent rounded-full animate-spin" />جاري الإرسال...</span> : "إرسال الإشعار"}
          </button>
        </div>
      ) : (
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] overflow-hidden">
          {history.length === 0 ? (
            <div className="p-12 text-center text-[var(--muted-foreground)]">
              <span className="text-4xl block mb-3">📬</span>
              لا يوجد سجل إشعارات
            </div>
          ) : (
            <div className="divide-y divide-[var(--border)]">
              {history.map(h => (
                <div key={h.id} className="px-5 py-4 hover:bg-[var(--accent)]/50 transition">
                  <div className="flex items-center justify-between mb-1">
                    <span className="text-sm font-medium">{h.title}</span>
                    <span className="text-xs text-[var(--muted-foreground)]">
                      {h.sentAt ? new Date(h.sentAt).toLocaleString("ar-SA") : ""}
                    </span>
                  </div>
                  <p className="text-sm text-[var(--muted-foreground)] line-clamp-1">{h.body}</p>
                  <div className="flex items-center gap-2 mt-1">
                    {h.topic && <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--accent)]">{h.topic}</span>}
                    <span className={`text-[10px] px-1.5 py-0.5 rounded ${h.status === "sent" ? "bg-green-100 text-green-700" : "bg-red-100 text-red-700"}`}>
                      {h.status === "sent" ? "مرسل" : "فشل"}
                    </span>
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      )}
    </div>
  );
}
