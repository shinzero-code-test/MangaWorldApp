import { NextRequest, NextResponse } from "next/server";
import { v2 as cloudinary } from "cloudinary";

cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

export const dynamic = "force-dynamic";

/**
 * POST /api/cloudinary/app-delete
 *
 * Public delete endpoint for the Android app.
 * Accepts a Cloudinary publicId and deletes the image.
 *
 * Request body:
 *   { "publicId": "avatars/abc123" }
 *
 * Response:
 *   { "result": "ok" } or { "result": "not_found" }
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { publicId } = body;

    if (!publicId) {
      return NextResponse.json({ error: "publicId is required" }, { status: 400 });
    }

    const result = await cloudinary.uploader.destroy(publicId);
    return NextResponse.json({ result: result.result });
  } catch (error: any) {
    console.error("Cloudinary app delete error:", error);
    return NextResponse.json({ error: error.message || "Delete failed" }, { status: 500 });
  }
}
