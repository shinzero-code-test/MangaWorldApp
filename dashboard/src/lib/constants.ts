export const COLLECTIONS = {
  USERS: "users",
  PUBLIC_PROFILES: "publicProfiles",
  USERNAMES: "usernames",
  FAVORITES: "favorites",
  READING_HISTORY: "readingHistory",
  READER_ANNOTATIONS: "readerAnnotations",
  PREFERENCES: "preferences",
  DEVICES: "devices",
  NOTIFICATIONS: "notifications",
  LISTS: "lists",
  COMMUNITY_MANGA: "community_manga",
  COMMUNITY_PRESENCE: "community_presence",
  MODERATION_REPORTS: "moderationReports",
  USER_ACHIEVEMENTS: "user_achievements",
} as const;

export const MANGA_SOURCES = [
  { id: "olympus", name: "Olympus Staff", baseUrl: "https://olympustaff.com", requiresCloudflare: true },
  { id: "azora", name: "Azora Moon", baseUrl: "https://azoramoon.com", requiresCloudflare: false },
  { id: "starz", name: "Manga Starz", baseUrl: "https://manga-starz.net", requiresCloudflare: true },
  { id: "mangasid", name: "Manga Sid", baseUrl: "https://mangasid.com", requiresCloudflare: false },
  { id: "meshmanga", name: "Meshmanga", baseUrl: "https://meshmanga.com", requiresCloudflare: false },
] as const;

export const REMOTE_CONFIG_DEFAULTS: Record<string, string | number | boolean> = {
  source_olympus_enabled: true,
  source_azora_enabled: true,
  source_starz_enabled: true,
  source_mangasid_enabled: true,
  source_meshmanga_enabled: true,
  scraper_selector_overrides: "{}",
  scraper_connect_timeout_seconds: 30,
  scraper_read_timeout_seconds: 30,
  scraper_write_timeout_seconds: 15,
  scraper_retry_count: 1,
  home_layout_variant: "default",
  community_banned_keywords: "",
  remote_alert_message: "",
};
