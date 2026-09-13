import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { DASHBOARD_ROLES, requireRole, wouldStrandLastSuperAdmin } from "@/lib/auth";
import { genericErrorResponse, logSecurityEvent, consumeRateLimit} from "@/lib/security";
import {
  isValidUsername,
  normalizeUsername,
  releaseUsernameIfOwned,
  tryClaimUsername,
} from "@/lib/username";

export const dynamic = 'force-dynamic';

export async function GET(request: NextRequest) {
  try {
    const admin = await requireRole("moderator");
    // Quota-burn guard: full Auth/fleet scans per request need a per-admin throttle.
    const rl = await consumeRateLimit("admin-read", admin.uid, 60, 60 * 1000);
    if (!rl.allowed) {
      return NextResponse.json({ error: "Too many requests" }, { status: 429 });
    }
    const { searchParams } = new URL(request.url);
    const search = (searchParams.get("search") ?? "").trim().toLowerCase().slice(0, 128);
    const roleFilter = searchParams.get("role") || "";
    const providerFilter = searchParams.get("provider") || "";
    // D-7: anonymous guest sessions (no providers) churn per install and
    // used to dominate the operator's list + viewer counts. Excluded by
    // default; pass includeGuests=1 to audit them explicitly.
    const includeGuests = searchParams.get("includeGuests") === "1";
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

    // 2. Map to a unified user object. Anonymous guests have empty
    // providerData (no email/password/google) — flag them so the operator
    // can distinguish churn from real accounts.
    let enriched = allAuthUsers.map(authUser => {
      const providers: string[] = authUser.providerData.map((p: { providerId: string }) => p.providerId);
      const isAnonymous = providers.length === 0;
      return {
        id: authUser.uid,
        email: authUser.email || null,
        displayName: authUser.displayName || null,
        username: authUser.displayName || authUser.email?.split('@')[0] || (isAnonymous ? "زائر" : "مستخدم"),
        role: authUser.customClaims?.role || "viewer",
        emailVerified: authUser.emailVerified,
        disabled: authUser.disabled,
        lastSignIn: authUser.metadata.lastSignInTime,
        createdAt: authUser.metadata.creationTime,
        providers,
        isAnonymous,
        phoneNumber: authUser.phoneNumber || null,
      };
    });

    const guestCount = enriched.filter(u => u.isAnonymous).length;
    // D-7 audit switch: provider=anonymous lists only guests; otherwise
    // guests are hidden unless includeGuests=1 is passed explicitly.
    if (providerFilter === "anonymous") {
      enriched = enriched.filter(u => u.isAnonymous);
    } else if (!includeGuests) {
      enriched = enriched.filter(u => !u.isAnonymous);
    }

    // Global role counts over the NAMED fleet (pre-filter) so the UI chips
    // show real totals, not the current page. D-7: guests never inflate
    // viewer — their volume is reported separately as guestCount.
    const roleCounts: Record<string, number> = { "super-admin": 0, moderator: 0, viewer: 0 };
    for (const u of enriched) {
      if (u.isAnonymous) continue;
      const r = typeof u.role === "string" && u.role in roleCounts ? u.role : "viewer";
      roleCounts[r] += 1;
    }
    if (providerFilter === "anonymous") {
      // Guest-audit view: enriched holds only guests, so recount chips from
      // the named fleet instead of showing zeros.
      for (const k of Object.keys(roleCounts)) roleCounts[k] = 0;
      for (const authUser of allAuthUsers) {
        if ((authUser.providerData?.length ?? 0) === 0) continue;
        const r = typeof authUser.customClaims?.role === "string" && authUser.customClaims.role in roleCounts
          ? (authUser.customClaims.role as string)
          : "viewer";
        roleCounts[r] += 1;
      }
    }

    // 3. Apply filters
    if (roleFilter) enriched = enriched.filter(u => u.role === roleFilter);
    // Anonymous guests carry EMPTY providerData (there is no "anonymous"
    // providerId) — the guest-audit view above already isolated them, so the
    // generic includes() check must not wipe that list.
    if (providerFilter && providerFilter !== "anonymous") {
      enriched = enriched.filter(u => u.providers.includes(providerFilter));
    }
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
      // D-7: guest volume is reported separately so the operator knows how
      // much churn is hidden when includeGuests is off.
      guestCount,
      includeGuests,
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
    if (uid === admin.uid && (role !== undefined || disabled === true)) {
      return NextResponse.json({ error: "لا يمكنك تغيير دورك أو تعطيل حسابك بنفسك" }, { status: 400 });
    }
    if (role !== undefined) {
      if (typeof role !== "string" || !DASHBOARD_ROLES.includes(role as (typeof DASHBOARD_ROLES)[number])) {
        return NextResponse.json({ error: "الدور غير صالح" }, { status: 400 });
      }
      if (await wouldStrandLastSuperAdmin(uid, role, undefined)) {
        return NextResponse.json({ error: "لا يمكن إزالة آخر مدير عام" }, { status: 400 });
      }
      const authUser = await getAdminAuth().getUser(uid);
      await getAdminAuth().setCustomUserClaims(uid, { ...authUser.customClaims, role });
      await getAdminAuth().revokeRefreshTokens(uid);
      await logSecurityEvent("role_change", { by: admin.uid, target: uid, role });
    }
    if (username !== undefined) {
      // D-4: same UsernameRules + claim enforcement as the [uid] route.
      if (!isValidUsername(username.trim())) {
        return NextResponse.json({ error: "اسم المستخدم غير صالح" }, { status: 400 });
      }
      const nextUsername = username.trim();
      const profileSnap = await getAdminDb().collection("publicProfiles").doc(uid).get();
      const prevUsername =
        typeof profileSnap.data()?.username === "string"
          ? (profileSnap.data()?.username as string)
          : "";
      if (normalizeUsername(prevUsername) !== normalizeUsername(nextUsername)) {
        const claimed = await tryClaimUsername(uid, nextUsername);
        if (!claimed) {
          return NextResponse.json({ error: "اسم المستخدم مستخدم بالفعل" }, { status: 409 });
        }
        await releaseUsernameIfOwned(uid, prevUsername);
        await logSecurityEvent("admin_username_change", {
          by: admin.uid,
          target: uid,
          from: prevUsername || null,
          to: nextUsername,
        });
      }
      updates.username = nextUsername;
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
      if (disabled === true && (await wouldStrandLastSuperAdmin(uid, undefined, true))) {
        return NextResponse.json({ error: "لا يمكن تعطيل آخر مدير عام" }, { status: 400 });
      }
      await getAdminAuth().updateUser(uid, { disabled });
      if (disabled === true) {
        // A disabled account must not keep usable sessions.
        await getAdminAuth().revokeRefreshTokens(uid).catch(() => {});
      }
      if (disabled !== undefined) {
        await logSecurityEvent("account_disable_toggle", { by: admin.uid, target: uid, disabled });
      }
    }

    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function DELETE(request: NextRequest) {
  try {
    const admin = await requireRole("super-admin");
    const { uid } = await request.json();
    if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
      return NextResponse.json({ error: "Missing uid" }, { status: 400 });
    }
    if (await wouldStrandLastSuperAdmin(uid, "viewer", undefined)) {
      return NextResponse.json({ error: "لا يمكن حذف آخر مدير عام" }, { status: 400 });
    }

    // D-5: capture the username BEFORE deleting the profile so the
    // usernames/{name} claim can be released — otherwise the name stays
    // permanently reserved against a dead uid.
    let claimedUsername: string | null = null;
    try {
      const profileSnap = await getAdminDb().collection("publicProfiles").doc(uid).get();
      const raw = profileSnap.data()?.username;
      if (typeof raw === "string" && raw) claimedUsername = raw;
    } catch {
      /* best-effort */
    }

    // Delete user from Auth
    await getAdminAuth().deleteUser(uid);

    // Delete profile
    await getAdminDb().collection("publicProfiles").doc(uid).delete().catch(() => {});

    // Delete user subcollections — paginate to completion so residual PII
    // doesn't survive when a subcollection exceeds the first page.
    // devices/lists/notifications leave push tokens + PII behind otherwise.
    // D-5: loginLogs/sessions/preferences/syncTombstones were missing —
    // without them a "deleted" account's sign-in history, session registry,
    // settings, and deletion markers survive.
    const subcols = [
      "favorites",
      "readingHistory",
      "readerAnnotations",
      "devices",
      "lists",
      "notifications",
      "loginLogs",
      "sessions",
      "preferences",
      "syncTombstones",
    ];
    for (const subcol of subcols) {
      for (;;) {
        const snap = await getAdminDb().collection("users").doc(uid).collection(subcol).limit(500).get();
        if (snap.empty) break;
        const batch = getAdminDb().batch();
        snap.docs.forEach((doc: any) => batch.delete(doc.ref));
        await batch.commit();
      }
    }

    // 2FA/OTP rows are keyed by uid outside users/ — remove them too.
    await getAdminDb().collection("admin2fa").doc(uid).delete().catch(() => {});
    await getAdminDb().collection("adminOtpAttempts").doc(uid).delete().catch(() => {});
    // Stale MFA grants for this uid must not stay replayable after delete.
    try {
      const grants = await getAdminDb()
        .collection("adminMfaSessions")
        .where("uid", "==", uid)
        .limit(50)
        .get();
      if (!grants.empty) {
        const batch = getAdminDb().batch();
        grants.docs.forEach((doc: any) => batch.delete(doc.ref));
        await batch.commit();
      }
    } catch {
      /* best-effort */
    }

    // D-5: release the username claim iff owned by this uid.
    if (claimedUsername) {
      const { releaseUsernameIfOwned } = await import("@/lib/username");
      await releaseUsernameIfOwned(uid, claimedUsername);
    }

    // Delete user doc
    await getAdminDb().collection("users").doc(uid).delete().catch(() => {});

    await logSecurityEvent("admin_user_delete", { by: admin.uid, target: uid });

    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
