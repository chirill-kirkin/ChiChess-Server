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

    private fun boardFrom(fen: String): Board = Board().apply { loadFromFen(fen) }
}

private fun Side.toPieceColor(): PieceColor = if (this == Side.WHITE) PieceColor.WHITE else PieceColor.BLACK

private fun Board.result(): GameResult? = when {
    isMated -> if (sideToMove == Side.WHITE) GameResult.BLACK_WON else GameResult.WHITE_WON
    isStaleMate || isDraw -> GameResult.DRAW
    else -> null
}

private fun Board.terminationReason(): TerminationReason? = when {
    isMated -> TerminationReason.CHECKMATE
    isStaleMate -> TerminationReason.STALEMATE
    else -> null
}
