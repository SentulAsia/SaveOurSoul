import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { test } from "node:test";
import { FRAME_LENGTH, decodeFrame, encodeFrame, extractSmsFrames, fromBase64Url, receiptFor, verifyFrame } from "../src/protocol.ts";
import type { PanicFrame } from "../src/protocol.ts";

const vectors = JSON.parse(readFileSync(new URL("../../protocol/test-vectors.json", import.meta.url), "utf8")) as {
  secretHex: string;
  vectors: Array<{ name: string; frame: PanicFrame; base64url: string; sms: string; receipt: string }>;
};
const secret = Buffer.from(vectors.secretHex, "hex");

for (const v of vectors.vectors) {
  test(`vector: ${v.name}`, () => {
    const encoded = encodeFrame(v.frame, secret);
    assert.equal(encoded.length, FRAME_LENGTH);
    assert.equal(encoded.toString("base64url"), v.base64url);
    assert.deepEqual(decodeFrame(fromBase64Url(v.base64url)), v.frame);
    assert.ok(verifyFrame(encoded, secret));
    assert.ok(v.sms.length <= 160, "must fit in a single SMS");
    assert.equal(receiptFor(v.frame.frameId, secret).toString("base64url"), v.receipt);
  });
}

test("hop counter is not covered by the MAC, everything else is", () => {
  const b = fromBase64Url(vectors.vectors[0]!.base64url);
  b[3] = 5;
  assert.ok(verifyFrame(b, secret), "relays may bump hops");
  for (const idx of [0, 1, 2, 10, 30, 50, 55, 60, 64, 66, 70]) {
    const t = fromBase64Url(vectors.vectors[0]!.base64url);
    t[idx] = t[idx]! ^ 0x01;
    assert.ok(!verifyFrame(t, secret), `tampering byte ${idx} must break the MAC`);
  }
  assert.ok(!verifyFrame(fromBase64Url(vectors.vectors[0]!.base64url), Buffer.alloc(32)));
});

test("extracts multiple frames from an SMS body", () => {
  const body = `${vectors.vectors[0]!.sms}\nhelp ${vectors.vectors[4]!.sms}`;
  assert.deepEqual(extractSmsFrames(body), [vectors.vectors[0]!.base64url, vectors.vectors[4]!.base64url]);
  assert.deepEqual(extractSmsFrames("hello"), []);
});
