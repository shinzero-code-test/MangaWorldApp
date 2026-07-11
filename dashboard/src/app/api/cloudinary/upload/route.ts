import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { v2 as cloudinary } from "cloudinary";

// Configure Cloudinary — API keys come from .env.local
cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

export const dynamic = 'force-dynamic';

/**
 * POST /api/cloudinary/upload
 * 
 * Upload an image to Cloudinary via the dashboard backend.
 * The app sends a base64 image or a URL, and this endpoint uploads it.
 * 
 * Request body:
 *   { "image": "data:image/jpeg;base64,..." or "https://...", "folder": "avatars" }
 * 
 * Response:
 *   { "url": "https://res.cloudinary.com/...", "publicId": "..." }
 */
export async function POST(request: NextRequest) {
  try {
    await requireRole("super-admin");

    const body = await request.json();
    const { image, folder = "uploads" } = body;

    if (!image) {
      return NextResponse.json({ error: "image is required" }, { status: 400 });
    }

    // Upload to Cloudinary
    const result = await cloudinary.uploader.upload(image, {
      folder,
      resource_type: "image",
      transformation: [
        { width: 800, height: 800, crop: "limit" },
        { quality: "auto:good" },
      ],
    });

    return NextResponse.json({
      url: result.secure_url,
      publicId: result.public_id,
      width: result.width,
      height: result.height,
    });
  } catch (error) {
    console.error("Cloudinary upload error:", error);
    return NextResponse.json({ error: "Upload failed" }, { status: 500 });
  }
}

/**
 * DELETE /api/cloudinary/upload?publicId=...
 * 
 * Delete an image from Cloudinary.
 */
export async function DELETE(request: NextRequest) {
  try {
    await requireRole("super-admin");

    const { searchParams } = new URL(request.url);
    const publicId = searchParams.get("publicId");

    if (!publicId) {
      return NextResponse.json({ error: "publicId is required" }, { status: 400 });
    }

    const result = await cloudinary.uploader.destroy(publicId);
    return NextResponse.json({ result });
  } catch (error) {
    console.error("Cloudinary delete error:", error);
    return NextResponse.json({ error: "Delete failed" }, { status: 500 });
  }
}
