import { authenticator } from "otplib";
import { describe, expect, it } from "vitest";
import {
  decryptSecret,
  encryptSecret,
  generateBackupCodes,
  genericErrorResponse,
  hashBackupCode,
  matchBackupCodeHash,
  resolveTotpSecret,
  verifyTotpConstantTime,
} from "./security";

// First test file for the admin dashboard (#5). Covers the pure,
// high-privilege helpers in lib/security.ts — TOTP round-trip, secret
// encryption at rest, error-response shape. Firestore-backed helpers
// (rate limits, OTP lockout, replay cache) need an emulator and stay out.

describe("verifyTotpConstantTime", () => {
  it("accepts a live otplib token for the same secret", () => {
    const secret = authenticator.generateSecret(20);
    const token = authenticator.generate(secret);
    expect(verifyTotpConstantTime(secret, token)).toBe(true);
  });

  it("rejects malformed tokens without throwing", () => {
    const secret = authenticator.generateSecret(20);
    for (const bad of ["", "12345", "1234567", "abcdef", "12 34", "------", null, undefined]) {
      expect(verifyTotpConstantTime(secret, bad as unknown as string)).toBe(false);
    }
  });

  it("rejects a token from a different secret", () => {
    const token = authenticator.generate(authenticator.generateSecret(20));
    expect(verifyTotpConstantTime(authenticator.generateSecret(20), token)).toBe(false);
  });

  it("rejects an invalid secret without throwing", () => {
    expect(verifyTotpConstantTime("", "123456")).toBe(false);
    expect(verifyTotpConstantTime("!!!", "123456")).toBe(false);
  });

  it("tolerates surrounding whitespace in the token", () => {
    const secret = authenticator.generateSecret(20);
    const token = authenticator.generate(secret);
    expect(verifyTotpConstantTime(secret, ` ${token} `)).toBe(true);
  });
});

describe("secret encryption at rest", () => {
  it("round-trips through AES-256-GCM", () => {
    process.env.MFA_SESSION_SECRET = "test-secret-for-vitest-only";
    const plain = authenticator.generateSecret(20);
    const enc = encryptSecret(plain);
    expect(enc.v).toBe(1);
    expect(decryptSecret(enc)).toBe(plain);
  });

  it("resolveTotpSecret prefers the encrypted form and falls back to legacy", () => {
    process.env.MFA_SESSION_SECRET = "test-secret-for-vitest-only";
    const plain = authenticator.generateSecret(20);
    expect(resolveTotpSecret({ secretEncrypted: encryptSecret(plain) })).toBe(plain);
    expect(resolveTotpSecret({ secret: "legacy-plain" })).toBe("legacy-plain");
    expect(resolveTotpSecret(undefined)).toBeNull();
    expect(resolveTotpSecret({})).toBeNull();
    // Corrupt ciphertext fails closed, never throws.
    expect(
      resolveTotpSecret({ secretEncrypted: { v: 1, iv: "00", tag: "00", data: "00" } })
    ).toBeNull();
  });
});

describe("genericErrorResponse", () => {
  it("passes auth control-flow signals through with real statuses", () => {
    expect(genericErrorResponse(new Error("Forbidden")).status).toBe(403);
    expect(genericErrorResponse(new Error("Unauthorized")).status).toBe(401);
    expect(genericErrorResponse(new Error("MFA verification required")).status).toBe(403);
  });

  it("masks unexpected errors behind a generic message plus correlation id", () => {
    const { body, status } = genericErrorResponse(new Error("db password=hunter2"));
    expect(status).toBe(500);
    expect(body.error).not.toContain("hunter2");
    expect(body.correlationId).toMatch(/^err_/);
  });
});

describe("backup codes", () => {
  it("issues unique 8-char codes from the unambiguous alphabet", () => {
    const { codes, hashes } = generateBackupCodes();
    expect(codes).toHaveLength(10);
    expect(new Set(codes).size).toBe(10);
    for (const code of codes) {
      expect(code).toMatch(/^[ABCDEFGHJKMNPQRSTUVWXYZ23456789]{8}$/);
    }
    expect(hashes).toHaveLength(10);
  });

  it("hash is deterministic, case-insensitive, and hides the code", () => {
    const { codes } = generateBackupCodes();
    const code = codes[0];
    expect(hashBackupCode(code)).toBe(hashBackupCode(code.toLowerCase()));
    expect(hashBackupCode(code)).not.toContain(code);
  });

  it("matches a stored hash and rejects strangers", () => {
    const { codes, hashes } = generateBackupCodes();
    expect(matchBackupCodeHash(codes[0], hashes)).toBe(hashes[0]);
    expect(matchBackupCodeHash("ZZZZZZZZ", hashes)).toBeNull();
    expect(matchBackupCodeHash(codes[0], [])).toBeNull();
    expect(matchBackupCodeHash(codes[0], undefined)).toBeNull();
    expect(matchBackupCodeHash(codes[0], "not-an-array")).toBeNull();
  });
});
