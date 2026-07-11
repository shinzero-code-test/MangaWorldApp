import { createHash } from "crypto";

export function cloudinaryAssetId(publicId: string): string {
  return createHash("sha256").update(publicId).digest("hex");
}
