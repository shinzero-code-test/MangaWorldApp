import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { DASHBOARD_ROLES, requireRole, wouldStrandLastSuperAdmin } from "@/lib/auth";
import { genericErrorResponse, logSecurityEvent } from "@/lib/security";
import { isValidEmail } from "@/lib/validate";
import { isValidDocId } from "@/lib/firestore-whitelist";
import {
  isValidUsername,
  normalizeUsername,
  releaseUsernameIfOwned,
  tryClaimUsername,
} from "@/lib/username";

export const dynamic = 'force-dynamic';

export async function GET(_request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("moderator");
    const { uid } = await params;
    if (!isValidDocId(uid)) {
      return NextResponse.json({ error: "Invalid uid" }, { status: 400 });
    }

    // Get Firebase Auth user
    let authUser: any = null;
    try {
      authUser = await getAdminAuth().getUser(uid);
    } catch {}

    // Get Firestore profile
    const profileDoc = await getAdminDb().collection("publicProfiles").doc(uid).get();
    const profile = profileDoc.data() || {};

    // Get user data counts
    const [favSnap, histSnap, annotSnap, deviceSnap, commentsSnap, reviewsSnap] = await Promise.all([
      getAdminDb().collection("users").doc(uid).collection("favorites").count().get(),
      getAdminDb().collection("users").doc(uid).collection("readingHistory").count().get(),
      getAdminDb().collection("users").doc(uid).collection("readerAnnotations").count().get(),
      getAdminDb().collection("users").doc(uid).collection("devices").count().get(),
      getAdminDb().collectionGroup("comments").where("authorUid", "==", uid).count().get(),
      getAdminDb().collectionGroup("reviews").where("authorUid", "==", uid).count().get(),
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
      displayName: authUser?.displayName || null,
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
      commentsCount: commentsSnap.data().count,
      reviewsCount: reviewsSnap.data().count,

      // Activity
      recentHistory: history,
      lists,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PATCH(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    const admin = await requireRole("super-admin");
    const { uid } = await params;
    if (!isValidDocId(uid)) {
      return NextResponse.json({ error: "Invalid uid" }, { status: 400 });
    }
    const body = await request.json();
    if (uid === admin.uid && (body.role !== undefined || body.disabled === true)) {
      return NextResponse.json({ error: "You cannot change your own role or disable your account" }, { status: 400 });
    }

    // Update profile
    const profileUpdates: Record<string, unknown> = { updatedAt: Date.now() };
    if (body.role !== undefined && !DASHBOARD_ROLES.includes(body.role)) {
      return NextResponse.json({ error: "Invalid role" }, { status: 400 });
    }
    if (body.username !== undefined) {
      // D-4: enforce the app's UsernameRules (3–20, charset) — the old
      // 1..64 check let admins plant names the app rejects or that collide
      // with the usernames/{name} claim system.
      if (!isValidUsername(body.username.trim())) {
        return NextResponse.json({ error: "Invalid username" }, { status: 400 });
      }
      const nextUsername = body.username.trim();
      const profileSnap = await getAdminDb().collection("publicProfiles").doc(uid).get();
      const prevUsername =
        typeof profileSnap.data()?.username === "string"
          ? (profileSnap.data()?.username as string)
          : "";
      if (normalizeUsername(prevUsername) !== normalizeUsername(nextUsername)) {
        const claimed = await tryClaimUsername(uid, nextUsername);
        if (!claimed) {
          return NextResponse.json({ error: "Username already taken" }, { status: 409 });
        }
        await releaseUsernameIfOwned(uid, prevUsername);
        await logSecurityEvent("admin_username_change", {
          by: admin.uid,
          target: uid,
          from: prevUsername || null,
          to: nextUsername,
        });
      }
      profileUpdates.username = nextUsername;
    }
    if (body.bio !== undefined) {
      if (typeof body.bio !== "string" || body.bio.length > 1_000) {
        return NextResponse.json({ error: "Invalid bio" }, { status: 400 });
      }
      profileUpdates.bio = body.bio;
    }
    if (body.isPublic !== undefined) {
      if (typeof body.isPublic !== "boolean") {
        return NextResponse.json({ error: "Invalid isPublic" }, { status: 400 });
      }
      profileUpdates.isPublic = body.isPublic;
    }

    await getAdminDb().collection("publicProfiles").doc(uid).update(profileUpdates);

    // Update Auth
    if (body.disabled !== undefined) {
      if (typeof body.disabled !== "boolean") {
        return NextResponse.json({ error: "Invalid disabled flag" }, { status: 400 });
      }
      if (body.disabled === true && (await wouldStrandLastSuperAdmin(uid, undefined, true))) {
        return NextResponse.json({ error: "Cannot disable the last super-admin" }, { status: 400 });
      }
      await getAdminAuth().updateUser(uid, { disabled: body.disabled });
      if (body.disabled === true) {
        await getAdminAuth().revokeRefreshTokens(uid).catch(() => {});
      }
    }
    if (body.email) {
      // Email format check before hitting the Auth API (M-6).
      if (typeof body.email !== "string" || body.email.length > 320 || !isValidEmail(body.email)) {
        return NextResponse.json({ error: "Invalid email" }, { status: 400 });
      }
      // D-4: a changed address must not inherit the old verification —
      // reset the flag and revoke sessions so the previous owner is signed
      // out everywhere until the new address is verified.
      await getAdminAuth().updateUser(uid, { email: body.email, emailVerified: false });
      await getAdminAuth().revokeRefreshTokens(uid);
      await logSecurityEvent("admin_email_change", { by: admin.uid, target: uid });
    }
    if (body.role !== undefined) {
      if (await wouldStrandLastSuperAdmin(uid, body.role, undefined)) {
        return NextResponse.json({ error: "Cannot remove the last super-admin" }, { status: 400 });
      }
      const authUser = await getAdminAuth().getUser(uid);
      await getAdminAuth().setCustomUserClaims(uid, { ...authUser.customClaims, role: body.role });
      await getAdminAuth().revokeRefreshTokens(uid);
      await logSecurityEvent("role_change", { by: admin.uid, target: uid, role: body.role });
    }

    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
