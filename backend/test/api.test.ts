import assert from "node:assert/strict";
import { randomUUID } from "node:crypto";
import { createServer } from "node:http";
import type { AddressInfo } from "node:net";
import { after, before, test } from "node:test";
import { createApp } from "../src/app.ts";
import { openDb } from "../src/db.ts";
import { Ingestor } from "../src/ingest.ts";
import { Flags, encodeFrame, receiptFor } from "../src/protocol.ts";
import type { PanicFrame } from "../src/protocol.ts";
import { Store } from "../src/store.ts";

let now = 1_800_000_000;
const KEY = "responder-key";
// SQLite in memory by default; set TEST_DATABASE_URL=postgres://… to run the same suite on PostgreSQL.
const target = process.env.TEST_DATABASE_URL ?? ":memory:";
const store = new Store(openDb(target));
const ingestor = new Ingestor(store);
const server = createServer(createApp(store, ingestor, { responderApiKey: KEY, smsWebhookToken: "smstok", clock: () => now }));
let base = "";

before(async () => {
  if (store.db.dialect === "postgres") {
    await store.db.exec("DROP TABLE IF EXISTS receipts, signals, alerts, devices, schema_migrations CASCADE");
  }
  await store.migrate();
  await new Promise<void>((r) => server.listen(0, "127.0.0.1", r));
  base = `http://127.0.0.1:${(server.address() as AddressInfo).port}`;
});
after(async () => {
  server.closeAllConnections();
  server.close();
  await store.close();
});

async function api(method: string, path: string, body?: unknown, headers: Record<string, string> = {}) {
  const res = await fetch(base + path, {
    method,
    headers: { "content-type": "application/json", ...headers },
    ...(body !== undefined ? { body: typeof body === "string" ? body : JSON.stringify(body) } : {}),
  });
  const text = await res.text();
  let json: any;
  try {
    json = JSON.parse(text);
  } catch {
    json = text;
  }
  return { status: res.status, json };
}
const responder = { authorization: `Bearer ${KEY}` };

async function enroll() {
  const r = await api("POST", "/api/v1/devices", { name: "Test phone", phone: "+15550001" });
  assert.equal(r.status, 201);
  return { deviceId: r.json.deviceId as string, secret: Buffer.from(r.json.secret, "base64url") };
}

function frame(dev: { deviceId: string; secret: Buffer }, over: Partial<PanicFrame> = {}): string {
  const f: PanicFrame = {
    version: 1,
    flags: Flags.HAS_LOCATION,
    kind: 0,
    hops: 0,
    frameId: randomUUID(),
    alertId: randomUUID(),
    deviceId: dev.deviceId,
    timestamp: now,
    latE7: 319_539_000,
    lonE7: 359_106_000,
    accuracyM: 10,
    batteryPct: 80,
    ...over,
  };
  return encodeFrame(f, dev.secret).toString("base64url");
}

test("panic over internet creates an open alert visible to responders", async () => {
  const dev = await enroll();
  const alertId = randomUUID();
  const r = await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId })] });
  assert.equal(r.status, 200);
  assert.equal(r.json.results[0].status, "accepted");
  assert.equal(r.json.results[0].alertStatus, "open");
  // The receipt is keyed with the device secret, so only the sender can verify it.
  assert.equal(r.json.results[0].receipt, receiptFor(r.json.results[0].frameId, dev.secret).toString("base64url"));

  const list = await api("GET", "/api/v1/alerts", undefined, responder);
  const a = list.json.alerts.find((x: any) => x.id === alertId);
  assert.ok(a);
  assert.equal(a.deviceName, "Test phone");
  assert.deepEqual(a.channels, ["internet"]);
  assert.ok(Math.abs(a.location.lat - 31.9539) < 1e-6);
});

test("the same frame arriving via SMS and a Bluetooth relay is deduplicated but all paths are recorded", async () => {
  const dev = await enroll();
  const alertId = randomUUID();
  const f = frame(dev, { alertId });

  const sms = await api("POST", "/api/v1/sms/inbound?token=smstok", `From=%2B15550001&Body=${encodeURIComponent("SOS1:" + f)}`, {
    "content-type": "application/x-www-form-urlencoded",
  });
  assert.equal(sms.status, 200);
  assert.match(sms.json, /<Response>/);

  // A stranger's phone picked the frame up over Bluetooth, bumped hops, and uploaded it.
  const relayed = Buffer.from(f, "base64url");
  relayed[3] = 2;
  const r = await api("POST", "/api/v1/signals", { frames: [relayed.toString("base64url")], channel: "bluetooth", relayDeviceId: "relay-1" });
  assert.equal(r.json.results[0].status, "duplicate");

  const detail = await api("GET", `/api/v1/alerts/${alertId}`, undefined, responder);
  assert.deepEqual(detail.json.alert.channels, ["bluetooth", "sms"]);
  assert.equal(detail.json.signals.length, 1);
  assert.equal(detail.json.signals[0].receipts.length, 2);
});

