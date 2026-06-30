import { NextRequest, NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { authenticator } from "otplib";

export const dynamic = "force-dynamic";

// POST: Validate OTP on login (after password auth, before dashboard access)
export async function POST(request: NextRequest) {
  try {
    const user = await getCurrentUser();
    const { token } = await request.json();
    if (!token) {
      return NextResponse.json({ error: "رمز التحقق مطلوب" }, { status: 400 });
    }

    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    if (!doc.exists || !doc.data()?.enabled) {
      return NextResponse.json(
        { error: "المصادقة الثنائية غير مفعلة" },
        { status: 400 }
      );
    }

    const secret = doc.data()!.secret;
    const isValid = authenticator.verify({ token, secret });

    if (!isValid) {
      return NextResponse.json(
        { error: "رمز التحقق غير صحيح. تأكد من الرمز وحاول مرة أخرى" },
        { status: 400 }
      );
    }

    // Set verified cookie
    const response = NextResponse.json({ success: true });
    response.cookies.set("2fa_verified", "true", {
      httpOnly: true,
      secure: process.env.NODE_ENV === "production",
      sameSite: "lax",
      maxAge: 60 * 60 * 24 * 7,
      path: "/",
    });

    return response;
  } catch (error: any) {
    return NextResponse.json(
      { error: error.message || "خطأ" },
      { status: 500 }
    );
  }
}
