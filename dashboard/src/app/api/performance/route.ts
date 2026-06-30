import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");

    // Return empty data since performance is not directly queryable via admin sdk
    return NextResponse.json({
      summary: {
        avgStartup:   0,
        avgNetwork:   0,
        avgRender:    0,
        totalTraces:  0,
      },
      traces: [],
      screenMetrics: [],
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
