package com.github.chirillkirkin.chichess.game

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val PROMOTED_QUEEN = 'Q'

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
        val afterWhite = assertIs<MoveOutcome.Applied>(engine.applyMove(START_FEN, OPENING_MOVE)).fenAfter
        assertEquals(1, engine.plyNumber(afterWhite))
        val afterBlack = assertIs<MoveOutcome.Applied>(engine.applyMove(afterWhite, OPENING_REPLY)).fenAfter
        assertEquals(2, engine.plyNumber(afterBlack))
    }

    @Test
    fun `applyMove advances a legal move and flips the side to move`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(START_FEN, OPENING_MOVE))

        assertEquals(null, outcome.result)
        assertEquals(null, outcome.terminationReason)
        assertEquals(PieceColor.BLACK, engine.sideToMove(outcome.fenAfter))
    }

    @Test
    fun `applyMove rejects an illegal move`() {
        assertTrue(engine.applyMove(START_FEN, ILLEGAL_MOVE_UCI) is MoveOutcome.Illegal)
    }

    @Test
    fun `applyMove rejects a malformed move`() {
        assertTrue(engine.applyMove(START_FEN, MALFORMED_MOVE_UCI) is MoveOutcome.Illegal)
    }

    @Test
    fun `applyMove detects checkmate`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(CHECKMATE_IN_ONE_FEN, CHECKMATE_MOVE))

        assertEquals(GameResult.BLACK_WON, outcome.result)
        assertEquals(TerminationReason.CHECKMATE, outcome.terminationReason)
    }

    @Test
    fun `applyMove detects stalemate`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(STALEMATE_IN_ONE_FEN, STALEMATE_MOVE))

        assertEquals(GameResult.DRAW, outcome.result)
        assertEquals(TerminationReason.STALEMATE, outcome.terminationReason)
    }

    @Test
    fun `applyMove detects a draw by insufficient material`() {
        val outcome = assertIs<MoveOutcome.Applied>(
            engine.applyMove(INSUFFICIENT_MATERIAL_FEN, INSUFFICIENT_MATERIAL_MOVE),
        )

        assertEquals(GameResult.DRAW, outcome.result)
        assertEquals(TerminationReason.INSUFFICIENT_MATERIAL, outcome.terminationReason)
    }

    @Test
    fun `applyMove detects the seventy-five move rule`() {
        val outcome = assertIs<MoveOutcome.Applied>(
            engine.applyMove(SEVENTY_FIVE_MOVE_FEN, SEVENTY_FIVE_MOVE_TRIGGER),
        )

        assertEquals(GameResult.DRAW, outcome.result)
        assertEquals(TerminationReason.SEVENTY_FIVE_MOVE_RULE, outcome.terminationReason)
    }

    @Test
    fun `applyMove accepts a promotion`() {
        val outcome = assertIs<MoveOutcome.Applied>(engine.applyMove(PROMOTION_FEN, PROMOTION_MOVE))

        // The promoted queen appears on the board; the source position had none.
        assertTrue(outcome.fenAfter.contains(PROMOTED_QUEEN))
    }

    @Test
    fun `isRepetition detects a fivefold repetition of the position`() {
        val fivefold = List(FIVEFOLD_REPETITION_ROUNDS) { KNIGHT_SHUFFLE_ROUND }.flatten()
        val threefold = List(THREEFOLD_REPETITION_ROUNDS) { KNIGHT_SHUFFLE_ROUND }.flatten()

        assertTrue(engine.isRepetition(fivefold, FIVEFOLD_REPETITION_OCCURRENCES))
        assertFalse(engine.isRepetition(threefold, FIVEFOLD_REPETITION_OCCURRENCES))
    }

    @Test
    fun `isRepetition detects a threefold repetition of the position`() {
        val threefold = List(THREEFOLD_REPETITION_ROUNDS) { KNIGHT_SHUFFLE_ROUND }.flatten()

        assertTrue(engine.isRepetition(threefold, THREEFOLD_REPETITION_OCCURRENCES))
    }
}
