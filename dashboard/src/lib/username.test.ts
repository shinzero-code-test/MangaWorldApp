import { describe, expect, it } from "vitest";
import {
  isValidUsername,
  normalizeUsername,
  sanitizeUsernameCandidate,
  usernameBaseFromEmail,
} from "./username";

// Pure username helpers mirroring Android UsernameRules (G/D-3, D-4).
// Firestore-backed helpers (tryClaimUsername, ensureDashboardProfile,
// releaseUsernameIfOwned) need an emulator and stay out of unit tests.

describe("isValidUsername", () => {
  it("accepts 3–20 alphanumerics/underscore without leading/trailing underscore", () => {
    expect(isValidUsername("abc")).toBe(true);
    expect(isValidUsername("user_123")).toBe(true);
    expect(isValidUsername("A1_")).toBe(false); // trailing underscore
    expect(isValidUsername("_ab")).toBe(false); // leading underscore
  });

  it("rejects short, long, and charset violations", () => {
    expect(isValidUsername("ab")).toBe(false);
    expect(isValidUsername("a".repeat(21))).toBe(false);
    expect(isValidUsername("user-name")).toBe(false);
    expect(isValidUsername("user name")).toBe(false);
    expect(isValidUsername("مستخدم")).toBe(false);
    expect(isValidUsername("")).toBe(false);
    expect(isValidUsername(null)).toBe(false);
    expect(isValidUsername(undefined)).toBe(false);
  });
});

describe("normalizeUsername", () => {
  it("trims and lowercases for the claim-doc id", () => {
    expect(normalizeUsername("  User_01 ")).toBe("user_01");
  });
});

describe("sanitizeUsernameCandidate", () => {
  it("sanitizes an email local-part like Android usernameFromEmail", () => {
    expect(sanitizeUsernameCandidate("John.Doe+tag", "uid123456")).toBe("john_doe_tag");
  });

  it("falls back to a uid-derived name when nothing usable remains", () => {
    const fallback = sanitizeUsernameCandidate("مستخدم", "ABCDEF123456");
    expect(isValidUsername(fallback)).toBe(true);
    expect(fallback.startsWith("user_")).toBe(true);
  });

  it("falls back for empty and single-char input", () => {
    expect(isValidUsername(sanitizeUsernameCandidate("", "uid999999"))).toBe(true);
    expect(isValidUsername(sanitizeUsernameCandidate("a", "uid999999"))).toBe(true);
  });
});

describe("usernameBaseFromEmail", () => {
  it("derives from the local-part", () => {
    expect(usernameBaseFromEmail("Admin@Example.com", "uid123456")).toBe("admin");
  });

  it("never returns an invalid name", () => {
    expect(isValidUsername(usernameBaseFromEmail("x@y.io", "uid123456"))).toBe(true);
  });
});
