# TalkBot 0.1.0 (Paper 1.21.11)

A first working-scope addon for PvpBot. It does not replace PvpBot's movement or combat AI. It treats selected online PvpBot player entities as chat-enabled bots and sends their generated messages through Paper's `Player.chat()` API.

## Build

Requires Java 21 and Maven 3.9+:

```bash
mvn clean package
```

The jar is `target/TalkBot-0.1.0.jar`.

Alternatively, push this folder to a GitHub repository and use **Actions -> Build TalkBot -> Run workflow**. Download the `TalkBot-plugin` artifact.

## Install

1. Keep PvpBot installed.
2. Put `TalkBot-0.1.0.jar` in `plugins/`.
3. Start the server once, then stop it.
4. Open `plugins/TalkBot/config.yml` and set `api.api-key` to your provider key. The default endpoint is Groq's OpenAI-compatible endpoint; free-tier availability and limits are controlled by Groq and can change.
5. Start the server.
6. Spawn your PvpBot players, then run `/talkbot <exact-bot-name>` for each bot.

## Commands

- `/talkbot <name>` — enable chat for an online bot/player name.
- `/talkbot <name> style <style>` — set `dry`, `sus`, `toxic`, `chill`, `supernice`, `brainrot`, `chillbot1`, or `chillbot2`.
- `/talkbot <name> off` — disable.
- `/talkbot list` — list registered names.

Profiles persist through restarts. Bots must be online when first registered.

## Current prototype limits

- This is an early prototype, not a finished release. PvpBot's documented add-on API does not expose a chat hook; this version uses the Bukkit/Paper Player API and does not call PvpBot internals.
- Bot recognition is manual: only names registered with `/talkbot` are treated as bots. It does not automatically discover PvpBot entities.
- The AI provider key is required for generated responses. Without it, commands and profiles work but no AI messages are generated.
- It currently reacts to ordinary player chat and basic damage/death events. It does not yet understand complex conversation turns, resource needs, coordinates/help offers, full combat states, or robust bot-to-bot turn-taking.
- Use a provider key with a free tier; never paste the key into public chat or commit it to GitHub. Keep it in the server's config.yml.
