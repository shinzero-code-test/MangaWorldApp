import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminStorage } from "@/lib/firebase-admin";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");

    try {
      const bucket = getAdminStorage().bucket();
      const [files] = await bucket.getFiles({ maxResults: 1000 });

      let totalBytes = 0;
      const breakdown: Record<string, { bytes: number; count: number }> = {
        images:    { bytes: 0, count: 0 },
        documents: { bytes: 0, count: 0 },
        cache:     { bytes: 0, count: 0 },
        other:     { bytes: 0, count: 0 },
      };

      for (const file of files) {
        const size = parseInt(file.metadata.size as string ?? "0");
        totalBytes += size;
        const name = file.name.toLowerCase();
        if (name.match(/\.(jpg|jpeg|png|gif|webp|avif|svg)$/)) {
          breakdown.images.bytes += size;
          breakdown.images.count++;
        } else if (name.match(/\.(pdf|doc|docx|txt)$/)) {
          breakdown.documents.bytes += size;
          breakdown.documents.count++;
        } else if (name.includes("cache/") || name.includes("temp/")) {
          breakdown.cache.bytes += size;
          breakdown.cache.count++;
        } else {
          breakdown.other.bytes += size;
          breakdown.other.count++;
        }
      }

      return NextResponse.json({
        totalBytes,
        bucketName: bucket.name,
        breakdown: Object.entries(breakdown).map(([id, v]) => ({
          id,
          label:     id === "images" ? "الصور" : id === "documents" ? "المستندات" : id === "cache" ? "الذاكرة المؤقتة" : "أخرى",
          bytes:     v.bytes,
          fileCount: v.count,
        })),
      });
    } catch {
      // Return empty data if storage isn't configured
      return NextResponse.json({
        totalBytes:  0,
        bucketName:  process.env.FIREBASE_STORAGE_BUCKET ?? "",
        breakdown: [],
      });
    }
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
