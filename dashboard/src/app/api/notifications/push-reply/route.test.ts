import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { allowAppMutation } from "@/lib/app-rate-limit";
import { isTextAllowed } from "@/lib/moderation";
import {
  req,
  mockAppAuthed,
  mockAppAnon,
  mockAppUnauthorized,
} from "@/app/api/__tests__/route-harness";
import { createFakeDb, fakeMessaging } from "@/app/api/__tests__/fake-firestore";

// U-32 rung 4 — /api/notifications/push-reply (reply/mention fan-out).
//
// Pinned contracts:
//  - app-user gate: 401 without a valid ID token, 403 for anonymous users
//  - the per-user rate limit (429)
//  - only the AUTHOR of a comment may dispatch its notifications (a forged
//    dispatch by another user is refused with 404)
//  - the dispatch is deduped through the commentNotificationDispatches doc
//    (a replay sends nothing)
//  - the thread-root author is notified and their replyCount is incremented
//  - attacker-controlled reply text is scrubbed through the keyword gate
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
vi.mock("@/lib/moderation", () => ({
  isTextAllowed: vi.fn(),
}));
vi.mock("firebase-admin/firestore", () => ({
  FieldValue: { increment: (n: number) => ({ __increment: n }) },
}));

let fake = createFakeDb();

beforeEach(() => {
  vi.clearAllMocks();
  fake = createFakeDb();
  vi.mocked(getAdminDb).mockReturnValue(fake.db as never);
  mockAppAuthed();
  vi.mocked(allowAppMutation).mockResolvedValue(true);
  vi.mocked(isTextAllowed).mockResolvedValue(true);
});

describe("POST /api/notifications/push-reply", () => {
  it("notifies the thread-root author, increments replyCount and pushes", async () => {
    fake.put("community_manga/m1/comments", "root", {
      authorUid: "author-2",
      text: "root comment",
      slug: "solo",
      sourceId: "azora",
    });
    fake.put("community_manga/m1/comments", "c1", {
      authorUid: "uid-1",
      parentId: "root",
      threadRootId: "root",
      text: "nice work",
      authorName: "Alice",
      mentions: [],
    });
    fake.put("users/author-2/devices", "d1", { token: "device-token" });
    const { messaging, sends } = fakeMessaging();
    vi.mocked(getAdminMessaging).mockReturnValue(messaging as never);

    const res = await POST(req({ mangaId: "m1", commentId: "c1" }));
    expect(res.status).toBe(200);
    const body = await res.json();
    expect(body.success).toBe(true);
    expect(body.sent).toBe(1);

    // The root author's replyCount went up (transactional increment).
    expect(fake.read("community_manga/m1/comments", "root")?.replyCount).toBeDefined();
    // The notification landed in the ROOT AUTHOR's tray, not the caller's.
    const notifications = fake.writesFor("users/author-2/notifications");
    expect(notifications).toHaveLength(1);
    expect(notifications[0].data?.type).toBe("REPLY");
    expect(notifications[0].data?.commentId).toBe("c1");
    expect(fake.writesFor("users/uid-1/notifications")).toHaveLength(0);
    expect(sends).toHaveLength(1);
  });

  it("notifies an @mentioned user", async () => {
    fake.put("community_manga/m1/comments", "c1", {
      authorUid: "uid-1",
      text: "hey @bob",
      authorName: "Alice",
      mentions: ["bob"],
    });
    fake.put("usernames", "bob", { uid: "author-2" });

    const res = await POST(req({ mangaId: "m1", commentId: "c1" }));
    expect(res.status).toBe(200);
    const notifications = fake.writesFor("users/author-2/notifications");
    expect(notifications).toHaveLength(1);
    expect(notifications[0].data?.type).toBe("MENTION");
  });

  it("sends nothing when the dispatch was already recorded (replay)", async () => {
    fake.put("community_manga/m1/comments", "root", { authorUid: "author-2" });
    fake.put("community_manga/m1/comments", "c1", {
      authorUid: "uid-1",
      threadRootId: "root",
      text: "again",
    });
    // A previous dispatch exists for this comment.
    const dispatchId = (await import("crypto"))
      .createHash("sha256")
      .update("m1::c1")
      .digest("hex");
    fake.put("commentNotificationDispatches", dispatchId, { authorUid: "uid-1" });

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

  it("rejects malformed identifiers with 400", async () => {
    expect((await POST(req({ mangaId: "a/b", commentId: "c1" }))).status).toBe(400);
    expect((await POST(req({ mangaId: "m1", commentId: "" }))).status).toBe(400);
    expect((await POST(req({ mangaId: "m1", commentId: "c1", chapterUrl: 5 }))).status).toBe(400);
    expect(fake.writes).toHaveLength(0);
  });

  it("scrubs a flagged reply body into a generic notice", async () => {
    fake.put("community_manga/m1/comments", "root", { authorUid: "author-2" });
    fake.put("community_manga/m1/comments", "c1", {
      authorUid: "uid-1",
      threadRootId: "root",
      text: "buy my coins",
      authorName: "Alice",
    });
    vi.mocked(isTextAllowed).mockResolvedValue(false);

    const res = await POST(req({ mangaId: "m1", commentId: "c1" }));
    expect(res.status).toBe(200);
    const notification = fake.writesFor("users/author-2/notifications")[0];
    expect(notification.data?.body).toBe("رد جديد على تعليقك");
    expect(String(notification.data?.body)).not.toContain("buy my coins");
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
