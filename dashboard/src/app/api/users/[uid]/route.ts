import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET(_request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("moderator");
    const { uid } = await params;
    const profileDoc = await adminDb.collection("publicProfiles").doc(uid).get();
    if (!profileDoc.exists) return NextResponse.json({ error: "User not found" }, { status: 404 });

    const [favSnap, histSnap] = await Promise.all([
      adminDb.collection("users").doc(uid).collection("favorites").count().get(),
      adminDb.collection("users").doc(uid).collection("readingHistory").count().get(),
    ]);

    return NextResponse.json({
      id: uid,
      ...profileDoc.data(),
      favoriteCount: favSnap.data().count,
      historyCount: histSnap.data().count,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}

export async function PATCH(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    const admin = await requireRole("super-admin");
    const { uid } = await params;
    const body = await request.json();
    if (body.role && ["viewer", "moderator", "super-admin"].includes(body.role)) {
      await adminDb.collection("publicProfiles").doc(uid).update({ role: body.role, updatedAt: Date.now() });
    }
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
