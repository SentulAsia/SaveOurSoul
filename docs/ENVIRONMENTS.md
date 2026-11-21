# Environments: dev, qa, prod

The same three names are used everywhere: backend `APP_ENV`, Render, Neon, git, the Android
build variants, the iOS schemes and the Bluetooth/Nearby namespaces.

| | dev | qa | prod |
|---|---|---|---|
| Purpose | day-to-day work on your computer | field trials and QA before release | real pilgrims |
| Runs on | your laptop | Render `saveoursoul-api-qa` | Render `saveoursoul-api` |
| Database | **SQLite** file (`backend/sos.db`) | **PostgreSQL**, Neon branch `qa` | **PostgreSQL**, Neon branch `production` |
| `APP_ENV` | `dev` (the default) | `qa` | `prod` |
| Git branch that deploys | — | `qa` | `main` |
| Android variant | `devDebug` ("SOS Dev") | `qaDebug` / `qaRelease` ("SOS QA") | `prodDebug` / `prodRelease` ("SaveOurSoul") |
| iOS scheme | `SaveOurSoul Dev` | `SaveOurSoul QA` | `SaveOurSoul` |
| App's default server | your computer, `:8080` | qa service URL | prod service URL |
| Bluetooth/Nearby namespace | DEV | QA | PROD |

**dev always uses SQLite.** Even if `DATABASE_URL` is set, `APP_ENV=dev` ignores it (with a warning),
so a laptop can never write to the qa or prod database by accident.

**qa and prod always use PostgreSQL.** They refuse to start without a PostgreSQL `DATABASE_URL`,
because a SQLite file on Render would be wiped on every deploy.

**Separate radio namespaces.** Phones running a dev or qa build use different Bluetooth service
UUIDs and Nearby service ids. They never pick up a real pilgrim's SOS, and dev/qa SOS signals never
reach production phones.

Code moves one way: feature branch → `qa` → `main`, through pull requests. Merging into `qa`
or `main` deploys automatically.

## dev: run everything on your computer

```bash
cd backend
npm install
RESPONDER_API_KEY=dev npm start          # APP_ENV defaults to dev → SQLite file backend/sos.db
```

Delete `backend/sos.db` to start from an empty database.

* **Android emulator:** `./gradlew installDevDebug`. The app already points to
  `http://10.0.2.2:8080`, which is your computer as seen from the emulator.
* **iOS simulator:** run the `SaveOurSoul Dev` scheme. It points to `http://localhost:8080`.
* **Real phones** (needed for Bluetooth): put the phone on the same Wi-Fi network as your computer,
  then in the app's registration screen set the server URL to `http://<your computer's LAN IP>:8080`.
  Dev builds allow plain HTTP; qa and prod builds are HTTPS only.

## One-time setup for qa and prod

### 1. Neon: a database branch for qa

The project `SaveOurSoul` (Singapore) already has the `production` branch, which prod uses.

1. Neon console → **Branches** → **Create branch** → name `qa`, parent `production`.
2. For each of `qa` and `production`: **Connect** → select the branch → turn on
   **Connection pooling** → **Copy snippet**. Never put these strings in git or chat.

The backend creates its tables on first start.

### 2. Git: the branch that feeds qa

```bash
git checkout main && git pull
git checkout -b qa && git push -u origin qa
```

On GitHub, consider protecting `main` and `qa` (Settings → Branches) so they only change through
reviewed pull requests with green CI.

### 3. Render: deploy both from the Blueprint

1. Render dashboard → **New** → **Blueprint** → choose this repository. Render reads `render.yaml`.
2. Paste each `DATABASE_URL`: the Neon `qa` string → `saveoursoul-api-qa`, the `production`
   string → `saveoursoul-api`.
3. Render generates `RESPONDER_API_KEY`, `ENROLL_TOKEN` and `SMS_WEBHOOK_TOKEN`. Read them in each
   service's **Environment** tab when you need them.
4. Open `<service URL>/healthz` for each. It should return `{"ok":true,"env":"qa","db":"postgres"}`
   (or `"prod"`).

If Render assigns different URLs than `https://saveoursoul-api-qa.onrender.com` and
`https://saveoursoul-api.onrender.com`, update them in `android/app/build.gradle.kts`
(productFlavors) and `ios/project.yml` (`SOS_BACKEND_URL`).

> **Before go-live:** change prod's plan from `free` to `starter` or higher. Free instances
> sleep after ~15 minutes idle and take up to a minute to wake, and an SOS cannot wait that
> long. Also consider a paid Neon plan for production (point-in-time recovery, no scale-to-zero delay).

### 4. SMS gateway

Point the SMS number for each environment at
`https://<service URL>/api/v1/sms/inbound?token=<that service's SMS_WEBHOOK_TOKEN>`.

## Tests and schema changes

* `npm test` runs on SQLite. `TEST_DATABASE_URL=postgres://… npm run test:pg` runs the same API
  suite on PostgreSQL. **Never point it at qa or prod: it drops the tables.** CI runs both,
  against a throwaway Postgres.
* To change the schema, append a new entry to `migrations()` in `backend/src/store.ts` (never edit
  a released one). It runs locally on SQLite first, then on qa and prod when the code is
  deployed there.
