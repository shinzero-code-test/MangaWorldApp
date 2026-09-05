"use client";

import { useEffect, useState, useCallback } from "react";
import {
  Database, Users, MessageSquare, Shield, Trophy,
  Eye, Trash2, Plus, RefreshCcw, X, Loader2
} from "lucide-react";
import { PageHeader, ConfirmDialog, EmptyState, Spinner } from "@/components/ui";
import { truncate } from "@/lib/utils";

interface Collection {
  id:          string;
  label:       string;
  description: string;
  icon:        React.ComponentType<any>;
}

// Only collections that actually exist in this project's Firestore topology.
// Keep in sync with DATA_BROWSER_COLLECTIONS in src/lib/firestore-whitelist.ts.
const COLLECTIONS: Collection[] = [
  { id: "publicProfiles",    label: "الملفات العامة", description: "ملفات المستخدمين العامة",        icon: Users },
  { id: "community_manga",   label: "المجتمع",        description: "مانجا المجتمع وتعليقاتها",       icon: MessageSquare },
  { id: "moderationReports", label: "البلاغات",       description: "تقارير المخالفات",               icon: Shield },
  { id: "user_achievements", label: "الإنجازات",      description: "إنجازات المستخدمين",             icon: Trophy },
  { id: "cloudinaryAssets",  label: "الأصول",         description: "أصول الصور المرفوعة على Cloudinary", icon: Database },
  { id: "releases",          label: "الإصدارات",      description: "إصدارات التطبيق المنشورة",       icon: Database },
];

interface DocRow {
  id:     string;
  fields: Record<string, any>;
}

function JsonViewer({ data }: { data: unknown }) {
  const str = JSON.stringify(data, null, 2);
  return (
    <pre
      className="text-xs overflow-auto p-3 rounded-lg font-mono max-h-[300px]"
      style={{ background: "var(--muted)", color: "var(--foreground)" }}
      dir="ltr"
    >
      {str}
    </pre>
  );
}

