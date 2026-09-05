"use client";
import { useEffect, useState } from "react";
import { Bell, Smartphone, Megaphone, BookOpen, RefreshCcw, Check, Send, Loader2, CheckCircle2 } from "lucide-react";
import { PageHeader, StatusBadge, EmptyState } from "@/components/ui";
import { formatRelative } from "@/lib/utils";

const TOPICS = [
  { id:"general",     label:"عام",              desc:"إشعار لجميع المستخدمين",    icon:Megaphone,  color:"var(--primary)"  },
  { id:"updates",     label:"تحديثات المانجا",   desc:"للمشتركين في التحديثات",    icon:BookOpen,   color:"#10b981"         },
  { id:"maintenance", label:"صيانة / تحديث",    desc:"إشعارات تقنية",             icon:RefreshCcw, color:"#f59e0b"         },
];

interface HistoryItem {
  id:string; title:string; body:string; topic?:string; sentAt:string|number; status?:string;
}

export default function NotificationsPage() {
  const [tab,          setTab]          = useState<"send"|"history">("send");
  const [topic,        setTopic]        = useState("general");
  const [title,        setTitle]        = useState("");
  const [body,         setBody]         = useState("");
  const [deviceCount,  setDeviceCount]  = useState<number|null>(null);
  const [sending,      setSending]      = useState(false);
  const [sent,         setSent]         = useState(false);
  const [error,        setError]        = useState("");
  const [history,      setHistory]      = useState<HistoryItem[]>([]);
  const [histLoading,  setHistLoading]  = useState(false);

  useEffect(() => {
    // API returns { tokens: [{token, platform, updatedAt}] }
    fetch("/api/notifications/tokens")
      .then(r => r.json())
      .then(d => setDeviceCount((d.tokens ?? []).length))
      .catch(() => setDeviceCount(null));
  }, []);

  useEffect(() => {
    if (tab !== "history") return;
    setHistLoading(true);
    fetch("/api/notifications/history")
      .then(r => r.json())
      .then(d => setHistory(d.history ?? []))
      .catch(() => setHistory([]))
      .finally(() => setHistLoading(false));
  }, [tab]);

  const handleSend = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!title.trim() || !body.trim()) return;
    setSending(true); setError("");
    try {
      const res = await fetch("/api/notifications/send", {
        method:"POST",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ title, body: body, topic }),
      });
      if (!res.ok) {
        const data = await res.json().catch(() => null) as { error?: string } | null;
        throw new Error(data?.error || "خطأ في الإرسال");
      }
      setSent(true); setTimeout(() => setSent(false), 3000);
      setTitle(""); setBody("");
    } catch (e: unknown) { setError(e instanceof Error ? e.message : "خطأ في الإرسال"); }
    finally { setSending(false); }
  };

  const topicLabel = (id?:string) => TOPICS.find(t => t.id===id)?.label ?? (id || "—");

  return (
    <div className="space-y-6">
      <PageHeader
        title="الإشعارات"
        subtitle="إرسال إشعارات FCM لمستخدمي التطبيق"
        icon={Bell}
        actions={deviceCount !== null ? (
          <div className="flex items-center gap-2 px-3 py-1.5 rounded-lg text-sm"
            style={{ background:"var(--accent)", color:"var(--primary)" }}>
            <Smartphone size={15} />
            <span className="font-semibold">{deviceCount.toLocaleString("ar-SA")}</span>
            <span style={{ color:"var(--muted-foreground)" }}>جهاز مسجّل</span>
          </div>
        ) : undefined}
      />

      <div className="flex gap-1 p-1 rounded-[var(--radius-lg)]" style={{ background:"var(--muted)" }}>
        {[{id:"send" as const,label:"إرسال"},{id:"history" as const,label:"السجل"}].map(t => (
          <button key={t.id} onClick={() => setTab(t.id)}
            className="flex-1 py-2 rounded-[var(--radius-md)] text-sm font-medium transition-all"
            style={{ background:tab===t.id?"var(--card)":"transparent", color:tab===t.id?"var(--foreground)":"var(--muted-foreground)", boxShadow:tab===t.id?"0 1px 3px rgba(0,0,0,0.15)":undefined }}>
            {t.label}
          </button>
        ))}
      </div>

      {tab === "send" && (
        <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
          <form onSubmit={handleSend} className="space-y-5">
            <div>
              <p className="text-sm font-medium mb-3">الموضوع</p>
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                {TOPICS.map(t => {
                  const Icon   = t.icon;
                  const active = topic === t.id;
                  return (
                    <button key={t.id} type="button" onClick={() => setTopic(t.id)}
                      className="p-4 rounded-[var(--radius-lg)] border text-start transition-all"
                      style={{ background:active?`${t.color}10`:"var(--card)", borderColor:active?t.color:"var(--border)", borderWidth:active?2:1 }}>
                      <div className="flex items-start justify-between mb-2">
                        <div className="w-8 h-8 rounded-lg flex items-center justify-center" style={{ background:`${t.color}15` }}>
                          <Icon size={15} style={{ color:t.color }} />
                        </div>
                        {active && <div className="w-5 h-5 rounded-full flex items-center justify-center" style={{ background:t.color }}><Check size={11} className="text-white" /></div>}
                      </div>
                      <p className="font-semibold text-sm">{t.label}</p>
                      <p className="text-xs mt-0.5" style={{ color:"var(--muted-foreground)" }}>{t.desc}</p>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium">العنوان</label>
                <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>{title.length}/60</span>
              </div>
              <input type="text" value={title} onChange={e => setTitle(e.target.value.slice(0,60))}
                placeholder="عنوان الإشعار" className="w-full" required />
            </div>

            <div className="space-y-1.5">
              <div className="flex items-center justify-between">
                <label className="text-sm font-medium">نص الإشعار</label>
                <span className="text-xs" style={{ color:"var(--muted-foreground)" }}>{body.length}/200</span>
              </div>
              <textarea value={body} onChange={e => setBody(e.target.value.slice(0,200))}
                placeholder="نص الإشعار الذي سيراه المستخدمون..." rows={4}
                className="w-full resize-none" required />
            </div>

            {error && <p className="text-sm" style={{ color:"var(--destructive)" }}>{error}</p>}

            <button type="submit" disabled={sending||!title.trim()||!body.trim()}
              className="w-full flex items-center justify-center gap-2 py-3 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-50"
              style={{ background:"var(--primary)", color:"var(--primary-foreground)" }}>
              {sending ? <Loader2 size={16} className="animate-spin" /> : sent ? <CheckCircle2 size={16} /> : <Send size={16} />}
              {sending?"جاري الإرسال...":sent?"تم الإرسال!":"إرسال الإشعار"}
            </button>
          </form>

          <div>
            <p className="text-sm font-medium mb-3">معاينة مباشرة</p>
            <div className="rounded-[var(--radius-xl)] border p-5" style={{ background:"var(--muted)", borderColor:"var(--border)" }}>
              <div className="rounded-xl border p-4" style={{ background:"var(--background)", borderColor:"var(--border)" }}>
                <div className="flex items-start gap-3">
                  <div className="w-10 h-10 rounded-xl flex items-center justify-center shrink-0" style={{ background:"color-mix(in srgb, var(--primary) 10%, transparent)" }}>
                    <Smartphone size={18} style={{ color:"var(--primary)" }} />
                  </div>
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center justify-between">
                      <p className="text-xs font-medium" style={{ color:"var(--muted-foreground)" }}>MangaWorld</p>
                      <p className="text-xs" style={{ color:"var(--muted-foreground)" }}>الآن</p>
                    </div>
                    <p className="text-sm font-semibold mt-0.5 line-clamp-1" style={{ color:title?"var(--foreground)":"var(--muted-foreground)" }}>
                      {title || "عنوان الإشعار"}
                    </p>
                    <p className="text-xs mt-0.5 line-clamp-2" style={{ color:"var(--muted-foreground)" }}>
                      {body || "نص الإشعار الذي سيراه المستخدمون..."}
                    </p>
                  </div>
                </div>
              </div>
              <div className="mt-3 flex items-center gap-2">
                <div className="w-2 h-2 rounded-full" style={{ background:"var(--success)" }} />
                <p className="text-xs" style={{ color:"var(--muted-foreground)" }}>
                  سيُرسل إلى موضوع: <strong>{topicLabel(topic)}</strong>
                </p>
              </div>
            </div>
          </div>
        </div>
      )}

      {tab === "history" && (
        <div className="rounded-[var(--radius-lg)] border overflow-hidden" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
          {histLoading ? (
            <div className="p-8 flex justify-center"><Loader2 size={22} className="animate-spin" style={{ color:"var(--primary)" }} /></div>
          ) : history.length === 0 ? (
            <EmptyState icon={Bell} title="لا يوجد سجل إشعارات" description="لم يتم إرسال أي إشعارات بعد" />
          ) : (
            <div className="divide-y" style={{ borderColor:"var(--border)" }}>
              {history.map(item => (
                <div key={item.id} className="flex items-start gap-4 px-5 py-4">
                  <div className="w-2 h-2 mt-2 rounded-full shrink-0"
                    style={{ background: item.status==="sent"?"var(--success)":"var(--destructive)" }} />
                  <div className="flex-1 min-w-0">
                    <div className="flex items-center gap-2 flex-wrap">
                      <p className="font-semibold text-sm">{item.title}</p>
                      {item.topic && (
                        <span className="text-xs px-2 py-0.5 rounded-full font-mono" style={{ background:"var(--muted)", color:"var(--muted-foreground)" }}>
                          {topicLabel(item.topic)}
                        </span>
                      )}
                      <StatusBadge status={item.status==="sent"?"active":"banned"} label={item.status==="sent"?"أُرسل":"فشل"} size="sm" />
                    </div>
                    <p className="text-sm mt-0.5 line-clamp-1" style={{ color:"var(--muted-foreground)" }}>{item.body}</p>
                    <p className="text-xs mt-1" style={{ color:"var(--muted-foreground)" }}>{formatRelative(item.sentAt)}</p>
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
