export interface UserProfile {
  uid: string;
  username: string;
  avatarUrl?: string;
  badgeLabel?: string;
  role: "super-admin" | "moderator" | "viewer";
  isPublic: boolean;
  showListsPublic: boolean;
  showActivityPublic: boolean;
  bio: string;
  updatedAt: number;
  email?: string;
  lastSignIn?: string;
  favoriteCount?: number;
  historyCount?: number;
  readingTimeMs?: number;
}

export interface UserStats {
  totalPagesRead: number;
  totalChaptersRead: number;
  totalReadingTimeMs: number;
  favoriteCount: number;
  streakDays: number;
}
