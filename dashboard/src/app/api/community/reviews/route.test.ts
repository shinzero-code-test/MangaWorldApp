import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, DELETE } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  req,
  reqUrl,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/community/reviews (moderator list + soft delete).
//
// Pinned contracts:
//  - moderator gate (401/403)
//  - soft-deleted reviews never resurface and never skew the list
//  - the mangaId-scoped list reads the manga's own reviews collection
//  - DELETE is a SOFT delete (the review document survives so its replies
//    stay anchored) and a missing review is a 404
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
});

describe("GET /api/community/reviews", () => {
  it("lists reviews and hides the soft-deleted ones", async () => {
    fake.put("community_manga/m1/reviews", "r1", {
      mangaId: "m1",
      title: "Great",
      body: "text",
      rating: 5,
      authorUid: "u1",
      authorName: "Alice",
      createdAt: 10,
    });
    fake.put("community_manga/m1/reviews", "r2", {
      mangaId: "m1",
      title: "",
      body: "",
      isDeleted: true,
      rating: 1,
      createdAt: 20,
    });

    const res = await GET(reqUrl("http://localhost/api/community/reviews?mangaId=m1"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.reviews).toHaveLength(1);
    expect(body.reviews[0].id).toBe("r1");
    expect(body.reviews[0].rating).toBe(5);
  });

  it("falls back to the group listing without a mangaId", async () => {
    fake.putGroup("reviews", "r9", { title: "Grouped", createdAt: 5 });

    const res = await GET(reqUrl("http://localhost/api/community/reviews"));
    expect(res.status).toBe(200);
    expect((await res.json()).reviews).toHaveLength(1);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/community/reviews"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/community/reviews"))).status).toBe(403);
  });
});

describe("DELETE /api/community/reviews", () => {
  it("soft-deletes the review and keeps the document", async () => {
    fake.put("community_manga/m1/reviews", "r1", { title: "Great", body: "text" });

    const res = await DELETE(req({ reviewId: "r1", mangaId: "m1" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });

    const stored = fake.read("community_manga/m1/reviews", "r1");
    expect(stored).toBeDefined();
    expect(stored?.title).toBe("");
    expect(stored?.body).toBe("");
    expect(stored?.isDeleted).toBe(true);
    expect(fake.writesFor("community_manga/m1/reviews")[0].op).toBe("update");
  });

  it("returns 404 for a missing review and 400 for malformed params", async () => {
    expect((await DELETE(req({ reviewId: "nope", mangaId: "m1" }))).status).toBe(404);
    expect((await DELETE(req({ reviewId: "a/b", mangaId: "m1" }))).status).toBe(400);
    expect((await DELETE(req({ reviewId: "r1" }))).status).toBe(400);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await DELETE(req({ reviewId: "r1", mangaId: "m1" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await DELETE(req({ reviewId: "r1", mangaId: "m1" }))).status).toBe(403);
  });
});
