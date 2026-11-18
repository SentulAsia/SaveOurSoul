# SaveOurSoul — multi-channel SOS

Press and hold the SOS button. The phone then tries **every channel it has at the same time**
to get a panic signal to the backend:

```
                    ┌──────────── Wi-Fi ───────────────┐
                    ├──────────── Cellular data ───────┤   HTTPS
  ┌──────────┐      ├──── forced cellular (Wi-Fi with  ├──────────────┐
  │  Victim  │──────┤       no internet / captive portal)              │
  │  phone   │      │                                                  ▼
  └──────────┘      ├──── SMS ──► SMS gateway (Twilio…) ──webhook──► ┌─────────┐
       │            │                                                │ Backend │──► dispatch console
       │            └──── SMS ──► personal emergency contacts        │   API   │    (REST + live SSE)
       │                         (plain text + map link)             └─────────┘
       │ Bluetooth / BLE / Wi-Fi Direct (Nearby Connections)               ▲
       ▼                                                                   │
  ┌──────────┐   ┌──────────┐                                              │
  │ stranger │──►│ stranger │── as soon as any phone in the chain is online┘
  │  phone   │   │  phone   │   (store-and-forward mesh, up to 8 hops)
  └──────────┘   └──────────┘
```

Every channel carries the same **83-byte signed frame**. The backend therefore trusts a frame
whichever route it took, de-duplicates it, and records every path it arrived on. The full
spec is in [docs/PROTOCOL.md](docs/PROTOCOL.md).

## Repository layout

| Path | What |
|---|---|
| `backend/` | Node.js 22 + TypeScript API. Zero runtime dependencies; SQLite through `node:sqlite`. |
| `android/core/` | Pure Kotlin: frame codec, relay policy, persistent outbox. Unit-tested on the JVM. |
| `android/app/` | Android app (Kotlin, Jetpack Compose, Nearby Connections, WorkManager). |
| `protocol/test-vectors.json` | Shared vectors that keep the Kotlin and TypeScript codecs byte-compatible. |

## Backend

```bash
cd backend
npm install
RESPONDER_API_KEY=change-me npm start      # listens on :8080, stores data in ./sos.db
npm test                                    # 14 tests: protocol, API, dedupe, auth, SSE
npm run simulate -- http://localhost:8080 --kind medical   # pretend to be a phone
```

| Env var | Purpose |
|---|---|
| `RESPONDER_API_KEY` | **Required.** Bearer key for the dispatch/responder API. |
| `ENROLL_TOKEN` | Optional. If set, phones must present it to register. |
| `SMS_WEBHOOK_TOKEN` | Optional. If set, the SMS webhook URL must include `?token=…`. |
| `DATABASE_PATH`, `PORT` | Defaults: `sos.db`, `8080`. |

### API

| Method & path | Who | Purpose |
|---|---|---|
| `POST /api/v1/devices` | phone (once) | Register. Returns `deviceId` and an HMAC `secret`. |
| `POST /api/v1/signals` | phone / relay | Up to 50 base64url frames + `channel`. Per-frame result: `accepted` / `duplicate` / `rejected`. |
| `POST /api/v1/sms/inbound` | SMS gateway | Twilio-style webhook (`From`, `Body`). Pulls every `SOS1:` frame out of the text. |
| `GET /api/v1/alerts/{id}/status` | phone | Poll for "a responder acknowledged you". |
| `GET /api/v1/alerts?status=open,acknowledged` | responder | List alerts with latest location, battery and channels used. |
| `GET /api/v1/alerts/{id}` | responder | Alert detail, with the location trail and every delivery receipt. |
| `POST /api/v1/alerts/{id}/ack` / `resolve` | responder | `{ "by": "Dispatcher 7", "note": "…" }` |
| `GET /api/v1/alerts/stream` | responder | Server-Sent Events: `alert.created`, `alert.updated`. |

**SMS gateway setup (Twilio example):** buy a number and set its *"A message comes in"*
webhook to `https://your-host/api/v1/sms/inbound?token=$SMS_WEBHOOK_TOKEN`. Then enter that
number in the app as the *SMS gateway number*. Any provider that can POST `From` and `Body`
will work.

## Android app

Open `android/` in Android Studio, or run `cd android && ./gradlew :app:assembleDebug` with
the Android SDK installed. `./gradlew :core:test` runs without an SDK. For an emulator talking
to a local backend, use the server URL `http://10.0.2.2:8080`.

How an SOS goes out (`SosEngine`):

1. **Hold the button for 1.5 s.** A 5-second cancellable countdown follows, to guard against pocket triggers.
2. The frame is written to a **durable outbox** first. It survives the app being killed or the phone rebooting.
3. **Internet:** the default network, then every other network, then *forced cellular*.
4. **Bluetooth / Wi-Fi mesh:** Google Nearby Connections (`P2P_CLUSTER`) advertises and
   discovers at the same time, picks BT, BLE, Wi-Fi hotspot or Wi-Fi Direct on its own, and
   pushes the frame to every nearby phone running the app.
5. **SMS:** personal contacts get a text with a Google Maps link straight away. The signed
   frame goes to the SMS gateway if the backend hasn't confirmed within the delay (15 s by default).
6. While the SOS is active: a fresh GPS frame goes out every 60 s, retries every 10 s, and the
   app polls whether a responder acknowledged. It stops when the alert is resolved or the user
   confirms "I'm safe" (which sends a signed cancel over all channels).

**Relay mode** (on by default) keeps a low-priority foreground service listening, so
*your* phone can carry a stranger's SOS. Received frames go into the outbox and are passed on
to other peers (hop count + 1). They are uploaded the moment any network appears; a
WorkManager job every 15 minutes acts as a backstop.

### Platform notes / known limits

* **iOS** is not included. iOS does not let apps send SMS silently, and it heavily limits
  background Bluetooth advertising, so a phone-to-phone mesh relay would need a different design
  (for example Multipeer Connectivity, used only in the foreground).
* **Google Play policy** limits `SEND_SMS`. Sideloaded and enterprise (MDM) builds are fine.
  For Play Store distribution you would need the policy exception or would have to fall back to
  `ACTION_SENDTO` (user taps send).
* Nearby Connections needs Google Play services. On devices without them (e.g. some AOSP
  builds) the mesh channel is off; internet and SMS still work.
* The relay foreground service costs some battery. Users can turn relay mode off in settings.

## Next steps worth considering

* A web dispatch console on top of `/alerts` and the SSE stream (map, ack/resolve buttons).
* Push notifications (FCM) to responders/contacts, and an ack channel back to the victim over SMS.
* Hardware triggers: power-button ×5, a Quick Settings tile, a Wear OS button.
* Rate limiting / abuse protection on `/signals` and `/devices` at the reverse proxy.
* Frame encryption for location privacy from relays (see the protocol doc).
* Postgres instead of SQLite if you need more than one backend instance.
