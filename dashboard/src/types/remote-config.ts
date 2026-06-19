export interface RemoteConfigParam {
  key: string;
  value: string | number | boolean;
  valueType: "string" | "number" | "boolean";
  description?: string;
}

export interface AnalyticsEvent {
  name: string;
  params?: Record<string, string | number>;
  timestamp?: string;
}

export interface CrashIssue {
  id: string;
  title: string;
  subtitle: string;
  count: number;
  firstOccurrence: string;
  lastOccurrence: string;
  appVersion?: string;
  osVersion?: string;
  device?: string;
  state?: string;
}
