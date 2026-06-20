import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

const ALLOWED_COLLECTIONS = [
  "publicProfiles", "users", "community_manga", "moderationReports",
  "user_achievements", "app_config", "usernames",
];

export async function GET(
  request: NextRequest,
  { params }: { params: Promise<{ collection: string }> }
) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    const { searchParams } = new URL(request.url);
    const limit = parseInt(searchParams.get("limit") || "30");
    const searchField = searchParams.get("searchField") || "";
    const searchValue = searchParams.get("searchValue") || "";
    const orderBy = searchParams.get("orderBy") || "";
    const orderDir = searchParams.get("orderDir") || "desc";

    if (!ALLOWED_COLLECTIONS.includes(collection)) {
      return NextResponse.json({ error: "Collection not in whitelist" }, { status: 403 });
    }

    let query: any = adminDb.collection(collection);

    if (searchField && searchValue) {
      query = query.where(searchField, ">=", searchValue).where(searchField, "<=", searchValue + "\uf8ff").limit(limit);
    } else if (orderBy) {
      query = query.orderBy(orderBy, orderDir as "asc" | "desc").limit(limit);
    } else {
      query = query.limit(limit);
    }

    const snapshot = await query.get();
    const docs = snapshot.docs.map((doc: any) => ({
      id: doc.id,
      ...doc.data(),
      _path: doc.ref.path,
    }));

    // Get subcollection names for each doc
    const docsWithSubcols = await Promise.all(
      docs.slice(0, 5).map(async (doc: any) => {
        try {
          const docRef = adminDb.collection(collection).doc(doc.id);
          const subcols = await docRef.listCollections();
          return { ...doc, _subcollections: subcols.map((c: any) => c.id) };
        } catch {
          return doc;
        }
      })
    );

    return NextResponse.json({
      collection,
      docs: docsWithSubcols.length > 0 ? docsWithSubcols : docs,
      count: docs.length,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function DELETE(
  request: NextRequest,
  { params }: { params: Promise<{ collection: string }> }
) {
  try {
    await requireRole("super-admin");
    const { collection } = await params;
    const { docId } = await request.json();

    if (!ALLOWED_COLLECTIONS.includes(collection)) {
      return NextResponse.json({ error: "Collection not in whitelist" }, { status: 403 });
    }

    if (!docId) return NextResponse.json({ error: "Missing docId" }, { status: 400 });

    await adminDb.collection(collection).doc(docId).delete();
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
