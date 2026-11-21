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
| `backend/` | Node.js 22 + TypeScript API. PostgreSQL (`pg`) when deployed, built-in SQLite for local development. |
| `render.yaml` | Render Blueprint for the qa and prod services. |
| `android/core/` | Pure Kotlin: frame codec, relay policy, persistent outbox. Unit-tested on the JVM. |
| `android/app/` | Android app (Kotlin, Jetpack Compose, Nearby Connections, WorkManager). |
| `ios/SOSCore/` | Swift package: frame codec, relay policy, persistent outbox. `swift test` checks the shared vectors. |
| `ios/SaveOurSoul/` | iOS app (Swift, SwiftUI, Nearby Connections, Core Location, App Intents). |
| `protocol/test-vectors.json` | Shared vectors that keep the Kotlin and TypeScript codecs byte-compatible. |

## Backend

```bash
cd backend
npm install
RESPONDER_API_KEY=change-me npm start      # APP_ENV=dev: listens on :8080, data in ./sos.db (SQLite)
npm test                                    # protocol, config and API tests on SQLite
TEST_DATABASE_URL=postgres://… npm run test:pg   # same API suite on PostgreSQL (drops tables!)
npm run simulate -- http://localhost:8080 --kind lost   # pretend to be a phone
```

Three environments: **dev** runs on your computer with SQLite; **qa** and **prod** run on
Render with PostgreSQL on Neon. See
**[docs/ENVIRONMENTS.md](docs/ENVIRONMENTS.md)** and `render.yaml`.

| Env var | Purpose |
|---|---|
| `APP_ENV` | `dev` (default), `qa` or `prod`. qa and prod refuse unsafe settings. |
| `DATABASE_URL` | `postgres://…` connection string. Required for qa and prod; ignored in dev, which always uses SQLite. |
| `RESPONDER_API_KEY` | **Required.** Bearer key for the dispatch/responder API (≥ 24 chars when deployed). |
| `ENROLL_TOKEN` | Optional. If set, phones must present it to register. |
| `SMS_WEBHOOK_TOKEN` | Optional. If set, the SMS webhook URL must include `?token=…`. |
| `DATABASE_PATH`, `PORT`, `DATABASE_POOL_MAX` | SQLite file for dev (`sos.db`), HTTP port (`8080`), Postgres pool size (`10`). |

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

## iOS app

```bash
cd ios
brew install xcodegen && xcodegen   # generates SaveOurSoul.xcodeproj from project.yml
open SaveOurSoul.xcodeproj          # set your signing team, run on a device
cd SOSCore && swift test            # protocol/outbox tests, no simulator needed
```

The iOS app works like the Android one and speaks the same protocol. Where iOS behaves differently:

| | Android | iOS |
|---|---|---|
| Internet | Tries every network, then forces cellular | One URLSession. iOS *Wi-Fi Assist* moves to cellular when Wi-Fi has no internet. Low Data Mode is ignored for SOS. |
| SMS | Sent silently | iOS forbids silent SMS: a **pre-filled Messages sheet** opens and the user taps Send. Contacts are offered right away; the signed gateway frame is offered if the server hasn't confirmed within the delay. |
| Mesh | Nearby Connections, also in the background | Nearby Connections with the **same service id and strategy**, so iPhones and Android phones relay each other's SOS. It runs while the app is open, and in the background while an SOS is active. |
| Staying alive | Foreground service | Continuous background location during an SOS (blue status-bar pill). This keeps retries, location updates and the mesh running. |
| Store-and-forward | WorkManager every 15 min | `BGAppRefreshTask` backstop and `NWPathMonitor` (flush as soon as a network appears) |
| Extra triggers | — | **Siri / Shortcuts / Action button / Back Tap** through the `Send SOS` App Intent |
| Secret storage | Android Keystore (non-exportable HMAC key) | Keychain, `AfterFirstUnlockThisDeviceOnly`, so a locked phone can still sign |

### Platform notes / known limits

* **iPhones in the background:** the Bluetooth LE relay ([docs/BLE.md](docs/BLE.md)) keeps
  scanning, collecting and pushing SOS frames from the background. However, iOS hides a
  backgrounded iPhone's advert from Android phones. An iPhone *victim* should keep the SOS
  screen open (the app keeps the screen awake); when locked it still pushes its SOS to any
  phone it sees advertising internet. Nearby Connections on iOS runs only in the foreground.
* **iOS Nearby:** Google's Swift package is pulled from `github.com/google/nearby`. The
  `NSBonjourServices` entry in `ios/project.yml` is worked out from the service id. Check it
  against Google's iOS setup guide if Wi-Fi discovery doesn't find peers.
* **Google Play policy** limits `SEND_SMS`. Sideloaded and enterprise (MDM) builds are fine.
  For Play Store distribution you would need the policy exception or would have to fall back to
  `ACTION_SENDTO` (user taps send).
* Nearby Connections needs Google Play services. On devices without them (e.g. some AOSP
  builds) the mesh channel is off; internet and SMS still work.
* The relay foreground service costs some battery. Users can turn relay mode off in settings.

## Analytics

Both apps include **opt-in, anonymous** Firebase Analytics, with one Firebase project per
environment. It never collects location or personal data, never affects an SOS, and builds
without the Firebase config files simply run without it. See [docs/ANALYTICS.md](docs/ANALYTICS.md).

## Hajj deployment (Arafat, Mina, Muzdalifah)

Built for crowds where cellular is saturated:

* **Bluetooth LE crowd relay** on both platforms. An offline phone with an SOS pushes it to the
  nearest phone that has internet; online phones collect from everyone nearby. Signed receipts
  flow back, so the crowd stops carrying a frame once the server has it. See [docs/BLE.md](docs/BLE.md).
* **"Lost" emergency type** (separated from the group), selected by default in the app.
* Frames, receipts and the BLE beacon are byte-compatible across backend, Android and iOS,
  enforced by `protocol/test-vectors.json`.

Before relying on it at Hajj, it **must** be field-tested with many phones (see the end of
docs/BLE.md). A Saudi SMS provider with two-way SMS (e.g. Unifonic or Taqnyat) also needs a
small adapter for its inbound-webhook format.

## Next steps worth considering

* A web dispatch console on top of `/alerts` and the SSE stream (map, ack/resolve buttons).
* Push notifications (FCM) to responders/contacts, and an ack channel back to the victim over SMS.
* Hardware triggers on Android: power-button ×5, a Quick Settings tile, a Wear OS button (iOS already has the App Intent).
* Rate limiting / abuse protection on `/signals` and `/devices` at the reverse proxy.
* Frame encryption for location privacy from relays (see the protocol doc).
* Postgres instead of SQLite if you need more than one backend instance.
