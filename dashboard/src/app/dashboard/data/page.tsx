"use client";

import { useEffect, useState } from "react";

const COLLECTIONS = [
  { id: "publicProfiles", label: "الملفات العامة", icon: "👤", description: "ملفات المستخدمين العامة" },
  { id: "users", label: "بيانات المستخدمين", icon: "📂", description: "بيانات المستخدمين الفرعية" },
  { id: "community_manga", label: "مجتمع المانجا", icon: "💬", description: "التعليقات والمراجعات" },
  { id: "moderationReports", label: "التقارير الإشرافية", icon: "🛡️", description: "تقارير المحتوى" },
  { id: "user_achievements", label: "الإنجازات", icon: "🏆", description: "إنجازات القراءة" },
  { id: "app_config", label: "إعدادات التطبيق", icon: "⚙️", description: "الإعدادات الافتراضية" },
];

export default function FirestoreBrowserPage() {
  const [selectedCollection, setSelectedCollection] = useState<string | null>(null);
  const [docs, setDocs] = useState<any[]>([]);
  const [loading, setLoading] = useState(false);
  const [selectedDoc, setSelectedDoc] = useState<any>(null);
  const [showCreateModal, setShowCreateModal] = useState(false);
  const [newDoc, setNewDoc] = useState({ id: "", data: "{}" });

  const loadCollection = async (col: string) => {
    setSelectedCollection(col);
    setSelectedDoc(null);
    setLoading(true);
    try {
      const res = await fetch(`/api/firestore/${col}?limit=30`);
      const data = await res.json();
      setDocs(data.docs || []);
    } catch { setDocs([]); }
    setLoading(false);
  };

  const loadDoc = async (col: string, docId: string) => {
    try {
      const res = await fetch(`/api/firestore/${col}/${docId}`);
      const data = await res.json();
      setSelectedDoc(data);
    } catch {}
  };

  const deleteDoc = async (col: string, docId: string) => {
    if (!confirm("حذف المستند؟")) return;
    await fetch(`/api/firestore/${col}/${docId}`, { method: "DELETE" });
    loadCollection(col);
    setSelectedDoc(null);
  };

  const createDoc = async () => {
    if (!selectedCollection) return;
    try {
      const data = JSON.parse(newDoc.data);
      await fetch("/api/firestore", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ collection: selectedCollection, data, docId: newDoc.id || undefined }),
      });
      setShowCreateModal(false);
      setNewDoc({ id: "", data: "{}" });
      loadCollection(selectedCollection);
    } catch { alert("JSON غير صالح"); }
  };

  return (
    <div className="space-y-6">
      <h3 className="text-lg font-semibold">متصفح Firestore</h3>

      <div className="grid grid-cols-1 lg:grid-cols-3 gap-6">
        {/* Collections List */}
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-4">
          <h4 className="font-medium mb-3">المجموعات</h4>
          <div className="space-y-1">
            {COLLECTIONS.map(col => (
              <button key={col.id} onClick={() => loadCollection(col.id)}
                className={`w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition ${
                  selectedCollection === col.id ? "bg-[var(--primary)]/10 text-[var(--primary)]" : "hover:bg-[var(--accent)] text-[var(--muted-foreground)]"
                }`}>
                <span>{col.icon}</span>
                <div className="text-right">
                  <p className="font-medium">{col.label}</p>
                  <p className="text-xs opacity-70">{col.description}</p>
                </div>
              </button>
            ))}
          </div>
        </div>

        {/* Documents List */}
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-4">
          <div className="flex items-center justify-between mb-3">
            <h4 className="font-medium">{selectedCollection || "اختر مجموعة"}</h4>
            {selectedCollection && (
              <button onClick={() => setShowCreateModal(true)} className="text-xs text-[var(--primary)] hover:underline">+ إضافة</button>
            )}
          </div>
          {loading ? (
            <div className="space-y-2">
              {[1,2,3].map(i => <div key={i} className="h-12 bg-[var(--muted)] rounded animate-pulse" />)}
            </div>
          ) : docs.length === 0 ? (
            <p className="text-sm text-[var(--muted-foreground)] text-center py-8">
              {selectedCollection ? "لا توجد مستندات" : "اختر مجموعة لعرض المستندات"}
            </p>
          ) : (
            <div className="space-y-1 max-h-[500px] overflow-y-auto">
              {docs.map(doc => (
                <button key={doc.id} onClick={() => loadDoc(selectedCollection!, doc.id)}
                  className={`w-full text-right px-3 py-2 rounded-lg text-sm transition ${
                    selectedDoc?.id === doc.id ? "bg-[var(--primary)]/10" : "hover:bg-[var(--accent)]"
                  }`}>
                  <p className="font-mono text-xs truncate">{doc.id}</p>
                  {doc.username && <p className="text-xs text-[var(--muted-foreground)]">{doc.username}</p>}
                  {doc.role && <span className="text-[10px] px-1.5 py-0.5 rounded bg-[var(--accent)]">{doc.role}</span>}
                </button>
              ))}
            </div>
          )}
        </div>

        {/* Document Detail */}
        <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-4">
          <div className="flex items-center justify-between mb-3">
            <h4 className="font-medium">تفاصيل المستند</h4>
            {selectedDoc && (
              <button onClick={() => deleteDoc(selectedCollection!, selectedDoc.id)} className="text-xs text-red-500 hover:underline">حذف</button>
            )}
          </div>
          {selectedDoc ? (
            <div className="space-y-3">
              <div className="p-2 bg-[var(--background)] rounded-lg">
                <p className="text-xs text-[var(--muted-foreground)]">ID</p>
                <p className="text-sm font-mono">{selectedDoc.id}</p>
              </div>
              <pre className="p-3 bg-[var(--background)] rounded-lg text-xs font-mono overflow-auto max-h-[400px] whitespace-pre-wrap">
                {JSON.stringify(
                  Object.fromEntries(Object.entries(selectedDoc).filter(([k]) => !k.startsWith("_"))),
                  null, 2
                )}
              </pre>
              {selectedDoc._subcollections && Object.keys(selectedDoc._subcollections).length > 0 && (
                <div>
                  <p className="text-xs text-[var(--muted-foreground)] mb-2">المجموعات الفرعية</p>
                  {Object.entries(selectedDoc._subcollections).map(([name, items]: [string, any]) => (
                    <div key={name} className="p-2 bg-[var(--background)] rounded-lg mb-1">
                      <p className="text-xs font-medium">{name} ({items.length})</p>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ) : (
            <p className="text-sm text-[var(--muted-foreground)] text-center py-8">اختر مستنداً لعرض التفاصيل</p>
          )}
        </div>
      </div>

      {/* Create Modal */}
      {showCreateModal && (
        <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50" onClick={() => setShowCreateModal(false)}>
          <div className="bg-[var(--card)] rounded-xl border border-[var(--border)] p-6 w-full max-w-lg" onClick={e => e.stopPropagation()}>
            <h3 className="text-lg font-semibold mb-4">إضافة مستند جديد</h3>
            <div className="space-y-3">
              <div>
                <label className="text-sm font-medium">Document ID (اختياري)</label>
                <input value={newDoc.id} onChange={e => setNewDoc({...newDoc, id: e.target.value})} className="w-full px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm font-mono mt-1" placeholder="اتركه فارغاً للإنشاء التلقائي" dir="ltr" />
              </div>
              <div>
                <label className="text-sm font-medium">البيانات (JSON)</label>
                <textarea value={newDoc.data} onChange={e => setNewDoc({...newDoc, data: e.target.value})} className="w-full h-40 px-3 py-2 rounded-lg border border-[var(--border)] bg-[var(--background)] text-sm font-mono mt-1" dir="ltr" />
              </div>
              <div className="flex gap-2 justify-end">
                <button onClick={() => setShowCreateModal(false)} className="px-4 py-2 rounded-lg border border-[var(--border)] text-sm">إلغاء</button>
                <button onClick={createDoc} className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm font-medium">إنشاء</button>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}
