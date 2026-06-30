import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("viewer");
    return NextResponse.json({ events: [], note: "Firebase Analytics Data API requires additional setup" });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
