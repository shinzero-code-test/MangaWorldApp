import { NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { getDashboardRoleCounts, requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("viewer");

    const [
      usersSnap, openReportsSnap,
      mangasSnap, weekAgoSnap
    ] = await Promise.all([
      getAdminDb().collection("publicProfiles").count().get(),
      getAdminDb().collection("moderationReports").where("status", "==", "open").count().get(),
      getAdminDb().collection("community_manga").count().get(),
      getAdminDb().collection("publicProfiles")
        .where("updatedAt", ">=", Date.now() - 7 * 86400000)
        .count().get(),
    ]);

    // Aggregate comments + reviews across manga subcollections
    let totalComments = 0;
    let totalReviews  = 0;
    try {
      const mangaDocs = await getAdminDb().collection("community_manga").limit(50).get();
      const counts = await Promise.all(
        mangaDocs.docs.map(async (m) => {
          const [cSnap, rSnap] = await Promise.all([
            getAdminDb().collectionGroup
              ? getAdminDb().collectionGroup("comments").where("mangaId", "==", m.id).count().get()
              : m.ref.collection("reviews").count().get(),
            m.ref.collection("reviews").count().get(),
          ]);
          return { comments: 0, reviews: rSnap.data().count };
        })
      );
      totalReviews = counts.reduce((a, c) => a + c.reviews, 0);
    } catch { /* Subcollections may not exist */ }

    const totalUsers   = usersSnap.data().count;
    const openReports  = openReportsSnap.data().count;
    const recentSignUps= weekAgoSnap.data().count;

    const roleCounts = await getDashboardRoleCounts();

    return NextResponse.json({
      totalUsers,
      totalComments,
      totalReviews,
      openReports,
      recentSignUps,
      roleCounts,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
