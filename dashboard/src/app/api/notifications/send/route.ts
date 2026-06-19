import { NextRequest, NextResponse } from "next/server";
import { adminMessaging } from "@/lib/firebase-admin";
import { requireRole } from "@/lib/auth";

export async function POST(request: NextRequest) {
  try {
    await requireRole("super-admin");
    const { title, body: msgBody, topic, tokens } = await request.json();

    const message: any = { notification: { title, body: msgBody } };
    if (topic) message.topic = topic;
    if (tokens && tokens.length > 0) {
      const response = await adminMessaging.sendEachForMulticast({
        tokens, notification: { title, body: msgBody },
      });
      return NextResponse.json({ success: true, sent: response.successCount, failed: response.failureCount });
    }

    const messageId = await adminMessaging.send(message);
    return NextResponse.json({ success: true, messageId });
  } catch (error: any) {
    return NextResponse.json({ error: error.message }, { status: 500 });
  }
}