test("rejects forged, unknown, stale and future frames", async () => {
  const dev = await enroll();
  const forged = { deviceId: dev.deviceId, secret: Buffer.alloc(32, 7) };
  const unknown = { deviceId: randomUUID(), secret: dev.secret };
  const r = await api("POST", "/api/v1/signals", {
    frames: [frame(forged), frame(unknown), frame(dev, { timestamp: now - 80 * 3600 }), frame(dev, { timestamp: now + 3600 }), "!!", "AAAA"],
  });
  assert.deepEqual(
    r.json.results.map((x: any) => x.reason ?? x.status),
    ["bad_signature", "unknown_device", "stale", "timestamp_in_future", "frame is not base64url", "frame must be 83 bytes, got 3"],
  );
});

test("location updates keep the newest fix even when frames arrive out of order", async () => {
  const dev = await enroll();
  const alertId = randomUUID();
  await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId, timestamp: now - 10, latE7: 100_000_000 })] });
  await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId, timestamp: now - 120, latE7: 50_000_000 })], channel: "mesh" });
  const d = await api("GET", `/api/v1/alerts/${alertId}`, undefined, responder);
  assert.equal(d.json.alert.location.lat, 10);
  assert.equal(d.json.alert.triggeredAt, now - 120);
});

test("device cancel, responder ack/resolve and device status polling", async () => {
  const dev = await enroll();
  const a1 = randomUUID();
  await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId: a1 })] });
  assert.equal((await api("GET", `/api/v1/alerts/${a1}/status`)).json.status, "open");

  const ack = await api("POST", `/api/v1/alerts/${a1}/ack`, { by: "Dispatcher 7" }, responder);
  assert.equal(ack.json.alert.status, "acknowledged");
  assert.equal(ack.json.alert.acknowledgedBy, "Dispatcher 7");
  assert.equal((await api("GET", `/api/v1/alerts/${a1}/status`)).json.status, "acknowledged");
  const res = await api("POST", `/api/v1/alerts/${a1}/resolve`, { note: "safe" }, responder);
  assert.equal(res.json.alert.status, "resolved");

  const a2 = randomUUID();
  await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId: a2 })] });
  const c = await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId: a2, flags: Flags.CANCEL })] });
  assert.equal(c.json.results[0].alertStatus, "cancelled");
});

test("an alert id cannot be hijacked by another device", async () => {
  const a = await enroll();
  const b = await enroll();
  const alertId = randomUUID();
  await api("POST", "/api/v1/signals", { frames: [frame(a, { alertId })] });
  const r = await api("POST", "/api/v1/signals", { frames: [frame(b, { alertId, flags: Flags.CANCEL })] });
  assert.equal(r.json.results[0].reason, "alert_device_mismatch");
});

test("responder endpoints require the API key; SMS webhook requires its token", async () => {
  assert.equal((await api("GET", "/api/v1/alerts")).status, 401);
  assert.equal((await api("GET", "/api/v1/alerts", undefined, { authorization: "Bearer nope" })).status, 401);
  assert.equal((await api("POST", "/api/v1/sms/inbound", "Body=x", { "content-type": "application/x-www-form-urlencoded" })).status, 401);
});

test("SSE stream pushes new alerts to dispatch consoles", async () => {
  const dev = await enroll();
  const ctrl = new AbortController();
  const res = await fetch(`${base}/api/v1/alerts/stream?key=${KEY}`, { signal: ctrl.signal });
  const reader = res.body!.getReader();
  await reader.read(); // ": connected"
  const alertId = randomUUID();
  await api("POST", "/api/v1/signals", { frames: [frame(dev, { alertId })] });
  let buf = "";
  while (!buf.includes(alertId)) buf += new TextDecoder().decode((await reader.read()).value);
  assert.match(buf, /event: alert.created/);
  ctrl.abort();
});

test(`[${store.db.dialect}] health check reports the database`, async () => {
  const r = await api("GET", "/healthz");
  assert.equal(r.status, 200);
  assert.equal(r.json.db, store.db.dialect);
});

test(`[${store.db.dialect}] 20 relays uploading the same frame at once yield one accept and 19 duplicates`, async () => {
  const dev = await enroll();
  const alertId = randomUUID();
  const f = frame(dev, { alertId });
  const results = await Promise.all(
    Array.from({ length: 20 }, (_, i) => api("POST", "/api/v1/signals", { frames: [f], channel: "mesh", relayDeviceId: `relay-${i}` })),
  );
  const statuses = results.map((r) => r.json.results[0].status).sort();
  assert.equal(statuses.filter((s) => s === "accepted").length, 1);
  assert.equal(statuses.filter((s) => s === "duplicate").length, 19);
  const detail = await api("GET", `/api/v1/alerts/${alertId}`, undefined, responder);
  assert.equal(detail.json.signals[0].receipts.length, 20);
});

test(`[${store.db.dialect}] migrations are idempotent`, async () => {
  await store.migrate();
  const row = await store.db.get("SELECT COUNT(*) AS n FROM schema_migrations");
  assert.equal(Number(row!.n), 1);
});
