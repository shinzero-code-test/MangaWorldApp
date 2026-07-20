import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";

export const dynamic = "force-dynamic";

const CLOUDINARY_CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "mangaworld";
const CLOUDINARY_API_KEY = process.env.CLOUDINARY_API_KEY || "";
const CLOUDINARY_API_SECRET = process.env.CLOUDINARY_API_SECRET || "";

interface CloudinaryUsage {
  created_bytes: number;
  count: number;
}

interface CloudinaryUsageResponse {
  storage: { used: number; limit: number };
  bandwidth: { used: number; limit: number };
  resources: CloudinaryUsage;
}

async function fetchCloudinaryUsage(): Promise<CloudinaryUsageResponse> {
  const timestamp = Math.floor(Date.now() / 1000);
  const signature = require("crypto")
    .createHash("sha1")
    .update(`usage${timestamp}${CLOUDINARY_API_SECRET}`)
    .digest("hex");

  const url = `https://api.cloudinary.com/v1_1/${CLOUDINARY_CLOUD_NAME}/usage?timestamp=${timestamp}&api_key=${CLOUDINARY_API_KEY}&signature=${signature}`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Cloudinary API error: ${response.status}`);
  return response.json();
}

export async function GET() {
  try {
    await requireRole("super-admin");

    if (!CLOUDINARY_API_KEY || !CLOUDINARY_API_SECRET) {
      return NextResponse.json({
        totalBytes: 0,
        bucketName: CLOUDINARY_CLOUD_NAME,
        breakdown: [],
        error: "Cloudinary credentials not configured",
      });
    }

    const usage = await fetchCloudinaryUsage();

    // Cloudinary doesn't provide file-level breakdown in the usage API
    // Return total storage from usage data
    const totalBytes = usage.resources?.created_bytes ?? 0;

    return NextResponse.json({
      totalBytes,
      bucketName: CLOUDINARY_CLOUD_NAME,
      breakdown: [
        { id: "images", label: "الصور", bytes: totalBytes, fileCount: usage.resources?.count ?? 0 },
      ],
    });
  } catch (error: any) {
    console.error("Storage API error:", error);
    return NextResponse.json({
      totalBytes: 0,
      bucketName: CLOUDINARY_CLOUD_NAME,
      breakdown: [],
    });
  }
}
