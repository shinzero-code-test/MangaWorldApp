"use client";

import React from "react";

interface Props {
  children: React.ReactNode;
  fallback?: React.ReactNode;
}

interface State {
  hasError: boolean;
  error: Error | null;
}

export class ErrorBoundary extends React.Component<Props, State> {
  constructor(props: Props) {
    super(props);
    this.state = { hasError: false, error: null };
  }

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, errorInfo: React.ErrorInfo) {
    console.error("Dashboard Error:", error, errorInfo);
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback || (
        <div className="min-h-[200px] flex items-center justify-center p-8 bg-[var(--card)] rounded-xl border border-[var(--border)]">
          <div className="text-center">
            <span className="text-4xl block mb-3">⚠️</span>
            <h3 className="text-lg font-semibold mb-2">حدث خطأ</h3>
            <p className="text-sm text-[var(--muted-foreground)] mb-4">
              {this.state.error?.message || "خطأ غير متوقع"}
            </p>
            <button
              onClick={() => this.setState({ hasError: false, error: null })}
              className="px-4 py-2 rounded-lg bg-[var(--primary)] text-[var(--primary-foreground)] text-sm hover:opacity-90"
            >
              إعادة المحاولة
            </button>
          </div>
        </div>
      );
    }

    return this.props.children;
  }
}
