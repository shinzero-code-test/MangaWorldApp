import { NextRequest, NextResponse } from "next/server";
import { clearMfaGrantCookie, DASHBOARD_ROLES, type DashboardRole } from "@/lib/auth";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    const { idToken } = await request.json();
    if (!idToken) {
      return NextResponse.json({ error: "Missing ID token" }, { status: 400 });
    }

    const decoded = await getAdminAuth().verifyIdToken(idToken);
    const adminAuth = getAdminAuth();
    const configuredSuperAdmin = process.env.SUPER_ADMIN_EMAIL?.trim().toLowerCase();
    if (configuredSuperAdmin && decoded.email?.trim().toLowerCase() === configuredSuperAdmin && decoded.role !== "super-admin") {
      const user = await adminAuth.getUser(decoded.uid);
      await adminAuth.setCustomUserClaims(decoded.uid, { ...user.customClaims, role: "super-admin" });
      return NextResponse.json({ refreshRequired: true });
    }
    const role = DASHBOARD_ROLES.includes(decoded.role as DashboardRole)
      ? decoded.role as DashboardRole
      : "viewer";

    const profileDoc = await getAdminDb().collection("publicProfiles").doc(decoded.uid).get();

    if (!profileDoc.exists) {
      await getAdminDb().collection("publicProfiles").doc(decoded.uid).set({
        username: decoded.name || decoded.email?.split("@")[0] || "user",
        avatarUrl: decoded.picture || "",
        isPublic: false,
        showListsPublic: false,
        showActivityPublic: false,
        bio: "",
        updatedAt: Date.now(),
      });
    }

    const expiresIn = 60 * 60 * 24 * 7 * 1000;
    const sessionCookie = await getAdminAuth().createSessionCookie(idToken, { expiresIn });

    const response = NextResponse.json({
      success: true,
      role,
    });
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: true,
      sameSite: "lax",
      maxAge: expiresIn / 1000,
      path: "/",
    });
    clearMfaGrantCookie(response);

    return response;
  } catch (error) {
    console.error("Auth error:", error);
    return NextResponse.json({ error: "خطأ في المصادقة" }, { status: 401 });
  }
}
