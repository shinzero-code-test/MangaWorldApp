import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { DASHBOARD_ROLES, requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET(_request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("moderator");
    const { uid } = await params;

    // Get Firebase Auth user
    let authUser: any = null;
    try {
      authUser = await getAdminAuth().getUser(uid);
    } catch {}

    // Get Firestore profile
    const profileDoc = await getAdminDb().collection("publicProfiles").doc(uid).get();
    const profile = profileDoc.data() || {};

    // Get user data counts
    const [favSnap, histSnap, annotSnap, deviceSnap] = await Promise.all([
      getAdminDb().collection("users").doc(uid).collection("favorites").count().get(),
      getAdminDb().collection("users").doc(uid).collection("readingHistory").count().get(),
      getAdminDb().collection("users").doc(uid).collection("readerAnnotations").count().get(),
      getAdminDb().collection("users").doc(uid).collection("devices").count().get(),
    ]);

    // Get recent activity
    const recentHistory = await getAdminDb()
      .collection("users").doc(uid).collection("readingHistory")
      .orderBy("lastReadAt", "desc").limit(10).get();

    const history = recentHistory.docs.map((doc: any) => ({ id: doc.id, ...doc.data() }));

    // Get user's custom lists
    const listsSnap = await getAdminDb().collection("users").doc(uid).collection("lists").limit(10).get();
    const lists = listsSnap.docs.map((doc: any) => ({ id: doc.id, ...doc.data() }));

    return NextResponse.json({
      // Auth info
      uid,
      email: authUser?.email || null,
      emailVerified: authUser?.emailVerified || false,
      disabled: authUser?.disabled || false,
      lastSignIn: authUser?.metadata?.lastSignInTime || null,
      createdAt: authUser?.metadata?.creationTime || null,
      providers: authUser?.providerData?.map((p: any) => ({
        providerId: p.providerId,
        email: p.email,
        displayName: p.displayName,
      })) || [],
      customClaims: authUser?.customClaims || {},
      phoneNumber: authUser?.phoneNumber || null,

      // Profile info
      username: profile.username || "",
      avatarUrl: profile.avatarUrl || "",
      role: authUser?.customClaims?.role || "viewer",
      bio: profile.bio || "",
      isPublic: profile.isPublic || false,

      // Stats
      favoriteCount: favSnap.data().count,
      historyCount: histSnap.data().count,
      annotationCount: annotSnap.data().count,
      deviceCount: deviceSnap.data().count,

      // Activity
      recentHistory: history,
      lists,
    });
  } catch (error: any) {
    const status = error.message === "Forbidden" ? 403 : error.message === "Unauthorized" ? 401 : 500;
    return NextResponse.json({ error: error.message }, { status });
  }
}

export async function PATCH(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("super-admin");
    const { uid } = await params;
    const body = await request.json();

    // Update profile
    const profileUpdates: any = { updatedAt: Date.now() };
    if (body.role !== undefined && !DASHBOARD_ROLES.includes(body.role)) {
      return NextResponse.json({ error: "Invalid role" }, { status: 400 });
    }
    if (body.username !== undefined) profileUpdates.username = body.username;
    if (body.bio !== undefined) profileUpdates.bio = body.bio;
    if (body.isPublic !== undefined) profileUpdates.isPublic = body.isPublic;

    await getAdminDb().collection("publicProfiles").doc(uid).update(profileUpdates);

    // Update Auth
    if (body.disabled !== undefined) {
      await getAdminAuth().updateUser(uid, { disabled: body.disabled });
    }
    if (body.email) {
      await getAdminAuth().updateUser(uid, { email: body.email });
    }
    if (body.role !== undefined) {
      const authUser = await getAdminAuth().getUser(uid);
      await getAdminAuth().setCustomUserClaims(uid, { ...authUser.customClaims, role: body.role });
      await getAdminAuth().revokeRefreshTokens(uid);
    }

    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
