import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { validateFirestoreDoc } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";
import { isDataBrowserCollection, isValidDocId } from "@/lib/firestore-whitelist";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { searchParams } = new URL(request.url);
    const limit = Math.min(Math.max(parseInt(searchParams.get("limit") || "50", 10) || 50, 1), 200);
    const startAfter = searchParams.get("startAfter") || "";

    const db = getAdminDb();
    let query: any = db.collection("publicProfiles");
    query = query.orderBy("updatedAt", "desc").limit(limit + 1);
    if (startAfter) {
      const startDoc = await db.collection("publicProfiles").doc(startAfter).get();
      if (startDoc.exists) query = query.startAfter(startDoc);
    }

    const snapshot = await query.get();
    const docs = snapshot.docs.slice(0, limit).map((doc: any) => ({
      id: doc.id,
      ...doc.data(),
    }));
    const hasMore = snapshot.docs.length > limit;

    return NextResponse.json({ docs, hasMore, collection: "publicProfiles" });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function POST(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { collection, data, docId } = await request.json();

    if (!collection || !data) {
      return NextResponse.json({ error: "Missing collection or data" }, { status: 400 });
    }

    // Whitelist allowed collections (shared with the dynamic browser routes).
    if (!isDataBrowserCollection(collection)) {
      return NextResponse.json({ error: "Collection not allowed" }, { status: 403 });
    }

    // Shape guard even inside whitelisted collections (M-6): plain object,
    // bounded size, no reserved field names.
    const docCheck = validateFirestoreDoc(data);
    if (!docCheck.ok) {
      return NextResponse.json({ error: docCheck.error }, { status: 400 });
    }
    if (docId !== undefined && !isValidDocId(docId)) {
      return NextResponse.json({ error: "Invalid docId" }, { status: 400 });
    }

    let ref;
    if (docId) {
      const db = getAdminDb();
      ref = db.collection(collection).doc(docId);
      await ref.set(data, { merge: true });
    } else {
      ref = getAdminDb().collection(collection).add(data);
    }

    const id = (await ref).id || docId;
    return NextResponse.json({ success: true, id });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
