import { createServer } from "node:http";
import { createApp } from "./app.ts";
import { ConfigError, loadConfig } from "./config.ts";
import { openDb } from "./db.ts";
import { Ingestor } from "./ingest.ts";
import { Store } from "./store.ts";

let loaded;
try {
  loaded = loadConfig();
} catch (e) {
  console.error(`configuration error: ${e instanceof ConfigError ? e.message : String(e)}`);
  process.exit(1);
}
const { config, warnings } = loaded;
warnings.forEach((w) => console.warn(`warning: ${w}`));

const store = new Store(openDb(config.database));
await store.migrate();

const ingestor = new Ingestor(store);
ingestor.events.on("alert", (e) => {
  const a = e.alert;
  const where = a.location ? `${a.location.lat},${a.location.lon}` : "no fix";
  console.log(`[${e.type}] ${a.id} ${a.status} ${a.kind} device=${a.deviceName} at ${where} via ${a.channels.join("+")}`);
});

const server = createServer(
  createApp(store, ingestor, {
    environment: config.environment,
    responderApiKey: config.responderApiKey,
    ...(config.enrollToken ? { enrollToken: config.enrollToken } : {}),
    ...(config.smsWebhookToken ? { smsWebhookToken: config.smsWebhookToken } : {}),
  }),
);
server.listen(config.port, () =>
  console.log(`SOS backend (${config.environment}, ${store.db.dialect}) listening on :${config.port}`),
);

// Render sends SIGTERM before replacing an instance: finish in-flight requests, then close the pool.
for (const sig of ["SIGTERM", "SIGINT"] as const) {
  process.once(sig, () => {
    console.log(`${sig}: shutting down`);
    server.close(() => void store.close().finally(() => process.exit(0)));
    setTimeout(() => process.exit(0), 10_000).unref();
  });
}
