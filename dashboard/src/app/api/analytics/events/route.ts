import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminDb } from "@/lib/firebase-admin";

export const dynamic = "force-dynamic";

export async function GET() {
  try {
    await requireRole("viewer");
    const db = getAdminDb();

    // Read analytics events from Firestore analytics_events collection
    let events: any[] = [];
    try {
      const snap = await db.collection("analytics_events")
        .orderBy("createdAt", "desc")
        .limit(200)
        .get();
      events = snap.docs.map((doc) => {
        const d = doc.data();
        return {
          id: doc.id,
          name: d.name || "unknown",
          params: d.params || {},
          userId: d.userId || "",
          timestamp: d.createdAt || 0,
        };
      });
    } catch {
      // analytics_events collection may not exist
    }

    // Aggregate event counts by name
    const eventCounts: Record<string, number> = {};
    for (const event of events) {
      const name = event.name || "unknown";
      eventCounts[name] = (eventCounts[name] || 0) + 1;
    }

    const topEvents = Object.entries(eventCounts)
      .map(([name, count]) => ({ name, count }))
      .sort((a, b) => b.count - a.count)
      .slice(0, 20);

    return NextResponse.json({
      events: events.slice(0, 100),
      topEvents,
      totalEvents: events.length,
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
