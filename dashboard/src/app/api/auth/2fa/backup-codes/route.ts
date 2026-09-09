import { NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  generateBackupCodes,
  genericErrorResponse,
  logSecurityEvent,
} from "@/lib/security";

export const dynamic = "force-dynamic";

// Minting recovery codes must happen on a fresh credential authentication, so
// a stolen long-lived session alone cannot create a backdoor (#3).
const FRESH_AUTH_WINDOW_MS = 15 * 60 * 1000;

/**
 * POST /api/auth/2fa/backup-codes — issue a fresh set of 10 single-use
 * recovery codes. Plaintext is returned exactly once (`no-store`); only
 * SHA-256 hashes are persisted. Re-issuing invalidates the previous set.
 */
export async function POST() {
  try {
    const user = await getCurrentUser({ requireMfa: false });

    if (!user.authTime || Date.now() - user.authTime > FRESH_AUTH_WINDOW_MS) {
      await logSecurityEvent("2fa_backup_stale_session", { uid: user.uid });
      return NextResponse.json(
        { error: "انتهت مدة الجلسة الحديثة. سجّل الخروج ثم أعد تسجيل الدخول للمتابعة." },
        { status: 403 }
      );
    }

    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    if (!doc.exists || doc.data()?.enabled !== true) {
      return NextResponse.json(
        { error: "المصادقة الثنائية غير مفعلة" },
        { status: 400 }
      );
    }

    const { codes, hashes } = generateBackupCodes();
    await getAdminDb().collection("admin2fa").doc(user.uid).set(
      { backupCodeHashes: hashes, backupCodesAt: Date.now() },
      { merge: true }
    );
    await logSecurityEvent("2fa_backup_issued", { uid: user.uid, count: codes.length });

    const res = NextResponse.json({ codes });
    res.headers.set("Cache-Control", "no-store");
    return res;
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function GET() {
  return NextResponse.json(
    { error: "Method not allowed — use POST" },
    { status: 405 }
  );
}
