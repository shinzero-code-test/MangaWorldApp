import { describe, expect, it } from "vitest";
import { computeVoteTransition } from "./vote-transition";

// Vote-toggle contract: double-tap retracts, cross-vote switches.
// No Firestore/network/env access — safe to run in plain vitest.

describe("computeVoteTransition", () => {
  it("adds the first like and dislike from zero", () => {
    expect(computeVoteTransition(undefined, 1, 0, 0)).toMatchObject({ likes: 1, dislikes: 0, action: "added", removeVoteDoc: false });
    expect(computeVoteTransition(undefined, -1, 0, 0)).toMatchObject({ likes: 0, dislikes: 1, action: "added", removeVoteDoc: false });
  });

  it("retracts a repeated like (toggle-off)", () => {
    const t = computeVoteTransition(1, 1, 6, 2);
    expect(t).toMatchObject({ likes: 5, dislikes: 2, action: "removed", changed: true, removeVoteDoc: true });
  });

  it("retracts a repeated dislike (toggle-off)", () => {
    const t = computeVoteTransition(-1, -1, 6, 2);
    expect(t).toMatchObject({ likes: 6, dislikes: 1, action: "removed", changed: true, removeVoteDoc: true });
  });

  it("switches like to dislike", () => {
    const t = computeVoteTransition(1, -1, 6, 2);
    expect(t).toMatchObject({ likes: 5, dislikes: 3, action: "switched", removeVoteDoc: false });
  });

  it("switches dislike to like", () => {
    const t = computeVoteTransition(-1, 1, 6, 2);
    expect(t).toMatchObject({ likes: 7, dislikes: 1, action: "switched", removeVoteDoc: false });
  });

  it("never drives counters negative", () => {
    expect(computeVoteTransition(1, 1, 0, 0).likes).toBe(0);
    expect(computeVoteTransition(-1, -1, 0, 0).dislikes).toBe(0);
    expect(computeVoteTransition(1, -1, 0, 0)).toMatchObject({ likes: 0, dislikes: 1 });
  });

  it("treats corrupt stored values as no previous vote", () => {
    expect(computeVoteTransition("1", 1, 4, 1)).toMatchObject({ likes: 5, dislikes: 1, action: "added" });
    expect(computeVoteTransition(null, -1, 4, 1)).toMatchObject({ likes: 4, dislikes: 2, action: "added" });
  });

  it("sanitizes corrupt counters", () => {
    expect(computeVoteTransition(undefined, 1, -3, NaN)).toMatchObject({ likes: 1, dislikes: 0 });
  });
});
