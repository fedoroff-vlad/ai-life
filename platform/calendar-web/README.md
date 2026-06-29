# calendar-web

**Status:** Active (introduced #195)

Read-only calendar view (port **8113**). Serves per-person **ICS feeds** over the events the system
creates, so each household member can subscribe their own **Apple / Google / Yandex** calendar and see
everything at a glance. **No DB, no write path** — writes keep going through the calendar-agent +
confirm. Reads go through `mcp-caldav`'s `GET /internal/events` passthrough (doctrine #201), never the
calendar schema directly. Plan: [calendar.md](../../domains/calendar/../../plans/calendar.md).

## Why a feed, not a custom web page

The owner's goal is "see all events in my own calendar app". The tool that delivers that across Apple,
Google **and** Yandex is a read-only **ICS feed by URL** (Google/Yandex only subscribe by ICS URL, not
CalDAV). So the deliverable is a per-person ICS endpoint a calendar app polls — meeting the user where
they already are. (A self-hosted CalDAV web client like AgenDAV/InfCloud was rejected: unmaintained PHP,
and a full CalDAV client exposes writes — a second write path.)

## Endpoints

| method | path | purpose |
|--------|------|---------|
| GET | `/ics/{token}.ics` | Read-only `text/calendar` feed for the household behind `{token}`. Unknown token → 404. |
| GET | `/actuator/health` | liveness |

The feed window is `[now-pastDays, now+futureDays)` (defaults 31 / 366 days). Events come from
`mcp-caldav` `GET /internal/events`.

## Configuration

| env var | default | purpose |
|---|---|---|
| `CALENDAR_WEB_PORT` | `8113` | HTTP port |
| `MCP_CALDAV_URL` | `http://mcp-caldav:8090` | mcp-caldav base URL (the read passthrough) |
| `CALENDAR_WEB_PAST_DAYS` | `31` | how far back the feed spans |
| `CALENDAR_WEB_FUTURE_DAYS` | `366` | how far ahead the feed spans |

**Feed resolution (track B):** a request token is resolved first against the **persistent store**
(`mcp-caldav` `GET /internal/feeds/{token}` — minted on demand, no restart to add a member), then against
the **static env feeds** below as a fallback. Unknown either way → 404. Most feeds are minted (e.g. the
calendar-agent auto-issues one on a user's first event); env feeds remain for static/manual setups.

**Env feeds** are env-bound (Spring relaxed list binding), one per member — no DB needed for this path:

```
CALENDAR_WEB_FEEDS_0_TOKEN=<long-random-secret>   # sits in the public ICS URL → must be unguessable
CALENDAR_WEB_FEEDS_0_HOUSEHOLD_ID=<uuid>
CALENDAR_WEB_FEEDS_0_LABEL=Vlad
CALENDAR_WEB_FEEDS_1_TOKEN=...                     # the spouse's feed
CALENDAR_WEB_FEEDS_1_HOUSEHOLD_ID=<uuid>
CALENDAR_WEB_FEEDS_1_LABEL=Maria
```

> **MVP scope:** a feed currently exposes the **whole household's** events (a 2-person household shares a
> calendar). Per-person private/shared filtering is a follow-up — `events_cache` carries `person_id` but
> no visibility flag yet.

## Subscribe your calendar (zero build on the client side)

Expose this service over **public HTTPS** (Google/Yandex must reach it; a token, not a login, guards it).
Easiest without a domain/cert: a tunnel — **Cloudflare Tunnel** or **Tailscale Funnel** — to
`http://localhost:8113`. Then add `https://<your-host>/ics/<token>.ics`:

- **Apple Calendar** — File → New Calendar Subscription → paste the URL (or on iOS: Settings → Calendar →
  Accounts → Add → Other → Add Subscribed Calendar).
- **Google Calendar** — Other calendars → **From URL** → paste. (Read-only; Google refreshes slowly —
  up to several hours.)
- **Yandex Calendar** — Subscribe to a calendar by link → paste.

Native CalDAV apps (Apple, Thunderbird, DAVx5) can alternatively point straight at the Radicale
collection — but the ICS feed is the one URL that works for all three.

## Key classes
- `CalendarWebApplication`.
- `config/CalendarWebProperties` — `calendar-web.{mcp-caldav-url, past-days, future-days, feeds[]}`; `feedByToken`.
- `http/CalendarReadClient` — `GET /internal/events` over mcp-caldav (the deterministic read surface).
- `ics/IcsWriter` — hand-rolled RFC-5545 renderer (escaping + CRLF + 75-octet folding).
- `web/IcsFeedController` — `GET /ics/{token}.ics`; token → household → events → ICS; 404 on unknown token.

## Run locally

```sh
mvn -B -pl platform/calendar-web -am spring-boot:run
```
