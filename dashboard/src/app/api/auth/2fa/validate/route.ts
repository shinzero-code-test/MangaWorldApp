import { NextRequest, NextResponse } from "next/server";
import { createMfaGrant, getCurrentUser, setMfaGrantCookie } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  clearOtpFailures,
  genericErrorResponse,
  isOtpLocked,
  consumeUsedToken,
  logSecurityEvent,
  matchBackupCodeHash,
  recordOtpFailure,
  resolveTotpSecret,
  verifyTotpConstantTime,
} from "@/lib/security";

export const dynamic = "force-dynamic";

// POST: Validate OTP on login (after password auth, before dashboard access)
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser({ requireMfa: false });
    const { token } = await request.json();
    if (typeof token !== "string") {
      return NextResponse.json({ error: "رمز التحقق مطلوب" }, { status: 400 });
    }

    // Lockout window after repeated failures — without this, a stolen password
    // reduces the second factor to an online brute-force exercise.
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

    const secret = resolveTotpSecret(doc.data());
    const isValid = secret
      ? verifyTotpConstantTime(secret, token)
      : false;

    // Lost-authenticator path (#3): a single-use recovery code proves
    // possession when the TOTP app is gone. Consumed (deleted) on success.
    let consumedBackupHash: string | null = null;
    if (!isValid) {
      consumedBackupHash = matchBackupCodeHash(token, doc.data()?.backupCodeHashes);
    }

    if (!isValid && !consumedBackupHash) {
      await recordOtpFailure(user.uid);
      return NextResponse.json(
        { error: "رمز التحقق غير صحيح. تأكد من الرمز وحاول مرة أخرى" },
        { status: 400 }
      );
    }
    if (isValid && !(await consumeUsedToken(user.uid, token))) {
      return NextResponse.json(
        { error: "رمز التحقق مستخدم مسبقاً" },
        { status: 400 }
      );
    }
    if (consumedBackupHash) {
      const remaining = ((doc.data()?.backupCodeHashes as unknown[]) ?? [])
        .filter((h) => h !== consumedBackupHash);
      await ref.set({ backupCodeHashes: remaining }, { merge: true });
      await logSecurityEvent("2fa_backup_used", {
        uid: user.uid,
        remaining: remaining.length,
      });
    }

    await clearOtpFailures(user.uid);
    const grantId = await createMfaGrant(user);
    const response = NextResponse.json({ success: true });
    setMfaGrantCookie(response, grantId);

    return response;
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
