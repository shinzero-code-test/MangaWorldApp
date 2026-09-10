import { describe, expect, it } from "vitest";
import {
  avatarColor,
  cn,
  formatAr,
  formatBytes,
  formatDate,
  formatDateFull,
  formatDuration,
  formatRelative,
  getInitials,
  roleLabel,
  truncate,
} from "./utils";

// Pure dashboard display helpers — locale formatting, labels, initials.
// No Firestore/network/env access.

// ─── Number formatters ──────────────────────────────
describe("formatAr", () => {
  it("formats small ints with Arabic-Indic digits", () => {
    expect(formatAr(0)).toBe("٠");
    expect(formatAr(5)).toBe("٥");
  });

  it("groups thousands like the Intl ar-SA pipeline", () => {
    expect(formatAr(1234567)).toBe((1234567).toLocaleString("ar-SA"));
    expect(formatAr(1234567)).toBe("١٬٢٣٤٬٥٦٧");
  });
});

// ─── Date formatters ────────────────────────────────
describe("formatDate / formatDateFull", () => {
  const ts = "2024-03-05T12:00:00Z";

  it("matches the ar-SA date pipeline and keeps the year", () => {
    expect(formatDate(ts)).toBe(
      new Date(ts).toLocaleDateString("ar-SA", {
        year: "numeric",
        month: "short",
        day: "numeric",
      })
    );
    expect(formatDate(ts)).toContain("٢٠٢٤");
  });

  it("full form adds the time component", () => {
    expect(formatDateFull(ts)).toBe(
      new Date(ts).toLocaleString("ar-SA", {
        year: "numeric",
        month: "short",
        day: "numeric",
        hour: "2-digit",
        minute: "2-digit",
      })
    );
    expect(formatDateFull(ts).length).toBeGreaterThan(formatDate(ts).length);
  });

  it("accepts epoch numbers and Date objects", () => {
    const epoch = Date.parse(ts);
    expect(formatDate(epoch)).toBe(formatDate(ts));
    expect(formatDate(new Date(ts))).toBe(formatDate(ts));
  });
});

// ─── Relative time ───────────────────────────────────
describe("formatRelative", () => {
  it("says الآن for now, recent past, and the future", () => {
    expect(formatRelative(Date.now())).toBe("الآن");
    expect(formatRelative(Date.now() - 30_000)).toBe("الآن");
    expect(formatRelative(Date.now() + 60_000)).toBe("الآن");
  });

  it("uses singular / dual / plural minute forms", () => {
    expect(formatRelative(Date.now() - 60_000)).toContain("دقيقة");
    expect(formatRelative(Date.now() - 2 * 60_000)).toBe("منذ دقيقتين");
    expect(formatRelative(Date.now() - 5 * 60_000)).toContain("دقائق");
    expect(formatRelative(Date.now() - 11 * 60_000)).toContain("دقيقة");
    expect(formatRelative(Date.now() - 11 * 60_000)).not.toContain("دقائق");
  });

  it("uses singular / dual / plural hour forms", () => {
    expect(formatRelative(Date.now() - 3_600_000)).toContain("ساعة");
    expect(formatRelative(Date.now() - 2 * 3_600_000)).toBe("منذ ساعتين");
    expect(formatRelative(Date.now() - 5 * 3_600_000)).toContain("ساعات");
  });

  it("uses singular / dual / plural day forms", () => {
    expect(formatRelative(Date.now() - 86_400_000)).toContain("يوم");
    expect(formatRelative(Date.now() - 2 * 86_400_000)).toBe("منذ يومين");
    expect(formatRelative(Date.now() - 5 * 86_400_000)).toContain("أيام");
  });

  it("falls back to the calendar date after a week", () => {
    const old = Date.now() - 10 * 86_400_000;
    expect(formatRelative(old)).toBe(formatDate(old));
  });
});

// ─── Duration ────────────────────────────────────────
describe("formatDuration", () => {
  it("returns an em-dash for non-finite or negative input", () => {
    expect(formatDuration(NaN)).toBe("—");
    expect(formatDuration(Infinity)).toBe("—");
    expect(formatDuration(-1)).toBe("—");
  });

  it("formats sub-second values as milliseconds", () => {
    expect(formatDuration(0)).toBe("٠ م.ث");
    expect(formatDuration(500)).toContain("م.ث");
  });

  it("formats second-scale values without the milli marker", () => {
    const oneSec = formatDuration(1000);
    expect(oneSec).toContain("ث");
    expect(oneSec).not.toContain("م.ث");
    expect(formatDuration(1500)).toContain("ث");
  });
});

