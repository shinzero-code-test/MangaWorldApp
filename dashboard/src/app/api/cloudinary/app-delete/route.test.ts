import { beforeEach, describe, expect, it, vi } from "vitest";
import { createHash } from "crypto";
import { POST } from "./route";
import { v2 as cloudinary } from "cloudinary";
import { getAdminDb } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { req, mockAppAuthed, mockAppAnon, mockAppUnauthorized } from "@/app/api/__tests__/route-harness";

// (2) cloudinary/app-delete — ownership proof before destroy: tracked row
// (uid + publicId match) OR prefix-scoped orphan (app/<uid>/...). Anything
// else is 404, so one user can never delete another's assets.
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

function assetId(publicId: string): string {
  return createHash("sha256").update(publicId).digest("hex");
}

/**
 * Fake asset table keyed by doc id. Doc data: { uid, publicId, ... }.
 */
function fakeDb(assets: Record<string, Record<string, unknown>>) {
  const deletes: string[] = [];
  const db = {
    collection: (_name: string) => ({
      doc: (id: string) => ({
        get: async () => ({
          exists: id in assets,
          data: () => assets[id],
        }),
        delete: async () => {
          deletes.push(id);
        },
      }),
    }),
  };
  return { db, deletes };
}

beforeEach(() => {
  vi.clearAllMocks();
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
  vi.mocked(cloudinary.uploader.destroy).mockResolvedValue({ result: "ok" } as never);
});

const PUBLIC_ID = "app/uid-1/avatars/abc";

describe("POST /api/cloudinary/app-delete", () => {
  it("deletes a caller-owned tracked asset and its index row", async () => {
    const { db, deletes } = fakeDb({
      [assetId(PUBLIC_ID)]: { uid: "uid-1", publicId: PUBLIC_ID },
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ publicId: PUBLIC_ID }));
    expect(res.status).toBe(200);
    expect(((await res.json()) as { result: string }).result).toBe("ok");
    expect(vi.mocked(cloudinary.uploader.destroy)).toHaveBeenCalledWith(PUBLIC_ID);
    expect(vi.mocked(allowAppMutation)).toHaveBeenCalledWith("delete:uid-1", 30, 60 * 60 * 1000);
    expect(deletes).toEqual([assetId(PUBLIC_ID)]);
  });

  it("deletes an orphan inside the caller's own prefix", async () => {
    const { db, deletes } = fakeDb({});
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ publicId: "app/uid-1/avatars/legacy" }));
    expect(res.status).toBe(200);
    expect(vi.mocked(cloudinary.uploader.destroy)).toHaveBeenCalledWith("app/uid-1/avatars/legacy");
    expect(deletes).toHaveLength(0); // no index row existed
  });

  it("refuses an asset owned by someone else with 404", async () => {
    const otherId = "app/uid-2/avatars/xyz";
    const { db } = fakeDb({
      [assetId(otherId)]: { uid: "uid-2", publicId: otherId },
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ publicId: otherId }));
    expect(res.status).toBe(404);
    expect(vi.mocked(cloudinary.uploader.destroy)).not.toHaveBeenCalled();
  });

  it("refuses a tracked row whose publicId does not match with 404", async () => {
    const { db } = fakeDb({
      [assetId(PUBLIC_ID)]: { uid: "uid-1", publicId: "app/uid-1/avatars/other" },
    });
    vi.mocked(getAdminDb).mockReturnValue(db as never);

    const res = await POST(req({ publicId: PUBLIC_ID }));
    expect(res.status).toBe(404);
    expect(vi.mocked(cloudinary.uploader.destroy)).not.toHaveBeenCalled();
  });

  it("refuses a missing asset outside the caller's prefix with 404", async () => {
    const { db } = fakeDb({});
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const res = await POST(req({ publicId: "app/uid-2/avatars/gone" }));
    expect(res.status).toBe(404);
    expect(vi.mocked(cloudinary.uploader.destroy)).not.toHaveBeenCalled();
  });

  it("rejects malformed publicIds with 400", async () => {
    const { db } = fakeDb({});
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ publicId: "../etc/passwd" }))).status).toBe(400);
    expect((await POST(req({ publicId: "a".repeat(201) }))).status).toBe(400);
    expect(vi.mocked(cloudinary.uploader.destroy)).not.toHaveBeenCalled();
  });

  it("returns 401 without a token", async () => {
    mockAppUnauthorized();
    expect((await POST(req({ publicId: PUBLIC_ID }))).status).toBe(401);
  });

  it("returns 403 for anonymous users", async () => {
    mockAppAnon();
    expect((await POST(req({ publicId: PUBLIC_ID }))).status).toBe(403);
  });

  it("rate-limits with 429", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    expect((await POST(req({ publicId: PUBLIC_ID }))).status).toBe(429);
  });
});
