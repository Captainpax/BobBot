# BobBot (OSRS Level Tracker)

A lightweight Discord bot built with **Java 21** and **JDA 5.2.2** that posts when linked Old School RuneScape players gain total levels. Storage is JSON-based (one file per collection) and ready for a future MongoDB swap.

## What this bot does
- Polls OSRS hiscores for linked players.
- Posts a message when a player gains total levels.
- Publishes a leaderboard on demand and on an interval.
- Exposes a lightweight `/health` HTTP endpoint for uptime checks.
- Uses slash commands only.

## Project layout
- `src/main/java/com/bobbot` — application entry point
- `src/main/java/com/bobbot/discord` — slash command listener
- `src/main/java/com/bobbot/osrs` — OSRS hiscore client
- `src/main/java/com/bobbot/service` — level scan + leaderboard services
- `src/main/java/com/bobbot/storage` — JSON storage and models
- `data/` — runtime JSON data (created at runtime)

## Build
```bash
gradle clean build
```

## Run locally
```bash
export DISCORD_TOKEN=YOUR_TOKEN
export DISCORD_SUPERUSER_ID=YOUR_USER_ID
gradle run
```

## Run with Docker
```bash
docker build -t bobbot .

docker run --rm \
  -e DISCORD_TOKEN=YOUR_TOKEN \
  -e DISCORD_SUPERUSER_ID=YOUR_USER_ID \
  -e LEADERBOARD_INTERVAL=60m \
  -e POLL_INTERVAL=300 \
  -e DATA_DIR=/data \
  -e HEALTH_PORT=8080 \
  -p 8080:8080 \
  -v $(pwd)/data:/data \
  bobbot
```

## Environment variables
Prefer `DISCORD_TOKEN` and other uppercase names for shells, IDE run configurations, and `.env` files. Docker accepts uppercase names reliably across operating systems, while dashed names may be ignored by some container runtimes.

### Mandatory
- `DISCORD_TOKEN`
  - Discord bot token.

### Optional
- `DISCORD_SUPERUSER_ID`
  - Discord user ID allowed to run privileged commands.
- `LEADERBOARD_INTERVAL`
  - How often to post the leaderboard. Accepts seconds (`300`) or `s/m/h` suffix (`60m`). Default: `60m`.
- `POLL_INTERVAL`
  - How often to check OSRS hiscores. Accepts seconds (`300`) or `s/m/h` suffix (`5m`). Default: `5m`.
- `DATA_DIR`
  - Directory for JSON storage. Default: `data`.
- `HEALTH_PORT` or `PORT`
  - Port for the `/health` HTTP endpoint. Default: `8080`.

### Template
See `template.env` for a copy/paste starter file that lists all variables with examples.

## Slash commands
### Player Commands (`/os`)
- `/os link <player_name>` — Link your Discord account to an OSRS username.
- `/os unlink` — Unlink your OSRS username.
- `/os stats [skill]` — Show your current levels and gains. Optional `skill` filters for one skill (e.g., `attack`, `mining`) or `all`.
- `/os pricelookup <item>` — Look up the current G.E. price of an item.

### Admin Commands (`/admin`)
- `/admin postleaderboard [skill]` — Manually post the leaderboard. Optional `skill` forces a specific skill leaderboard.
- `/admin setleaderboard <channel_id>` — Set the channel where level-ups and leaderboards are posted.
- `/admin set env <value>` — Update the bot's environment setting (e.g., `production`, `development`).
- `/admin set bobschat <channel_id>` — Set the main channel for Bob's pings.
- `/admin ai url <url>` — Set the local AI API URL (OpenAI-compatible).
- `/admin ai model <model>` — Set the AI model name.
- `/admin ai personality <file>` — Upload a `personality.txt` file to define Bob's personality.
- `/admin ai test [prompt]` — Test the AI configuration with an optional prompt.
- `/admin invite <target> <target_id>` — Get an invite link or info for chat installs.
- `/admin health` — Check bot health and connectivity.
- `/admin status <state>` — Update the bot's presence status (`online`, `busy`, `offline`).
- `/admin power <action>` — Restart or shutdown the bot.
- `/admin addadmin <user_id>` — Add a user to the persistent admin list.
- `/admin removeadmin <user_id>` — Remove a user from the admin list.

## Health endpoint
- `GET /health` returns a plain-text status report with Discord connectivity, OSRS probe, and scheduling details.

## Data files
- `data/players.json` — map of Discord user IDs to linked OSRS usernames and last total level
- `data/settings.json` — stored channel ID for leaderboard posts

## Customization
- `personality.txt` — Create this file in the project root or `data/` directory to define Bob's personality. If present, the AI will use these instructions to shape its responses.

## Notes
- Discord bots cannot be added to group DMs; use servers for announcements.
- Set the leaderboard channel with `/setleaderboard` before expecting automatic posts.
- The Gradle wrapper JAR is intentionally omitted to avoid committing binary files; install Gradle 8.14+ locally.
