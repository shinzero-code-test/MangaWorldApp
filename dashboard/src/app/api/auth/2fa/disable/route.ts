import { NextRequest, NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  clearOtpFailures,
  consumeUsedToken,
  genericErrorResponse,
  isOtpLocked,
  logSecurityEvent,
  matchBackupCodeHash,
  recordOtpFailure,
  resolveTotpSecret,
  verifyTotpConstantTime,
} from "@/lib/security";

export const dynamic = "force-dynamic";

// Disabling the second factor must prove possession of it (or of a recovery
// code) on a fresh session — otherwise a stolen cookie silently unprotects
// the account (#3).
const FRESH_AUTH_WINDOW_MS = 15 * 60 * 1000;

/**
 * POST /api/auth/2fa/disable — self-service 2FA removal.
 * Body: { proof: string } where proof is a current TOTP token or an unused
 * backup code. Wipes the seed, hashes, grant state, and lockout counters.
 */
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser({ requireMfa: false });
    const { proof } = await request.json();
    if (typeof proof !== "string" || proof.length === 0) {
      return NextResponse.json({ error: "رمز التحقق مطلوب" }, { status: 400 });
    }

    if (!user.authTime || Date.now() - user.authTime > FRESH_AUTH_WINDOW_MS) {
      await logSecurityEvent("2fa_disable_stale_session", { uid: user.uid });
      return NextResponse.json(
        { error: "انتهت مدة الجلسة الحديثة. سجّل الخروج ثم أعد تسجيل الدخول للمتابعة." },
        { status: 403 }
      );
    }

    if (await isOtpLocked(user.uid)) {
      return NextResponse.json(
        { error: "تم قفل التحقق مؤقتاً بسبب محاولات فاشلة متكررة. حاول بعد بضع دقائق." },
        { status: 429 }
      );
    }

    const ref = getAdminDb().collection("admin2fa").doc(user.uid);
    const doc = await ref.get();
    if (!doc.exists || doc.data()?.enabled !== true) {
      return NextResponse.json(
        { error: "المصادقة الثنائية غير مفعلة" },
        { status: 400 }
      );
    }

    // Possession proof: live TOTP first, then single-use backup code.
    let provedByBackupHash: string | null = null;
    const secret = resolveTotpSecret(doc.data());
    const totpOk = secret ? verifyTotpConstantTime(secret, proof) : false;
    if (!totpOk) {
      provedByBackupHash = matchBackupCodeHash(proof, doc.data()?.backupCodeHashes);
    }
    if (!totpOk && !provedByBackupHash) {
      await recordOtpFailure(user.uid);
      return NextResponse.json({ error: "رمز التحقق غير صحيح" }, { status: 400 });
    }
    if (totpOk && !(await consumeUsedToken(user.uid, proof))) {
      return NextResponse.json({ error: "رمز التحقق مستخدم مسبقاً" }, { status: 400 });
    }

    await ref.set(
      {
        enabled: false,
        secret: null,
        secretEncrypted: null,
        backupCodeHashes: [],
        disabledAt: Date.now(),
        disabledViaBackup: provedByBackupHash !== null,
      },
      { merge: true }
    );
    await clearOtpFailures(user.uid);
    await logSecurityEvent("2fa_disabled", {
      uid: user.uid,
      viaBackup: provedByBackupHash !== null,
    });

    return NextResponse.json({ success: true });
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
