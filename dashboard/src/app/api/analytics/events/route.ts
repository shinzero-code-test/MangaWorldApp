import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAccessToken } from "@/lib/firebase-admin";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

const PROJECT_ID = process.env.FIREBASE_PROJECT_ID ?? "";
const GA4_PROPERTY_ID = process.env.GA4_PROPERTY_ID ?? "";

export async function GET() {
  try {
    // Moderator minimum: "viewer" rank would admit the viewer role itself (M-4).
    await requireRole("moderator");

    if (!GA4_PROPERTY_ID) {
      return NextResponse.json({
        events: [],
        topEvents: [],
        totalEvents: 0,
        note: "GA4_PROPERTY_ID not configured",
      });
    }

    const token = await getAccessToken();

    // Fetch recent events from GA4 Data API
    let events: any[] = [];
    try {
      const thirtyDaysAgo = new Date();
      thirtyDaysAgo.setDate(thirtyDaysAgo.getDate() - 30);
      const response = await fetch(
        `https://analyticsdata.googleapis.com/v1beta/properties/${GA4_PROPERTY_ID}:runReport`,
        {
          method: "POST",
          headers: {
            Authorization: `Bearer ${token}`,
            "Content-Type": "application/json",
          },
          body: JSON.stringify({
            dateRanges: [{ startDate: thirtyDaysAgo.toISOString().split("T")[0], endDate: "today" }],
            dimensions: [{ name: "eventName" }],
            metrics: [{ name: "eventCount" }],
            orderBys: [{ metric: { metricName: "eventCount" }, desc: true }],
            limit: 50,
          }),
        }
      );

      if (response.ok) {
        const data = await response.json();
        events = (data.rows || []).map((row: any) => ({
          id: row.dimensionValues?.[0]?.value || "unknown",
          name: row.dimensionValues?.[0]?.value || "unknown",
          params: {},
          userId: "",
          timestamp: Date.now(),
          count: Number(row.metricValues?.[0]?.value) || 0,
        }));
      }
    } catch {
      // GA4 API may not be available
    }

    const topEvents = events.map((e) => ({ name: e.name, count: e.count })).slice(0, 20);
    const totalEvents = events.reduce((sum, e) => sum + (e.count || 0), 0);

    return NextResponse.json({
      events: events.slice(0, 100),
      topEvents,
      totalEvents,
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}
