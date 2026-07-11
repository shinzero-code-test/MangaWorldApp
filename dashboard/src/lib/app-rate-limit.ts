type RateLimit = { count: number; resetAt: number };

const limits = new Map<string, RateLimit>();

export function allowAppMutation(key: string, limit: number, windowMs: number): boolean {
  const now = Date.now();
  const current = limits.get(key);
  if (!current || current.resetAt <= now) {
    limits.set(key, { count: 1, resetAt: now + windowMs });
    return true;
  }
  if (current.count >= limit) return false;
  current.count += 1;
  return true;
}
