package com.github.chirillkirkin.chichess.game

import com.github.chirillkirkin.chichess.configureTestApplication
import com.github.chirillkirkin.chichess.decodeJsonBody
import com.github.chirillkirkin.chichess.session.GuestSessionResponse
import com.github.chirillkirkin.chichess.session.createGuestSession
import com.github.chirillkirkin.chichess.withTestDatabase
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private const val WRONG_PROTOCOL_VERSION = 999

class GameSocketTest {
    @Test
    fun `connecting participant receives a snapshot`() = testGame { game, ws ->
        ws.webSocket(game.socketUrl(game.creator.token)) {
            val event = assertIs<SnapshotEvent>(receiveEvent())
            assertEquals(game.gameId, event.snapshot.gameId)
            assertEquals(GameStatus.WAITING_FOR_OPPONENT, event.snapshot.status)
        }
    }

    @Test
    fun `connecting without a token is rejected`() = testGame { game, ws ->
        ws.webSocket(game.socketUrl(token = null)) {
            assertEquals(CLOSE_UNAUTHORIZED, closeReason.await()?.code)
        }
    }

    @Test
    fun `connecting to an unknown game is rejected`() = testGame { game, ws ->
        ws.webSocket("$GAME_ROUTE/${UUID.randomUUID()}?$GAME_SOCKET_TOKEN_PARAMETER=${game.creator.token}") {
            assertEquals(CLOSE_NOT_FOUND, closeReason.await()?.code)
        }
    }

    @Test
    fun `non-participant is rejected`() = testGame { game, ws ->
        val outsider = ws.createGuestSession()

        ws.webSocket(game.socketUrl(outsider.token)) {
            assertEquals(CLOSE_FORBIDDEN, closeReason.await()?.code)
        }
    }

    @Test
    fun `request sync returns a fresh snapshot`() = testGame { game, ws ->
        ws.webSocket(game.socketUrl(game.creator.token)) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(RequestSync(GAME_PROTOCOL_VERSION, commandId = "sync-1"))
            assertIs<SnapshotEvent>(receiveEvent())
        }
    }

    @Test
    fun `unsupported protocol version is rejected`() = testGame { game, ws ->
        ws.webSocket(game.socketUrl(game.creator.token)) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(RequestSync(WRONG_PROTOCOL_VERSION, commandId = "sync-1"))
            val rejected = assertIs<CommandRejectedEvent>(receiveEvent())
            assertEquals(UNSUPPORTED_PROTOCOL_VERSION_CODE, rejected.code)
        }
    }

    @Test
    fun `malformed command is rejected`() = testGame { game, ws ->
        ws.webSocket(game.socketUrl(game.creator.token)) {
            assertIs<SnapshotEvent>(receiveEvent())
            send(Frame.Text("not json"))
            val rejected = assertIs<CommandRejectedEvent>(receiveEvent())
            assertEquals(MALFORMED_COMMAND_CODE, rejected.code)
        }
    }

    @Test
    fun `other participant is notified when a player connects`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.webSocket(game.socketUrl(game.creator.token)) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.webSocket(game.socketUrl(joiner.token)) {
                assertIs<SnapshotEvent>(receiveEvent())
            }
            assertIs<PlayerJoinedEvent>(receiveEvent())
        }
    }

    @Test
    fun `white player makes a legal move`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.whiteToken)) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "m1", snapshot.revision, "e2e4"))
            val applied = assertIs<MoveAppliedEvent>(receiveEvent())
            assertEquals("e2e4", applied.lastMove)
            assertEquals(snapshot.revision + 1, applied.revision)
            assertEquals(GameStatus.IN_PROGRESS, applied.status)
        }
    }

    @Test
    fun `move with a stale revision is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.whiteToken)) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "m1", snapshot.revision + 5, "e2e4"))
            assertEquals(REVISION_CONFLICT_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
            assertIs<SnapshotEvent>(receiveEvent())
        }
    }

    @Test
    fun `illegal move is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.whiteToken)) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "m1", snapshot.revision, "e2e5"))
            assertEquals(ILLEGAL_MOVE_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
        }
    }

    @Test
    fun `moving out of turn is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.blackToken)) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "m1", snapshot.revision, "e7e5"))
            assertEquals(NOT_YOUR_TURN_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
        }
    }

    @Test
    fun `resigning finishes the game`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.whiteToken)) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(Resign(GAME_PROTOCOL_VERSION, "r1", snapshot.revision))
            val finished = assertIs<GameFinishedEvent>(receiveEvent())
            assertEquals(GameStatus.FINISHED, finished.status)
            assertEquals(TerminationReason.RESIGNATION, finished.terminationReason)
        }
    }

    @Test
    fun `duplicate move command is ignored`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.whiteToken)) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "dup", snapshot.revision, "e2e4"))
            val applied = assertIs<MoveAppliedEvent>(receiveEvent())

            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "dup", applied.revision, "e2e4"))
            assertEquals(applied.revision, assertIs<SnapshotEvent>(receiveEvent()).snapshot.revision)
        }
    }

    @Test
    fun `both players receive an applied move`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.webSocket(game.socketUrl(started.blackToken)) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.webSocket(game.socketUrl(started.whiteToken)) {
                val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
                sendCommand(MakeMove(GAME_PROTOCOL_VERSION, "m1", snapshot.revision, "e2e4"))
                assertIs<MoveAppliedEvent>(receiveEvent())
            }
            // The black connection first sees PlayerJoined for white, then the broadcast move.
            var event = receiveEvent()
            while (event !is MoveAppliedEvent) event = receiveEvent()
            assertEquals("e2e4", event.lastMove)
        }
    }

    private suspend fun HttpClient.startGame(game: GameFixture): StartedGame {
        val joiner = createGuestSession()
        postJoinGame(game.inviteCode, joiner.token)
        val creatorColor = getGame(game.gameId, game.creator.token).decodeJsonBody<GameSnapshot>().yourColor
        return if (creatorColor == PieceColor.WHITE) {
            StartedGame(whiteToken = game.creator.token, blackToken = joiner.token)
        } else {
            StartedGame(whiteToken = joiner.token, blackToken = game.creator.token)
        }
    }

    private data class StartedGame(val whiteToken: String, val blackToken: String)

    private fun testGame(test: suspend ApplicationTestBuilder.(GameFixture, HttpClient) -> Unit) {
        withTestDatabase { databaseUrl ->
            testApplication {
                configureTestApplication(databaseUrl)
                val ws = createClient { install(WebSockets) }
                val creator = ws.createGuestSession()
                val game = ws.postCreateGame(creator.token).decodeJsonBody<CreateGameResponse>()
                test(GameFixture(game.gameId, game.inviteCode, creator), ws)
            }
        }
    }

    private data class GameFixture(
        val gameId: String,
        val inviteCode: String,
        val creator: GuestSessionResponse,
    ) {
        fun socketUrl(token: String?): String {
            val query = token?.let { "?$GAME_SOCKET_TOKEN_PARAMETER=$it" }.orEmpty()
            return "$GAME_ROUTE/$gameId$query"
        }
    }
}

private const val CLOSE_UNAUTHORIZED: Short = 4401
private const val CLOSE_FORBIDDEN: Short = 4403
private const val CLOSE_NOT_FOUND: Short = 4404

private suspend fun DefaultClientWebSocketSession.receiveEvent(): GameEvent {
    val frame = incoming.receive() as Frame.Text
    return gameProtocolJson.decodeFromString(frame.readText())
}

private suspend fun DefaultClientWebSocketSession.sendCommand(command: GameCommand) {
    send(Frame.Text(gameProtocolJson.encodeToString(command)))
}
