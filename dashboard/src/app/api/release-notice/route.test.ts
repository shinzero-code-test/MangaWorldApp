import { beforeEach, describe, expect, it, vi } from "vitest";
import { GET } from "./route";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";

// The release-floor notice behind the login page. It reads the LIVE Remote
// Config template (the same source the app reads) so the admin-facing
// notice can never drift from the floor the fleet is gated on.
//
// These tests pin the three things that matter: the floor is what the
// template says, a NON-https update_url is never handed out as a link, and
// an unreachable Remote Config degrades to "no notice" instead of breaking
// the login page.
vi.mock("@/lib/firebase-admin", () => ({
  getAdminRemoteConfig: vi.fn(),
}));

function fakeRc(parameters: Record<string, unknown>) {
  return {
    getTemplate: vi.fn().mockResolvedValue({ parameters, conditions: {}, etag: "etag-1" }),
    publishTemplate: vi.fn(),
  };
}

beforeEach(() => {
  vi.clearAllMocks();
});

describe("GET /api/release-notice", () => {
  it("returns the floor and the latest version from the live template", async () => {
    const rc = fakeRc({
      min_supported_version: { defaultValue: { value: "9.7.1" } },
      latest_version: { defaultValue: { value: "9.8.0" } },
      update_url: { defaultValue: { value: "https://github.com/x/y/releases/latest" } },
    });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = (await res.json()) as {
      minSupportedVersion: string | null;
      latestVersion: string | null;
      releaseUrl: string;
    };
    expect(body.minSupportedVersion).toBe("9.7.1");
    expect(body.latestVersion).toBe("9.8.0");
    expect(body.releaseUrl).toBe("https://github.com/x/y/releases/latest");
  });

  it("reports no floor when the template has none (the fail-open default)", async () => {
    const rc = fakeRc({});
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    const body = (await res.json()) as { minSupportedVersion: string | null };
    expect(body.minSupportedVersion).toBeNull();
  });

  it("never hands out a non-https link", async () => {
    const rc = fakeRc({
      min_supported_version: { defaultValue: { value: "9.7.1" } },
      update_url: { defaultValue: { value: "javascript:alert(1)" } },
    });
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    const body = (await res.json()) as { releaseUrl: string };
    expect(body.releaseUrl.startsWith("https://")).toBe(true);
  });

  it("degrades to no notice when Remote Config is unreachable", async () => {
    const rc = {
      getTemplate: vi.fn().mockRejectedValue(new Error("network down")),
      publishTemplate: vi.fn(),
    };
    vi.mocked(getAdminRemoteConfig).mockReturnValue(rc as never);

    const res = await GET();
    expect(res.status).toBe(200);
    const body = (await res.json()) as { minSupportedVersion: string | null };
    expect(body.minSupportedVersion).toBeNull();
  });
});
