package com.github.chirillkirkin.chichess.game

const val START_FEN = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

// A position occurring five times is an automatic draw (FIDE 9.6); three times is only claimable.
const val FIVEFOLD_REPETITION_OCCURRENCES = 5
const val THREEFOLD_REPETITION_OCCURRENCES = 3

interface ChessEngine {
    fun sideToMove(fen: String): PieceColor

    /** 1-based index of the half-move (ply) that produced [fen]; `0` for the starting position. */
    fun plyNumber(fen: String): Int

    fun applyMove(fen: String, uci: String): MoveOutcome

    /** Half-moves since the last irreversible move (capture or pawn move) in [fen]. */
    fun halfMoveClock(fen: String): Int

    /**
     * Whether the position after replaying [moves] from the standard start has occurred at least
     * [occurrences] times. Repetition is invisible from a single position, so it needs the history.
     */
    fun isRepetition(moves: List<String>, occurrences: Int): Boolean
}

sealed interface MoveOutcome {
    data class Applied(
        val fenAfter: String,
        val result: GameResult?,
        val terminationReason: TerminationReason?,
    ) : MoveOutcome

    data object Illegal : MoveOutcome
}
