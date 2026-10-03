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
import io.ktor.client.request.bearerAuth
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val WRONG_PROTOCOL_VERSION = 999

class GameSocketTest {
    @Test
    fun `connecting participant receives a snapshot`() = testGame { game, ws ->
        ws.gameSocket(game.gameId, game.creator.token) {
            val event = assertIs<SnapshotEvent>(receiveEvent())
            assertEquals(game.gameId, event.snapshot.gameId)
            assertEquals(GameStatus.WAITING_FOR_OPPONENT, event.snapshot.status)
        }
    }

    @Test
    fun `connecting without a token fails the handshake`() = testGame { game, ws ->
        // Missing bearer -> 401 on the upgrade, so the socket never opens.
        assertFails { ws.gameSocket(game.gameId, token = null) {} }
    }

    @Test
    fun `connecting to an unknown game is rejected`() = testGame { game, ws ->
        ws.gameSocket(UUID.randomUUID().toString(), game.creator.token) {
            assertEquals(CLOSE_NOT_FOUND, closeReason.await()?.code)
        }
    }

    @Test
    fun `non-participant is rejected`() = testGame { game, ws ->
        val outsider = ws.createGuestSession()

        ws.gameSocket(game.gameId, outsider.token) {
            assertEquals(CLOSE_FORBIDDEN, closeReason.await()?.code)
        }
    }

    @Test
    fun `request sync returns a fresh snapshot`() = testGame { game, ws ->
        ws.gameSocket(game.gameId, game.creator.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(RequestSync(GAME_PROTOCOL_VERSION, commandId = SYNC_COMMAND_ID))
            assertIs<SnapshotEvent>(receiveEvent())
        }
    }

    @Test
    fun `unsupported protocol version is rejected`() = testGame { game, ws ->
        ws.gameSocket(game.gameId, game.creator.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(RequestSync(WRONG_PROTOCOL_VERSION, commandId = SYNC_COMMAND_ID))
            val rejected = assertIs<CommandRejectedEvent>(receiveEvent())
            assertEquals(UNSUPPORTED_PROTOCOL_VERSION_CODE, rejected.code)
        }
    }

    @Test
    fun `malformed command is rejected`() = testGame { game, ws ->
        ws.gameSocket(game.gameId, game.creator.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            send(Frame.Text(MALFORMED_COMMAND_TEXT))
            val rejected = assertIs<CommandRejectedEvent>(receiveEvent())
            assertEquals(MALFORMED_COMMAND_CODE, rejected.code)
        }
    }

