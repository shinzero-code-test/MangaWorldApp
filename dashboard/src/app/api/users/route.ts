import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { DASHBOARD_ROLES, requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    const admin = await requireRole("moderator");
    const { searchParams } = new URL(request.url);
    const search = (searchParams.get("search") ?? "").trim().toLowerCase().slice(0, 128);
    const roleFilter = searchParams.get("role") || "";
    const providerFilter = searchParams.get("provider") || "";
    // Clamp pagination: unbounded page/limit turns a full listUsers scan into
    // a DoS/cost vector with giant offsets.
    const page = Math.min(10000, Math.max(1, parseInt(searchParams.get("page") || "1") || 1));
    const limit = Math.min(100, Math.max(1, parseInt(searchParams.get("limit") || "20") || 20));
    const SORT_KEYS = ["createdAt", "lastSignIn", "email", "username", "role"] as const;
    const rawSort = searchParams.get("sortBy") || "createdAt";
    const sortBy: string = (SORT_KEYS as readonly string[]).includes(rawSort) ? rawSort : "createdAt";
    const sortDir = searchParams.get("sortDir") === "asc" ? "asc" : "desc";

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
      displayName: authUser.displayName || null,
      username: authUser.displayName || authUser.email?.split('@')[0] || "مستخدم",
      role: authUser.customClaims?.role || "viewer",
      emailVerified: authUser.emailVerified,
      disabled: authUser.disabled,
      lastSignIn: authUser.metadata.lastSignInTime,
      createdAt: authUser.metadata.creationTime,
      providers: authUser.providerData.map((p: { providerId: string }) => p.providerId),
      phoneNumber: authUser.phoneNumber || null,
    }));

    // Global role counts (pre-filter) so the UI chips show fleet totals,
    // not just the current page.
    const roleCounts: Record<string, number> = { "super-admin": 0, moderator: 0, viewer: 0 };
    for (const u of enriched) {
      const r = typeof u.role === "string" && u.role in roleCounts ? u.role : "viewer";
      roleCounts[r] += 1;
    }

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
      roleCounts,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PATCH(request: NextRequest) {
  try {
    const admin = await requireRole("super-admin");
    const { uid, role, username, bio, disabled } = await request.json();

    if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
      return NextResponse.json({ error: "معرف المستخدم غير صالح" }, { status: 400 });
    }

    const updates: Record<string, unknown> = { updatedAt: Date.now() };
    if (role !== undefined) {
      if (typeof role !== "string" || !DASHBOARD_ROLES.includes(role as (typeof DASHBOARD_ROLES)[number])) {
        return NextResponse.json({ error: "الدور غير صالح" }, { status: 400 });
      }
      const authUser = await getAdminAuth().getUser(uid);
      await getAdminAuth().setCustomUserClaims(uid, { ...authUser.customClaims, role });
      await getAdminAuth().revokeRefreshTokens(uid);
    }
    if (username !== undefined) {
      if (typeof username !== "string" || username.trim().length < 1 || username.length > 64) {
        return NextResponse.json({ error: "اسم المستخدم غير صالح" }, { status: 400 });
      }
      updates.username = username.trim();
    }
    if (bio !== undefined) {
      if (typeof bio !== "string" || bio.length > 1000) {
        return NextResponse.json({ error: "النبذة غير صالحة" }, { status: 400 });
      }
      updates.bio = bio;
    }

    await getAdminDb().collection("publicProfiles").doc(uid).update(updates);

    if (disabled !== undefined) {
      if (typeof disabled !== "boolean") {
        return NextResponse.json({ error: "حالة التعطيل غير صالحة" }, { status: 400 });
      }
      await getAdminAuth().updateUser(uid, { disabled });
    }

    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { uid } = await request.json();
    if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
      return NextResponse.json({ error: "Missing uid" }, { status: 400 });
    }

    // Delete user from Auth
    await getAdminAuth().deleteUser(uid);

    // Delete profile
    await getAdminDb().collection("publicProfiles").doc(uid).delete();

    // Delete user subcollections — paginate to completion so residual PII
    // doesn't survive when a subcollection exceeds the first page.
    const subcols = ["favorites", "readingHistory", "readerAnnotations"];
    for (const subcol of subcols) {
      for (;;) {
        const snap = await getAdminDb().collection("users").doc(uid).collection(subcol).limit(500).get();
        if (snap.empty) break;
        const batch = getAdminDb().batch();
        snap.docs.forEach((doc: any) => batch.delete(doc.ref));
        await batch.commit();
      }
    }

    // Delete user doc
    await getAdminDb().collection("users").doc(uid).delete();

    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
