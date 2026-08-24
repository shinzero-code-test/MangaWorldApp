export type CommunityTargetType = "comment" | "review";

export interface ModerationReport {
  id: string;
  targetId: string;
  targetType: CommunityTargetType;
  mangaId: string;
  chapterUrl: string | null;
  reportedUid: string;
  reporterUid: string;
  reason: string;
  createdAt: number;
  status: "open" | "resolved" | "dismissed";
  priority: string | null;
}

export interface CommunityComment {
  id: string;
  mangaId: string;
  chapterUrl: string | null;
  slug: string;
  sourceId: string;
  parentId: string | null;
  threadRootId: string | null;
  reviewId: string | null;
  replyToUid: string | null;
  replyToUsername: string | null;
  authorUid: string;
  authorName: string;
  authorUsername: string | null;
  authorAvatarUrl: string | null;
  authorBadge: string | null;
  text: string;
  mentions: string[];
  spoiler: boolean;
  isDeleted: boolean;
  editedAt: number | null;
  reportedCount: number;
  createdAt: number;
  replyCount: number;
  likes: number;
  dislikes: number;
}

export interface MangaReview {
  id: string;
  mangaId: string;
  authorUid: string;
  authorName: string;
  authorUsername: string | null;
  authorAvatarUrl: string | null;
  authorBadge: string | null;
  rating: number;
  title: string;
  body: string;
  createdAt: number;
  updatedAt: number;
  replyCount: number;
  likes: number;
  dislikes: number;
  reportedCount: number;
  isDeleted: boolean;
}

export interface ChatMessage {
  id: string;
  roomId: string;
  authorUid: string;
  authorName: string;
  authorBadge?: string;
  text: string;
  createdAt: number;
}
