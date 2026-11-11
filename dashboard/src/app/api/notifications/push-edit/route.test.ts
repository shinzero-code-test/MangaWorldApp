import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import {
  req,
  mockAppAuthed,
  mockAppAnon,
  mockAppUnauthorized,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb, fakeMessaging } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/notifications/push-edit (edit-mention fan-out).
//
// Pinned contracts:
//  - app-user gate: 401 without a valid ID token, 403 for anonymous users
//  - the per-user rate limit (429)
//  - only the AUTHOR of the comment/review may dispatch (404 otherwise)
//  - ONLY FRESHLY ADDED mentions are notified — the previously notified
//    mentions are the baseline in commentNotificationDispatches
//  - the dispatch baseline is updated with the union
vi.mock("@/lib/app-auth", () => ({
  verifyAppIdToken: vi.fn(),
  rejectAnonymousUser: vi.fn(),
}));
vi.mock("@/lib/app-rate-limit", () => ({
  allowAppMutation: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
  getAdminMessaging: vi.fn(),
}));

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
});

describe("POST /api/notifications/push-edit", () => {
  it("notifies only the mentions added by this edit", async () => {
    fake.put("community_manga/m1/comments", "c1", {
      authorUid: "uid-1",
      authorName: "Alice",
      slug: "solo",
      sourceId: "azora",
      text: "hey @bob and @carol",
    });
    fake.put("usernames", "bob", { uid: "author-2" });
    fake.put("usernames", "carol", { uid: "author-3" });
    // bob was already notified by an earlier edit — only carol is fresh.
    const dispatchKey = "m1::c1";
    const dispatchId = (await import("crypto"))
      .createHash("sha256")
      .update(dispatchKey)
      .digest("hex");
    fake.put("commentNotificationDispatches", dispatchId, {
      authorUid: "uid-1",
      notifiedMentions: ["bob"],
    });
    fake.put("users/author-3/devices", "d1", { token: "device-token" });
    const { messaging, sends } = fakeMessaging();
    vi.mocked(getAdminMessaging).mockReturnValue(messaging as never);

    const res = await POST(req({ mangaId: "m1", commentId: "c1" }));
    expect(res.status).toBe(200);
    expect((await res.json()).sent).toBe(1);

    expect(fake.writesFor("users/author-2/notifications")).toHaveLength(0);
    const fresh = fake.writesFor("users/author-3/notifications");
    expect(fresh).toHaveLength(1);
    expect(fresh[0].data?.type).toBe("MENTION");
    expect(sends).toHaveLength(1);
  });

  it("sends nothing when the edit adds no new mention", async () => {
    fake.put("community_manga/m1/comments", "c1", {
      authorUid: "uid-1",
      text: "hey @bob",
    });
    fake.put("usernames", "bob", { uid: "author-2" });
    const dispatchId = (await import("crypto"))
      .createHash("sha256")
      .update("m1::c1")
      .digest("hex");
    fake.put("commentNotificationDispatches", dispatchId, {
      authorUid: "uid-1",
      notifiedMentions: ["bob"],
    });

    const res = await POST(req({ mangaId: "m1", commentId: "c1" }));
    expect(res.status).toBe(200);
    expect((await res.json()).sent).toBe(0);
    expect(fake.writesFor("users/author-2/notifications")).toHaveLength(0);
  });

  it("refuses a comment the caller did not write", async () => {
    fake.put("community_manga/m1/comments", "c1", { authorUid: "someone-else", text: "x" });

    const res = await POST(req({ mangaId: "m1", commentId: "c1" }));
    expect(res.status).toBe(404);
    expect(fake.writes).toHaveLength(0);
  });

  it("requires a commentId or a reviewId and validates the ids", async () => {
    expect((await POST(req({ mangaId: "m1" }))).status).toBe(400);
    expect((await POST(req({ mangaId: "a/b", commentId: "c1" }))).status).toBe(400);
    expect((await POST(req({ mangaId: "m1", commentId: "a/b" }))).status).toBe(400);
    expect((await POST(req({ mangaId: "m1", reviewId: "" }))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 429 when the caller is rate limited", async () => {
    vi.mocked(allowAppMutation).mockResolvedValue(false);
    expect((await POST(req({ mangaId: "m1", commentId: "c1" }))).status).toBe(429);
    expect(fake.writes).toHaveLength(0);
  });

  it("returns 401 without a valid ID token and 403 for anonymous users", async () => {
    mockAppUnauthorized();
    expect((await POST(req({ mangaId: "m1", commentId: "c1" }))).status).toBe(401);
    mockAppAnon();
    expect((await POST(req({ mangaId: "m1", commentId: "c1" }))).status).toBe(403);
    expect(fake.writes).toHaveLength(0);
  });
});
