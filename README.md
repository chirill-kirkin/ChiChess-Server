# ChiChess-Server

> 🚧 Work in progress

**ChiChess Server** (Chi[rkin]Chess) — a simple server for the chess mobile app.

## Features

- **Guest sessions** — an opaque Bearer token per player.
- **Create & join games** — create a game and share a 10-char invite code; the second player
  joins by that code, colors are assigned randomly.
- **Real-time game channel** — a per-game WebSocket for moves, resignation, and draws
  (offer/accept/decline and claims), with server-authoritative validation via chesslib.
- **Reconnect** — a snapshot restores the full game state on (re)connect.

## Tech stack

- **Language** — Kotlin
- **Networking** — Ktor, Netty, WebSockets
- **Persistence** — SQLite, Exposed
- **DI** — Koin
- **Serialization** — Kotlinx Serialization (JSON)
- **Chess rules & validation library** — [chesslib](https://github.com/bhlangonijr/chesslib)

## Planned features

- **Clock**
- **Accounts**
- **Chat**
