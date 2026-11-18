import { createServer } from "node:http";
import { createApp } from "./app.ts";
import { Ingestor } from "./ingest.ts";
import { Store } from "./store.ts";

const env = process.env;
const responderApiKey = env.RESPONDER_API_KEY;
if (!responderApiKey) {
  console.error("RESPONDER_API_KEY must be set");
  process.exit(1);
}

const store = new Store(env.DATABASE_PATH ?? "sos.db");
const ingestor = new Ingestor(store);
ingestor.events.on("alert", (e) => {
  const a = e.alert;
  const where = a.location ? `${a.location.lat},${a.location.lon}` : "no fix";
  console.log(`[${e.type}] ${a.id} ${a.status} ${a.kind} device=${a.deviceName} at ${where} via ${a.channels.join("+")}`);
});

const handler = createApp(store, ingestor, {
  responderApiKey,
  ...(env.ENROLL_TOKEN ? { enrollToken: env.ENROLL_TOKEN } : {}),
  ...(env.SMS_WEBHOOK_TOKEN ? { smsWebhookToken: env.SMS_WEBHOOK_TOKEN } : {}),
});

const port = Number(env.PORT ?? 8080);
createServer(handler).listen(port, () => console.log(`SOS backend listening on :${port}`));
