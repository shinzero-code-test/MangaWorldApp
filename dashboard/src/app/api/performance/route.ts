import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    await requireRole("super-admin");
    const db = getAdminDb();

    // Read performance traces from Firestore performance_traces collection
    // (populated by the app's FirebaseTelemetry.traceSuspend/trace calls)
    let traces: any[] = [];
    try {
      const snap = await db.collection("performance_traces")
        .orderBy("startedAt", "desc")
        .limit(100)
        .get();
      traces = snap.docs.map((doc) => {
        const d = doc.data();
        return {
          id: doc.id,
          name: d.name || "unknown",
          duration: d.duration || 0,
          networkType: d.networkType || "unknown",
          metrics: d.metrics || {},
          startedAt: d.startedAt || 0,
        };
      });
    } catch {
      // performance_traces collection may not exist
    }

    // Calculate summary stats from traces
    const totalTraces = traces.length;
    const avgDuration = totalTraces > 0
      ? Math.round(traces.reduce((sum, t) => sum + (t.duration || 0), 0) / totalTraces)
      : 0;

    // Separate by trace type
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
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
