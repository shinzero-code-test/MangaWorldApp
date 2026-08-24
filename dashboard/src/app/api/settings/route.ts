import { NextRequest, NextResponse } from "next/server";
import { getAdminRemoteConfig } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";
import { validateRemoteConfigParams } from "@/lib/validate";
import { genericErrorResponse } from "@/lib/security";

export const dynamic = 'force-dynamic';

export async function GET() {
  try {
    await requireRole("super-admin");
    const template = await getAdminRemoteConfig().getTemplate();
    const settings: Record<string, any> = {};
    for (const [key, param] of Object.entries(template.parameters)) {
      if (param.defaultValue && 'value' in param.defaultValue) {
        let val: any = param.defaultValue.value;
        if (val === "true" || val === "false") val = val === "true";
        else if (!isNaN(Number(val)) && val.trim() !== "") val = Number(val);
        settings[key] = val;
      }
    }
    return NextResponse.json({ settings });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error);
    return NextResponse.json(body, { status });
  }
}

export async function PUT(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { settings } = await request.json();
    // Config-injection guard: known key charset, primitive values, bounded count/size.
    const validation = validateRemoteConfigParams(settings);
    if (!validation.ok) {
      return NextResponse.json({ error: validation.error }, { status: 400 });
    }
    const template = await getAdminRemoteConfig().getTemplate();

    for (const [key, value] of Object.entries(settings)) {
      if (!template.parameters) template.parameters = {};
      template.parameters[key] = {
        defaultValue: { value: String(value) },
        valueType: typeof value === "boolean" ? "BOOLEAN" : typeof value === "number" ? "NUMBER" : "STRING"
      };
    }

    await getAdminRemoteConfig().publishTemplate(template);
    return NextResponse.json({ success: true });
  } catch (error: unknown) {
    const { body, status } = genericErrorResponse(error, "فشل حفظ الإعدادات");
    return NextResponse.json(body, { status });
  }
}
