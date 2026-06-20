import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("super-admin");

    // Performance monitoring data
    // In production, this would query Firebase Performance Monitoring API
    const traces = [
      { id: "t1", name: "app_startup", duration: 1250, status: "ok", timestamp: Date.now() - 3600000, device: "Samsung Galaxy S24", os: "Android 14" },
      { id: "t2", name: "manga_detail_load", duration: 890, status: "ok", timestamp: Date.now() - 7200000, device: "Pixel 8", os: "Android 14" },
      { id: "t3", name: "chapter_page_load", duration: 2100, status: "slow", timestamp: Date.now() - 1800000, device: "Xiaomi Redmi Note 11", os: "Android 12" },
      { id: "t4", name: "search_query", duration: 450, status: "ok", timestamp: Date.now() - 900000, device: "Samsung Galaxy A54", os: "Android 13" },
      { id: "t5", name: "image_download", duration: 3200, status: "slow", timestamp: Date.now() - 600000, device: "OnePlus 11", os: "Android 14" },
      { id: "t6", name: "firebase_sync", duration: 780, status: "ok", timestamp: Date.now() - 300000, device: "Samsung Galaxy S24", os: "Android 14" },
    ];

    const metrics = {
      appStartup: { avg: 1250, p50: 1100, p95: 2800, p99: 4500 },
      pageLoad: { avg: 890, p50: 750, p95: 2100, p99: 3800 },
      networkRequests: { avg: 450, p50: 350, p95: 1200, p99: 2500 },
      imageLoad: { avg: 2100, p50: 1800, p95: 4500, p99: 8000 },
    };

    const screenMetrics = [
      { screen: "HomeScreen", avgTime: 850, renders: 12500 },
      { screen: "MangaDetailScreen", avgTime: 1200, renders: 8900 },
      { screen: "ReaderScreen", avgTime: 650, renders: 45000 },
      { screen: "SearchScreen", avgTime: 450, renders: 15000 },
      { screen: "LibraryScreen", avgTime: 380, renders: 9200 },
    ];

    return NextResponse.json({ traces, metrics, screenMetrics });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
