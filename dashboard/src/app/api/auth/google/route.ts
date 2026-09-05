import { NextRequest, NextResponse } from "next/server";
import { clearMfaGrantCookie, DASHBOARD_ROLES, type DashboardRole } from "@/lib/auth";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { consumeRateLimit, logSecurityEvent } from "@/lib/security";

export const dynamic = 'force-dynamic';

function clientIp(request: NextRequest): string {
  return (
    request.headers.get("x-real-ip")?.split(",")[0]?.trim() ||
    request.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ||
    "unknown"
  );
}

export async function POST(request: NextRequest) {
  try {
    // Public token-exchange endpoint: throttle like the sibling auth routes.
    const ipAttempt = await consumeRateLimit("auth-google-ip", clientIp(request), 30, 15 * 60 * 1000);
    if (!ipAttempt.allowed) {
      return NextResponse.json(
        { error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." },
        { status: 429 }
      );
    }
    const { idToken } = await request.json();
    if (typeof idToken !== "string" || idToken.length === 0 || idToken.length > 8192) {
      return NextResponse.json({ error: "Missing ID token" }, { status: 400 });
    }

    const decoded = await getAdminAuth().verifyIdToken(idToken);
    const adminAuth = getAdminAuth();
    const configuredSuperAdmin = process.env.SUPER_ADMIN_EMAIL?.trim().toLowerCase();
    // email_verified is mandatory: during config drift (typo'd env var, freed and
    // re-registered address) an unverified account matching the configured email
    // must never be elevated.
    if (
      configuredSuperAdmin &&
      decoded.email?.trim().toLowerCase() === configuredSuperAdmin &&
      decoded.email_verified === true &&
      decoded.role !== "super-admin"
    ) {
      const user = await adminAuth.getUser(decoded.uid);
      await adminAuth.setCustomUserClaims(decoded.uid, { ...user.customClaims, role: "super-admin" });
      await logSecurityEvent("super_admin_auto_promotion", { uid: decoded.uid, email: decoded.email });
      return NextResponse.json({ refreshRequired: true });
    }
    const role = DASHBOARD_ROLES.includes(decoded.role as DashboardRole)
      ? decoded.role as DashboardRole
      : "viewer";

    // Block viewers from accessing the dashboard. The signed-in email is
    // echoed back so an admin who used the wrong Google account can tell
    // immediately (previously a bare "no permission" looked like broken auth).
    if (role === "viewer") {
      return NextResponse.json(
        {
          error: "ليس لديك صلاحية الوصول إلى لوحة التحكم. هذه اللوحة مخصصة للمشرفين والمديرين فقط.",
          email: decoded.email ?? null,
        },
        { status: 403 }
      );
    }

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
    // `secure` must follow the environment: hardcoded `true` prevents the
    // cookie from being set over http://localhost during local development,
    // which surfaces as "Google Auth is not working".
    const isProd = process.env.NODE_ENV === "production";
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: isProd,
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
