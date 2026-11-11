import { createHash } from "crypto";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET, PATCH } from "./route";
import { getAdminDb } from "@/lib/firebase-admin";
import {
  req,
  mockRoleOk,
  mockRoleUnauthorized,
  mockRoleForbidden,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/moderation/reports (moderator report queue).
//
// Pinned contracts:
//  - moderator gate (401/403)
//  - the report status vocabulary (resolved / dismissed) and the param guard
//  - removeContent is only allowed together with status=resolved (a
//    dismissed report must never take content down)
//  - a missing report is a 404
//  - M-3: the take-down is a SERVER-SIDE soft delete of the reported
//    comment/review, then the report itself is resolved
vi.mock("@/lib/auth", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/auth")>();
  return { ...actual, requireRole: vi.fn() };
});
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
}));

function chapterKey(url: string): string {
  return createHash("sha256").update(url, "utf8").digest("hex").slice(0, 24);
}

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockRoleOk();
});

describe("GET /api/moderation/reports", () => {
  it("lists the reports", async () => {
    fake.put("moderationReports", "rep1", {
      targetId: "c1",
      targetType: "comment",
      mangaId: "m1",
      reason: "spam",
      createdAt: 10,
      status: "open",
    });

    const res = await GET();
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.reports).toHaveLength(1);
    expect(body.reports[0].id).toBe("rep1");
    expect(body.reports[0].status).toBe("open");
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await GET()).status).toBe(401);
    mockRoleForbidden();
    expect((await GET()).status).toBe(403);
  });
});

describe("PATCH /api/moderation/reports", () => {
  it("resolves a report without touching the content", async () => {
    fake.put("moderationReports", "rep1", { targetId: "c1", targetType: "comment", mangaId: "m1" });
    fake.put("community_manga/m1/comments", "c1", { text: "keep me" });

    const res = await PATCH(req({ reportId: "rep1", status: "resolved" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true });
    expect(fake.read("moderationReports", "rep1")?.status).toBe("resolved");
    // No take-down was requested → the comment is untouched.
    expect(fake.read("community_manga/m1/comments", "c1")?.text).toBe("keep me");
  });

  it("soft-deletes the reported comment when removeContent is set", async () => {
    fake.put("moderationReports", "rep1", { targetId: "c1", targetType: "comment", mangaId: "m1" });
    fake.put("community_manga/m1/comments", "c1", { text: "spam", mentions: ["bob"] });

    const res = await PATCH(req({ reportId: "rep1", status: "resolved", removeContent: true }));
    expect(res.status).toBe(200);
    const stored = fake.read("community_manga/m1/comments", "c1");
    expect(stored?.isDeleted).toBe(true);
    expect(stored?.text).toBe("");
    expect(stored?.mentions).toEqual([]);
  });

  it("soft-deletes a chapter-scoped comment in its own thread", async () => {
    const url = "/manga/1/chapter/2";
    fake.put("moderationReports", "rep1", {
      targetId: "c9",
      targetType: "comment",
      mangaId: "m1",
      chapterUrl: url,
    });
    fake.put(`community_manga/m1/chapters/${chapterKey(url)}/comments`, "c9", { text: "spam" });

    const res = await PATCH(req({ reportId: "rep1", status: "resolved", removeContent: true }));
    expect(res.status).toBe(200);
    expect(fake.read(`community_manga/m1/chapters/${chapterKey(url)}/comments`, "c9")?.isDeleted).toBe(true);
  });

  it("soft-deletes the reported review", async () => {
    fake.put("moderationReports", "rep1", { targetId: "r1", targetType: "review", mangaId: "m1" });
    fake.put("community_manga/m1/reviews", "r1", { title: "bad", body: "bad" });

    const res = await PATCH(req({ reportId: "rep1", status: "resolved", removeContent: true }));
    expect(res.status).toBe(200);
    const stored = fake.read("community_manga/m1/reviews", "r1");
    expect(stored?.isDeleted).toBe(true);
    expect(stored?.title).toBe("");
    expect(stored?.body).toBe("");
  });

  it("refuses removeContent without status=resolved", async () => {
    fake.put("moderationReports", "rep1", { targetId: "c1", targetType: "comment", mangaId: "m1" });
    fake.put("community_manga/m1/comments", "c1", { text: "spam" });

    expect((await PATCH(req({ reportId: "rep1", status: "dismissed", removeContent: true }))).status).toBe(400);
    expect(fake.read("community_manga/m1/comments", "c1")?.isDeleted).toBeUndefined();
  });

  it("rejects malformed params with 400 and returns 404 for a missing report", async () => {
    expect((await PATCH(req({ reportId: "rep1", status: "open" }))).status).toBe(400);
    expect((await PATCH(req({ reportId: "a/b", status: "resolved" }))).status).toBe(400);
    expect((await PATCH(req({ status: "resolved" }))).status).toBe(400);
    expect(
      (await PATCH(req({ reportId: "missing", status: "resolved", removeContent: true }))).status,
    ).toBe(404);
  });

  it("returns 401/403 without the moderator role", async () => {
    mockRoleUnauthorized();
    expect((await PATCH(req({ reportId: "rep1", status: "resolved" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await PATCH(req({ reportId: "rep1", status: "resolved" }))).status).toBe(403);
  });
});
