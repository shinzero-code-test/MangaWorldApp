import { NextRequest, NextResponse } from "next/server";
import { v2 as cloudinary } from "cloudinary";

// Configure Cloudinary — API keys come from .env.local
cloudinary.config({
  cloud_name: process.env.CLOUDINARY_CLOUD_NAME,
  api_key: process.env.CLOUDINARY_API_KEY,
  api_secret: process.env.CLOUDINARY_API_SECRET,
});

export const dynamic = 'force-dynamic';

/**
 * POST /api/cloudinary/app-upload
 * 
 * Public upload endpoint for the Android app.
 * Accepts base64 images and uploads to Cloudinary.
 * No admin auth required — uses Cloudinary's upload preset for app uploads.
 * 
 * Request body:
 *   { "image": "data:image/jpeg;base64,...", "folder": "avatars" }
 * 
 * Response:
 *   { "url": "https://res.cloudinary.com/...", "publicId": "..." }
 */
export async function POST(request: NextRequest) {
  try {
    const body = await request.json();
    const { image, folder = "uploads" } = body;

    if (!image) {
      return NextResponse.json({ error: "image is required" }, { status: 400 });
    }

    // Validate image size (max 5MB base64)
    if (image.length > 7_000_000) {
      return NextResponse.json({ error: "Image too large (max 5MB)" }, { status: 400 });
    }

    // Upload to Cloudinary with unsigned upload (no auth needed)
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
  } catch (error: any) {
    console.error("Cloudinary app upload error:", error);
    return NextResponse.json({ error: error.message || "Upload failed" }, { status: 500 });
  }
}