// ─── Avatar ──────────────────────────────────────────
describe("avatarColor", () => {
  it("falls back to the first palette color for empty uids", () => {
    expect(avatarColor()).toBe("#7c3aed");
    expect(avatarColor(null)).toBe("#7c3aed");
    expect(avatarColor("")).toBe("#7c3aed");
  });

  it("is deterministic and stays inside the palette", () => {
    const palette = [
      "#7c3aed", "#2563eb", "#059669", "#d97706",
      "#dc2626", "#0891b2", "#9333ea", "#0d9488",
    ];
    expect(avatarColor("uid-123")).toBe(avatarColor("uid-123"));
    for (const uid of ["a", "uid-123", "مستخدم-١", "x".repeat(200)]) {
      expect(palette).toContain(avatarColor(uid));
    }
  });
});

// ─── Initials ─────────────────────────────────────────
describe("getInitials", () => {
  it("returns ? for missing input", () => {
    expect(getInitials()).toBe("?");
    expect(getInitials(null, null)).toBe("?");
    expect(getInitials("", "")).toBe("?");
  });

  it("takes first letters of the first two words", () => {
    expect(getInitials("John Doe")).toBe("JD");
    expect(getInitials("أحمد محمد")).toBe("أم");
  });

  it("falls back to the email and splits on separators", () => {
    expect(getInitials(undefined, "john.doe@example.com")).toBe("JD");
    expect(getInitials(undefined, "john@example.com")).toBe("JE");
  });

  it("uses two leading chars for a single token", () => {
    expect(getInitials("John")).toBe("JO");
    expect(getInitials("a")).toBe("A");
  });
});

// ─── Class join ──────────────────────────────────────
describe("cn", () => {
  it("joins truthy classes with spaces", () => {
    expect(cn("a", "b")).toBe("a b");
  });

  it("drops falsy entries", () => {
    expect(cn("a", false, null, undefined, "b")).toBe("a b");
    expect(cn(false, null, undefined)).toBe("");
    expect(cn()).toBe("");
  });
});

// ─── Bytes ────────────────────────────────────────────
describe("formatBytes", () => {
  it("handles zero and invalid input", () => {
    expect(formatBytes(0)).toBe("0 B");
    expect(formatBytes(-5)).toBe("—");
    expect(formatBytes(NaN)).toBe("—");
    expect(formatBytes(Infinity)).toBe("—");
  });

  it("scales through B/KB/MB", () => {
    expect(formatBytes(512)).toContain("B");
    expect(formatBytes(1024)).toContain("KB");
    expect(formatBytes(1024 * 1024)).toContain("MB");
    expect(formatBytes(1500)).toContain("KB");
  });
});

// ─── Truncate ─────────────────────────────────────────
describe("truncate", () => {
  it("returns empty for missing input", () => {
    expect(truncate()).toBe("");
    expect(truncate(null)).toBe("");
    expect(truncate("")).toBe("");
  });

  it("leaves short strings alone", () => {
    expect(truncate("hello")).toBe("hello");
    expect(truncate("a".repeat(60))).toBe("a".repeat(60));
  });

  it("appends an ellipsis past the limit", () => {
    const out = truncate("a".repeat(61));
    expect(out).toBe("a".repeat(60) + "…");
    expect(truncate("abcdef", 3)).toBe("abc…");
  });
});

// ─── Role label ───────────────────────────────────────
describe("roleLabel", () => {
  it("maps known roles to Arabic labels", () => {
    expect(roleLabel("super-admin")).toBe("مدير عام");
    expect(roleLabel("moderator")).toBe("مشرف");
  });

  it("defaults strangers to viewer", () => {
    expect(roleLabel("viewer")).toBe("مشاهد");
    expect(roleLabel("anything-else")).toBe("مشاهد");
    expect(roleLabel("")).toBe("مشاهد");
  });
});
