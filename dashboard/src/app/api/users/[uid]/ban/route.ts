import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("super-admin");
    const { uid } = await params;
    const { banned } = await request.json();
    await getAdminAuth().updateUser(uid, { disabled: banned });
    return NextResponse.json({ success: true, banned });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: error.message === "Forbidden" ? 403 : 500 });
  }
}
