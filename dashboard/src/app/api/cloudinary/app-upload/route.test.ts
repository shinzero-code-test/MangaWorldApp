import { beforeEach, describe, expect, it, vi } from "vitest";
import { createHash } from "crypto";
import { POST } from "./route";
import { v2 as cloudinary } from "cloudinary";
import { getAdminDb } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { req, mockAppAuthed, mockAppAnon, mockAppUnauthorized } from "@/app/api/__tests__/route-harness";

// (2) cloudinary/app-upload — the ONLY app image-upload path: app-token
// auth → uid-keyed rate limit → strict data-URI validation BEFORE decode
// (8M-char pre-cap, 5MB byte cap) → Cloudinary upload → ownership row in
// cloudinaryAssets (so app-delete can prove ownership later).
vi.mock("@/lib/app-auth", () => ({
  verifyAppIdToken: vi.fn(),
  rejectAnonymousUser: vi.fn(),
}));
vi.mock("@/lib/app-rate-limit", () => ({
  allowAppMutation: vi.fn(),
}));
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

const PNG_BYTES = Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a, 0x00, 0x01, 0x02, 0x03]);
const PNG_URI = `data:image/png;base64,${PNG_BYTES.toString("base64")}`;

const UPLOAD_RESULT = {
  secure_url: "https://res.cloudinary.com/demo/image/upload/app/uid-1/avatars/abc.png",
  public_id: "app/uid-1/avatars/abc",
  width: 800,
  height: 800,
};

function fakeDb() {
  const sets: Array<{ path: string; data: Record<string, unknown> }> = [];
  const db = {
    collection: (_name: string) => ({
      doc: (id: string) => ({
        set: async (data: Record<string, unknown>) => {
          sets.push({ path: `cloudinaryAssets/${id}`, data });
        },
      }),
    }),
  };
  return { db, sets };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
  vi.mocked(cloudinary.uploader.upload).mockResolvedValue(UPLOAD_RESULT as never);
});

describe("POST /api/cloudinary/app-upload", () => {
  it("uploads to the caller-scoped folder and records ownership", async () => {
    const { db, sets } = fakeDb();
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ image: PNG_URI, assetType: "avatar" }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { url: string; publicId: string; width: number; height: number };
    expect(body).toEqual({
      url: UPLOAD_RESULT.secure_url,
      publicId: "app/uid-1/avatars/abc",
      width: 800,
      height: 800,
    });

    expect(vi.mocked(cloudinary.uploader.upload)).toHaveBeenCalledWith(PNG_URI, {
      folder: "app/uid-1/avatars",
      public_id: expect.any(String),
      resource_type: "image",
      transformation: [{ width: 800, height: 800, crop: "limit" }, { quality: "auto:good" }],
    });
    // Rate limit is keyed on uid alone (X-Forwarded-For is client-controlled).
    expect(vi.mocked(allowAppMutation)).toHaveBeenCalledWith("upload:uid-1", 20, 60 * 60 * 1000);

    // Ownership index: doc id = sha256(public_id) — the id app-delete recomputes.
    const expectedId = createHash("sha256").update(UPLOAD_RESULT.public_id).digest("hex");
    expect(sets).toHaveLength(1);
    expect(sets[0].path).toBe(`cloudinaryAssets/${expectedId}`);
    expect(sets[0].data).toMatchObject({
      uid: "uid-1",
      publicId: "app/uid-1/avatars/abc",
      assetType: "avatar",
      url: UPLOAD_RESULT.secure_url,
    });
  });

  it("maps asset types to their folders", async () => {
    vi.mocked(getAdminDb).mockReturnValue(fakeDb().db as never);
    for (const [type, folder] of [["banner", "banners"], ["list-cover", "list-covers"]] as const) {
      vi.clearAllMocks();
      mockAppAuthed();
      vi.mocked(allowAppMutation).mockResolvedValue(true);
      vi.mocked(cloudinary.uploader.upload).mockResolvedValue(UPLOAD_RESULT as never);
      vi.mocked(getAdminDb).mockReturnValue(fakeDb().db as never);
      const res = await POST(req({ image: PNG_URI, assetType: type }));
      expect(res.status).toBe(200);
      expect(
        vi.mocked(cloudinary.uploader.upload).mock.calls[0][1]
      ).toMatchObject({ folder: `app/uid-1/${folder}` });
    }
  });

  it("rejects missing image or unknown asset type with 400", async () => {
    const { db } = fakeDb();
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({ assetType: "avatar" }))).status).toBe(400);
    expect((await POST(req({ image: PNG_URI, assetType: "nope" }))).status).toBe(400);
    expect((await POST(req({ image: 42, assetType: "avatar" }))).status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("pre-caps the raw string before base64 decoding with 400", async () => {
    const { db } = fakeDb();
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const huge = `data:image/png;base64,${"A".repeat(8_000_100)}`;
    const res = await POST(req({ image: huge, assetType: "avatar" }));
    expect(res.status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("rejects unsupported formats with 400", async () => {
    const { db } = fakeDb();
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({ image: "data:image/gif;base64,AAAA", assetType: "avatar" }))).status).toBe(400);
    expect((await POST(req({ image: "https://x.com/a.png", assetType: "avatar" }))).status).toBe(400);
    expect((await POST(req({ image: "data:image/png;base64,", assetType: "avatar" }))).status).toBe(400);
  });

  it("rejects bodies beyond the 5MB byte cap with 400", async () => {
    const { db } = fakeDb();
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const big = Buffer.alloc(5 * 1024 * 1024 + 1, 7);
    const uri = `data:image/png;base64,${big.toString("base64")}`;
    const res = await POST(req({ image: uri, assetType: "avatar" }));
    expect(res.status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.upload)).not.toHaveBeenCalled();
  });

  it("returns 401 without a token", async () => {
    mockAppUnauthorized();
    const res = await POST(req({ image: PNG_URI, assetType: "avatar" }));
    expect(res.status).toBe(401);
  });

  it("returns 403 for anonymous users", async () => {
    mockAppAnon();
    const res = await POST(req({ image: PNG_URI, assetType: "avatar" }));
    expect(res.status).toBe(403);
  });

  it("rate-limits with 429", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    const res = await POST(req({ image: PNG_URI, assetType: "avatar" }));
    expect(res.status).toBe(429);
  });

  it("surfaces upload failures as 500 via the generic error", async () => {
    const { db } = fakeDb();
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(cloudinary.uploader.upload).mockRejectedValue(new Error("cloudinary down"));
    const res = await POST(req({ image: PNG_URI, assetType: "avatar" }));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("cloudinary down");
  });
});
