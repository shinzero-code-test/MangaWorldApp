"use client";
import { useEffect, useState } from "react";
import { Shield, Plus, X, Save, CheckCircle2, Loader2 } from "lucide-react";
import { PageHeader, EmptyState } from "@/components/ui";

export default function BannedKeywordsPage() {
  const [keywords,  setKeywords]  = useState<string[]>([]);
  const [loading,   setLoading]   = useState(true);
  const [saving,    setSaving]    = useState(false);
  const [saved,     setSaved]     = useState(false);
  const [newKw,     setNewKw]     = useState("");

  useEffect(() => {
    fetch("/api/moderation/banned-keywords")
      .then(r => r.json())
      .then(d => {
        // API returns { keywords: "word1,word2,word3" } as comma-separated string
        const raw = d.keywords ?? "";
        const arr = typeof raw === "string"
          ? raw.split(",").map((s:string) => s.trim()).filter(Boolean)
          : Array.isArray(raw) ? raw : [];
        setKeywords(arr);
        setLoading(false);
      })
      .catch(() => setLoading(false));
  }, []);

  const saveKeywords = async (newList: string[]) => {
    setSaving(true);
    try {
      await fetch("/api/moderation/banned-keywords", {
        method:"PUT",
        headers:{ "Content-Type":"application/json" },
        body: JSON.stringify({ keywords: newList.join(",") }),
      });
      setSaved(true);
      setTimeout(() => setSaved(false), 2000);
    } finally { setSaving(false); }
  };

  const handleAdd = (e: React.FormEvent) => {
    e.preventDefault();
    const kw = newKw.trim();
    if (!kw || keywords.includes(kw)) return;
    const next = [...keywords, kw];
    setKeywords(next);
    setNewKw("");
    saveKeywords(next);
  };

  const handleRemove = (kw: string) => {
    const next = keywords.filter(k => k !== kw);
    setKeywords(next);
    saveKeywords(next);
  };

  return (
    <div className="space-y-5">
      <PageHeader
        title="الكلمات المحظورة"
        subtitle={`${keywords.length} كلمة محظورة`}
        icon={Shield}
        actions={saved ? (
          <span className="flex items-center gap-1.5 text-sm" style={{ color:"var(--success)" }}>
            <CheckCircle2 size={15} /> تم الحفظ
          </span>
        ) : saving ? (
          <Loader2 size={15} className="animate-spin" style={{ color:"var(--primary)" }} />
        ) : undefined}
      />

      <form onSubmit={handleAdd} className="flex gap-3">
        <input type="text" value={newKw} onChange={e => setNewKw(e.target.value)}
          placeholder="أدخل كلمة لحظرها..." className="flex-1" />
        <button type="submit" disabled={!newKw.trim()}
          className="flex items-center gap-2 px-4 py-2 rounded-xl text-sm font-semibold transition hover:opacity-90 disabled:opacity-60"
          style={{ background:"var(--primary)", color:"var(--primary-foreground)" }}>
          <Plus size={15} /> إضافة
        </button>
      </form>

      <div className="rounded-[var(--radius-lg)] border p-5 min-h-[200px]" style={{ background:"var(--card)", borderColor:"var(--border)" }}>
        {loading ? (
          <div className="flex flex-wrap gap-2">
            {Array.from({length:8}).map((_,i) => <div key={i} className="h-8 w-20 rounded-full skeleton-shimmer" />)}
          </div>
        ) : keywords.length === 0 ? (
          <EmptyState icon={Shield} title="لا توجد كلمات محظورة" description="أضف كلمات لمنع ظهورها في التعليقات" />
        ) : (
          <div className="flex flex-wrap gap-2">
            {keywords.map(kw => (
              <span key={kw} className="inline-flex items-center gap-1.5 px-3 py-1.5 rounded-full text-sm border"
                style={{ background:"rgba(239,68,68,0.08)", borderColor:"rgba(239,68,68,0.2)", color:"var(--destructive)" }}>
                {kw}
                <button onClick={() => handleRemove(kw)} className="hover:opacity-70 transition" aria-label={`حذف ${kw}`}>
                  <X size={13} />
                </button>
              </span>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
