import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

/**
 * FCM tokens are credentials: the bulk export masks them to a recognisable
 * suffix so operators can match devices without exfiltrating usable tokens.
 */
function maskToken(token: string): string {
  if (typeof token !== "string" || token.length <= 12) return "•••";
  return `••••••••${token.slice(-8)}`;
}

export async function GET() {
  try {
    await requireRole("super-admin");
    const devicesSnap = await getAdminDb().collectionGroup("devices").limit(200).get();
    const tokens = devicesSnap.docs.map((doc) => ({
      tokenMasked: maskToken(doc.data().token),
      tokenSuffix: typeof doc.data().token === "string" ? doc.data().token.slice(-8) : "",
      platform: doc.data().platform,
      updatedAt: doc.data().updatedAt,
    })).filter((d) => d.tokenSuffix);
    return NextResponse.json({ tokens });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
