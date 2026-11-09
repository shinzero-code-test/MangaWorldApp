import { beforeEach, describe, expect, it, vi } from "vitest";
import { POST } from "./route";
import { consumeRateLimit } from "@/lib/security";
import { isTextAllowed } from "@/lib/moderation";
import { req, mockAppAuthed, mockAppAnon, mockAppUnauthorized } from "@/app/api/__tests__/route-harness";

// Bonus (not in the U-32 priority ladder, but tiny and now carries the
// U-29 rejectAnonymousUser parity fix): POST /api/community/moderate —
// fail-CLOSED on auth/validation, fail-OPEN only on the keyword scan.
vi.mock("@/lib/app-auth", () => ({
  verifyAppIdToken: vi.fn(),
  rejectAnonymousUser: vi.fn(),
}));
vi.mock("@/lib/security", async (importOriginal) => {
  const actual = await importOriginal<typeof import("@/lib/security")>();
  return {
    ...actual,
    consumeRateLimit: vi.fn(),
  };
});
vi.mock("@/lib/moderation", () => ({
  isTextAllowed: vi.fn(),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockAppAuthed();
  vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: true });
  vi.mocked(isTextAllowed).mockResolvedValue(true);
});

describe("POST /api/community/moderate", () => {
  it("allows clean text", async () => {
    const res = await POST(req({ text: "great chapter" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ allowed: true });
    expect(vi.mocked(isTextAllowed)).toHaveBeenCalledWith("great chapter");
  });

  it("disallows banned keywords without an error status", async () => {
    vi.mocked(isTextAllowed).mockResolvedValue(false);
    const res = await POST(req({ text: "something banned" }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ allowed: false, reason: "banned_keyword" });
  });

  it("rejects overlong text without scanning", async () => {
    const res = await POST(req({ text: "x".repeat(2001) }));
    expect(res.status).toBe(200);
    expect(await res.json()).toEqual({ allowed: false, reason: "too_long" });
    expect(vi.mocked(isTextAllowed)).not.toHaveBeenCalled();
  });

  it("rejects empty text with 400", async () => {
    expect((await POST(req({ text: "   " }))).status).toBe(400);
    expect((await POST(req({}))).status).toBe(400);
    expect((await POST(req({ text: 42 }))).status).toBe(400);
  });

  it("surfaces scan-helper failures as 500 without leaking internals", async () => {
    // lib isTextAllowed is itself fail-open (resolves true on RC errors);
    // a REJECTED helper is a programming error and must not become
    // allowed:true by accident.
    vi.mocked(isTextAllowed).mockRejectedValue(new Error("rc template boom hunter2"));
    const res = await POST(req({ text: "hello" }));
    expect(res.status).toBe(500);
    expect(JSON.stringify(await res.json())).not.toContain("hunter2");
  });

  it("returns 401 without a token", async () => {
    mockAppUnauthorized();
    expect((await POST(req({ text: "hello" }))).status).toBe(401);
  });

  it("returns 403 for anonymous users (U-29 parity)", async () => {
    mockAppAnon();
    expect((await POST(req({ text: "hello" }))).status).toBe(403);
  });

  it("rate-limits with 429", async () => {
    vi.mocked(consumeRateLimit).mockResolvedValue({ allowed: false });
    expect((await POST(req({ text: "hello" }))).status).toBe(429);
  });
});
