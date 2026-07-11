import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { DASHBOARD_ROLES, requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    const admin = await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const search = searchParams.get("search")?.toLowerCase() || "";
    const roleFilter = searchParams.get("role") || "";
    const providerFilter = searchParams.get("provider") || "";
    const page = parseInt(searchParams.get("page") || "1");
    const limit = parseInt(searchParams.get("limit") || "20");
    const sortBy = searchParams.get("sortBy") || "createdAt";
    const sortDir = searchParams.get("sortDir") || "desc";

    // 1. Fetch all users from Auth (since publicProfiles may be incomplete)
    let allAuthUsers: any[] = [];
    let pageToken: string | undefined = undefined;
    do {
      const result = await getAdminAuth().listUsers(1000, pageToken);
      allAuthUsers.push(...result.users);
      pageToken = result.pageToken;
    } while (pageToken);

    // 2. Map to a unified user object
    let enriched = allAuthUsers.map(authUser => ({
      id: authUser.uid,
      email: authUser.email || null,
      username: authUser.displayName || authUser.email?.split('@')[0] || "مستخدم",
      role: authUser.customClaims?.role || "viewer",
      emailVerified: authUser.emailVerified,
      disabled: authUser.disabled,
      lastSignIn: authUser.metadata.lastSignInTime,
      createdAt: authUser.metadata.creationTime,
      providers: authUser.providerData.map(p => p.providerId),
      phoneNumber: authUser.phoneNumber || null,
    }));

    // 3. Apply filters
    if (roleFilter) enriched = enriched.filter(u => u.role === roleFilter);
    if (providerFilter) enriched = enriched.filter(u => u.providers.includes(providerFilter));
    if (search) {
      enriched = enriched.filter(u => 
        u.email?.toLowerCase().includes(search) || 
        u.id.toLowerCase().includes(search) ||
        u.username.toLowerCase().includes(search)
      );
    }

    // 4. Sort
    enriched.sort((a, b) => {
      let valA = a[sortBy as keyof typeof a];
      let valB = b[sortBy as keyof typeof b];
      if (sortBy === "createdAt" || sortBy === "lastSignIn") {
        valA = new Date(valA as string).getTime();
        valB = new Date(valB as string).getTime();
      }
      if (valA! < valB!) return sortDir === "asc" ? -1 : 1;
      if (valA! > valB!) return sortDir === "asc" ? 1 : -1;
      return 0;
    });

    // 5. Paginate
    const total = enriched.length;
    const offset = (page - 1) * limit;
    const users = enriched.slice(offset, offset + limit);
    const hasMore = offset + limit < total;

    return NextResponse.json({
      users,
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
    if (role !== undefined) {
      if (!DASHBOARD_ROLES.includes(role)) return NextResponse.json({ error: "Invalid role" }, { status: 400 });
      const authUser = await getAdminAuth().getUser(uid);
      await getAdminAuth().setCustomUserClaims(uid, { ...authUser.customClaims, role });
      await getAdminAuth().revokeRefreshTokens(uid);
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
