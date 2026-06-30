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
        const snap = await getAdminDb().collection("community_manga").doc(mangaId).collection("comments").orderBy("createdAt", "desc").limit(limit).get();
        comments.push(...snap.docs.map(d => ({ id: d.id, mangaId, chapterId: d.data().chapterUrl || "", ...d.data() })));
        
        // Also get chapter comments
        const chaptersSnap = await getAdminDb().collectionGroup("comments").where("mangaId", "==", mangaId).orderBy("createdAt", "desc").limit(limit).get();
        comments.push(...chaptersSnap.docs.map(d => ({ id: d.id, mangaId, chapterId: d.data().chapterUrl || "", ...d.data() })));
      }
    } else {
      const snap = await getAdminDb().collectionGroup("comments").orderBy("createdAt", "desc").limit(limit).get();
      comments = snap.docs.map((d) => ({ id: d.id, mangaId: d.data().mangaId || "", chapterId: d.data().chapterUrl || "", ...d.data() }));
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
    if (!commentId || !mangaId) {
      return NextResponse.json({ error: "Missing params" }, { status: 400 });
    }
    
    let ref: any = getAdminDb().collection("community_manga").doc(mangaId);
    if (chapterId) {
      ref = ref.collection("chapters").doc(chapterId);
    }
    await ref.collection("comments").doc(commentId).delete();
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
