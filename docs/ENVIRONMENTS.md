# Environments: development, testing, production

| | development | testing | production |
|---|---|---|---|
| Purpose | day-to-day work, may break | field trials and QA before release | real pilgrims |
| Git branch | `develop` | `testing` | `main` |
| Render service | `saveoursoul-api-dev` | `saveoursoul-api-testing` | `saveoursoul-api` |
| Neon branch | `dev` | `testing` | `production` |
| `APP_ENV` | `development` | `testing` | `production` |
| Android variant | `devDebug` / `devRelease` (app "SOS Dev") | `testing…` ("SOS Testing") | `prod…` ("SaveOurSoul") |
| iOS scheme | `SaveOurSoul Dev` | `SaveOurSoul Testing` | `SaveOurSoul` |
| Bluetooth/Nearby namespace | DEV | TESTING | PRODUCTION |

Code moves one way: `develop` → `testing` → `main`, through pull requests. Merging into a branch
deploys it automatically to that environment.

**Why separate radio namespaces?** Test phones running a dev or testing build use different
Bluetooth service UUIDs and Nearby service ids. They never pick up a real pilgrim's SOS (which
their backend would reject), and test SOS signals never reach production phones.

## One-time setup

### 1. Neon: one database branch per environment

The project `SaveOurSoul` (Singapore) already has the `production` branch.

1. Neon console → **Branches** → **Create branch** → name `testing`, parent `production`.
2. Again for `dev`, parent `production`.
3. For each branch: **Connect** → select the branch → turn on **Connection pooling** → **Copy
   snippet**. Keep the three strings somewhere safe, never in git or chat.

Each branch is a separate database, and the backend creates its tables on first start.

### 2. Git: branches that feed each environment

```bash
git checkout main && git pull
git checkout -b testing && git push -u origin testing
git checkout -b develop && git push -u origin develop
```

On GitHub, consider protecting `main` and `testing` (Settings → Branches) so they only change
through reviewed pull requests with green CI.

### 3. Render: deploy all three from the Blueprint

1. Render dashboard → **New** → **Blueprint** → choose this repository. Render reads `render.yaml`.
2. Render asks for each `DATABASE_URL`. Paste the matching Neon string: dev → `saveoursoul-api-dev`,
   testing → `saveoursoul-api-testing`, production → `saveoursoul-api`.
3. Render generates `RESPONDER_API_KEY`, `ENROLL_TOKEN` and `SMS_WEBHOOK_TOKEN`. Read them in each
   service's **Environment** tab when you need them (dispatch console, agents, SMS provider).
4. Check each service's URL with `/healthz`. It should return `{"ok":true,"env":"…","db":"postgres"}`.

If Render assigns different URLs than `https://saveoursoul-api[-dev|-testing].onrender.com`,
update them in `android/app/build.gradle.kts` (productFlavors) and `ios/project.yml` (SOS_BACKEND_URL).

> **Before go-live:** change the production service's plan from `free` to `starter` or higher.
> Free instances sleep after ~15 minutes idle and take up to a minute to wake. An SOS cannot
> wait that long. Also consider a paid Neon plan for production (point-in-time recovery, no
> scale-to-zero delay).

### 4. SMS gateway

Point each environment's SMS number (or one test number) at
`https://<service-url>/api/v1/sms/inbound?token=<that service's SMS_WEBHOOK_TOKEN>`.

## Day to day

* **Run locally:** `cd backend && RESPONDER_API_KEY=dev npm start` uses a SQLite file, so no
  database setup is needed. To use your Neon `dev` branch instead, also set `DATABASE_URL`.
* **Tests:** `npm test` runs on SQLite. `TEST_DATABASE_URL=postgres://… npm run test:pg` runs the
  same API suite on PostgreSQL. **Never point it at a real environment: it drops the tables.**
  CI runs both.
* **Build the apps:** `./gradlew installDevDebug`, or `assembleTestingRelease` / `assembleProdRelease`.
  On iOS, pick the scheme in Xcode.
* **Schema changes:** append a new entry to `migrations()` in `backend/src/store.ts` (never edit a
  released one). Each service applies pending migrations on start, so the change reaches dev,
  then testing, then production as the code moves through the branches.

## Safety checks built into the backend

`testing` and `production` refuse to start unless:

* `DATABASE_URL` is a PostgreSQL URL. A SQLite file on Render would be wiped on every deploy.
* `RESPONDER_API_KEY` is at least 24 characters.

`production` also logs a warning if `ENROLL_TOKEN` or `SMS_WEBHOOK_TOKEN` is missing.
`/healthz` returns 503 when the database is unreachable, so Render won't route traffic to a
broken instance.
