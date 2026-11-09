import { NextRequest, NextResponse } from "next/server";
import { getAdminAuth } from "@/lib/firebase-admin";
import { requireRole, wouldStrandLastSuperAdmin } from "@/lib/auth";
import { genericErrorResponse, logSecurityEvent } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function POST(request: NextRequest, { params }: { params: Promise<{ uid: string }> }) {
  try {
    const admin = await requireRole("super-admin");
    const { uid } = await params;
    if (typeof uid !== "string" || uid.length < 1 || uid.length > 128) {
      return NextResponse.json({ error: "معرف المستخدم غير صالح" }, { status: 400 });
    }
    const body = await request.json();
    // Strict boolean — non-boolean input previously threw inside updateUser (→ 500).
    if (typeof body?.banned !== "boolean") {
      return NextResponse.json({ error: "banned must be a boolean" }, { status: 400 });
    }
    // U-29: self-ban guard (sibling parity with users PATCH + users/[uid] PATCH).
    if (uid === admin.uid && body.banned === true) {
      return NextResponse.json({ error: "لا يمكنك حظر حسابك بنفسك" }, { status: 400 });
    }
    // U-29: never strand the fleet with zero super-admins (sibling parity).
    if (body.banned === true && (await wouldStrandLastSuperAdmin(uid, undefined, true))) {
      return NextResponse.json({ error: "لا يمكن حظر آخر مدير عام" }, { status: 409 });
    }
    await getAdminAuth().updateUser(uid, { disabled: body.banned });
    if (body.banned === true) {
      // A banned account must not keep usable sessions (sibling parity).
      await getAdminAuth().revokeRefreshTokens(uid).catch(() => {});
    }
    // U-29: audit every ban/unban (sibling event name for log continuity).
    await logSecurityEvent("account_disable_toggle", { by: admin.uid, target: uid, disabled: body.banned });
    return NextResponse.json({ success: true, banned: body.banned });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
