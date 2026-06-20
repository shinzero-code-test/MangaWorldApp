import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET(
  _request: NextRequest,
  { params }: { params: Promise<{ collection: string; docId: string }> }
) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;

    const docRef = adminDb.collection(collection).doc(docId);
    const doc = await docRef.get();

    if (!doc.exists) {
      return NextResponse.json({ error: "Document not found" }, { status: 404 });
    }

    // Get subcollections
    const subcols = await docRef.listCollections();
    const subcolData: Record<string, any[]> = {};

    for (const subcol of subcols) {
      const subSnap = await subcol.limit(20).get();
      subcolData[subcol.id] = subSnap.docs.map((d: any) => ({ id: d.id, ...d.data() }));
    }

    return NextResponse.json({
      id: doc.id,
      ...doc.data(),
      _path: doc.ref.path,
      _subcollections: subcolData,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function PATCH(
  request: NextRequest,
  { params }: { params: Promise<{ collection: string; docId: string }> }
) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    const data = await request.json();

    await adminDb.collection(collection).doc(docId).set(data, { merge: true });
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(
  _request: NextRequest,
  { params }: { params: Promise<{ collection: string; docId: string }> }
) {
  try {
    await requireRole("super-admin");
    const { collection, docId } = await params;
    await adminDb.collection(collection).doc(docId).delete();
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
