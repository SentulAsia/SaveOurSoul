/**
 * Simulates a phone: enrolls (or reuses) a device, then sends a panic frame over HTTP and
 * prints the SMS text that the same frame would be sent as.
 *
 *   npm run simulate -- http://localhost:8080 [--kind medical] [--sms-only] [--cancel <alertId>]
 */
import { randomUUID } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";
import { Flags, Kinds, SMS_PREFIX, encodeFrame } from "../src/protocol.ts";

const args = process.argv.slice(2);
const base = args.find((a) => a.startsWith("http")) ?? "http://localhost:8080";
const opt = (name: string) => {
  const i = args.indexOf(name);
  return i >= 0 ? args[i + 1] : undefined;
};
const credFile = ".simulated-device.json";

let cred: { deviceId: string; secret: string };
if (existsSync(credFile)) {
  cred = JSON.parse(readFileSync(credFile, "utf8"));
} else {
  const r = await fetch(`${base}/api/v1/devices`, {
    method: "POST",
    headers: { "content-type": "application/json", ...(process.env.ENROLL_TOKEN ? { authorization: `Bearer ${process.env.ENROLL_TOKEN}` } : {}) },
    body: JSON.stringify({ name: "Simulated phone", phone: "+10000000000" }),
  });
  if (!r.ok) throw new Error(`enroll failed: ${r.status} ${await r.text()}`);
  cred = await r.json();
  writeFileSync(credFile, JSON.stringify(cred));
  console.log("enrolled device", cred.deviceId);
}

const cancel = opt("--cancel");
const kind = Math.max(0, Kinds.indexOf((opt("--kind") ?? "general") as (typeof Kinds)[number]));
const alertId = cancel ?? randomUUID();
const frame = encodeFrame(
  {
    version: 1,
    flags: cancel ? Flags.CANCEL : Flags.HAS_LOCATION,
    kind,
    hops: 0,
    frameId: randomUUID(),
    alertId,
    deviceId: cred.deviceId,
    timestamp: Math.floor(Date.now() / 1000),
    latE7: 319_539_000,
    lonE7: 359_106_000,
    accuracyM: 15,
    batteryPct: 42,
  },
  Buffer.from(cred.secret, "base64url"),
).toString("base64url");

console.log("alertId:", alertId);
console.log("SMS body:", SMS_PREFIX + frame);
if (!args.includes("--sms-only")) {
  const r = await fetch(`${base}/api/v1/signals`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ frames: [frame], channel: "internet" }),
  });
  console.log("backend:", r.status, JSON.stringify(await r.json()));
}
