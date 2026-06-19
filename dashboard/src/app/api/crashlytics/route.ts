import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("super-admin");
    return NextResponse.json({ issues: [], note: "Crashlytics API requires Firebase Admin setup" });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
