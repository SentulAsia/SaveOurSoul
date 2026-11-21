import { randomBytes, randomUUID, timingSafeEqual } from "node:crypto";
import type { IncomingMessage, ServerResponse } from "node:http";
import { Channels, Ingestor } from "./ingest.ts";
import type { AlertEvent, Channel } from "./ingest.ts";
import { extractSmsFrames } from "./protocol.ts";
import type { AlertStatus, Store } from "./store.ts";

export interface AppConfig {
  /** Responders/dispatch consoles authenticate with this bearer key. */
  responderApiKey: string;
  /** If set, device enrollment requires `Authorization: Bearer <enrollToken>`. */
  enrollToken?: string;
  /** If set, the SMS gateway webhook must be called with `?token=<smsWebhookToken>`. */
  smsWebhookToken?: string;
  /** Max frames per /signals request (relays upload in batches). */
  maxBatch?: number;
  /** dev | qa | prod (reported by /healthz). */
  environment?: string;
  /** Unix-seconds clock, injectable for tests. */
  clock?: () => number;
}

const MAX_BODY = 64 * 1024;
const ALERT_STATUSES: AlertStatus[] = ["open", "acknowledged", "resolved", "cancelled"];

class HttpError extends Error {
  readonly status: number;
  constructor(status: number, message: string) {
    super(message);
    this.status = status;
  }
}

function safeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a);
  const bb = Buffer.from(b);
  return ab.length === bb.length && timingSafeEqual(ab, bb);
}

function bearer(req: IncomingMessage): string | null {
  const h = req.headers.authorization;
  return h?.startsWith("Bearer ") ? h.slice(7).trim() : null;
}

async function readBody(req: IncomingMessage): Promise<string> {
  const chunks: Buffer[] = [];
  let size = 0;
  for await (const chunk of req) {
    size += (chunk as Buffer).length;
    if (size > MAX_BODY) throw new HttpError(413, "body too large");
    chunks.push(chunk as Buffer);
  }
  return Buffer.concat(chunks).toString("utf8");
}

async function readJson(req: IncomingMessage): Promise<Record<string, unknown>> {
  const text = await readBody(req);
  if (!text) return {};
  try {
    const v: unknown = JSON.parse(text);
    if (typeof v !== "object" || v === null || Array.isArray(v)) throw new Error();
    return v as Record<string, unknown>;
  } catch {
    throw new HttpError(400, "invalid JSON body");
  }
}

function send(res: ServerResponse, status: number, body: unknown): void {
  const payload = JSON.stringify(body);
  res.writeHead(status, { "content-type": "application/json; charset=utf-8", "cache-control": "no-store" });
  res.end(payload);
}

function optString(v: unknown, field: string, max = 200): string | undefined {
  if (v === undefined || v === null) return undefined;
  if (typeof v !== "string" || v.length > max) throw new HttpError(400, `${field} must be a string of at most ${max} chars`);
  return v;
}

