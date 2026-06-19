import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const mangaId = searchParams.get("mangaId");
    const limit = parseInt(searchParams.get("limit") || "50");

    let reviews: any[] = [];
    if (mangaId) {
      const snap = await adminDb.collection("community_manga").doc(mangaId).collection("reviews").orderBy("createdAt", "desc").limit(limit).get();
      reviews = snap.docs.map((d) => ({ id: d.id, mangaId, ...d.data() }));
    } else {
      const mangas = await adminDb.collection("community_manga").limit(20).get();
      for (const manga of mangas.docs) {
        const revSnap = await manga.ref.collection("reviews").orderBy("createdAt", "desc").limit(5).get();
        reviews.push(...revSnap.docs.map((d) => ({ id: d.id, mangaId: manga.id, ...d.data() })));
      }
    }

    reviews.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    return NextResponse.json({ reviews: reviews.slice(0, limit) });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { reviewId, mangaId } = await request.json();
    if (!reviewId || !mangaId) return NextResponse.json({ error: "Missing params" }, { status: 400 });
    await adminDb.collection("community_manga").doc(mangaId).collection("reviews").doc(reviewId).delete();
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
