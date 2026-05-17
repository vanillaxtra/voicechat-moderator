# VoicechatModerator

[![Version](https://img.shields.io/badge/version-1.0.0-blue)](https://github.com/)
[![Platform](https://img.shields.io/badge/platform-Paper%201.21.4-brightgreen)](https://papermc.io/)
[![Java](https://img.shields.io/badge/Java-21-orange)](https://adoptium.net/)
[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?logo=discord&logoColor=white)](https://discord.gg/WpYZkrdNVe)

A server-side Paper/Folia plugin that transcribes [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) audio using [faster-whisper](https://github.com/SYSTRAN/faster-whisper) over a persistent WebSocket connection, and enforces configurable word-group moderation rules — with Discord alerts, database-backed bans/mutes, voice reports, and flagged clip saving.

---

## Features

- **faster-whisper** speech-to-text — 4–8× faster than openai-whisper, same accuracy
- **Persistent WebSocket** transport — one connection per server, auto-reconnects on drop
- **Word-group moderation** — configurable groups with regex matching, cooldowns, and bulk word lists from URLs
- **Discord webhooks** — sends a 10-second WAV clip and embed to your staff channel
- **Player avatars** via [mc-heads.net](https://mc-heads.net)
- **Voice reports** — `/reportvoice <player>` opens a GUI to categorize violations (slur, abuse, etc.); staff review with `/vcm reviews`
- **Flagged clip saving** — WAV files saved to `plugins/VoicechatModerator/recordings/` when a word-group fires
- **Database** — SQLite (default) or MySQL for persistent mutes, bans, and reports
- **Custom ban screens** — MiniMessage-formatted kick messages defined in `ban.yml`
- **Ban plugin hooks** — auto-detects AdvancedBan / LiteBans, or falls back to vanilla `/ban`
- **Mute notifications** — actionbar, chat, title, and/or bossbar when a muted player tries to speak
- **PlaceholderAPI** integration — `%vcm_is_muted%`, `%vcm_mute_remaining%`, and more
- **URL word lists** — download word lists from a URL; cached locally so they only download once
- **Multi-server** — one Whisper VPS handles many Minecraft servers simultaneously with backpressure and per-server logging
- **Mute ladder** — escalating auto-mute durations per repeat offender tier, backed by LuckPerms temp nodes
- **Folia compatible** — uses global region scheduler on Folia servers
- Hot-reload with `/vcm reload`

---

## Repository layout

```
voicechat-moderator/
├── paper-plugin/          # Java Paper/Folia plugin (Gradle)
│   └── src/main/java/dev/voicechat/moderator/
└── whisper-service/       # Python faster-whisper WebSocket service
    ├── server.py          # Deploy this on your VPS
    └── requirements.txt
```

Players install **nothing**. The plugin runs entirely server-side. The Python service runs on a separate VPS or any machine reachable from your Minecraft server.

---

## Requirements

| Dependency | Notes |
|---|---|
| [Paper 1.21.4](https://papermc.io/downloads/paper) | or Purpur |
| [Simple Voice Chat](https://modrinth.com/plugin/simple-voice-chat) `2.6.x` | must be installed |
| Python 3.8+ | for the transcription service on your VPS |
| [faster-whisper](https://github.com/SYSTRAN/faster-whisper) | `pip install -r whisper-service/requirements.txt` |
| ffmpeg | required by faster-whisper on the transcription host (not needed on the Minecraft server) |

**Optional:**
- [PlaceholderAPI](https://hangar.papermc.io/HelpChat/PlaceholderAPI) — for `%vcm_*%` placeholders
- [AdvancedBan](https://www.spigotmc.org/resources/advancedban.8695/) or [LiteBans](https://www.spigotmc.org/resources/litebans.3715/) — for ban command routing
- [LuckPerms](https://luckperms.net/) — required when `mute_ladder.enabled: true`

---

## Installation

1. Drop `voicechat-moderator.jar` into your `plugins/` folder.
2. Start the server once to generate config files.
3. Set up and start the [Whisper service](#whisper-service).
4. Configure `plugins/VoicechatModerator/config.yml`.
5. Restart or run `/vcm reload`.

---

## Using a shared public Whisper service

If someone is already running a public VoicechatModerator Whisper service, you can connect your server to it without setting up anything yourself.

In `plugins/VoicechatModerator/config.yml`, set:

```yaml
whisper:
  host: "the.service.ip.or.hostname"
  port: 8765
  instance_id: "my-server-name"   # shows in the service's logs so the operator can identify you
  max_inflight: 8
```

That's all. Restart or `/vcm reload` and the plugin connects automatically.

**Limits on shared services** (defaults, operators may adjust):

| Limit | Default | Effect when exceeded |
|---|---|---|
| Max total connections | 20 | Connection refused until a slot opens |
| Max connections per IP | 3 | Your IP is rejected until a slot opens |
| Requests per minute per connection | 30 | Error frame returned; connection stays open |

You can check service health at `http://the.service.ip.or.hostname:8766/` — returns JSON with current load.

---

## Running your own public Whisper service

If you are hosting the service for others to use, deploy [whisper-service/server.py](whisper-service/server.py) on a VPS and tune these environment variables:

```bash
WHISPER_HOST=0.0.0.0          # accept from all IPs
WHISPER_PORT=8765              # WebSocket port (open in firewall)
WHISPER_STATUS_PORT=8766       # plain HTTP status page (open in firewall, or 0 to disable)
WHISPER_MODEL=base             # tiny | base | small | medium | large-v3
WHISPER_DEVICE=cpu             # cpu | cuda
WHISPER_WORKERS=2              # parallel transcription threads
WHISPER_MAX_QUEUE=8            # max buffered jobs before rejection
WHISPER_MAX_CONNECTIONS=20     # global concurrent WebSocket cap
WHISPER_MAX_CONN_PER_IP=3      # connections allowed per source IP
WHISPER_RATE_LIMIT=30          # max audio chunks per minute per connection
```

Open both ports in your firewall:

```bash
ufw allow 8765/tcp   # WebSocket
ufw allow 8766/tcp   # status page (optional)
```

The status page at `http://your-ip:8766/` returns:

```json
{
  "status": "ok",
  "model": "base",
  "connections": 3,
  "max_connections": 20,
  "queue_len": 1,
  "rate_limit_rpm": 30
}
```

Use this URL with an uptime monitor (UptimeRobot, BetterStack, etc.) to track availability.

---

## Whisper Service (faster-whisper + WebSocket)

The plugin communicates with an external Python service over a **persistent WebSocket** connection. The service uses [faster-whisper](https://github.com/SYSTRAN/faster-whisper) — 4–8× faster than openai-whisper with the same model files.

### Setup

```bash
cd whisper-service
pip install -r requirements.txt
python server.py
```

The server listens on `ws://0.0.0.0:8765` by default. Customize with environment variables:

```bash
WHISPER_HOST=0.0.0.0 \
WHISPER_PORT=8765 \
WHISPER_MODEL=base \
WHISPER_DEVICE=cpu \
WHISPER_WORKERS=2 \
python server.py
```

**Available models:** `tiny`, `base`, `small`, `medium`, `large-v3`  
Larger models are more accurate but require more RAM and CPU/GPU time.

**GPU acceleration:** set `WHISPER_DEVICE=cuda` for 10–30× faster transcription on a compatible GPU.

### Health check

Connect any WebSocket client to `ws://127.0.0.1:8765/health` and you'll receive a JSON status message. The plugin also shows connection state in `/vcm status`.

### Running on a remote VPS

1. Bind to `0.0.0.0` (default).
2. Open port `8765` TCP in your cloud firewall and `ufw`.
3. Set `whisper.host` in `config.yml` to the VPS IP.

```bash
# Ubuntu firewall
ufw allow 8765/tcp
```

> Consider restricting port 8765 to your Minecraft server's IP only using `ufw` source rules.

The plugin **auto-reconnects** if the service is restarted — no need to reload or restart the Minecraft server.

---

## Configuration

### `config.yml`

```yaml
whisper:
  host: "127.0.0.1"
  port: 8765
  reconnect_delay_seconds: 5   # initial delay; doubles on each failure, max 30s
  instance_id: "my-server"     # label shown in VPS logs (useful with multiple servers)
  max_inflight: 8              # max concurrent in-flight requests; extras are dropped

audio:
  sample_rate: 48000
  chunk_seconds: 3
  language: ""        # blank = auto-detect; or BCP-47 e.g. "en"

recordings:
  save_flagged: true             # save WAV clip to disk when a word-group fires
  retention_days: 7              # 0 = keep forever (no auto-cleanup)

voice_reports:
  enabled: true
  transcript_minutes: 10        # how many minutes of transcript to include in reports
  notify_staff: true            # ping online staff when a report is filed

database:
  type: sqlite        # sqlite | mysql
  # MySQL settings (ignored for sqlite):
  host: "localhost"
  port: 3306
  database: "vcm"
  username: "root"
  password: ""

ban_hooks:
  provider: auto      # auto | advancedbanx | litebans | vanilla
  custom_ban_command: ""
  custom_tempban_command: ""

moderation:
  enabled: true

word_groups:
  slurs:
    enabled: true
    match_mode: WORD_BOUNDARY   # WORD_BOUNDARY | CONTAINS
    cooldown_seconds: 60
    words: []
    word_list_urls: []          # URLs to download word lists from
    run_commands_as_console:
      - "warn %player% Used a slur in voice chat"
    voice_mute:
      enabled: true
      duration_seconds: 300
    ban:
      enabled: false
      reason: "Voice chat violation: %word%"
      ban_screen_key: "default"
      duration: ""              # blank = permanent; e.g. "7d", "2h30m"
    webhook:
      key: "staff-alerts"

mute:
  # Single channel override — set one of: actionbar, chat, title, bossbar
  # Overrides the per-channel 'enabled' flags in mute.yml.
  # Leave blank to respect mute.yml toggles directly.
  mute_notification: "actionbar"

discord:
  enabled: false
  webhooks:
    staff-alerts:
      url: "https://discord.com/api/webhooks/..."
      username: "VoiceModerator"
  clip:
    clip_seconds: 10            # length of WAV audio clip attached to alerts
```

> **Note:** Discord clips are always sent as **WAV**. No ffmpeg is required on the Minecraft server for this.

#### Command placeholders

| Placeholder | Value |
|---|---|
| `%player%` | In-game name |
| `%uuid%` | Player UUID |
| `%word%` | Matched word |
| `%message%` | Full Whisper transcript |
| `%time%` | ISO-8601 timestamp |
| `%group%` | Word-group ID |

---

### `ban.yml`

Define custom kick screens shown when a player is auto-banned. Supports [MiniMessage](https://docs.advntr.dev/minimessage/format.html).

```yaml
ban_screens:
  default:
    title: "<red><bold>You have been banned"
    message: |
      <gray>Reason: <white>%reason%
      <gray>Expires: <white>%expiry%

  slur_ban:
    title: "<dark_red><bold>Permanently Banned"
    message: |
      <red>Using slurs in voice chat is not allowed.
      <gray>Reason: <white>%reason%
```

**Placeholders:** `%player%`, `%reason%`, `%expiry%`, `%banned_by%`

Reference a screen in your word group:
```yaml
    ban:
      ban_screen_key: "slur_ban"
```

---

### `mute.yml`

Configure what muted players see when they try to speak. You can also use the shorthand `mute.mute_notification` in `config.yml` to select a single notification channel, which overrides all `enabled` flags in this file.

```yaml
mute_notification:
  cooldown_seconds: 5

  actionbar:
    enabled: true
    message: "<red>⚠ You are voice muted. Expires: <white>%expiry%"

  chat:
    enabled: false
    message: "<red>You are voice muted."

  title:
    enabled: false
    title: "<red><bold>Voice Muted"
    subtitle: "<gray>Expires: %expiry%"

  bossbar:
    enabled: false
    message: "<red>Voice muted — expires %expiry%"
    color: RED
    style: SOLID
    duration_ticks: 100
```

**Placeholders:** `%player%`, `%expiry%`, `%reason%`, `%muted_by%`

---

## URL Word Lists

Add external word lists to any word group:

```yaml
word_groups:
  slurs:
    word_list_urls:
      - "https://example.com/slurs.txt"
```

- Downloaded **once** on first start/reload and cached in `plugins/VoicechatModerator/wordlists/`
- Delete the cache file to force a re-download on next reload
- Format: one word per line; lines starting with `#` are comments

---

## Database

Bans and mutes are stored persistently in the database. By default SQLite is used (no setup required). Switch to MySQL for multi-server setups:

```yaml
database:
  type: mysql
  host: "localhost"
  port: 3306
  database: "vcm"
  username: "vcm_user"
  password: "secret"
```

The plugin creates `vcm_mutes` and `vcm_bans` tables automatically.

---

## PlaceholderAPI

Install [PlaceholderAPI](https://hangar.papermc.io/HelpChat/PlaceholderAPI) to use these placeholders in scoreboards, tab lists, chat plugins, etc:

| Placeholder | Example value |
|---|---|
| `%vcm_is_muted%` | `true` / `false` |
| `%vcm_mute_remaining%` | `1h 23m` / `Permanent` / `Not muted` |
| `%vcm_mute_reason%` | reason string |
| `%vcm_is_banned%` | `true` / `false` |
| `%vcm_ban_remaining%` | `2d 5h` / `Permanent` / `Not banned` |
| `%vcm_ban_reason%` | reason string |

---

## Commands & Permissions

| Command | Permission | Description |
|---|---|---|
| `/vcm reload` | `voicechatmoderator.reload` | Reload config, ban.yml, mute.yml, and re-validate webhooks |
| `/vcm enable` | `voicechatmoderator.toggle` | Resume transcription and word matching |
| `/vcm disable` | `voicechatmoderator.toggle` | Pause transcription and word matching (DB mutes still enforced) |
| `/vcm status` | `voicechatmoderator.reload` | Show moderation state and WebSocket connection status |
| `/vcm reviews` | `voicechatmoderator.review` | List pending (unreviewed) voice reports |
| `/vcm viewreport <id>` | `voicechatmoderator.review` | Show full transcript for a report |
| `/vcm markreviewed <id>` | `voicechatmoderator.review` | Mark a report as reviewed |
| `/reportvoice <player>` | `voicechatmoderator.report` (default: true) | Open the report GUI for a player's recent voice activity |

---

## Ban Plugin Hooks

VoicechatModerator auto-detects ban plugins at startup:

1. **AdvancedBan / AdvancedBanX** — uses `ban` / `tempban` commands
2. **LiteBans** — uses `ban` / `tempban` commands
3. **Vanilla** — uses `/ban`

Override with `ban_hooks.provider` or provide custom command templates:

```yaml
ban_hooks:
  provider: litebans
  custom_tempban_command: "tempban %player% %duration% %reason%"
```

---

## Discord Alerts

Each alert includes:
- Player avatar from [mc-heads.net](https://mc-heads.net)
- Matched word and full transcript
- A 10-second **WAV** audio clip (no ffmpeg required on the Minecraft server)

Set `discord.enabled: true` and configure your webhook URL under `discord.webhooks`.

---

## Mute Ladder

Enable escalating auto-mute durations for repeat offenders. Requires LuckPerms.

```yaml
mute_ladder:
  enabled: false
  tiers:
    1: "5m"
    2: "15m"
    3: "30m"
    4: "1h"
    5: "6h"
  max_tier_duration: "24h"
  reset_after_days: 30
```

- Each automated word-group mute advances the player's tier by 1.
- The tier resets to 1 after `reset_after_days` days without a new strike.
- No single mute will exceed `max_tier_duration` regardless of tier.
- Manual mutes (e.g. via other plugins or commands) do **not** advance the ladder.
- When the ladder is on, LuckPerms applies a timed `voicechat.speak: false` temp node — the node expires automatically even if the server restarts.

If `mute_ladder.enabled: true` but LuckPerms is not installed, the ladder is automatically disabled with a clear warning in the console. The plugin continues running with fixed-duration mutes.

---

## Voice Reports

Players can file reports against anyone who has spoken recently:

```
/reportvoice <player>
```

A 3-row chest GUI opens with five categories:

| Category | Description |
|---|---|
| Slur | Racial or discriminatory slurs |
| Excessive Swearing | Repeated heavy profanity |
| Abuse / Harassment | Targeted threats or harassment |
| Sexual Harassment | Unwanted sexual comments |
| Other | Anything that doesn't fit above |

The last `voice_reports.transcript_minutes` minutes of that player's transcript are attached to the report. Staff with `voicechatmoderator.review` are notified in chat and can review/close reports with `/vcm reviews`.

---

## Flagged Clip Saving

When `recordings.save_flagged: true` and a word-group match fires, the 10-second WAV clip is written to:

```
plugins/VoicechatModerator/recordings/<YYYY-MM-DD>/<playerName>-<epoch>.wav
```

The Python service can also mirror-save clips on the VPS side (the `flagged` flag is sent in the WebSocket request). Cleanup is manual — set `retention_days` as a reminder for how long to keep them.

---

## Troubleshooting

**WebSocket not connecting**

Run `/vcm status` — if it shows `DISCONNECTED`, the service is not reachable. Check:
- `whisper.host` and `whisper.port` in `config.yml`
- Port `8765` is open in the firewall (`ufw allow 8765/tcp`)
- The Python service is running (`python server.py`)

**VPS — connection refused**

```bash
# Check service is bound to 0.0.0.0
ss -tlnp | grep 8765

# Open firewall
ufw allow 8765/tcp
```

**Word list not updating**

Delete the cache file and reload:
```
plugins/VoicechatModerator/wordlists/<groupname>.txt
```
Then run `/vcm reload`.

---

## bStats

This plugin collects anonymous usage statistics via [bStats](https://bstats.org). You can opt out by setting `enabled: false` in `plugins/bStats/config.yml`.

---

[![Discord](https://img.shields.io/badge/Discord-Join%20Server-5865F2?logo=discord&logoColor=white)](https://discord.gg/WpYZkrdNVe)
