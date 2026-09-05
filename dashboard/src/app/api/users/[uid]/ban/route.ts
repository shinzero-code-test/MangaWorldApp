import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    await requireRole("super-admin");
    const { uid } = await params;
    if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
      return NextResponse.json({ error: "معرف المستخدم غير صالح" }, { status: 400 });
    }
    const body = await request.json();
    // Strict boolean — non-boolean input previously threw inside updateUser (→ 500).
    if (typeof body?.banned !== "boolean") {
      return NextResponse.json({ error: "banned must be a boolean" }, { status: 400 });
    }
    await getAdminAuth().updateUser(uid, { disabled: body.banned });
    return NextResponse.json({ success: true, banned: body.banned });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
