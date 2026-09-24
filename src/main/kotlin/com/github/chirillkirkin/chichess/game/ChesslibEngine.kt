package com.github.chirillkirkin.chichess.game

import com.github.bhlangonijr.chesslib.Board
import com.github.bhlangonijr.chesslib.Side
import com.github.bhlangonijr.chesslib.move.Move

class ChesslibEngine : ChessEngine {
    override fun sideToMove(fen: String): PieceColor = boardFrom(fen).sideToMove.toPieceColor()

    override fun plyNumber(fen: String): Int {
        val board = boardFrom(fen)
        val completedFullMoves = board.moveCounter - 1
        val blackHasMovedInFullMove = if (board.sideToMove == Side.WHITE) 0 else 1
        return completedFullMoves * 2 + blackHasMovedInFullMove
    }

    override fun applyMove(fen: String, uci: String): MoveOutcome {
        val board = boardFrom(fen)
        val move = try {
            Move(uci, board.sideToMove)
        } catch (_: RuntimeException) {
            return MoveOutcome.Illegal
        }
        if (move !in board.legalMoves()) return MoveOutcome.Illegal
        board.doMove(move)
        return MoveOutcome.Applied(
            fenAfter = board.fen,
            result = board.result(),
            terminationReason = board.terminationReason(),
        )
    }

    override fun halfMoveClock(fen: String): Int = boardFrom(fen).halfMoveCounter

    override fun isRepetition(moves: List<String>, occurrences: Int): Boolean {
        val board = Board()
        moves.forEach { uci -> board.doMove(Move(uci, board.sideToMove)) }
        return board.isRepetition(occurrences)
    }

    private fun boardFrom(fen: String): Board = Board().apply { loadFromFen(fen) }
}

// FIDE's 75-move rule ends the game automatically after 150 half-moves without progress.
private const val SEVENTY_FIVE_MOVE_RULE_PLIES = 150

private fun Side.toPieceColor(): PieceColor = if (this == Side.WHITE) PieceColor.WHITE else PieceColor.BLACK

private fun Board.result(): GameResult? = when {
    isMated -> if (sideToMove == Side.WHITE) GameResult.BLACK_WON else GameResult.WHITE_WON
    autoDrawReason() != null -> GameResult.DRAW
    else -> null
}

private fun Board.terminationReason(): TerminationReason? =
    if (isMated) TerminationReason.CHECKMATE else autoDrawReason()

// Draws the server settles automatically. The 50-move rule is a claim (handled elsewhere), and
// repetition needs the move history, which a single loaded position does not carry — so neither
// is decided here.
private fun Board.autoDrawReason(): TerminationReason? = when {
    isStaleMate -> TerminationReason.STALEMATE
    isInsufficientMaterial -> TerminationReason.INSUFFICIENT_MATERIAL
    halfMoveCounter >= SEVENTY_FIVE_MOVE_RULE_PLIES -> TerminationReason.SEVENTY_FIVE_MOVE_RULE
    else -> null
}
