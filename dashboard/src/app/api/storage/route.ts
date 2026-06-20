import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { adminDb } from "@/lib/firebase-admin";

export async function GET(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { searchParams } = new URL(request.url);
    const path = searchParams.get("path") || "";

    // Get storage stats from Firestore
    const [profilesSnap, historySnap, favsSnap] = await Promise.all([
      adminDb.collection("publicProfiles").count().get(),
      adminDb.collectionGroup("readingHistory").count().get(),
      adminDb.collectionGroup("favorites").count().get(),
    ]);

    // Calculate estimated storage
    const userCount = profilesSnap.data().count;
    const historyCount = historySnap.data().count;
    const favsCount = favsSnap.data().count;

    const estimatedStorage = {
      profiles: userCount * 0.5, // ~0.5KB per profile
      history: historyCount * 0.2, // ~0.2KB per history entry
      favorites: favsCount * 0.3, // ~0.3KB per favorite
      total: 0,
    };
    estimatedStorage.total = estimatedStorage.profiles + estimatedStorage.history + estimatedStorage.favorites;

    // Get download directories from app filesystem
    const downloads = [
      { name: "downloads", type: "directory", children: [] },
    ];

    return NextResponse.json({
      stats: {
        ...estimatedStorage,
        unit: "KB",
        profileCount: userCount,
        historyCount,
        favsCount,
      },
      downloads,
      bucket: "mangaworld-live-260519.appspot.com",
    });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
