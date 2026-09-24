# ChiChess-Server

Backend for the ChiChess online chess game. The server is the source of truth for
online play; clients may validate moves locally for UX, but confirmed state comes
only from the server.

## Commands

```
./gradlew build          # compile + test
./gradlew run            # start the server (Netty, port 8080)
./gradlew test           # JUnit Platform (ktor server test host)
```

## Tech stack

- **Kotlin/JVM 21** (toolchain managed by Gradle), Kotlin official code style.
- **Ktor + Netty** — explicit `embeddedServer` entry point. Default port `8080`,
  overridable via the `PORT` env var.
- **SQLite + Exposed** for persistence (`jdbc:sqlite:./chichess.db`).
- **Koin** for dependency injection.
- **kotlinx.serialization** JSON.
- Ktor **Authentication** (Bearer) + **WebSockets**, **StatusPages** for errors, logback.

## Structure

`src/main/kotlin/com/github/chirillkirkin/chichess/`

- `Main.kt` — explicit Netty entry point + composition root (`Application.module()`).
- `config/` — `Authentication`, `Exposed`, `ErrorHandling`, `Koin`, `Routing`,
  `Serialization`, `Websockets`. Each exposes a `configure*()` function wired in `module()`.
- `di/` — Koin bindings (`AppModule`).
- `session/` — guest sessions (service, repository, routes).
- `game/` — game domain (service, repository, routes).
- `api/` — shared API DTOs (e.g. `ApiErrorResponse`).

Feature code follows the `service` / `repository` / `routes` split per feature package.

## Rules

- **Server is the source of truth** for online games: validate the move, persist it,
  bump a monotonic `revision`, then broadcast. Clients only mirror confirmed state.
- **Error codes** are machine-readable `UPPER_SNAKE_CASE`, not user-facing text — the
  client picks a localized message per code. Unexpected exceptions become
  `{"code":"INTERNAL_ERROR"}`.
- The server does **not** normalize invite codes (no `trim()`/`uppercase()`); the client
  owns invite-code validity.
- Do **not** store `sessionId` in `UserIdPrincipal.name`; use `GuestPrincipal(sessionId)`.
- **Tokens** are opaque Bearer tokens from `SecureRandom`; only their SHA-256 hash is
  stored in SQLite. `SecureRandom` is registered in Koin as `RandomGenerator`.
- Do **not** use `SchemaUtils.createMissingTablesAndColumns` (Exposed flags it as unsafe).
  Change an existing schema through versioned migrations (e.g. Flyway).
- Give meaningful literals **named constants next to their feature**. Do not create a
  central file of all route constants. Feature routes are `Route` extensions;
  `configureRouting()` is the single registration point for feature routes.
- Access control never relies on `gameId` alone: the server always checks the token's
  `sessionId` against the game participants.
- Only `.DS_Store` is left untracked in the repo; do not add or remove it without a
  separate request.

## HTTP contract

```
POST /sessions/guest        # -> sessionId + opaque Bearer token
POST /game                  # create; -> gameId + 10-char invite code
POST /game/join             # body {"inviteCode":"..."}; joins by invite code
GET  /game/{id}             # -> GameSnapshot for a participant
GET  /games/history         # -> caller's games as a GameSnapshot list
```

- `gameId` is the permanent identifier of a game; the invite code is only for the second
  player to join. Colors (`white`/`black`) are assigned randomly.
- `GameSnapshot` is the per-caller restore format: `yourColor`, `status`, `revision`, `fen`,
  `lastMove`, `pendingDrawOfferBy`, and `result`/`terminationReason` once finished.
- Join errors: `GAME_NOT_FOUND`, `CANNOT_JOIN_OWN_GAME`, `GAME_ALREADY_JOINED`. The joining
  player fills the empty color slot under a `<color>_session_id IS NULL` guard (race protection).
- Read errors: `GAME_NOT_FOUND` (404), `NOT_A_GAME_PARTICIPANT` (403).

## WebSocket protocol

```
WS   /game/{id}?token=...    # live game channel for a participant
```

- Auth: `token` is passed as a query parameter (WebSocket carries no bearer header).
  Non-participant / unknown game / bad token close the socket with an application close
  code (`4401` unauthorized, `4403` not a participant, `4404` not found) whose reason is
  the domain code.
- Messages are JSON with a `"type"` discriminator. Every command carries `protocolVersion`
  and `commandId`.
- Commands (client -> server): `REQUEST_SYNC`; `MAKE_MOVE` (`expectedRevision`, `uci`);
  `RESIGN` (`expectedRevision`, ignored — resignation is unconditional); `OFFER_DRAW`,
  `ACCEPT_DRAW`, `DECLINE_DRAW` (no `expectedRevision`).
- Events (server -> client): `SNAPSHOT` (on connect and `REQUEST_SYNC`); `PLAYER_JOINED`
  (a participant connected); `MOVE_APPLIED` and `GAME_FINISHED` (color-neutral state
  deltas, broadcast to all); `DRAW_OFFERED` (`by`) and `DRAW_DECLINED`; `COMMAND_REJECTED`
  (`code`, to the sender).
- Mutations run under a per-game lock; `expectedRevision != revision` -> `COMMAND_REJECTED`
  (`REVISION_CONFLICT`) plus a fresh `SNAPSHOT`. A repeated `commandId` (recorded atomically
  with the move) is idempotent: the sender just receives the current `SNAPSHOT`.
- Draw offers are side-state: they set `pendingDrawOfferBy` without bumping `revision` and are
  cleared by any move or a decline. Only the opponent may accept or decline; accepting finishes
  the game as `DRAW` / `AGREEMENT`.
- Command error codes: `UNSUPPORTED_PROTOCOL_VERSION`, `MALFORMED_COMMAND`, `NOT_YOUR_TURN`,
  `ILLEGAL_MOVE`, `GAME_NOT_READY`, `GAME_FINISHED`, `REVISION_CONFLICT`, `NO_DRAW_OFFER`,
  `DRAW_ALREADY_OFFERED`.
