package com.github.chirillkirkin.chichess.game

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val GAME_PROTOCOL_VERSION = 1

const val UNSUPPORTED_PROTOCOL_VERSION_CODE = "UNSUPPORTED_PROTOCOL_VERSION"
const val MALFORMED_COMMAND_CODE = "MALFORMED_COMMAND"
const val NOT_YOUR_TURN_CODE = "NOT_YOUR_TURN"
const val ILLEGAL_MOVE_CODE = "ILLEGAL_MOVE"
const val GAME_NOT_READY_CODE = "GAME_NOT_READY"
const val GAME_FINISHED_CODE = "GAME_FINISHED"
const val REVISION_CONFLICT_CODE = "REVISION_CONFLICT"

internal val gameProtocolJson = Json {
    ignoreUnknownKeys = true
    classDiscriminator = "type"
}

@Serializable
sealed interface GameCommand {
    val protocolVersion: Int
    val commandId: String
}

@Serializable
@SerialName("REQUEST_SYNC")
data class RequestSync(
    override val protocolVersion: Int,
    override val commandId: String,
) : GameCommand

@Serializable
@SerialName("MAKE_MOVE")
data class MakeMove(
    override val protocolVersion: Int,
    override val commandId: String,
    val expectedRevision: Long,
    val uci: String,
) : GameCommand

@Serializable
@SerialName("RESIGN")
data class Resign(
    override val protocolVersion: Int,
    override val commandId: String,
    val expectedRevision: Long,
) : GameCommand

@Serializable
sealed interface GameEvent

@Serializable
@SerialName("SNAPSHOT")
data class SnapshotEvent(val snapshot: GameSnapshot) : GameEvent

@Serializable
@SerialName("PLAYER_JOINED")
data class PlayerJoinedEvent(val color: PieceColor) : GameEvent

@Serializable
@SerialName("MOVE_APPLIED")
data class MoveAppliedEvent(
    val revision: Long,
    val fen: String,
    val status: GameStatus,
    val lastMove: String,
    val result: GameResult? = null,
    val terminationReason: TerminationReason? = null,
) : GameEvent

@Serializable
@SerialName("GAME_FINISHED")
data class GameFinishedEvent(
    val revision: Long,
    val status: GameStatus,
    val result: GameResult,
    val terminationReason: TerminationReason,
) : GameEvent

@Serializable
@SerialName("COMMAND_REJECTED")
data class CommandRejectedEvent(val commandId: String?, val code: String) : GameEvent
