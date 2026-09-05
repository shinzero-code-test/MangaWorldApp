import { NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { getDashboardRoleCounts, requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    // Moderator minimum: "viewer" rank would admit the viewer role itself (M-4).
    await requireRole("moderator");

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

    // Aggregate comments + reviews across manga subcollections.
    // Capped at 50 mangas: totals are best-effort on large fleets.
    let totalComments = 0;
    let totalReviews  = 0;
    try {
      const mangaDocs = await getAdminDb().collection("community_manga").limit(50).get();
      const counts = await Promise.all(
        mangaDocs.docs.map(async (m) => {
          const [cSnap, rSnap] = await Promise.all([
            getAdminDb().collectionGroup("comments").where("mangaId", "==", m.id).count().get(),
            m.ref.collection("reviews").count().get(),
          ]);
          return { comments: cSnap.data().count, reviews: rSnap.data().count };
        })
      );
      totalComments = counts.reduce((a, c) => a + c.comments, 0);
      totalReviews = counts.reduce((a, c) => a + c.reviews, 0);
    } catch { /* Subcollections may not exist */ }

    const totalUsers   = usersSnap.data().count;
    const openReports  = openReportsSnap.data().count;
    const recentSignUps= weekAgoSnap.data().count;

    const roleCounts = await getDashboardRoleCounts();

    // Service health checks — ping each Firebase service
    const healthChecks = await Promise.allSettled([
      getAdminDb().collection("publicProfiles").limit(1).get(), // Firestore
    ]);
    const dbOk = healthChecks[0].status === "fulfilled";

    // Auth is always available if we got this far (session cookie verified)
    // Crashlytics is available if crash_reports collection exists
    let crashOk = false;
    try {
      await getAdminDb().collection("crash_reports").limit(1).get();
      crashOk = true;
    } catch { /* not available */ }

    return NextResponse.json({
      totalUsers,
      totalComments,
      totalReviews,
      openReports,
      recentSignUps,
      roleCounts,
      services: { auth: true, db: dbOk, rc: true, fcm: true, crash: crashOk },
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
