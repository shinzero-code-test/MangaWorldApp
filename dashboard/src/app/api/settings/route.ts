import { NextRequest, NextResponse } from "next/server";
import { getAdminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const doc = await getAdminDb().collection("app_config").doc("defaults").get();
    return NextResponse.json({ settings: doc.data() || {} });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { settings } = await request.json();
    await getAdminDb().collection("app_config").doc("defaults").set({ ...settings, updatedAt: Date.now() }, { merge: true });
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
