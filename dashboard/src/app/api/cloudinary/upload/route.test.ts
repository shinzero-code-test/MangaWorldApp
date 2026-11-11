import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST, DELETE } from "./route";
import { v2 as cloudinary } from "cloudinary";
import { consumeRateLimit } from "@/lib/security";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  req,
  reqUrl,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/cloudinary/upload (the ADMIN upload path; the app
// uses /api/cloudinary/app-upload instead).
//
// Pinned contracts:
//  - super-admin gate on both verbs (401/403)
//  - the per-admin rate limit (429)
//  - data-URI inputs ONLY — a remote URL is refused, so Cloudinary cannot
//    be used as an SSRF-by-proxy
//  - only jpeg/png/webp/gif data URIs are accepted
//  - the folder must be a bounded relative path (no traversal)
//  - DELETE validates the publicId and keeps the tracking row in sync
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return { ...actual, consumeRateLimit: vi.fn().mockResolvedValue({ allowed: true }) };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));
vi.mock("cloudinary", () => ({
  v2: {
    config: vi.fn(),
    uploader: {
      upload: vi.fn(),
      destroy: vi.fn(),
    },
  },
}));

const PNG_DATA_URI = "data:image/png;base64,iVBORw0KGgo=";

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(cloudinary.uploader.upload).mockResolvedValue({
    secure_url: "https://res.cloudinary.com/test-cloud/image/upload/avatars/a.png",
    public_id: "avatars/a",
    width: 800,
    height: 600,
  } as never);
  vi.mocked(cloudinary.uploader.destroy).mockResolvedValue({ result: "ok" });
});

describe("POST /api/cloudinary/upload", () => {
  it("uploads a data URI and returns the public URL", async () => {
    const res = await POST(req({ image: PNG_DATA_URI, folder: "avatars" }));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.publicId).toBe("avatars/a");
    expect(body.url).toContain("https://res.cloudinary.com/");
    expect(vi.mocked(cloudinary.uploader.upload)).toHaveBeenCalledWith(
      PNG_DATA_URI,
      expect.objectContaining({ folder: "avatars", resource_type: "image" }),
    );
  });

  it("refuses a remote URL (no SSRF-by-proxy through Cloudinary)", async () => {
    const res = await POST(req({ image: "https://evil.example/x.png" }));
    expect(res.status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("refuses a disallowed mime type and a missing image", async () => {
    expect((await POST(req({ image: "data:image/svg+xml;base64,PHN2Zz48L3N2Zz4=" }))).status).toBe(400);
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ image: "" }))).status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("refuses a folder with path traversal", async () => {
    expect((await POST(req({ image: PNG_DATA_URI, folder: "../secrets" }))).status).toBe(400);
    expect((await POST(req({ image: PNG_DATA_URI, folder: "a//b" }))).status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("returns 429 when the admin is rate limited", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    expect((await POST(req({ image: PNG_DATA_URI }))).status).toBe(429);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await POST(req({ image: PNG_DATA_URI }))).status).toBe(401);
    mockRoleForbidden();
    expect((await POST(req({ image: PNG_DATA_URI }))).status).toBe(403);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });
});

describe("DELETE /api/cloudinary/upload", () => {
  it("destroys the asset and drops the tracking row", async () => {
    const res = await DELETE(reqUrl("http://localhost/api/cloudinary/upload?publicId=avatars/a"));
    expect(res.status).toBe(200);
    expect(vi.mocked(cloudinary.uploader.destroy)).toHaveBeenCalledWith("avatars/a");
    // The tracking row is deleted so the data browser stops referencing
    // bytes that no longer exist.
    expect(fake.writes.some((w) => w.op === "delete" && w.path === "cloudinaryAssets")).toBe(true);
  });

  it("rejects a malformed publicId with 400", async () => {
    expect((await DELETE(reqUrl("http://localhost/api/cloudinary/upload"))).status).toBe(400);
    expect((await DELETE(reqUrl("http://localhost/api/cloudinary/upload?publicId=bad%20id"))).status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.destroy)).not.toHaveBeenCalled();
  });

  it("returns 401/403 without the super-admin role", async () => {
    mockRoleUnauthorized();
    expect((await DELETE(reqUrl("http://localhost/api/cloudinary/upload?publicId=avatars/a"))).status).toBe(401);
    mockRoleForbidden();
    expect((await DELETE(reqUrl("http://localhost/api/cloudinary/upload?publicId=avatars/a"))).status).toBe(403);
    expect(vi.mocked(cloudinary.uploader.destroy)).not.toHaveBeenCalled();
  });
});
