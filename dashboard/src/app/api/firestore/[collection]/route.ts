import { NextRequest, NextResponse } from "next/server";
import { FieldPath } from "firebase-admin/firestore";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { validateFirestoreDoc } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";
import { isDataBrowserCollection, isValidDocId } from "@/lib/firestore-whitelist";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest, { params }: { params: Promise<{ collection: string }> }) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    if (!isDataBrowserCollection(collection)) {
      return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    }
    const { searchParams } = new URL(request.url);
    const limit = Math.min(Math.max(parseInt(searchParams.get("limit") || "50", 10) || 50, 1), 200);
    // DA-2: cursor pagination — the browser used to show only the first
    // page, making moderation past doc ~100 impossible on large collections.
    const startAfterId = searchParams.get("startAfter") || "";
    if (startAfterId && !isValidDocId(startAfterId)) {
      return NextResponse.json({ error: "Invalid startAfter" }, { status: 400 });
    }
    const db = getAdminDb();
    let query = db.collection(collection).orderBy(FieldPath.documentId()).limit(limit + 1);
    if (startAfterId) {
      const startDoc = await db.collection(collection).doc(startAfterId).get();
      if (startDoc.exists) query = query.startAfter(startDoc);
    }
    const snap = await query.get();
    const page = snap.docs.slice(0, limit);
    const documents = page.map(d => ({ id: d.id, fields: d.data() }));
    const hasMore = snap.docs.length > limit;
    return NextResponse.json({
      documents,
      hasMore,
      nextCursor: hasMore ? page[page.length - 1].id : null,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function POST(request: NextRequest, { params }: { params: Promise<{ collection: string }> }) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    if (!isDataBrowserCollection(collection)) {
      return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    }
    const { id, data }   = await request.json();
    const docCheck = validateFirestoreDoc(data);
    if (!docCheck.ok) {
      return NextResponse.json({ error: docCheck.error }, { status: 400 });
    }
    if (id !== undefined && !isValidDocId(id)) {
      return NextResponse.json({ error: "Invalid docId" }, { status: 400 });
    }
    let ref;
    // DA-1: merge on explicit-id create — a blind set() would silently
    // replace a live doc (e.g. counters on user_achievements/{uid}); the
    // sibling PUT and /api/firestore POST already merge.
    if (id) { ref = getAdminDb().collection(collection).doc(id); await ref.set(data, { merge: true }); }
    else     { ref = await getAdminDb().collection(collection).add(data); }
    return NextResponse.json({ id: ref.id });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
