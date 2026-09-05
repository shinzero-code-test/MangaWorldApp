import { NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    const user = await getCurrentUser({ requireMfa: false });
    // Viewers are not allowed to access the dashboard
    if (user.role === "viewer") {
      return NextResponse.json(
        { error: "ليس لديك صلاحية الوصول إلى لوحة التحكم", role: user.role },
        { status: 403 }
      );
    }
    return NextResponse.json(user);
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
