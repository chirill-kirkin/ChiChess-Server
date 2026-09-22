package com.github.chirillkirkin.chichess.game

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

interface ChessEngine {
    fun sideToMove(fen: String): PieceColor

    /** 1-based index of the half-move (ply) that produced [fen]; `0` for the starting position. */
    fun plyNumber(fen: String): Int

    fun applyMove(fen: String, uci: String): MoveOutcome
}

sealed interface MoveOutcome {
    data class Applied(
        val fenAfter: String,
        val result: GameResult?,
        val terminationReason: TerminationReason?,
    ) : MoveOutcome

    data object Illegal : MoveOutcome
}
