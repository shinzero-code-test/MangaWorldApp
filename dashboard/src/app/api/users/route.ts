import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    const user = await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const search = searchParams.get("search") || "";
    const roleFilter = searchParams.get("role") || "";
    const providerFilter = searchParams.get("provider") || "";
    const page = parseInt(searchParams.get("page") || "1");
    const limit = parseInt(searchParams.get("limit") || "20");
    const sortBy = searchParams.get("sortBy") || "updatedAt";
    const sortDir = searchParams.get("sortDir") || "desc";
    const offset = (page - 1) * limit;

    // Get profiles from Firestore
    let query: any = getAdminDb().collection("publicProfiles");
    if (roleFilter) query = query.where("role", "==", roleFilter);
    query = query.orderBy(sortBy, sortDir as "asc" | "desc");

    const snapshot = await query.offset(offset).limit(limit + 1).get();
    const profiles = snapshot.docs.map((doc: any) => ({ id: doc.id, ...doc.data() }));
    const hasMore = snapshot.docs.length > limit;
    const profilesPage = profiles.slice(0, limit);

    // Enrich with Firebase Auth data
    const enriched = await Promise.all(
      profilesPage.map(async (profile: any) => {
        try {
          const authUser = await getAdminAuth().getUser(profile.id);
          return {
            ...profile,
            email: authUser.email || null,
            emailVerified: authUser.emailVerified,
            disabled: authUser.disabled,
            lastSignIn: authUser.metadata.lastSignInTime,
            createdAt: authUser.metadata.creationTime,
            providers: authUser.providerData.map((p: any) => p.providerId),
            phoneNumber: authUser.phoneNumber || null,
          };
        } catch {
          return { ...profile, email: null, providers: [] };
        }
      })
    );

    // Apply search filter (after enrichment since search may need email)
    let filtered = enriched;
    if (search) {
      const s = search.toLowerCase();
      filtered = enriched.filter(
        (u: any) =>
          u.username?.toLowerCase().includes(s) ||
          u.email?.toLowerCase().includes(s) ||
          u.id.toLowerCase().includes(s)
      );
    }

    // Apply provider filter
    if (providerFilter) {
      filtered = filtered.filter((u: any) =>
        u.providers?.includes(providerFilter)
      );
    }

    // Get total count
    const countSnapshot = await getAdminDb().collection("publicProfiles").count().get();
    const total = countSnapshot.data().count;

    return NextResponse.json({
      users: filtered,
      total,
      page,
      limit,
      hasMore,
    });
  } catch (error: any) {
    const status = error.message === "Forbidden" ? 403 : error.message === "Unauthorized" ? 401 : 500;
    return NextResponse.json({ error: error.message }, { status });
  }
}

export async function PATCH(request: NextRequest) {
  try {
    const admin = await requireRole("super-admin");
    const { uid, role, username, bio, disabled } = await request.json();

    if (!uid) return NextResponse.json({ error: "Missing uid" }, { status: 400 });

    const updates: any = { updatedAt: Date.now() };
    if (role && ["viewer", "moderator", "super-admin"].includes(role)) {
      updates.role = role;
      // Also set custom claim
      await getAdminAuth().setCustomUserClaims(uid, { role });
    }
    if (username !== undefined) updates.username = username;
    if (bio !== undefined) updates.bio = bio;

    await getAdminDb().collection("publicProfiles").doc(uid).update(updates);

    if (disabled !== undefined) {
      await getAdminAuth().updateUser(uid, { disabled });
    }

    return NextResponse.json({ success: true });
  } catch (error: any) {
    const status = error.message === "Forbidden" ? 403 : error.message === "Unauthorized" ? 401 : 500;
    return NextResponse.json({ error: error.message }, { status });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { uid } = await request.json();
    if (!uid) return NextResponse.json({ error: "Missing uid" }, { status: 400 });

    // Delete user from Auth
    await getAdminAuth().deleteUser(uid);

    // Delete profile
    await getAdminDb().collection("publicProfiles").doc(uid).delete();

    // Delete user subcollections
    const subcols = ["favorites", "readingHistory", "readerAnnotations"];
    for (const subcol of subcols) {
      const snap = await getAdminDb().collection("users").doc(uid).collection(subcol).limit(500).get();
      const batch = getAdminDb().batch();
      snap.docs.forEach((doc: any) => batch.delete(doc.ref));
      await batch.commit();
    }

    // Delete user doc
    await getAdminDb().collection("users").doc(uid).delete();

    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
