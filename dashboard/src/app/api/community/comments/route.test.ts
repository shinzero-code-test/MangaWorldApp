import { createHash } from "crypto";
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

// U-32 rung 4 — /api/community/comments (moderator list + soft delete).
//
// Pinned contracts:
//  - moderator gate (401/403)
//  - soft-deleted rows never resurface in the moderation list
//  - the search filter matches text / author / manga id
//  - DELETE is a SOFT delete (the document survives so replies stay
//    anchored) and a missing comment is a 404
//  - the id/chapterUrl guards (no path traversal into other collections)
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));

function chapterKey(url: string): string {
  return createHash("sha256").update(url).digest("hex").slice(0, 24);
}

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
});

describe("GET /api/community/comments", () => {
  it("lists comments and hides the soft-deleted ones", async () => {
    fake.putGroup("comments", "c1", {
      mangaId: "m1",
      text: "first",
      authorUid: "u1",
      authorName: "Alice",
      createdAt: 10,
    });
    fake.putGroup("comments", "c2", {
      mangaId: "m1",
      text: "",
      isDeleted: true,
      authorUid: "u2",
      createdAt: 20,
    });

    const res = await GET(reqUrl("http://localhost/api/community/comments"));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.comments).toHaveLength(1);
    expect(body.comments[0].id).toBe("c1");
    expect(body.comments[0].text).toBe("first");
  });

  it("filters by mangaId and by the search term", async () => {
    fake.putGroup("comments", "c1", { mangaId: "m1", text: "alpha", authorName: "Alice" });
    fake.putGroup("comments", "c2", { mangaId: "m2", text: "beta", authorName: "Bob" });

    const byManga = await GET(reqUrl("http://localhost/api/community/comments?mangaId=m2"));
    expect((await byManga.json()).comments).toHaveLength(1);

    const bySearch = await GET(reqUrl("http://localhost/api/community/comments?search=ALPH"));
    expect((await bySearch.json()).comments).toHaveLength(1);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET(reqUrl("http://localhost/api/community/comments"))).status).toBe(401);
    mockRoleForbidden();
    expect((await GET(reqUrl("http://localhost/api/community/comments"))).status).toBe(403);
  });
});

describe("DELETE /api/community/comments", () => {
  it("soft-deletes the comment and keeps the document", async () => {
    fake.put("community_manga/m1/comments", "c1", { text: "spam", mentions: ["bob"] });

    const res = await DELETE(req({ commentId: "c1", mangaId: "m1", chapterUrl: null }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });

    const stored = fake.read("community_manga/m1/comments", "c1");
    expect(stored).toBeDefined();
    expect(stored?.text).toBe("");
    expect(stored?.mentions).toEqual([]);
    expect(stored?.isDeleted).toBe(true);
    // A hard delete would orphan the replies thread — assert the op.
    expect(fake.writesFor("community_manga/m1/comments")[0].op).toBe("update");
  });

  it("soft-deletes a chapter-scoped comment in its own thread", async () => {
    const url = "/manga/1/chapter/2";
    fake.put(`community_manga/m1/chapters/${chapterKey(url)}/comments`, "c9", { text: "spam" });

    const res = await DELETE(req({ commentId: "c9", mangaId: "m1", chapterUrl: url }));
    expect(res.status).toBe(200);
    expect(fake.read(`community_manga/m1/chapters/${chapterKey(url)}/comments`, "c9")?.isDeleted).toBe(true);
  });

  it("returns 404 for a missing comment and 400 for malformed params", async () => {
    expect((await DELETE(req({ commentId: "nope", mangaId: "m1", chapterUrl: null }))).status).toBe(404);
    expect((await DELETE(req({ commentId: "a/b", mangaId: "m1", chapterUrl: null }))).status).toBe(400);
    expect((await DELETE(req({ commentId: "c1", mangaId: "", chapterUrl: null }))).status).toBe(400);
    expect((await DELETE(req({ commentId: "c1", mangaId: "m1", chapterUrl: 5 }))).status).toBe(400);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await DELETE(req({ commentId: "c1", mangaId: "m1", chapterUrl: null }))).status).toBe(401);
    mockRoleForbidden();
    expect((await DELETE(req({ commentId: "c1", mangaId: "m1", chapterUrl: null }))).status).toBe(403);
  });
});
