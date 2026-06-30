import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest) {
  try {
    const { idToken } = await request.json();
    if (!idToken) {
      return NextResponse.json({ error: "Missing ID token" }, { status: 400 });
    }

    const decoded = await getAdminAuth().verifyIdToken(idToken);
    const isSuperAdmin = decoded.email === process.env.SUPER_ADMIN_EMAIL;

    const profileDoc = await getAdminDb().collection("publicProfiles").doc(decoded.uid).get();

    if (!profileDoc.exists) {
      await getAdminDb().collection("publicProfiles").doc(decoded.uid).set({
        uid: decoded.uid,
        username: decoded.name || decoded.email?.split("@")[0] || "user",
        avatarUrl: decoded.picture || "",
        role: isSuperAdmin ? "super-admin" : "viewer",
        isPublic: false,
        showListsPublic: false,
        showActivityPublic: false,
        bio: "",
        updatedAt: Date.now(),
      });
    } else if (isSuperAdmin && profileDoc.data()?.role !== "super-admin") {
      await getAdminDb().collection("publicProfiles").doc(decoded.uid).update({
        role: "super-admin",
        updatedAt: Date.now(),
      });
    }

    const expiresIn = 60 * 60 * 24 * 7 * 1000;
    const sessionCookie = await getAdminAuth().createSessionCookie(idToken, { expiresIn });

    const response = NextResponse.json({
      success: true,
      role: profileDoc.data()?.role || "viewer",
    });
    response.cookies.set("session", sessionCookie, {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      maxAge: expiresIn / 1000,
      path: "/",
    });

    return response;
  } catch (error: any) {
    console.error("Auth error:", error);
    return NextResponse.json({ error: error.message || "خطأ في المصادقة" }, { status: 401 });
  }
}