export function createApp(store: Store, ingestor: Ingestor, config: AppConfig) {
  const clock = config.clock ?? (() => Math.floor(Date.now() / 1000));
  const maxBatch = config.maxBatch ?? 50;

  function requireResponder(req: IncomingMessage, url: URL): void {
    const key = bearer(req) ?? url.searchParams.get("key");
    if (!key || !safeEqual(key, config.responderApiKey)) throw new HttpError(401, "responder API key required");
  }

  async function enroll(req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (config.enrollToken) {
      const t = bearer(req);
      if (!t || !safeEqual(t, config.enrollToken)) throw new HttpError(401, "enrollment token required");
    }
    const body = await readJson(req);
    const name = optString(body.name, "name", 100) ?? "Unnamed device";
    const phone = optString(body.phone, "phone", 32) ?? null;
    const id = randomUUID();
    const secret = randomBytes(32);
    await store.insertDevice({ id, secret, name, phone, createdAt: clock() });
    send(res, 201, { deviceId: id, secret: secret.toString("base64url") });
  }

  async function signals(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const body = await readJson(req);
    const frames = body.frames;
    if (!Array.isArray(frames) || frames.length === 0 || frames.length > maxBatch || !frames.every((f) => typeof f === "string")) {
      throw new HttpError(400, `frames must be a non-empty array of up to ${maxBatch} base64url strings`);
    }
    const channel = (body.channel ?? "internet") as Channel;
    if (!Channels.includes(channel)) throw new HttpError(400, `channel must be one of ${Channels.join(", ")}`);
    const relayDeviceId = optString(body.relayDeviceId, "relayDeviceId", 64) ?? null;
    const now = clock();
    const results = [];
    for (const f of frames as string[]) results.push(await ingestor.ingest(f, { channel, relayDeviceId, now }));
    send(res, 200, { results });
  }

  /** Inbound SMS webhook (Twilio-compatible form fields `From` and `Body`; JSON also accepted). */
  async function smsInbound(req: IncomingMessage, res: ServerResponse, url: URL): Promise<void> {
    if (config.smsWebhookToken) {
      const t = url.searchParams.get("token");
      if (!t || !safeEqual(t, config.smsWebhookToken)) throw new HttpError(401, "invalid webhook token");
    }
    const raw = await readBody(req);
    let from: string | undefined;
    let text: string | undefined;
    if ((req.headers["content-type"] ?? "").includes("application/json")) {
      let j: Record<string, unknown>;
      try {
        j = JSON.parse(raw || "{}") as Record<string, unknown>;
      } catch {
        throw new HttpError(400, "invalid JSON body");
      }
      from = optString(j.from ?? j.From, "from", 32);
      text = optString(j.body ?? j.Body, "body", 2000);
    } else {
      const p = new URLSearchParams(raw);
      from = p.get("From") ?? undefined;
      text = p.get("Body") ?? undefined;
    }
    const now = clock();
    const results = [];
    for (const f of extractSmsFrames(text ?? "")) results.push(await ingestor.ingest(f, { channel: "sms", smsFrom: from ?? null, now }));
    if (url.searchParams.get("format") === "json") {
      send(res, 200, { results });
      return;
    }
    // Empty TwiML: never auto-reply (replies could reveal the victim's phone is being used).
    res.writeHead(200, { "content-type": "text/xml" });
    res.end("<?xml version=\"1.0\" encoding=\"UTF-8\"?><Response></Response>");
  }

  function stream(req: IncomingMessage, res: ServerResponse): void {
    res.writeHead(200, {
      "content-type": "text/event-stream",
      "cache-control": "no-store",
      connection: "keep-alive",
    });
    res.write(": connected\n\n");
    const onAlert = (e: AlertEvent) => res.write(`event: ${e.type}\ndata: ${JSON.stringify(e.alert)}\n\n`);
    const ping = setInterval(() => res.write(": ping\n\n"), 25_000);
    ingestor.events.on("alert", onAlert);
    req.on("close", () => {
      clearInterval(ping);
      ingestor.events.off("alert", onAlert);
    });
  }

  async function route(req: IncomingMessage, res: ServerResponse): Promise<void> {
    const url = new URL(req.url ?? "/", "http://localhost");
    const path = url.pathname.replace(/\/+$/, "") || "/";
    const method = req.method ?? "GET";

    if (method === "GET" && path === "/healthz") {
      // Render health checks hit this; a database outage must show up as unhealthy.
      try {
        await store.db.ping();
        return send(res, 200, { ok: true, env: config.environment ?? "dev", db: store.db.dialect });
      } catch {
        return send(res, 503, { ok: false, env: config.environment ?? "dev", error: "database unreachable" });
      }
    }
    if (method === "POST" && path === "/api/v1/devices") return enroll(req, res);
    if (method === "POST" && path === "/api/v1/signals") return signals(req, res);
    if (method === "POST" && path === "/api/v1/sms/inbound") return smsInbound(req, res, url);

    // Device-facing status poll: the alert id is an unguessable 128-bit value only the sender knows.
    let m = path.match(/^\/api\/v1\/alerts\/([0-9a-f-]{36})\/status$/);
    if (method === "GET" && m) {
      const a = await store.getAlert(m[1]!);
      if (!a) throw new HttpError(404, "alert not found (yet)");
      return send(res, 200, { alertId: a.id, status: a.status, acknowledgedAt: a.acknowledgedAt, channels: a.channels });
    }

    // --- responder API ---
    if (path.startsWith("/api/v1/alerts")) {
      requireResponder(req, url);
      if (method === "GET" && path === "/api/v1/alerts/stream") return stream(req, res);
      if (method === "GET" && path === "/api/v1/alerts") {
        const statusParam = url.searchParams.get("status");
        const statuses = statusParam ? (statusParam.split(",") as AlertStatus[]) : undefined;
        if (statuses && !statuses.every((s) => ALERT_STATUSES.includes(s))) throw new HttpError(400, "invalid status filter");
        const limit = Math.min(Number(url.searchParams.get("limit") ?? 100) || 100, 500);
        return send(res, 200, { alerts: await store.listAlerts({ ...(statuses ? { status: statuses } : {}), limit }) });
      }
      m = path.match(/^\/api\/v1\/alerts\/([0-9a-f-]{36})$/);
      if (method === "GET" && m) {
        const a = await store.getAlert(m[1]!);
        if (!a) throw new HttpError(404, "alert not found");
        return send(res, 200, { alert: a, signals: await store.signalsForAlert(a.id) });
      }
      m = path.match(/^\/api\/v1\/alerts\/([0-9a-f-]{36})\/(ack|resolve)$/);
      if (method === "POST" && m) {
        const body = await readJson(req);
        const by = optString(body.by, "by", 100);
        const note = optString(body.note, "note", 2000);
        const a = await ingestor.transition(m[1]!, m[2] === "ack" ? "acknowledged" : "resolved", clock(), by, note);
        if (!a) throw new HttpError(404, "alert not found");
        return send(res, 200, { alert: a });
      }
    }

    throw new HttpError(404, "not found");
  }

  return async (req: IncomingMessage, res: ServerResponse): Promise<void> => {
    try {
      await route(req, res);
    } catch (e) {
      if (res.headersSent) {
        res.end();
        return;
      }
      if (e instanceof HttpError) return send(res, e.status, { error: e.message });
      console.error(e);
      send(res, 500, { error: "internal error" });
    }
  };
}
