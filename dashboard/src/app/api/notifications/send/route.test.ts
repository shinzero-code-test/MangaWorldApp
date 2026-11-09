import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { getAdminDb, getAdminMessaging } from "@/lib/firebase-admin";
import { consumeRateLimit } from "@/lib/security";
import { req, mockRoleOk, mockRoleUnauthorized, mockRoleForbidden, mockRoleMfaRequired } from "@/app/api/__tests__/route-harness";

// (6) notifications/send — super-admin broadcast: harder rate limit
// (10/hour) → FCM-aligned body bounds → topic allow-list (rejected, never
// coerced) → explicit-token multicast (deduped, ≤500) or bounded fleet
// broadcast (5000 devices max, 500-token chunks, dead-token cleanup).
vi.mock("@/lib/auth", () => ({
  requireRole: vi.fn(),
}));
vi.mock("@/lib/firebase-admin", () => ({
  getAdminDb: vi.fn(),
  getAdminMessaging: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    consumeRateLimit: vi.fn(),
  };
});

/** Fake broadcast fleet: collectionGroup("devices").select("token").limit(n). */
function fakeDb(deviceTokens: string[]) {
  const history: Array<Record<string, unknown>> = [];
  const deletedRefs: string[] = [];
  const batch = {
    delete: vi.fn((r: { path: string }) => {
      deletedRefs.push(r.path);
    }),
    commit: vi.fn().mockResolvedValue(undefined),
  };
  const db = {
    collection: (name: string) => {
      if (name === "notification_history") {
        return {
          add: async (data: Record<string, unknown>) => {
            history.push(data);
            return { id: "history-1" };
          },
        };
      }
      return {};
    },
    collectionGroup: (_name: string) => ({
      select: () => ({
        limit: () => ({
          get: async () => ({
            docs: deviceTokens.map((token) => ({
              data: () => ({ token }),
              ref: { path: `users/u/${token}` },
            })),
          }),
        }),
      }),
    }),
    batch: () => batch,
  };
  return { db, history, deletedRefs, batch };
}

const TOKEN_A = "token-aaaaaaaaaa";
const TOKEN_B = "token-bbbbbbbbbb";

beforeEach(() => {
  vi.clearAllMocks();
  mockRoleOk();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
});

