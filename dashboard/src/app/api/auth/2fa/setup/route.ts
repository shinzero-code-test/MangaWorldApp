import { NextRequest, NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { authenticator } from "otplib";
import QRCode from "qrcode";
import {
  encryptSecret,
  genericErrorResponse,
  logSecurityEvent,
  resolveTotpSecret,
} from "@/lib/security";

export const dynamic = "force-dynamic";

// Sessions must be fresher than this to start (or restart) 2FA enrollment, so a
// stolen long-lived session cannot silently enroll an attacker's own authenticator.
const FRESH_AUTH_WINDOW_MS = 15 * 60 * 1000;

/**
 * POST /api/auth/2fa/setup — generate or return the pending TOTP secret + QR.
 * Deliberately a POST: it generates/rotates state, so SameSite=Lax must not let
 * top-level cross-site GET navigations trigger it.
 */
export async function POST() {
  try {
    const user = await getCurrentUser({ requireMfa: false });

    if (!user.authTime || Date.now() - user.authTime > FRESH_AUTH_WINDOW_MS) {
      await logSecurityEvent("2fa_setup_stale_session", { uid: user.uid });
      return NextResponse.json(
        { error: "انتهت مدة الجلسة الحديثة. سجّل الخروج ثم أعد تسجيل الدخول لإعداد المصادقة الثنائية." },
        { status: 403 }
      );
    }

    // Check if already set up
    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    if (doc.exists && doc.data()?.enabled) {
      return NextResponse.json({ alreadyEnabled: true });
    }

    // Generate or reuse pending secret
    let secret: string;
    if (doc.exists) {
      const existing = resolveTotpSecret(doc.data());
      if (existing) {
        secret = existing;
      } else {
        // RFC 6238 §5.1: ≥128 bits, ideally 160 — 20 bytes via otplib.
        secret = authenticator.generateSecret(20);
        await getAdminDb()
          .collection("admin2fa")
          .doc(user.uid)
          .set({ secret: null, secretEncrypted: encryptSecret(secret), enabled: false, createdAt: Date.now() });
      }
    } else {
      secret = authenticator.generateSecret(20);
      await getAdminDb()
        .collection("admin2fa")
        .doc(user.uid)
        .set({
          secret: null,
          secretEncrypted: encryptSecret(secret), // never store the raw seed at rest
          enabled: false,
          createdAt: Date.now(),
        });
      await logSecurityEvent("2fa_pending_secret_issued", { uid: user.uid });
    }

    const otpauth = authenticator.keyuri(
      user.email,
      "MangaWorld Admin",
      secret
    );
    const qrDataUrl = await QRCode.toDataURL(otpauth, {
      width: 280,
      margin: 2,
      color: { dark: "#ffffff", light: "#00000000" },
    });

    // The plaintext seed is returned exactly once here and only ever stored encrypted.
    return NextResponse.json({ secret, qrDataUrl });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status: status === 500 ? 401 : status });
  }
}

// Keep a hard rejection for the legacy side-effecting GET shape.
export async function GET() {
  return NextResponse.json(
    { error: "Method not allowed — use POST" },
    { status: 405 }
  );
}
