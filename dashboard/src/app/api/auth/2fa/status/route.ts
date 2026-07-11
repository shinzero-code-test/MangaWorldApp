import { NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

// GET: Check 2FA status for current user
export async function GET() {
  try {
    const user = await getCurrentUser({ requireMfa: false });

    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    const enabled = doc.exists && doc.data()?.enabled === true;

    const verified = user.mfaVerified;

    return NextResponse.json({
      enabled,
      verified,
      needsSetup: !enabled,
      needsValidation: enabled && !verified,
    });
  } catch (error: any) {
    return NextResponse.json(
      { error: error.message || "خطأ" },
      { status: 401 }
    );
  }
}