describe("POST /api/notifications/send", () => {
  it("sends to explicit tokens (deduped) and records history", async () => {
    const { db, history } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const sendEach = vi.fn().mockResolvedValue({ successCount: 2, failureCount: 0, responses: [] });
    vi.mocked(getAdminMessaging).mockReturnValue({ sendEachForMulticast: sendEach } as never);

    const res = await POST(req({
      title: "Big news",
      body: "New chapter out",
      topic: "updates",
      tokens: [TOKEN_A, TOKEN_B, TOKEN_A],
      imageUrl: "https://cdn.example/cover.png",
      mangaId: "m1",
      slug: "solo",
      sourceId: "azora",
      chapterUrl: "https://x.com/solo/12",
    }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ success: true, sent: 2, failed: 0 });

    expect(sendEach).toHaveBeenCalledTimes(1);
    const payload = sendEach.mock.calls[0][0] as {
      tokens: string[];
      data: Record<string, string>;
      android: Record<string, string>;
    };
    expect(payload.tokens).toEqual([TOKEN_A, TOKEN_B]);
    expect(payload.android).toEqual({ priority: "high" });
    expect(payload.data).toMatchObject({
      title: "Big news",
      body: "New chapter out",
      type: "push",
      topic: "updates",
      imageUrl: "https://cdn.example/cover.png",
      mangaId: "m1",
      slug: "solo",
      sourceId: "azora",
      chapterUrl: "https://x.com/solo/12",
    });
    // Data-only payload: every value must be a string for FCM.
    for (const value of Object.values(payload.data)) expect(typeof value).toBe("string");

    expect(history).toHaveLength(1);
    expect(history[0]).toMatchObject({
      title: "Big news",
      body: "New chapter out",
      topic: "updates",
      status: "sent",
      sent: 2,
      failed: 0,
    });
  });

  it("broadcasts to the fleet in 500-token chunks", async () => {
    const tokens = Array.from({ length: 505 }, (_, i) => `fleet-token-${String(i).padStart(4, "0")}`);
    const { db } = fakeDb(tokens);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const sendEach = vi.fn().mockResolvedValue({ successCount: 0, failureCount: 0, responses: [] });
    vi.mocked(getAdminMessaging).mockReturnValue({ sendEachForMulticast: sendEach } as never);

    const res = await POST(req({ title: "News", body: "Hello", topic: "general" }));
    expect(res.status).toBe(200);
    expect(sendEach).toHaveBeenCalledTimes(2);
    expect((sendEach.mock.calls[0][0] as { tokens: string[] }).tokens).toHaveLength(500);
    expect((sendEach.mock.calls[1][0] as { tokens: string[] }).tokens).toHaveLength(5);
  });

  it("cleans up dead registrations reported by FCM", async () => {
    const { db, batch } = fakeDb([TOKEN_A, TOKEN_B]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const sendEach = vi.fn().mockResolvedValue({
      successCount: 1,
      failureCount: 1,
      responses: [
        { success: true },
        { success: false, error: { code: "messaging/registration-token-not-registered" } },
      ],
    });
    vi.mocked(getAdminMessaging).mockReturnValue({ sendEachForMulticast: sendEach } as never);

    const res = await POST(req({ title: "News", body: "Hello", topic: "general" }));
    expect(res.status).toBe(200);
    const body = (await res.json()) as { success: boolean; cleanedTokens: number };
    expect(body.cleanedTokens).toBe(1);
    expect(batch.delete).toHaveBeenCalledWith({ path: `users/u/${TOKEN_B}` });
    expect(batch.commit).toHaveBeenCalled();
  });

  it("sends to an empty fleet without touching FCM", async () => {
    const { db } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    const sendEach = vi.fn();
    vi.mocked(getAdminMessaging).mockReturnValue({ sendEachForMulticast: sendEach } as never);

    const res = await POST(req({ title: "News", body: "Hello", topic: "general" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toMatchObject({ success: true, sent: 0, failed: 0 });
    expect(sendEach).not.toHaveBeenCalled();
  });

  it("marks history failed when every send fails", async () => {
    const { db, history } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    vi.mocked(getAdminMessaging).mockReturnValue({
      sendEachForMulticast: vi.fn().mockResolvedValue({ successCount: 0, failureCount: 2, responses: [] }),
    } as never);

    const res = await POST(req({ title: "News", body: "Hello", topic: "general", tokens: [TOKEN_A, TOKEN_B] }));
    expect(res.status).toBe(200);
    expect(history[0]).toMatchObject({ status: "failed", sent: 0, failed: 2 });
  });

  it("rejects malformed bodies with 400", async () => {
    const { db } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req("not-an-object"))).status).toBe(400);
    expect((await POST(req({ body: "x", topic: "general" }))).status).toBe(400);
    expect((await POST(req({ title: "x".repeat(121), body: "x", topic: "general" }))).status).toBe(400);
    expect((await POST(req({ title: "x", body: "x".repeat(301), topic: "general" }))).status).toBe(400);
  });

  it("rejects topics outside the allow-list with 400", async () => {
    const { db } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({ title: "x", body: "x", topic: "all" }))).status).toBe(400);
    expect((await POST(req({ title: "x", body: "x" }))).status).toBe(400);
  });

  it("rejects invalid token arrays and link fields with 400", async () => {
    const { db } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({ title: "x", body: "x", topic: "general", tokens: "tok" }))).status).toBe(400);
    expect((await POST(req({ title: "x", body: "x", topic: "general", tokens: ["short"] }))).status).toBe(400);
    expect((await POST(req({
      title: "x", body: "x", topic: "general",
      tokens: Array.from({ length: 501 }, (_, i) => `token-${String(i).padStart(6, "0")}`),
    }))).status).toBe(400);
    expect((await POST(req({ title: "x", body: "x", topic: "general", imageUrl: "http://x.com/a.png" }))).status).toBe(400);
    expect((await POST(req({ title: "x", body: "x", topic: "general", mangaId: "x".repeat(1025) }))).status).toBe(400);
  });

  it("throttles broadcasts with 429", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    const { db } = fakeDb([]);
    vi.mocked(getAdminDb).mockReturnValue(db as never);
    expect((await POST(req({ title: "x", body: "x", topic: "general" }))).status).toBe(429);
    expect(vi.mocked(consumeRateLimit)).toHaveBeenCalledWith("admin-broadcast", "admin-1", 10, 60 * 60 * 1000);
  });

  it("returns 401/403 without the role", async () => {
    mockRoleUnauthorized();
    expect((await POST(req({ title: "x", body: "x", topic: "general" }))).status).toBe(401);
    mockRoleForbidden();
    expect((await POST(req({ title: "x", body: "x", topic: "general" }))).status).toBe(403);
    mockRoleMfaRequired();
    expect((await POST(req({ title: "x", body: "x", topic: "general" }))).status).toBe(403);
  });
});
