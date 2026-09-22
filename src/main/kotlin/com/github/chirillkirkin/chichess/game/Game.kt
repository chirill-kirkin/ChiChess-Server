package com.github.chirillkirkin.chichess.game

import java.util.UUID
import kotlinx.serialization.Serializable

@Serializable
enum class PieceColor { WHITE, BLACK }

@Serializable
enum class GameStatus { WAITING_FOR_OPPONENT, IN_PROGRESS, FINISHED }

@Serializable
enum class GameResult { WHITE_WON, BLACK_WON, DRAW }

@Serializable
enum class TerminationReason { CHECKMATE, STALEMATE, RESIGNATION }

data class Game(
    val id: UUID,
    val inviteCode: String,
    val whiteSessionId: UUID?,
    val blackSessionId: UUID?,
    val status: GameStatus,
    val revision: Long,
    val fen: String,
    val result: GameResult?,
    val terminationReason: TerminationReason?,
) {
    fun colorOf(sessionId: UUID): PieceColor? = when (sessionId) {
        whiteSessionId -> PieceColor.WHITE
        blackSessionId -> PieceColor.BLACK
        else -> null
    }
}