export default function DataBrowserPage() {
  const [selected,       setSelected]       = useState<string>("publicProfiles");
  const [docs,           setDocs]           = useState<DocRow[]>([]);
  const [loading,        setLoading]        = useState(false);
  const [viewDoc,        setViewDoc]        = useState<DocRow | null>(null);
  const [deleteId,       setDeleteId]       = useState<string | null>(null);
  const [deleteLoading,  setDeleteLoading]  = useState(false);
  const [createOpen,     setCreateOpen]     = useState(false);
  const [newId,          setNewId]          = useState("");
  const [newJson,        setNewJson]        = useState("{\n  \n}");
  const [createLoading,  setCreateLoading]  = useState(false);
  const [jsonError,      setJsonError]      = useState("");

  const loadCollection = useCallback(async (col: string) => {
    setLoading(true);
    setViewDoc(null);
    try {
      const res  = await fetch(`/api/firestore/${col}`);
      const data = await res.json();
      setDocs(
        (data.documents ?? data.docs ?? []).map((d: any) => ({
          id:     d.id ?? d._id ?? "?",
          fields: d.data ?? d.fields ?? d,
        }))
      );
    } catch { setDocs([]); }
    finally  { setLoading(false); }
  }, []);

  useEffect(() => { loadCollection(selected); }, [selected, loadCollection]);

  const handleDelete = async () => {
    if (!deleteId) return;
    setDeleteLoading(true);
    try {
      const res = await fetch(`/api/firestore/${selected}/${deleteId}`, { method: "DELETE" });
      // Remove the row only when the server actually deleted it.
      if (res.ok) setDocs((prev) => prev.filter((d) => d.id !== deleteId));
    } finally {
      setDeleteLoading(false);
      setDeleteId(null);
    }
  };

  const handleCreate = async (e: React.FormEvent) => {
    e.preventDefault();
    setJsonError("");
    let parsed: any;
    try { parsed = JSON.parse(newJson); }
    catch { setJsonError("JSON غير صالح"); return; }
    setCreateLoading(true);
    try {
      const res = await fetch(`/api/firestore/${selected}`, {
        method:  "POST",
        headers: { "Content-Type": "application/json" },
        body:    JSON.stringify({ id: newId || undefined, data: parsed }),
      });
      if (!res.ok) { setJsonError("فشل إنشاء المستند"); return; }
      const d = await res.json();
      setDocs((prev) => [{ id: d.id ?? newId, fields: parsed }, ...prev]);
      setCreateOpen(false);
      setNewId(""); setNewJson("{\n  \n}");
    } catch { setJsonError("فشل إنشاء المستند"); }
    finally  { setCreateLoading(false); }
  };

  const colCfg = COLLECTIONS.find((c) => c.id === selected) ?? COLLECTIONS[0];
  const previewFields = (fields: Record<string, any>) =>
    Object.entries(fields).slice(0, 2).map(([k, v]) => (
      <span
        key={k}
        className="text-xs px-1.5 py-0.5 rounded font-mono"
        style={{ background: "var(--muted)", color: "var(--muted-foreground)" }}
        dir="ltr"
      >
        {k}: {String(v).slice(0, 20)}
      </span>
    ));

  return (
    <div className="space-y-5">
      <PageHeader
        title="متصفح البيانات"
        subtitle="Firestore — تصفح وإدارة قواعد البيانات"
        icon={Database}
        actions={
          <button
            onClick={() => setCreateOpen(true)}
            className="flex items-center gap-2 px-3 py-2 rounded-xl text-sm font-semibold transition hover:opacity-90"
            style={{ background: "var(--primary)", color: "var(--primary-foreground)" }}
          >
            <Plus size={15} />
            مستند جديد
          </button>
        }
      />

      <div className="flex flex-col md:flex-row gap-5 min-h-[500px]">
        {/* Collection sidebar */}
        <div
          className="w-full md:w-[220px] shrink-0 rounded-[var(--radius-lg)] border overflow-hidden"
          style={{ background: "var(--card)", borderColor: "var(--border)" }}
        >
          <div
            className="px-4 py-3 border-b text-xs font-semibold uppercase tracking-wider"
            style={{ borderColor: "var(--border)", color: "var(--muted-foreground)" }}
          >
            المجموعات
          </div>
          <div className="p-2 space-y-0.5">
            {COLLECTIONS.map((col) => {
              const active = selected === col.id;
              const Icon   = col.icon ?? Database;
              return (
                <button
                  key={col.id}
                  onClick={() => setSelected(col.id)}
                  className="w-full flex items-center gap-3 px-3 py-2.5 rounded-lg text-sm transition-all text-start"
                  style={{
                    background: active
                      ? "color-mix(in srgb, var(--primary) 10%, transparent)"
                      : "transparent",
                    color:      active ? "var(--primary)" : "var(--foreground)",
                  }}
                >
                  <Icon size={15} style={{ color: active ? "var(--primary)" : "var(--muted-foreground)" }} />
                  <div className="flex-1 min-w-0">
                    <p className="font-medium truncate">{col.label}</p>
                    <p className="text-[10px] truncate" style={{ color: "var(--muted-foreground)" }}>
                      {col.description}
                    </p>
                  </div>
                </button>
              );
            })}
          </div>
        </div>

        {/* Document table */}
        <div className="flex-1 rounded-[var(--radius-lg)] border overflow-hidden" style={{ background: "var(--card)", borderColor: "var(--border)" }}>
          <div
            className="px-5 py-3 border-b flex items-center justify-between"
            style={{ borderColor: "var(--border)" }}
          >
            <div className="flex items-center gap-2">
              <colCfg.icon size={16} style={{ color: "var(--primary)" }} />
              <span className="font-semibold text-sm">{colCfg.label}</span>
              {!loading && (
                <span
                  className="text-xs font-mono px-1.5 py-0.5 rounded"
                  style={{ background: "var(--muted)", color: "var(--muted-foreground)" }}
                >
                  {docs.length}
                </span>
              )}
            </div>
            <button
              onClick={() => loadCollection(selected)}
              className="p-1.5 rounded-lg transition hover:bg-[var(--accent)]"
              aria-label="تحديث"
            >
              <RefreshCcw size={14} style={{ color: "var(--muted-foreground)" }} />
            </button>
          </div>

          {loading ? (
            <div className="flex items-center justify-center h-48">
              <Spinner />
            </div>
          ) : docs.length === 0 ? (
            <EmptyState
              icon={Database}
              title="المجموعة فارغة"
              description="لا توجد مستندات في هذه المجموعة"
            />
          ) : (
            <div className="overflow-x-auto">
              <table aria-label="جدول المستندات">
                <thead>
                  <tr>
                    <th scope="col">المعرّف</th>
                    <th scope="col">البيانات</th>
                    <th scope="col" className="w-20"></th>
                  </tr>
                </thead>
                <tbody>
                  {docs.map((doc) => (
                    <tr key={doc.id}>
                      <td>
                        <code
                          className="text-xs font-mono"
                          style={{ color: "var(--primary)" }}
                          dir="ltr"
                        >
                          {truncate(doc.id, 24)}
                        </code>
                      </td>
                      <td>
                        <div className="flex flex-wrap gap-1.5">
                          {previewFields(doc.fields)}
                        </div>
                      </td>
                      <td>
                        <div className="flex gap-1">
                          <button
                            onClick={() => setViewDoc(doc)}
                            className="p-1.5 rounded-lg transition hover:bg-[var(--accent)]"
                            aria-label="عرض"
                          >
                            <Eye size={14} style={{ color: "var(--muted-foreground)" }} />
                          </button>
                          <button
                            onClick={() => setDeleteId(doc.id)}
                            className="p-1.5 rounded-lg transition hover:bg-red-500/10"
                            aria-label="حذف"
                          >
                            <Trash2 size={14} style={{ color: "var(--destructive)" }} />
                          </button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>

      {/* View doc modal */}
      {viewDoc && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setViewDoc(null)} />
          <dialog
            open aria-modal="true"
            className="relative w-full max-w-lg rounded-2xl border shadow-2xl z-10 overflow-hidden"
            style={{ background: "var(--card)", borderColor: "var(--border)", color: "var(--foreground)" }}
          >
            <div className="flex items-center justify-between px-5 py-4 border-b" style={{ borderColor: "var(--border)" }}>
              <p className="font-semibold font-mono text-sm" dir="ltr">{viewDoc.id}</p>
              <button onClick={() => setViewDoc(null)} className="p-1 rounded hover:bg-[var(--accent)]">
                <X size={16} />
              </button>
            </div>
            <div className="p-5">
              <JsonViewer data={viewDoc.fields} />
            </div>
          </dialog>
        </div>
      )}

      {/* Create modal */}
      {createOpen && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4">
          <div className="absolute inset-0 bg-black/60 backdrop-blur-sm" onClick={() => setCreateOpen(false)} />
          <dialog
            open aria-modal="true"
            className="relative w-full max-w-md rounded-2xl border shadow-2xl z-10"
            style={{ background: "var(--card)", borderColor: "var(--border)", color: "var(--foreground)" }}
          >
            <div className="flex items-center justify-between px-5 py-4 border-b" style={{ borderColor: "var(--border)" }}>
              <p className="font-semibold">إنشاء مستند جديد</p>
              <button onClick={() => setCreateOpen(false)} className="p-1 rounded hover:bg-[var(--accent)]">
                <X size={16} />
              </button>
            </div>
            <form onSubmit={handleCreate} className="p-5 space-y-4">
              <div className="space-y-1.5">
                <label className="text-sm font-medium">معرّف المستند (اختياري)</label>
                <input
                  type="text" value={newId}
                  onChange={(e) => setNewId(e.target.value)}
                  placeholder="auto-generated"
                  className="w-full font-mono"
                  dir="ltr"
                />
              </div>
              <div className="space-y-1.5">
                <label className="text-sm font-medium">البيانات (JSON)</label>
                <textarea
                  value={newJson}
                  onChange={(e) => setNewJson(e.target.value)}
                  rows={6}
                  className="w-full font-mono text-sm resize-none"
                  dir="ltr"
                />
                {jsonError && <p className="text-xs" style={{ color: "var(--destructive)" }}>{jsonError}</p>}
              </div>
              <div className="flex gap-3 justify-end">
                <button
                  type="button"
                  onClick={() => setCreateOpen(false)}
                  className="px-4 py-2 rounded-lg text-sm border transition hover:bg-[var(--accent)]"
                  style={{ borderColor: "var(--border)" }}
                >
                  إلغاء
                </button>
                <button
                  type="submit"
                  disabled={createLoading}
                  className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-semibold transition hover:opacity-90 disabled:opacity-60"
                  style={{ background: "var(--primary)", color: "var(--primary-foreground)" }}
                >
                  {createLoading && <Loader2 size={14} className="animate-spin" />}
                  إنشاء
                </button>
              </div>
            </form>
          </dialog>
        </div>
      )}

      <ConfirmDialog
        open={!!deleteId}
        title="حذف المستند"
        description={`هل تريد حذف المستند "${deleteId}"؟ هذا الإجراء لا يمكن التراجع عنه.`}
        confirmLabel="حذف"
        variant="danger"
        onConfirm={handleDelete}
        onCancel={() => setDeleteId(null)}
        loading={deleteLoading}
      />
    </div>
  );
}
