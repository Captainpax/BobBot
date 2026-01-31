# BobBot (OSRS Level Tracker)

A lightweight Discord bot built with **Java 21** and **JDA 6.3.0** that posts when linked Old School RuneScape players gain total levels. Storage is JSON-based (one file per collection) and ready for a future MongoDB swap.

## What this bot does
- Polls OSRS hiscores for linked players.
- Posts a message when a player gains total levels.
- Publishes a leaderboard on demand and on an interval.
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
export discord-token=YOUR_TOKEN
export discord-superuser-id=YOUR_USER_ID
gradle run
```

## Run with Docker
```bash
docker build -t bobbot .

docker run --rm \
  -e discord-token=YOUR_TOKEN \
  -e discord-superuser-id=YOUR_USER_ID \
  -e leaderboard-interval=60m \
  -e poll-interval=300 \
  -e data-dir=/data \
  -v $(pwd)/data:/data \
  bobbot
```

## Environment variables
The bot accepts both dashed and uppercase names so Docker `-e` usage is easy.

### Mandatory
- `discord-token` / `DISCORD_TOKEN`
  - Discord bot token.

### Optional
- `discord-superuser-id` / `DISCORD_SUPERUSER_ID`
  - Discord user ID allowed to run privileged commands.
- `leaderboard-interval` / `LEADERBOARD_INTERVAL`
  - How often to post the leaderboard. Accepts seconds (`300`) or `s/m/h` suffix (`60m`). Default: `60m`.
- `poll-interval` / `POLL_INTERVAL`
  - How often to check OSRS hiscores. Accepts seconds (`300`) or `s/m/h` suffix (`5m`). Default: `5m`.
- `data-dir` / `DATA_DIR`
  - Directory for JSON storage. Default: `data`.

### Template
See `template.env` for a copy/paste starter file that lists all variables with examples.

## Slash commands
- `/invitebot <target> <target_id>`
  - `target`: `chat` or `discord`
  - `target_id`: chat name or guild ID to reference in the reply
- `/link <player_username>`
- `/postleaderboard`
- `/setleaderboard <channel_id>`

## Data files
- `data/players.json` — map of Discord user IDs to linked OSRS usernames and last total level
- `data/settings.json` — stored channel ID for leaderboard posts

## Notes
- Discord bots cannot be added to group DMs; use servers for announcements.
- Set the leaderboard channel with `/setleaderboard` before expecting automatic posts.
- The Gradle wrapper JAR is intentionally omitted to avoid committing binary files; install Gradle 8.14+ locally.
