import { NextRequest, NextResponse } from "next/server";
import { rejectAnonymousUser, verifyAppIdToken } from "@/lib/app-auth";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { getAdminAuth, getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse, logSecurityEvent } from "@/lib/security";

export const dynamic = 'force-dynamic';

/**
 * POST /api/users/me/revoke-sessions — "sign out all devices" for the app's
 * security centre. Firebase has no per-session revocation, so this performs
 * the true per-user invalidation (revokeRefreshTokens) plus wipes the
 * push-device registry and flags every session revoked. The caller signs out
 * locally afterwards.
 */
export async function POST(request: NextRequest) {
  try {
    const user = await verifyAppIdToken(request);
    rejectAnonymousUser(user);
    if (!(await allowAppMutation(`revoke-sessions:${user.uid}`, 5, 60 * 1000))) {
      return NextResponse.json({ error: "تم إرسال عدد كبير من المحاولات. حاول مرة أخرى لاحقاً." }, { status: 429 });
    }

    const db = getAdminDb();
    const uid = user.uid;

    // True token invalidation — every refresh token for this user stops working.
    await getAdminAuth().revokeRefreshTokens(uid);

    // Wipe the push-device registry so no device keeps receiving pushes.
    const devices = await db.collection("users").doc(uid).collection("devices").limit(100).get();
    const batch = db.batch();
    devices.docs.forEach((doc) => batch.delete(doc.ref));
    // Flag every session revoked for clients that still hold an ID token.
    const sessions = await db.collection("users").doc(uid).collection("sessions").limit(100).get();
    sessions.docs.forEach((doc) => batch.update(doc.ref, { revoked: true }));
    await batch.commit();

    await logSecurityEvent("user_revoke_sessions", { uid });
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
