import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const mangaId = searchParams.get("mangaId");
    const limit = parseInt(searchParams.get("limit") || "50");

    let comments: any[] = [];
    if (mangaId) {
      const mangaDoc = await getAdminDb().collection("community_manga").doc(mangaId).get();
      if (mangaDoc.exists) {
        const chaptersCol = await getAdminDb().collection("community_manga").doc(mangaId).collection("chapters").get();
        for (const chapter of chaptersCol.docs) {
          const commentsSnap = await chapter.ref.collection("comments").orderBy("createdAt", "desc").limit(limit).get();
          comments.push(...commentsSnap.docs.map((d) => ({ id: d.id, mangaId, chapterId: chapter.id, ...d.data() })));
        }
      }
    } else {
      const mangas = await getAdminDb().collection("community_manga").limit(20).get();
      for (const manga of mangas.docs) {
        const chaptersCol = await manga.ref.collection("chapters").limit(5).get();
        for (const chapter of chaptersCol.docs) {
          const commentsSnap = await chapter.ref.collection("comments").orderBy("createdAt", "desc").limit(10).get();
          comments.push(...commentsSnap.docs.map((d) => ({ id: d.id, mangaId: manga.id, chapterId: chapter.id, ...d.data() })));
        }
      }
    }

    comments.sort((a, b) => (b.createdAt || 0) - (a.createdAt || 0));
    return NextResponse.json({ comments: comments.slice(0, limit) });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    await requireRole("moderator");
    const { commentId, mangaId, chapterId } = await request.json();
    if (!commentId || !mangaId || !chapterId) {
      return NextResponse.json({ error: "Missing params" }, { status: 400 });
    }
    await getAdminDb().collection("community_manga").doc(mangaId)
      .collection("chapters").doc(chapterId)
      .collection("comments").doc(commentId).delete();
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
