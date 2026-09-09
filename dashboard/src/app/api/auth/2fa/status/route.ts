import { NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

// GET: Check 2FA status for current user
export async function GET() {
  try {
    const user = await getCurrentUser({ requireMfa: false });

    const doc = await getAdminDb().collection("admin2fa").doc(user.uid).get();
    const enabled = doc.exists && doc.data()?.enabled === true;

    const verified = user.mfaVerified;
    const hashes = doc.data()?.backupCodeHashes;
    const backupCodesRemaining = enabled && Array.isArray(hashes) ? hashes.length : 0;

    return NextResponse.json({
      enabled,
      verified,
      needsSetup: !enabled,
      needsValidation: enabled && !verified,
      backupCodesRemaining,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
