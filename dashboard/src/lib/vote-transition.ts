/**
 * Pure vote-transition math for POST /api/community/vote.
 *
 * Three outcomes:
 * - `added`    — first vote on the content (counter +1).
 * - `switched` — opposite vote replaces the previous one (one counter -1,
 *   the other +1).
 * - `removed`  — repeat of the current vote retracts it (toggle-off,
 *   counter -1, vote doc deleted). A double-tap must unvote, never no-op.
 *
 * No Firestore/network/env access — safe to run in plain vitest.
 */

export type VoteValue = 1 | -1;
export type VoteAction = "added" | "switched" | "removed";

export interface VoteTransition {
  likes: number;
  dislikes: number;
  action: VoteAction;
  /** Kept for response compatibility: every valid vote changes the counts. */
  changed: boolean;
  /** True only for `removed` — the caller must delete the vote doc. */
  removeVoteDoc: boolean;
}

export function computeVoteTransition(
  previousVote: unknown,
  nextVote: VoteValue,
  likes: unknown,
  dislikes: unknown
): VoteTransition {
  const baseLikes = nonNegativeCount(likes);
  const baseDislikes = nonNegativeCount(dislikes);
  if (previousVote === nextVote) {
    if (nextVote === 1) {
      return { likes: Math.max(0, baseLikes - 1), dislikes: baseDislikes, action: "removed", changed: true, removeVoteDoc: true };
    }
    return { likes: baseLikes, dislikes: Math.max(0, baseDislikes - 1), action: "removed", changed: true, removeVoteDoc: true };
  }
  return {
    likes: Math.max(0, baseLikes + voteDelta(previousVote, nextVote, 1)),
    dislikes: Math.max(0, baseDislikes + voteDelta(previousVote, nextVote, -1)),
    action: isVote(previousVote) ? "switched" : "added",
    changed: true,
    removeVoteDoc: false,
  };
}

export function isVote(value: unknown): value is VoteValue {
  return value === 1 || value === -1;
}

export function nonNegativeCount(value: unknown): number {
  return typeof value === "number" && Number.isFinite(value) && value > 0 ? Math.floor(value) : 0;
}

function voteDelta(previousVote: unknown, nextVote: VoteValue, targetVote: VoteValue): number {
  return Number(previousVote === targetVote) * -1 + Number(nextVote === targetVote);
}
