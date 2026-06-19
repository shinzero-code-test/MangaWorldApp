import { NextRequest, NextResponse } from "next/server";
import { adminDb } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function GET() {
  try {
    await requireRole("super-admin");
    const template = await (await import("firebase-admin/app")).getApps()[0]
      ? (await import("firebase-admin/remote-config")).getRemoteConfig((await import("firebase-admin/app")).getApps()[0]).getTemplate()
      : null;

    if (!template) return NextResponse.json({ parameters: {} });

    const params: Record<string, any> = {};
    for (const [key, param] of Object.entries(template.parameters)) {
      params[key] = {
        defaultValue: param.defaultValue?.value || "",
        valueType: param.valueType,
      };
    }
    return NextResponse.json({ parameters: params });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { parameters } = await request.json();
    const { getApps } = await import("firebase-admin/app");
    const { getRemoteConfig } = await import("firebase-admin/remote-config");
    const rc = getRemoteConfig(getApps()[0]);
    const template = await rc.getTemplate();

    for (const [key, value] of Object.entries(parameters)) {
      template.parameters[key] = {
        defaultValue: { value: String(value) },
        valueType: typeof value === "number" ? "NUMBER" : typeof value === "boolean" ? "BOOLEAN" : "STRING",
      };
    }

    await rc.publishTemplate(template);
    return NextResponse.json({ success: true });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