    @Test
    fun `other participant is notified when a player connects`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.gameSocket(game.gameId, game.creator.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.gameSocket(game.gameId, joiner.token) {
                assertIs<SnapshotEvent>(receiveEvent())
            }
            assertIs<PlayerJoinedEvent>(receiveEvent())
        }
    }

    @Test
    fun `other participant is told when a player leaves and when they return`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.gameSocket(game.gameId, game.creator.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.gameSocket(game.gameId, joiner.token) {
                assertIs<SnapshotEvent>(receiveEvent())
            }
            assertIs<PlayerJoinedEvent>(receiveEvent())
            assertIs<PlayerLeftEvent>(receiveEvent())

            ws.gameSocket(game.gameId, joiner.token) {
                assertIs<SnapshotEvent>(receiveEvent())
            }
            assertIs<PlayerJoinedEvent>(receiveEvent())
        }
    }

    @Test
    fun `snapshot reports an opponent who has not connected yet as offline`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.gameSocket(game.gameId, game.creator.token) {
            assertFalse(assertIs<SnapshotEvent>(receiveEvent()).opponentConnected)
        }
    }

    @Test
    fun `snapshot reports an already connected opponent as online`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.gameSocket(game.gameId, game.creator.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.gameSocket(game.gameId, joiner.token) {
                assertTrue(assertIs<SnapshotEvent>(receiveEvent()).opponentConnected)
            }
        }
    }

    @Test
    fun `reconnecting player sees that the opponent left while they were away`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.gameSocket(game.gameId, joiner.token) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.gameSocket(game.gameId, game.creator.token) {
                assertIs<SnapshotEvent>(receiveEvent())
            }
            assertIs<PlayerJoinedEvent>(receiveEvent())
            // Waiting for PLAYER_LEFT guarantees the server has dropped the creator before the reconnect.
            assertIs<PlayerLeftEvent>(receiveEvent())
        }
        ws.gameSocket(game.gameId, joiner.token) {
            assertFalse(assertIs<SnapshotEvent>(receiveEvent()).opponentConnected)
        }
    }

    @Test
    fun `request sync reports the opponent's current presence`() = testGame { game, ws ->
        val joiner = ws.createGuestSession()
        ws.postJoinGame(game.inviteCode, joiner.token)

        ws.gameSocket(game.gameId, game.creator.token) {
            val creatorSocket = this
            assertIs<SnapshotEvent>(receiveEvent())
            ws.gameSocket(game.gameId, joiner.token) {
                assertIs<SnapshotEvent>(receiveEvent())
                assertIs<PlayerJoinedEvent>(creatorSocket.receiveEvent())
                creatorSocket.sendCommand(RequestSync(GAME_PROTOCOL_VERSION, SYNC_COMMAND_ID))
                assertTrue(assertIs<SnapshotEvent>(creatorSocket.receiveEvent()).opponentConnected)
            }
            assertIs<PlayerLeftEvent>(receiveEvent())
            sendCommand(RequestSync(GAME_PROTOCOL_VERSION, SYNC_COMMAND_ID))
            assertFalse(assertIs<SnapshotEvent>(receiveEvent()).opponentConnected)
        }
    }

    @Test
    fun `white player makes a legal move`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, MOVE_COMMAND_ID, snapshot.revision, OPENING_MOVE))
            val applied = assertIs<MoveAppliedEvent>(receiveEvent())
            assertEquals(OPENING_MOVE, applied.lastMove)
            assertEquals(snapshot.revision + 1, applied.revision)
            assertEquals(GameStatus.IN_PROGRESS, applied.status)
        }
    }

    @Test
    fun `snapshot restores the last move after a sync`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            assertEquals(null, snapshot.lastMove)
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, MOVE_COMMAND_ID, snapshot.revision, OPENING_MOVE))
            assertIs<MoveAppliedEvent>(receiveEvent())

            sendCommand(RequestSync(GAME_PROTOCOL_VERSION, SYNC_COMMAND_ID))
            assertEquals(OPENING_MOVE, assertIs<SnapshotEvent>(receiveEvent()).snapshot.lastMove)
        }
    }

    @Test
    fun `move with a stale revision is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, MOVE_COMMAND_ID, snapshot.revision + STALE_REVISION_OFFSET, OPENING_MOVE))
            assertEquals(REVISION_CONFLICT_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
            assertIs<SnapshotEvent>(receiveEvent())
        }
    }

    @Test
    fun `illegal move is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, MOVE_COMMAND_ID, snapshot.revision, ILLEGAL_MOVE_UCI))
            assertEquals(ILLEGAL_MOVE_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
        }
    }

    @Test
    fun `moving out of turn is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.blackToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, MOVE_COMMAND_ID, snapshot.revision, OPENING_REPLY))
            assertEquals(NOT_YOUR_TURN_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
        }
    }

    @Test
    fun `resigning finishes the game`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(Resign(GAME_PROTOCOL_VERSION, RESIGN_COMMAND_ID, snapshot.revision))
            val finished = assertIs<GameFinishedEvent>(receiveEvent())
            assertEquals(GameStatus.FINISHED, finished.status)
            assertEquals(TerminationReason.RESIGNATION, finished.terminationReason)
        }
    }

    @Test
    fun `duplicate move command is ignored`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, DUPLICATE_COMMAND_ID, snapshot.revision, OPENING_MOVE))
            val applied = assertIs<MoveAppliedEvent>(receiveEvent())

            sendCommand(MakeMove(GAME_PROTOCOL_VERSION, DUPLICATE_COMMAND_ID, applied.revision, OPENING_MOVE))
            assertEquals(applied.revision, assertIs<SnapshotEvent>(receiveEvent()).snapshot.revision)
        }
    }

    @Test
    fun `both players receive an applied move`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.blackToken) {
            assertIs<SnapshotEvent>(receiveEvent())
            ws.gameSocket(game.gameId, started.whiteToken) {
                val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
                sendCommand(MakeMove(GAME_PROTOCOL_VERSION, MOVE_COMMAND_ID, snapshot.revision, OPENING_MOVE))
                assertIs<MoveAppliedEvent>(receiveEvent())
            }
            // The black connection first sees PlayerJoined for white, then the broadcast move.
            var event = receiveEvent()
            while (event !is MoveAppliedEvent) event = receiveEvent()
            assertEquals(OPENING_MOVE, event.lastMove)
        }
    }

    @Test
    fun `offering a draw notifies the player and persists for reconnect`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(OfferDraw(GAME_PROTOCOL_VERSION, OFFER_COMMAND_ID))
            assertEquals(PieceColor.WHITE, assertIs<DrawOfferedEvent>(receiveEvent()).by)

            sendCommand(RequestSync(GAME_PROTOCOL_VERSION, SYNC_COMMAND_ID))
            assertEquals(PieceColor.WHITE, assertIs<SnapshotEvent>(receiveEvent()).snapshot.pendingDrawOfferBy)
        }
    }

    @Test
    fun `accepting a draw finishes the game by agreement`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(OfferDraw(GAME_PROTOCOL_VERSION, OFFER_COMMAND_ID))
            assertIs<DrawOfferedEvent>(receiveEvent())
        }
        ws.gameSocket(game.gameId, started.blackToken) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(AcceptDraw(GAME_PROTOCOL_VERSION, ACCEPT_COMMAND_ID))
            val finished = assertIs<GameFinishedEvent>(receiveEvent())
            assertEquals(GameResult.DRAW, finished.result)
            assertEquals(TerminationReason.AGREEMENT, finished.terminationReason)
        }
    }

    @Test
    fun `declining a draw notifies the player`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(OfferDraw(GAME_PROTOCOL_VERSION, OFFER_COMMAND_ID))
            assertIs<DrawOfferedEvent>(receiveEvent())
        }
        ws.gameSocket(game.gameId, started.blackToken) {
            assertIs<SnapshotEvent>(receiveEvent())
            sendCommand(DeclineDraw(GAME_PROTOCOL_VERSION, DECLINE_COMMAND_ID))
            assertIs<DrawDeclinedEvent>(receiveEvent())
        }
    }

    @Test
    fun `claiming a draw with no grounds is rejected`() = testGame { game, ws ->
        val started = ws.startGame(game)
        ws.gameSocket(game.gameId, started.whiteToken) {
            val snapshot = assertIs<SnapshotEvent>(receiveEvent()).snapshot
            sendCommand(ClaimDraw(GAME_PROTOCOL_VERSION, CLAIM_COMMAND_ID, snapshot.revision))
            assertEquals(DRAW_NOT_CLAIMABLE_CODE, assertIs<CommandRejectedEvent>(receiveEvent()).code)
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
    )
}

private const val CLOSE_FORBIDDEN: Short = 4403
private const val CLOSE_NOT_FOUND: Short = 4404

private suspend fun HttpClient.gameSocket(
    gameId: String,
    token: String?,
    block: suspend DefaultClientWebSocketSession.() -> Unit,
) = webSocket("$GAME_ROUTE/$gameId", request = { token?.let(::bearerAuth) }, block = block)

private suspend fun DefaultClientWebSocketSession.receiveEvent(): GameEvent {
    val frame = incoming.receive() as Frame.Text
    return gameProtocolJson.decodeFromString(frame.readText())
}

private suspend fun DefaultClientWebSocketSession.sendCommand(command: GameCommand) {
    send(Frame.Text(gameProtocolJson.encodeToString(command)))
}
