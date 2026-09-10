import { describe, expect, it } from "vitest";
import { cloudinaryAssetId } from "./cloudinary-assets";

// Pure SHA-256 public-ID derivation — no network, no env access.

describe("cloudinaryAssetId", () => {
  it("matches known SHA-256 vectors", () => {
    expect(cloudinaryAssetId("")).toBe(
      "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
    );
    expect(cloudinaryAssetId("test")).toBe(
      "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
    );
  });

  it("returns lowercase 64-char hex", () => {
    for (const id of ["a", "manga/cover/1", "مجلد/صورة"]) {
      expect(cloudinaryAssetId(id)).toMatch(/^[0-9a-f]{64}$/);
    }
  });

  it("is deterministic and collision-sensitive", () => {
    expect(cloudinaryAssetId("manga/cover/1")).toBe(cloudinaryAssetId("manga/cover/1"));
    expect(cloudinaryAssetId("manga/cover/1")).not.toBe(cloudinaryAssetId("manga/cover/2"));
    expect(cloudinaryAssetId("Cover")).not.toBe(cloudinaryAssetId("cover"));
    expect(cloudinaryAssetId("a b")).not.toBe(cloudinaryAssetId("ab"));
  });

  it("handles unicode and long inputs", () => {
    expect(cloudinaryAssetId("مانجا/غلاف")).toMatch(/^[0-9a-f]{64}$/);
    expect(cloudinaryAssetId("x".repeat(10_000))).toMatch(/^[0-9a-f]{64}$/);
    expect(cloudinaryAssetId("x".repeat(10_000))).not.toBe(cloudinaryAssetId("x".repeat(9999)));
  });
});
