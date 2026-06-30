export const COLLECTIONS = {
  USERS:    "publicProfiles",
  MANGA:    "manga",
  COMMENTS: "comments",
  REVIEWS:  "reviews",
  REPORTS:  "reports",
  RELEASES: "releases",
  SETTINGS: "appSettings",
  NOTIF_HISTORY: "notificationHistory",
} as const;

export const ROLES = ["super-admin", "moderator", "viewer"] as const;

export const APP_NAME = "MangaWorld";

export const FCM_TOPICS = {
  GENERAL:     "general",
  UPDATES:     "updates",
  MAINTENANCE: "maintenance",
} as const;

export const SESSION_COOKIE_MAX_AGE = 60 * 60 * 24 * 7; // 7 days in seconds
