import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { validateRemoteConfigParams } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const { getApps } = await import("firebase-admin/app");
    const { getRemoteConfig } = await import("firebase-admin/remote-config");

    const app = getApps()[0];
    if (!app) return NextResponse.json({ parameters: {}, template: null });

    const rc = getRemoteConfig(app);
    const template = await rc.getTemplate();

    const params: Record<string, any> = {};
    for (const [key, param] of Object.entries(template.parameters)) {
      const def = param.defaultValue;
      const val = typeof def === "string" ? def : def && "value" in def ? String(def.value) : "";
      params[key] = {
        defaultValue: val,
        valueType: param.valueType,
        description: param.description || "",
      };
    }

    return NextResponse.json({
      parameters: params,
      template: {
        parameterCount: Object.keys(template.parameters).length,
        conditionCount: Object.keys(template.conditions ?? {}).length,
        etag: template.etag,
      },
    });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { parameters } = await request.json();
    // Config-injection guard before anything reaches the live template (M-6).
    const validation = validateRemoteConfigParams(parameters);
    if (!validation.ok) {
      return NextResponse.json({ error: validation.error }, { status: 400 });
    }
    const { getApps } = await import("firebase-admin/app");
    const { getRemoteConfig } = await import("firebase-admin/remote-config");

    const app = getApps()[0];
    if (!app) return NextResponse.json({ error: "Firebase not initialized" }, { status: 500 });

    const rc = getRemoteConfig(app);
    const template = await rc.getTemplate();

    for (const [key, value] of Object.entries(parameters)) {
      template.parameters[key] = {
        defaultValue: { value: String(value) },
        valueType: typeof value === "number" ? "NUMBER" : typeof value === "boolean" ? "BOOLEAN" : "STRING",
      };
    }

    await rc.publishTemplate(template);
    return NextResponse.json({ success: true, etag: template.etag });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error, "فشل نشر الإعدادات");
    return NextResponse.json(body, { status });
  }
}
