import { NextRequest, NextResponse } from "next/server";
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
    const snap  = await getAdminDb().collection(collection).limit(limit).get();
    const documents = snap.docs.map(d => ({ id:d.id, fields:d.data() }));
    return NextResponse.json({ documents });
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
    if (id) { ref = getAdminDb().collection(collection).doc(id); await ref.set(data); }
    else     { ref = await getAdminDb().collection(collection).add(data); }
    return NextResponse.json({ id: ref.id });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
