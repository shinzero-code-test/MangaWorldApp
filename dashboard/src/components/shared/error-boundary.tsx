"use client";

import React from "react";
import { AlertTriangle, RefreshCcw } from "lucide-react";

interface State {
  hasError: boolean;
  error?: Error;
}

export class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  State
> {
  constructor(props: { children: React.ReactNode }) {
    super(props);
    this.state = { hasError: false };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  render() {
    if (this.state.hasError) {
      return (
        <div className="flex flex-col items-center justify-center min-h-[400px] gap-4 p-8">
          <div className="w-14 h-14 rounded-2xl bg-destructive/10 flex items-center justify-center">
            <AlertTriangle size={24} className="text-destructive" style={{ color: "var(--destructive)" }} />
          </div>
          <div className="text-center">
            <p className="font-semibold text-base">حدث خطأ غير متوقع</p>
            <p className="text-sm mt-1" style={{ color: "var(--muted-foreground)" }}>
              {this.state.error?.message || "يرجى إعادة تحميل الصفحة"}
            </p>
          </div>
          <button
            onClick={() => window.location.reload()}
            className="flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition"
            style={{
              background: "var(--primary)",
              color: "var(--primary-foreground)",
            }}
          >
            <RefreshCcw size={14} />
            إعادة تحميل
          </button>
        </div>
      );
    }

    return this.props.children;
  }
}
