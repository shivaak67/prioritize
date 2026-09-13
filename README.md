# Prioritize

Personal productivity and planning platform. Organize work as Goals → Projects → Tasks, block time on a schedule, manage calendar events and routines, and track progress with reminders, insights, and time entries.

## Screenshots

**Live app:** [theprioritize.com](https://theprioritize.com/) · **Default branch / current release:** [main](https://github.com/shivaak67/prioritize/tree/main)

Refreshed September 9, 2026. Authenticated pages show the current application running locally with a fictional account and synthetic tasks and Canvas data. The sign-in capture is from the live app. No private feed links or real account data are shown.

### Dashboard

Due Today, Overdue, This Week, and weekly progress include personal tasks and Canvas assignments, with completed assignments excluded from open counts. Dates use the user's timezone; date-only Canvas deadlines keep their original date. The AI assistant checks these assignments when recommending today's work.

![Dashboard with today's plan and weekly progress](docs/screenshots/dashboard.jpg)

### Canvas assignments

Imported assignments have their own section, separate from events and time blocks. Mark an assignment **Complete** or **Reopen** it. Completion is saved in Prioritize and survives feed refreshes; it does not submit work or change Canvas.

![Canvas assignments with Complete and Reopen controls](docs/screenshots/canvas-assignments.jpg)

### Canvas discovery and setup

A dashboard card helps new users discover the import. Settings walks them through **Canvas → Calendar → Calendar Feed**, timezone selection, and connection. No developer key is required.

![Canvas discovery card with three setup steps](docs/screenshots/canvas-welcome.jpg)

![Canvas Calendar Feed setup instructions](docs/screenshots/canvas-settings.jpg)

### Tasks and calendar planning

Create tasks with due dates and priorities, filter existing work, and reserve study time. The calendar brings personal tasks, editable time blocks, and imported Canvas deadlines together.

![Task creation, time blocks, and task filters](docs/screenshots/tasks.jpg)

![Calendar with tasks and Canvas assignments](docs/screenshots/calendar.jpg)

<details>
<summary>See time-block editing and Canvas assignment details</summary>

![Editing a personal time block](docs/screenshots/calendar-edit.jpg)

![Canvas assignment details and local completion status](docs/screenshots/canvas-calendar.jpg)

</details>

### Reminders

Select several assignments or use **Select all** to schedule reminders together. **Clear history** clears recent activity from view while keeping pending reminders.

![Selecting multiple Canvas assignments and events for reminders](docs/screenshots/reminder-selection.jpg)

<details>
<summary>See reminder history controls</summary>

![Recent reminder activity with Clear history](docs/screenshots/reminder-history.jpg)

</details>

### Focus timer

Choose a task and a study duration, then start a timed session or use the stopwatch to log focused work.

![Focus timer with a selected task and session duration](docs/screenshots/focus.jpg)

### Getting started and public pages

The guided first session helps users create a task, reserve time, and start Focus. The home URL opens your dashboard when signed in, or sign-in when signed out. Sign-in supports email/password and Google.

<details>
<summary>See the first-session guide and sign-in</summary>

![Guided first planning session](docs/screenshots/onboarding.jpg)



![Prioritize sign-in with Google option](docs/screenshots/sign-in.jpg)

</details>

## Overview

Prioritize helps you plan and execute work with a clear hierarchy: categories and goals break into projects and tasks. You set manual priorities and schedule blocks yourself. Canvas Calendar Feed integration imports assignment deadlines and events into your calendar; it does not use the Canvas API or sync grades or submissions. There is no Google Calendar sync or automatic priority engine. The app also covers routines, reminders, notifications, time tracking, and insights. Power BI can connect separately for historical analytics.

## Features

- Email/password and Google OAuth authentication (JWT)
- Direct entry to dashboard or sign-in, with account creation and Google sign-in
- Guided first session: task → calendar time block → Focus
- Calendar creation and editing with date validation and failed-save recovery
- Canvas Calendar Feed connection: read-only deadlines/events, automatic refresh, and manual sync
- Categories, goals, projects, and tasks (manual priority)
- Schedule blocks (time-blocking) and personal calendar events
- Recurring routines and occurrences
- Reminders and in-app notifications
- Time entries and insights summary
- Dashboard overview
- Power BI–ready PostgreSQL schema

*(Sections expand as each development phase lands.)*

## Architecture

Monorepo with a Spring Boot API, Angular SPA, and PostgreSQL.

- Backend enforces ownership and authorization on every resource
- Frontend consumes REST APIs via Angular services
- Planning model: Category / Goal / Project / Task plus schedule, calendar, routines, reminders, time tracking
- Analytics (Power BI) reads the database; it is not the main UI

See [docs/architecture.md](docs/architecture.md) for schema, auth flow, and phase plan.

## Tech Stack

| Layer | Technologies |
|-------|----------------|
| Frontend | Angular, TypeScript, Angular Material |
| Backend | Java, Spring Boot, Spring Security, Spring Data JPA |
| Database | PostgreSQL |
| Auth | JWT + Google OAuth 2.0 / OIDC |
| Analytics | Power BI |
| DevOps | Docker, Docker Compose, GitHub Actions |
| Cloud | AWS EC2 + EBS; PostgreSQL runs in Docker on EC2 |

## Project Structure

```
├── backend/          # Spring Boot API
├── frontend/         # Angular SPA
├── docs/             # Architecture, API contracts, agent workflow
├── infra/            # AWS / deployment notes (later)
├── .github/          # CI/CD workflows (later)
├── .env.example      # Environment variable template
└── docker-compose.yml  # Local full stack (Postgres + API + SPA)
```

## Getting Started

### Prerequisites

- JDK 21+
- Node.js 20+ and npm
- PostgreSQL 16+ (or Docker)
- Google Cloud OAuth client (for Google sign-in)

### Setup

1. Clone the repository
2. Copy `.env.example` to `.env` and fill in values
3. Start PostgreSQL and create the `prioritize` database
4. Backend:
   - Requires JDK 21+
   - From `backend/`: `./mvnw spring-boot:run` (Windows: `mvnw.cmd spring-boot:run`)
   - Health check: `GET http://localhost:8080/actuator/health`
   - Tests: `./mvnw test`
5. Frontend:
   - Requires Node.js 20+ and npm
   - From `frontend/`: `npm install` then `npm start` → http://localhost:4200
   - API base URL: `http://localhost:8080` (see `src/environments/`)
   - Production build: `npm run build`

Planning APIs (goals, projects, tasks, etc.) require `Authorization: Bearer <JWT>` from `/api/auth/login` or `/api/auth/register`. Cross-user access returns `404`.

## Authentication

- **Local:** register / login with email and password (BCrypt hashes)
- **Google:** “Continue with Google” via Spring Security OAuth2 Login; app issues the same JWT

### Google OAuth setup (local)

1. In [Google Cloud Console](https://console.cloud.google.com/), create an OAuth 2.0 Client ID (Web application)
2. Add authorized redirect URI: `http://localhost:8080/login/oauth2/code/google`
3. Copy client ID/secret into `.env`:
   - `GOOGLE_OAUTH_ENABLED=true`
   - `GOOGLE_CLIENT_ID=...`
   - `GOOGLE_CLIENT_SECRET=...`
   - `APP_OAUTH_SUCCESS_REDIRECT=http://localhost:4200/auth/callback`
4. Restart the backend, then use **Continue with Google** on the login/register pages

Secrets: `GOOGLE_CLIENT_ID`, `GOOGLE_CLIENT_SECRET`, `JWT_SECRET` — never commit real values.

## Planning model

Work is organized as **Goals → Projects → Tasks**, with optional categories. Priority on tasks is set manually (`LOW` / `MEDIUM` / `HIGH` / `URGENT`). Schedule blocks attach tasks to calendar time; routines, reminders, time entries, and insights support day-to-day planning—not an automated ranking engine.

## API Overview

REST under `/api/*`. Auth: `/api/auth/*` and Google OAuth endpoints. See [docs/api-contract.md](docs/api-contract.md).

## Testing

- Backend: JUnit, Mockito, Spring Boot integration tests
- Frontend: Angular unit tests (added with the app scaffold)
- Ownership rules and planning CRUD are high-priority test targets

## Docker

Run the full local stack (PostgreSQL, Spring Boot API, Angular SPA via nginx) with Docker Compose.

1. Copy `.env.example` to `.env` and fill in secrets (`JWT_SECRET`, optional Google OAuth values).
2. From the repo root:

```bash
docker compose up --build
```

3. Open the app at [http://localhost:4200](http://localhost:4200). API: [http://localhost:8080](http://localhost:8080) (health: `/actuator/health`).

**How ports map**

| Service  | Host port | Notes |
|----------|-----------|--------|
| frontend | 4200 → 80 | nginx serves the SPA with client-side routing fallback (`FRONTEND_PUBLISHED_PORT`) |
| backend  | 8080 → 8080 | Browser calls `http://localhost:8080` for API and OAuth (matches Angular `apiBaseUrl`) |
| db       | 5433 → 5432 | Default host mapping avoids clashing with a local Postgres on 5432 (`POSTGRES_PUBLISHED_PORT`) |

Compose waits for Postgres and the API health check before starting the frontend.

**Google OAuth:** keep the authorized redirect URI as `http://localhost:8080/login/oauth2/code/google` (same as non-Docker local). Success redirect stays `http://localhost:4200/auth/callback`.

**Running backend on the host against Compose Postgres:** point `POSTGRES_HOST=localhost` and `POSTGRES_PORT=5433` in `.env` (or whatever you set for `POSTGRES_PUBLISHED_PORT`).

Stop with `Ctrl+C` or `docker compose down`. Add `-v` to also remove the database volume.

## CI/CD

GitHub Actions runs backend tests and the frontend production build on pull requests and pushes to `main` and `develop`. A separate job builds both Docker images to catch Dockerfile regressions. Pushes to `develop` that change application files also publish versioned images to GitHub Container Registry. Deployment selects a versioned image on the existing EC2 host.

Frontend behavior tests run with `npm test -- --watch=false --browsers=ChromeHeadless` from `frontend/`. Frontend tests cover assignment labels, time-block editing, bulk reminder selection, and clearing history. Backend tests cover feed parsing, dates, encryption, URL restrictions, bounded downloads, duplicate prevention, failure preservation, and account isolation.

## Connect Canvas

In Canvas, open **Calendar → Calendar Feed** and copy your private iCal link. In Prioritize, open **Settings → Bring Canvas into your day**, paste it, confirm the timezone, and select **Connect Canvas**. No developer key or Canvas password is needed. Currently supports HTTPS calendar-feed links on `*.instructure.com`.

Assignment deadlines and events appear as labeled, read-only calendar entries. Select an entry to view details or open Canvas. Imported deadlines keep their Canvas title and due date, but you can mark them Complete or Reopen them in Prioritize. This local status survives feed refreshes; it does not submit work or synchronize completion back to Canvas. Disconnecting or removing an item from the feed removes its local completion record. Refreshes run about every 30 minutes on a separate scheduler; **Sync now** is available with a one-minute cooldown. Canvas normally provides the previous 30 days and next 366 days and omits undated to-do items. See [Canvas's feed guide](https://community.instructure.com/en/kb/articles/662804-unknown).

Each successful refresh upserts by the Canvas item UID and removes imported copies no longer present in the feed. Failed or malformed downloads keep the previous successful snapshot. Disconnect removes the connection and its imported copies, leaving personal records intact. Canvas exports repeated occurrences individually; feeds containing recurrence rules are rejected rather than partially imported.

Feed URLs are encrypted with AES-GCM and never returned by the API. By default the encryption key is derived with a Canvas-specific label from the existing JWT secret; operators may set a stable `CANVAS_FEED_KEY` environment variable instead. Changing that key requires reconnecting feeds. Keep links, keys, database dumps, and feed contents out of source control. Downloads require HTTPS Canvas hosts, prohibit redirects/private addresses, have a 20-second total wait limit, and are capped at 2 MiB. V10 adds the connection table and import metadata without changing existing personal records.

## Deployment (AWS)

- Host: one AWS EC2 instance running Docker Compose
- Frontend: Angular build served by nginx, with API/OAuth requests proxied to Spring Boot
- Backend: Spring Boot container on the same EC2 host
- Database: PostgreSQL 16 container with data on a mounted EBS volume
- Releases: GitHub Actions → GHCR versioned images → EC2 deployment through AWS Systems Manager
- Configuration: environment variables supplied on the server

This is the live topology verified on September 9, 2026. EBS persistence is separate from backups; the single host remains a shared failure point. See `docker-compose.prod.yml` and `infra/` for deployment configuration.

## Analytics (Power BI)

Power BI connects to PostgreSQL for completion rates, workload trends, and estimated vs actual time. Angular remains the interactive app UI.

### Assignment and reminder controls

The Tasks page separates Canvas assignments and Canvas events from personal time blocks. Assignment cards show a due date or due time, and personal time blocks support inline editing of title, start, end, and all-day status.

In Reminders, select individual items or use **Select all** to apply reminder times to multiple items. Partial failures remain selected for retry. **Clear history** hides sent, failed, and cancelled activity for your account while preserving pending/processing reminders and internal delivery records.

Users without a Canvas connection see a dashboard setup card with three simple steps and a direct link to Settings.
