import { NextResponse } from "next/server";
import { getCurrentUser } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    const user = await getCurrentUser({ requireMfa: false });
    return NextResponse.json(user);
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 401 });
  }
}
