import { NextRequest, NextResponse } from "next/server";
import { requireRole } from "@/lib/auth";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { validateRemoteConfigParams } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const rc = getAdminRemoteConfig();
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
    const msg = error instanceof Error ? error.message : String(error);
    // Remote Config etag/version conflicts: concurrent edit lost, retry on fresh read.
    if (/etag|version.*match|already exists|out of date|conflict/i.test(msg)) {
      return NextResponse.json({ error: "تعارض مع تعديل آخر. أعد التحميل وحاول مجدداً." }, { status: 409 });
    }
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
    const rc = getAdminRemoteConfig();
    const template = await rc.getTemplate();

    for (const [key, value] of Object.entries(parameters)) {
      template.parameters[key] = {
        defaultValue: { value: String(value) },
        valueType: typeof value === "number" ? "NUMBER" : typeof value === "boolean" ? "BOOLEAN" : "STRING",
      };
    }

    await rc.publishTemplate(template);
    // Note: the fetched template carries its etag, so a concurrent publish
    // fails here instead of silently winning (last-write-wins avoided).
    return NextResponse.json({ success: true, etag: template.etag });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error, "فشل نشر الإعدادات");
    return NextResponse.json(body, { status });
  }
}
