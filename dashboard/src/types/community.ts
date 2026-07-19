export interface ModerationReport {
  id: string;
  commentId: string;
  mangaId: string;
  chapterUrl: string;
  reportedUid: string;
  reporterUid: string;
  reason: string;
  createdAt: number;
  status: "open" | "resolved" | "dismissed";
}

export interface CommunityComment {
  id: string;
  mangaId: string;
  chapterUrl: string;
  parentId?: string;
  authorUid: string;
  authorName: string;
  authorUsername?: string;
  authorAvatarUrl?: string;
  authorBadge?: string;
  text: string;
  mentions?: string[];
  spoiler: boolean;
  reportedCount: number;
  createdAt: number;
  replyCount: number;
}

export interface MangaReview {
  id: string;
  mangaId: string;
  authorUid: string;
  authorName: string;
  authorUsername?: string;
  authorAvatarUrl?: string;
  authorBadge?: string;
  rating: number;
  title: string;
  body: string;
  createdAt: number;
  updatedAt: number;
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
