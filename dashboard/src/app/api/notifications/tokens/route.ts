import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const devicesSnap = await getAdminDb().collectionGroup("devices").limit(200).get();
    const tokens = devicesSnap.docs.map((doc) => ({
      token: doc.data().token,
      platform: doc.data().platform,
      updatedAt: doc.data().updatedAt,
    })).filter((d) => d.token);
    return NextResponse.json({ tokens });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
