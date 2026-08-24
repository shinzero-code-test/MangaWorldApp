import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAccessToken } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

const PROJECT_ID = process.env.FIREBASE_PROJECT_ID ?? "";

export async function GET() {
  try {
    await requireRole("super-admin");

    const token = await getAccessToken();
    const baseUrl = `https://firebaseperformance.googleapis.com/v1beta1/projects/${PROJECT_ID}`;

    // Fetch performance traces from Performance Monitoring API
    let traces: any[] = [];
    try {
      // List traces (custom traces from the app)
      const response = await fetch(
        `${baseUrl}/traces?pageSize=100&orderBy=startTime%20desc`,
        { headers: { Authorization: `Bearer ${token}` } }
      );
      if (response.ok) {
        const data = await response.json();
        traces = (data.traces || []).map((trace: any) => ({
          name: trace.name || "unknown",
          duration: trace.durations
            ? Object.values(trace.durations).reduce((a: number, b: any) => a + (Number(b) || 0), 0)
            : 0,
          networkType: trace.attributes?.network_type || "unknown",
          metrics: trace.metrics || {},
          startedAt: trace.startTime ? new Date(trace.startTime).getTime() : 0,
        }));
      }
    } catch {
      // Performance API may not be available
    }

    const totalTraces = traces.length;
    const startupTraces = traces.filter((t) => t.name?.includes("startup") || t.name?.includes("app_start"));
    const networkTraces = traces.filter((t) => t.name?.includes("network") || t.name?.includes("http"));
    const renderTraces = traces.filter((t) => t.name?.includes("render") || t.name?.includes("draw"));

    const avgStartup = startupTraces.length > 0
      ? Math.round(startupTraces.reduce((sum, t) => sum + (t.duration || 0), 0) / startupTraces.length)
      : 0;
    const avgNetwork = networkTraces.length > 0
      ? Math.round(networkTraces.reduce((sum, t) => sum + (t.duration || 0), 0) / networkTraces.length)
      : 0;
    const avgRender = renderTraces.length > 0
      ? Math.round(renderTraces.reduce((sum, t) => sum + (t.duration || 0), 0) / renderTraces.length)
      : 0;

    return NextResponse.json({
      summary: { avgStartup, avgNetwork, avgRender, totalTraces },
      traces: traces.slice(0, 20),
      screenMetrics: [],
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
