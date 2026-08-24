import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("super-admin");
    const { uid } = await params;
    const body = await request.json();
    // Strict boolean — non-boolean input previously threw inside updateUser (→ 500).
    if (typeof body?.banned !== "boolean") {
      return NextResponse.json({ error: "banned must be a boolean" }, { status: 400 });
    }
    await getAdminAuth().updateUser(uid, { disabled: body.banned });
    return NextResponse.json({ success: true, banned: body.banned });
  } catch (error: any) {
    const message = error instanceof Error ? error.message : "";
    return NextResponse.json({ error: message === "Forbidden" ? "Forbidden" : "فشل تحديث حالة الحظر" }, { status: message === "Forbidden" ? 403 : 500 });
  }
}
