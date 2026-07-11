import { NextRequest, NextResponse } from "next/server";
import { createMfaGrant, getCurrentUser, setMfaGrantCookie } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { authenticator } from "otplib";

export const dynamic = "force-dynamic";

// POST: Verify OTP during initial setup to enable 2FA
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser({ requireMfa: false });
    const { token } = await request.json();
    if (!token) {
      return NextResponse.json({ error: "رمز التحقق مطلوب" }, { status: 400 });
    }

    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    if (!doc.exists || !doc.data()?.secret) {
      return NextResponse.json(
        { error: "لم يتم إعداد المصادقة الثنائية" },
        { status: 400 }
      );
    }

    const secret = doc.data()!.secret;
    const isValid = authenticator.verify({ token, secret });

    if (!isValid) {
      return NextResponse.json(
        { error: "رمز التحقق غير صحيح" },
        { status: 400 }
      );
    }

    // Enable 2FA
    await getAdminDb().collection("admin2fa").doc(user.uid).update({
      enabled: true,
      enabledAt: Date.now(),
    });

    const grantId = await createMfaGrant(user);
    const response = NextResponse.json({ success: true, enabled: true });
    setMfaGrantCookie(response, grantId);

    return response;
  } catch (error: any) {
    return NextResponse.json(
      { error: error.message || "خطأ" },
      { status: 500 }
    );
  }
}
