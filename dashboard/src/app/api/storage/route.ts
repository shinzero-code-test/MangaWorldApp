import { createHash } from "crypto";
import { NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = "force-dynamic";

const CLOUDINARY_CLOUD_NAME = process.env.CLOUDINARY_CLOUD_NAME || "";
const CLOUDINARY_API_KEY = process.env.CLOUDINARY_API_KEY || "";
const CLOUDINARY_API_SECRET = process.env.CLOUDINARY_API_SECRET || "";

interface CloudinaryUsage {
  resources?: { created_bytes?: number; count?: number };
  storage?: { usage?: number; limit?: number };
  objects?: { usage?: number };
  bandwidth?: { usage?: number; limit?: number };
}

interface CloudinaryUsageResponse extends CloudinaryUsage {}

async function fetchCloudinaryUsage(): Promise<CloudinaryUsageResponse> {
  const timestamp = Math.floor(Date.now() / 1000);
  // Admin API request signing: SHA1Hex of the sorted "k=v" params (api_key
  // excluded) + api_secret. For usage the only signed param is timestamp.
  const signature = createHash("sha1")
    .update(`timestamp=${timestamp}${CLOUDINARY_API_SECRET}`)
    .digest("hex");

  const url = `https://api.cloudinary.com/v1_1/${CLOUDINARY_CLOUD_NAME}/usage?timestamp=${timestamp}&api_key=${CLOUDINARY_API_KEY}&signature=${signature}`;
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Cloudinary API error: ${response.status}`);
  return response.json();
}

export async function GET() {
  try {
    await requireRole("super-admin");

    if (!CLOUDINARY_CLOUD_NAME || !CLOUDINARY_API_KEY || !CLOUDINARY_API_SECRET) {
      return NextResponse.json({ error: "Cloudinary credentials not configured" }, { status: 500 });
    }

    const usage = await fetchCloudinaryUsage();

    // Shape-tolerant: the usage endpoint has returned different envelopes
    // across API versions ({resources:{created_bytes}} vs {storage:{usage}}).
    const num = (v: unknown): number => (typeof v === "number" && Number.isFinite(v) ? v : 0);
    const totalBytes = num(usage.resources?.created_bytes)
      || num(usage.storage?.usage)
      || num(usage.objects?.usage);

    return NextResponse.json({
      totalBytes,
      bucketName: CLOUDINARY_CLOUD_NAME,
      breakdown: [
        { id: "images", label: "الصور", bytes: totalBytes, fileCount: num(usage.resources?.count) },
      ],
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error, "تعذر قراءة إحصائيات التخزين");
    return NextResponse.json(body, { status });
  }
}
