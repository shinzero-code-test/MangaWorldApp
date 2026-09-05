import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { validateFirestoreDoc } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";
import { isDataBrowserCollection, isValidDocId } from "@/lib/firestore-whitelist";

export const dynamic = 'force-dynamic';

type Params = Promise<{ collection: string; docId: string }>;

export async function GET(_: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    if (!isDataBrowserCollection(collection)) return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    if (!isValidDocId(docId)) return NextResponse.json({ error: "Invalid docId" }, { status: 400 });
    const doc = await getAdminDb().collection(collection).doc(docId).get();
    if (!doc.exists) return NextResponse.json({ error:"المستند غير موجود" }, { status:404 });
    return NextResponse.json({ id:doc.id, fields:doc.data() });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PUT(request: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    if (!isDataBrowserCollection(collection)) return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    if (!isValidDocId(docId)) return NextResponse.json({ error: "Invalid docId" }, { status: 400 });
    const data = await request.json();
    const docCheck = validateFirestoreDoc(data);
    if (!docCheck.ok) {
      return NextResponse.json({ error: docCheck.error }, { status: 400 });
    }
    await getAdminDb().collection(collection).doc(docId).set(data, { merge:true });
    return NextResponse.json({ success:true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function DELETE(_: NextRequest, { params }: { params: Params }) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    if (!isDataBrowserCollection(collection)) return NextResponse.json({ error: "Collection not allowed" }, { status: 400 });
    if (!isValidDocId(docId)) return NextResponse.json({ error: "Invalid docId" }, { status: 400 });
    await getAdminDb().collection(collection).doc(docId).delete();
    return NextResponse.json({ success:true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
