/** Regenerates ../protocol/test-vectors.json, shared by the backend and Android test suites. */
import { writeFileSync } from "node:fs";
import { Flags, SMS_PREFIX, encodeFrame, receiptFor, toBase64Url } from "../src/protocol.ts";
import type { PanicFrame } from "../src/protocol.ts";

const secret = Buffer.from("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", "hex");
const base: PanicFrame = {
  version: 1,
  flags: Flags.HAS_LOCATION,
  kind: 1,
  hops: 0,
  frameId: "11111111-2222-4333-8444-555555555555",
  alertId: "aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee",
  deviceId: "01234567-89ab-4cde-8f01-23456789abcd",
  timestamp: 1_790_000_000,
  latE7: 319_539_000,
  lonE7: 357_900_000,
  accuracyM: 12,
  batteryPct: 57,
};
const cases: Array<{ name: string; frame: PanicFrame }> = [
  { name: "medical with location", frame: base },
  { name: "southern/western hemisphere", frame: { ...base, latE7: -338_688_000, lonE7: -1_512_093_000, kind: 4 } },
  { name: "relayed 3 hops (hops not signed)", frame: { ...base, hops: 3 } },
  { name: "lost pilgrim (kind 5)", frame: { ...base, kind: 5, frameId: "55555555-2222-4333-8444-555555555555", latE7: 214_133_000, lonE7: 399_840_000 } },
  {
    name: "cancel without location",
    frame: { ...base, flags: Flags.CANCEL, frameId: "99999999-2222-4333-8444-555555555555", latE7: 0, lonE7: 0, accuracyM: 0xffff, batteryPct: 0xff },
  },
];
const out = {
  secretHex: secret.toString("hex"),
  vectors: cases.map(({ name, frame }) => {
    const b64 = toBase64Url(encodeFrame(frame, secret));
    return { name, frame, base64url: b64, sms: SMS_PREFIX + b64, receipt: receiptFor(frame.frameId, secret).toString("base64url") };
  }),
};
writeFileSync(new URL("../../protocol/test-vectors.json", import.meta.url), JSON.stringify(out, null, 2) + "\n");
console.log(`wrote ${out.vectors.length} vectors`);
