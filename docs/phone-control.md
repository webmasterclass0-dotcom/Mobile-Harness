# Phone control (agent drives the device)

This feature gives the AI coding agent running inside the Mobile Harness Linux
subsystem **direct control over the Android phone** — the same shape of control
that OpenCode / Claude Code has over a desktop: run tools, read state, act.

It does **not** require root, ADB, or a PC.

## What the agent can do

| Action | How | Requires |
|---|---|---|
| Tap / long-press / swipe | Accessibility gestures | Accessibility service on |
| Type text into fields | `ACTION_SET_TEXT` | Accessibility service on |
| Read the current UI hierarchy (JSON) | `rootInActiveWindow` | Accessibility service on |
| Screenshot (PNG, base64) | Accessibility `takeScreenshot` | Android 11+ (API 30+) |
| Open any installed app | Launcher intent | — |
| List installed apps | `queryIntentActivities` | — |
| Press system keys (`home`, `back`, …) | `performGlobalAction` | Accessibility service on |
| Run shell commands in the app's sandbox | `ProcessBuilder` (app UID) | — |

## Architecture

```
┌─ Android (app) ────────────────────────────┐      ┌─ Linux runtime (PRoot) ──┐
│ PhoneControlService (a11y)  ◄── gestures ──┤      │ claude / dsh / agy CLI    │
│        ▲              ▲                     │      │   │    │    │             │
│        │ ui tree      │ calls               │      │   └─ agent MCP tools      │
│ PhoneControlServer ◄──┼── HTTP :8765 (127.0.0.1) ──┘   │  │                  │
│   (loopback only)     │                      │    phone / phone-mcp (curl/fetch)
└───────────────────────┘                      └──────────────────────────────┘
```

The app binds a tiny HTTP server on `127.0.0.1:8765`. The PRoot environment is
not a network namespace, so processes inside it reach the same loopback
interface and `curl http://127.0.0.1:8765/...` just works.

## Enabling (one time)

1. **Build & install the app** with the new `control` package (see below).
2. Open **Settings → System app info → Phone control (agent access)** — or open
   Accessibility settings and tap the „settings" link next to the
   „Mobile Harness — Phone control" service.
3. Turn on **Phone control**.
4. Tap **Open accessibility settings** and enable the **Mobile Harness**
   service.
5. Keep **Require access token** on (default). Any installed app can reach
   loopback; the token stops strangers.

The control server starts automatically with the runtime service and stops with
it. Agents launched afterwards inherit `MH_PHONE_PORT` and `MH_PHONE_TOKEN`.

## Using it from the agent terminal

Install the helper once inside the runtime (needs the internet):

```bash
curl -fsSL -o /usr/local/bin/phone \
  https://raw.githubusercontent.com/techjarves/Mobile-Harness/main/scripts/phone \
  && chmod +x /usr/local/bin/phone
```

Then:

```bash
phone health
phone ui                       # JSON UI tree
phone apps
phone tap --x 540 --y 1200
phone tap --text "Open"
phone tap --id "com.app:id/btn"
phone longpress --x 540 --y 800
phone swipe --x1 540 --y1 400 --x2 540 --y2 1700 --duration 400
phone type "hello from the agent"
phone key home
phone open com.whatsapp
phone screenshot /data/local/tmp/screen.png
phone shell am start -n com.android.settings/.Settings
```

The agent discovers the helper naturally (it can read `phone --help`), no extra
setup needed.

**Manual terminal.** The control server runs while the runtime service is active
(i.e. while a session or task is running). In the plain Terminal tab, export the
values once if they are missing:

```bash
export MH_PHONE_PORT=8765
export MH_PHONE_TOKEN=<copy the token in the Phone control screen>
```

## Using it as an MCP server (optional, recommended)

`scripts/phone-mcp` is a dependency-free Node implementation of the MCP stdio
protocol (the runtime ships Node.js). It exposes `phone_ui`, `phone_tap`,
`phone_swipe`, `phone_type`, `phone_key`, `phone_open_app`, `phone_apps`,
`phone_screenshot`, `phone_shell` as tools.

```bash
curl -fsSL -o /usr/local/bin/phone-mcp \
  https://raw.githubusercontent.com/techjarves/Mobile-Harness/main/scripts/phone-mcp \
  && chmod +x /usr/local/bin/phone-mcp
```

Local test:

```bash
node scripts/phone-mcp &
printf '%s\n' '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' \
  '{"jsonrpc":"2.0","id":2,"method":"tools/list"}' | node scripts/phone-mcp
```

### Wiring into Claude Code sessions

```json
{
  "mcpServers": {
    "phone": { "command": "/usr/local/bin/phone-mcp" }
  }
}
```

Point Claude Code at it with
`claude --mcp-config /path/to/mcp.json …` or merge it into the agent's
`~/.claude.json`. The integration hook lives in
`RuntimeLaunchConfigBuilder` (env injection) — the app-side plumbing is already
in place once this feature is built.

## HTTP API (for reference)

All responses are JSON `{ "ok": bool, ... }`. If token auth is enabled, every
request except `/health` must include `X-Access-Token`.

| Method | Path | Body |
|---|---|---|
| GET | `/health` | — |
| GET | `/ui` | — |
| GET | `/apps` | — |
| GET | `/screenshot` | — |
| POST | `/tap` | `{x,y}` **or** `{text}` **or** `{resourceId}` |
| POST | `/longpress` | `{x,y,durationMs?}` |
| POST | `/swipe` | `{x1,y1,x2,y2,durationMs?}` |
| POST | `/type` | `{text}` |
| POST | `/key` | `{key: home\|back\|recents\|notifications\|quick_settings\|power_dialog\|lock_screen\|screenshot}` |
| POST | `/open` | `{package}` |
| POST | `/shell` | `{args:[...]}` or `{command:"am start ..."}` |

## Security model & honest limitations

- **Loopback only.** The server never binds to Wi-Fi / cellular interfaces.
- **Master switch.** With the feature disabled the server does not bind at all.
- **Token.** Protects against other installed apps driving the device through
  the loopback socket.
- **Same app sandbox.** `/shell` runs with the app's UID, not root: it cannot
  change system settings, read other apps' data, or bypass Android's permission
  model. On a stock device without root you cannot do more than this feature's
  boundaries; that is Android's design, not a bug.
- **Screen content is sensitive.** UI dumps and screenshots may contain
  passwords, chats, or other secrets. Only run this with providers and projects
  you trust, and prefer `phone ui` over screenshots.
- **Play policy.** Accessibility-based automation is restricted on Google Play;
  this project distributes signed direct APKs / F-Droid, so it is not affected.
- **OS versions.** Screenshots need Android 11+; gestures, text entry and UI
  dumps work from Android 9 (the app's minimum).

## What was added

- `app/src/main/java/com/jarves/mh/control/PhoneControlService.kt`
- `app/src/main/java/com/jarves/mh/control/PhoneControlActions.kt`
- `app/src/main/java/com/jarves/mh/control/PhoneControlServer.kt`
- `app/src/main/java/com/jarves/mh/control/PhoneControlSettings.kt`
- `app/src/main/java/com/jarves/mh/control/PhoneControlActivity.kt`
- `app/src/main/res/xml/accessibility_service_config.xml`
- `scripts/phone` (agent CLI), `scripts/phone-mcp` (MCP server)
- Manifest: accessibility service, settings activity, `<queries>` for launcher apps
- `RuntimeLaunchConfigBuilder`: injects `MH_PHONE_PORT` / `MH_PHONE_TOKEN`
- `RuntimeExecutionService`: starts/stops the control server with the service