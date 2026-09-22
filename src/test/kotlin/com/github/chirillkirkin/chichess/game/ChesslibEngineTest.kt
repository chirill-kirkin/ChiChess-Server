package com.github.chirillkirkin.chichess.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

// Position after 1. f3 e5 2. g4, black to move: d8h4 is Qh4#.
private const val CHECKMATE_IN_ONE_FEN = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"

// White queen on g1, kings on f7/h8: g1g6 leaves black with no legal move and no check.
private const val STALEMATE_IN_ONE_FEN = "7k/5K2/8/8/8/8/8/6Q1 w - - 0 1"

// White pawn one square from promotion, kings out of the way.
private const val PROMOTION_FEN = "k7/7P/8/8/8/8/8/7K w - - 0 1"

class ChesslibEngineTest {
    private val engine = ChesslibEngine()

    @Test
    fun `sideToMove reads the active color from fen`() {
        assertEquals(PieceColor.WHITE, engine.sideToMove(START_FEN))
        assertEquals(PieceColor.BLACK, engine.sideToMove(CHECKMATE_IN_ONE_FEN))
    }

    @Test
    fun `plyNumber counts half-moves from the starting position`() {
        assertEquals(0, engine.plyNumber(START_FEN))
        val afterWhite = assertIs<MoveOutcome.Applied>(engine.applyMove(START_FEN, "e2e4")).fenAfter
        assertEquals(1, engine.plyNumber(afterWhite))
        val afterBlack = assertIs<MoveOutcome.Applied>(engine.applyMove(afterWhite, "e7e5")).fenAfter
        assertEquals(2, engine.plyNumber(afterBlack))
    }

    @Test
    fun `applyMove advances a legal move and flips the side to move`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(START_FEN, "e2e4"))

        assertEquals(null, outcome.result)
        assertEquals(null, outcome.terminationReason)
        assertEquals(PieceColor.BLACK, engine.sideToMove(outcome.fenAfter))
    }

    @Test
    fun `applyMove rejects an illegal move`() {
        assertTrue(engine.applyMove(START_FEN, "e2e5") is MoveOutcome.Illegal)
    }

    @Test
    fun `applyMove rejects a malformed move`() {
        assertTrue(engine.applyMove(START_FEN, "zzzz") is MoveOutcome.Illegal)
    }

    @Test
    fun `applyMove detects checkmate`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(CHECKMATE_IN_ONE_FEN, "d8h4"))

        assertEquals(GameResult.BLACK_WON, outcome.result)
        assertEquals(TerminationReason.CHECKMATE, outcome.terminationReason)
    }

    @Test
    fun `applyMove detects stalemate`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(STALEMATE_IN_ONE_FEN, "g1g6"))

        assertEquals(GameResult.DRAW, outcome.result)
        assertEquals(TerminationReason.STALEMATE, outcome.terminationReason)
    }

    @Test
    fun `applyMove accepts a promotion`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(PROMOTION_FEN, "h7h8q"))

        // The promoted queen appears on the board; the source position had none.
        assertTrue(outcome.fenAfter.contains('Q'))
    }
}
