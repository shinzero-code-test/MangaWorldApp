import { NextRequest, NextResponse } from "next/server";
import { createMfaGrant, getCurrentUser, setMfaGrantCookie } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  clearOtpFailures,
  genericErrorResponse,
  isOtpLocked,
  logSecurityEvent,
  recordOtpFailure,
  resolveTotpSecret,
  upgradeLegacySecret,
  verifyTotpConstantTime,
} from "@/lib/security";

export const dynamic = "force-dynamic";

// Enrollment must happen on a fresh credential authentication, so a stolen
// session cookie alone cannot bind an attacker's authenticator to the account.
const FRESH_AUTH_WINDOW_MS = 15 * 60 * 1000;

// POST: Verify OTP during initial setup to enable 2FA
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser({ requireMfa: false });
    const { token } = await request.json();
    if (typeof token !== "string") {
      return NextResponse.json({ error: "رمز التحقق مطلوب" }, { status: 400 });
    }

    if (await isOtpLocked(user.uid)) {
      return NextResponse.json(
        { error: "تم قفل التحقق مؤقتاً بسبب محاولات فاشلة متكررة. حاول بعد بضع دقائق." },
        { status: 429 }
      );
    }

    if (!user.authTime || Date.now() - user.authTime > FRESH_AUTH_WINDOW_MS) {
      await logSecurityEvent("2fa_enable_stale_session", { uid: user.uid });
      return NextResponse.json(
        { error: "انتهت مدة الجلسة الحديثة. سجّل الخروج ثم أعد تسجيل الدخول لإكمال التفعيل." },
        { status: 403 }
      );
    }

    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    if (!doc.exists) {
      return NextResponse.json(
        { error: "لم يتم إعداد المصادقة الثنائية" },
        { status: 400 }
      );
    }

    const secret = resolveTotpSecret(doc.data());
    if (!secret) {
      return NextResponse.json(
        { error: "لم يتم إعداد المصادقة الثنائية" },
        { status: 400 }
      );
    }

    const isValid = verifyTotpConstantTime(secret, token);
    if (!isValid) {
      await recordOtpFailure(user.uid);
      return NextResponse.json(
        { error: "رمز التحقق غير صحيح" },
        { status: 400 }
      );
    }

    // Enable 2FA (and migrate any legacy plaintext seed to encrypted-at-rest).
    await getAdminDb().collection("admin2fa").doc(user.uid).update({
      enabled: true,
      enabledAt: Date.now(),
    });
    if (doc.data()?.secretEncrypted == null && typeof doc.data()?.secret === "string") {
      await upgradeLegacySecret(user.uid, secret);
    }
    await clearOtpFailures(user.uid);
    await logSecurityEvent("2fa_enabled", { uid: user.uid });

    const grantId = await createMfaGrant(user);
    const response = NextResponse.json({ success: true, enabled: true });
    setMfaGrantCookie(response, grantId);

    return response;
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
