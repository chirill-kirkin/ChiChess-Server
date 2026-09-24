package com.github.chirillkirkin.chichess.game

// Shared UCI moves used across game tests.
internal const val OPENING_MOVE = "e2e4"
internal const val OPENING_REPLY = "e7e5"
internal const val ILLEGAL_MOVE_UCI = "e2e5"
internal const val MALFORMED_MOVE_UCI = "zzzz"
internal const val CHECKMATE_MOVE = "d8h4"
internal const val STALEMATE_MOVE = "g1g6"
internal const val PROMOTION_MOVE = "h7h8q"

// Positions one move away from a terminal state.
// Reached by 1. f3 e5 2. g4; black to move plays d8h4 (Qh4#).
internal const val CHECKMATE_IN_ONE_FEN = "rnbqkbnr/pppp1ppp/8/4p3/6P1/5P2/PPPPP2P/RNBQKBNR b KQkq g3 0 2"

// White queen on g1, kings on f7/h8: g1g6 leaves black with no legal move and no check.
internal const val STALEMATE_IN_ONE_FEN = "7k/5K2/8/8/8/8/8/6Q1 w - - 0 1"

// White pawn one square from promotion, kings out of the way.
internal const val PROMOTION_FEN = "k7/7P/8/8/8/8/8/7K w - - 0 1"

// White king one move from capturing black's last knight, leaving king vs king.
internal const val INSUFFICIENT_MATERIAL_FEN = "k7/8/1n6/2K5/8/8/8/8 w - - 0 1"
internal const val INSUFFICIENT_MATERIAL_MOVE = "c5b6"

// Half-move clock at 149; a quiet rook move reaches 150 (the 75-move rule) with material still on.
internal const val SEVENTY_FIVE_MOVE_FEN = "r6k/8/8/8/8/8/8/2R4K w - - 149 80"
internal const val SEVENTY_FIVE_MOVE_TRIGGER = "c1c2"

// Both knights out and back returns to the starting position, which recurs once per round.
internal val KNIGHT_SHUFFLE_ROUND = listOf("g1f3", "g8f6", "f3g1", "f6g8")
internal const val FIVEFOLD_REPETITION_ROUNDS = 4 // start position occurs five times
internal const val BELOW_FIVEFOLD_ROUNDS = 2

// Command identifiers are client-generated; the values are arbitrary but distinct per role.
internal const val MOVE_COMMAND_ID = "move-1"
internal const val SYNC_COMMAND_ID = "sync-1"
internal const val RESIGN_COMMAND_ID = "resign-1"
internal const val DUPLICATE_COMMAND_ID = "dup-1"
internal const val OFFER_COMMAND_ID = "offer-1"
internal const val ACCEPT_COMMAND_ID = "accept-1"
internal const val DECLINE_COMMAND_ID = "decline-1"
internal const val SECOND_COMMAND_ID = "cmd-2"

// A revision far enough ahead of the current one to be stale.
internal const val STALE_REVISION_OFFSET = 5L

internal const val IN_PROGRESS_INVITE_CODE = "INPROGRESS"
internal const val MALFORMED_GAME_ID = "not-a-uuid"
internal const val MALFORMED_COMMAND_TEXT = "not json"
