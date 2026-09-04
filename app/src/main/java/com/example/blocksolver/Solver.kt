package com.example.blocksolver

import java.lang.Long.bitCount
import kotlin.math.max
import kotlin.math.min

/**
 * v2.0 Grandmaster solver.
 *
 * 1) Searches the complete legal order/placement tree for the CURRENT visible pieces.
 * 2) Clears completed rows/columns after every placement.
 * 3) Scores the final board for long-term survival using future-piece mobility:
 *    how many placements remain for a representative library of difficult/common shapes.
 */
class Solver(private val size: Int = 8) {

    private data class Key(
        val board: Long,
        val remainingMask: Int,
        val clearedInBatch: Boolean
    )

    private data class Candidate(
        val top: Int,
        val left: Int,
        val mask: Long
    )

    private data class Choice(
        val pieceIndex: Int,
        val candidate: Candidate,
        val lines: Int,
        val nextBatchCleared: Boolean
    )

    private data class FutureShape(
        val weight: Double,
        val masks: LongArray
    )

    private val rowMasks = LongArray(8) { r ->
        var mask = 0L
        for (c in 0 until 8) mask = mask or bit(r, c)
        mask
    }

    private val colMasks = LongArray(8) { c ->
        var mask = 0L
        for (r in 0 until 8) mask = mask or bit(r, c)
        mask
    }

    private val windows3x3 = buildWindowMasks(3, 3)
    private val windows2x3 = buildWindowMasks(2, 3)
    private val windows3x2 = buildWindowMasks(3, 2)
    private val windows2x2 = buildWindowMasks(2, 2)
    private val windows1x5 = buildWindowMasks(1, 5)
    private val windows5x1 = buildWindowMasks(5, 1)

    // Representative catalog of future pieces.
    // The exact game can contain more variants; these cover the shapes
    // that most often kill a fragmented 8x8 board.
    private val futureShapes: List<FutureShape> = buildFutureShapes()

    // A smaller, deliberately difficult next-piece catalog used for
    // one-piece lookahead at terminal states. This is much deeper than
    // static mobility, but still fast enough for real-time phone use.
    private val rolloutShapes: List<FutureShape> = buildRolloutShapes()

    fun solve(
        initial: Array<BooleanArray>,
        pieces: List<Piece>,
        comboCount: Int = 0,
        batchCleared: Boolean = false
    ): Solution? {
        if (pieces.isEmpty() || pieces.size > 3) return null
        if (pieces.any { it.cells.isEmpty() }) return null

        val normalized = pieces.map { it.normalized() }
        val placements = normalized.map { buildCandidates(it) }
        if (placements.any { it.isEmpty() }) return null

        val signatures = normalized.map { shapeSignature(it) }
        val memo = HashMap<Key, Double>(65536)
        val choices = HashMap<Key, Choice>(65536)
        val evalCache = HashMap<Long, Double>(16384)
        val rolloutCache = HashMap<Long, Double>(16384)

        val startBoard = toBits(initial)
        val allMask = (1 shl normalized.size) - 1

        fun terminalScore(
            board: Long,
            clearedInBatch: Boolean
        ): Double {
            val base = evalCache.getOrPut(board) {
                evaluateBoard(board)
            }

            val futureLookahead = rolloutCache.getOrPut(board) {
                futureRobustness(board)
            }

            val comboContinuity =
                if (clearedInBatch) {
                    5200.0 +
                        min(comboCount, 50) * 220.0 +
                        nextBatchSetupPotential(board) * 1.15
                } else if (comboCount > 0) {
                    // Losing a live combo is catastrophic for score.
                    // Keep this far below any normal board-evaluation swing.
                    -1_000_000.0 -
                        min(comboCount, 50) * 10_000.0
                } else {
                    // Even from combo 0, strongly prefer starting the chain.
                    -40_000.0
                }

            return base +
                comboContinuity +
                futureLookahead
        }

        fun search(
            board: Long,
            remainingMask: Int,
            clearedInBatch: Boolean
        ): Double {
            val key = Key(
                board,
                remainingMask,
                clearedInBatch
            )
            memo[key]?.let { return it }

            if (remainingMask == 0) {
                val score = terminalScore(
                    board,
                    clearedInBatch
                )
                memo[key] = score
                return score
            }

            var bestScore = Double.NEGATIVE_INFINITY
            var bestChoice: Choice? = null
            val usedShapes = HashSet<Long>(3)

            for (pieceIndex in normalized.indices) {
                val pieceBit = 1 shl pieceIndex
                if ((remainingMask and pieceBit) == 0) continue

                // Avoid searching equivalent orders when two visible pieces are identical.
                if (!usedShapes.add(signatures[pieceIndex])) continue

                for (candidate in placements[pieceIndex]) {
                    if ((board and candidate.mask) != 0L) continue

                    val clear = clearLines(board or candidate.mask)
                    val nextBoard = clear.first
                    val clearedLines = clear.second

                    val nextBatchCleared =
                        clearedInBatch || clearedLines > 0

                    val child = search(
                        nextBoard,
                        remainingMask and pieceBit.inv(),
                        nextBatchCleared
                    )
                    if (child == Double.NEGATIVE_INFINITY) continue

                    val clearBonus = estimatedClearValue(
                        clearedLines,
                        comboCount
                    )

                    val firstClearBonus =
                        if (
                            !clearedInBatch &&
                            clearedLines > 0
                        ) {
                            6200.0 +
                                min(comboCount, 45) * 300.0
                        } else {
                            0.0
                        }

                    val score =
                        child +
                        clearBonus +
                        firstClearBonus

                    if (score > bestScore) {
                        bestScore = score
                        bestChoice = Choice(
                            pieceIndex,
                            candidate,
                            clearedLines,
                            nextBatchCleared
                        )
                    }
                }
            }

            memo[key] = bestScore
            if (bestChoice != null) choices[key] = bestChoice
            return bestScore
        }

        val bestScore = search(
            startBoard,
            allMask,
            batchCleared
        )
        if (bestScore == Double.NEGATIVE_INFINITY) return null

        val steps = mutableListOf<Placement>()
        var board = startBoard
        var remainingMask = allMask
        var clearedInBatch = batchCleared
        var firstMoveLines = 0
        var projectedBatchCleared = clearedInBatch

        while (remainingMask != 0) {
            val key = Key(
                board,
                remainingMask,
                clearedInBatch
            )
            val choice = choices[key] ?: break
            val piece = normalized[choice.pieceIndex]
            val candidate = choice.candidate

            val cells = piece.cells.map {
                Cell(
                    r = candidate.top + it.r,
                    c = candidate.left + it.c
                )
            }.toSet()

            if (steps.isEmpty()) {
                firstMoveLines = choice.lines
                projectedBatchCleared =
                    choice.nextBatchCleared
            }

            steps += Placement(
                pieceIndex = choice.pieceIndex,
                top = candidate.top,
                left = candidate.left,
                cells = cells
            )

            board = clearLines(board or candidate.mask).first
            remainingMask =
                remainingMask and (1 shl choice.pieceIndex).inv()

            clearedInBatch =
                choice.nextBatchCleared
        }

        return if (steps.isEmpty()) {
            null
        } else {
            Solution(
                steps = steps,
                score = bestScore,
                firstMoveLines = firstMoveLines,
                projectedCombo = comboCount,
                projectedGap = 0,
                projectedBatchCleared =
                    projectedBatchCleared
            )
        }
    }

    private fun estimatedClearValue(
        lines: Int,
        comboCount: Int
    ): Double {
        if (lines <= 0) return 0.0

        val base = when (lines) {
            1 -> 10.0
            2 -> 20.0
            3 -> 60.0
            4 -> 120.0
            5 -> 200.0
            else -> 300.0 + (lines - 6) * 90.0
        }

        val multiplier =
            (comboCount + 2)
                .coerceAtMost(40)
                .toDouble()

        val multiLineExtra =
            when (lines) {
                1 -> 0.0
                2 -> 900.0
                3 -> 2600.0
                4 -> 5200.0
                5 -> 8200.0
                else -> 11000.0
            }

        return base * multiplier * 5.0 +
            multiLineExtra
    }

    private fun nextBatchSetupPotential(
        board: Long
    ): Double {
        var bonus = 0.0
        val rowCounts = IntArray(8)
        val colCounts = IntArray(8)

        for (r in 0 until 8) {
            rowCounts[r] =
                bitCount(board and rowMasks[r])
        }

        for (c in 0 until 8) {
            colCounts[c] =
                bitCount(board and colMasks[c])
        }

        for (r in 0 until 8) {
            bonus += when (rowCounts[r]) {
                7 -> 520.0
                6 -> 170.0
                5 -> 45.0
                else -> 0.0
            }
        }

        for (c in 0 until 8) {
            bonus += when (colCounts[c]) {
                7 -> 520.0
                6 -> 170.0
                5 -> 45.0
                else -> 0.0
            }
        }

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if ((board and bit(r, c)) != 0L) continue

                if (
                    rowCounts[r] == 7 &&
                    colCounts[c] == 7
                ) {
                    bonus += 1700.0
                } else if (
                    rowCounts[r] >= 6 &&
                    colCounts[c] >= 6
                ) {
                    bonus += 360.0
                }
            }
        }

        return bonus
    }

    /**
     * One unseen-piece lookahead.
     *
     * For every dangerous/common future shape, try EVERY legal placement,
     * keep the best response, then combine weighted expectation with the
     * worst dangerous outcome. This makes the current move robust to the
     * next random piece instead of relying only on hand-written heuristics.
     */
    private fun futureRobustness(
        board: Long
    ): Double {
        var weighted = 0.0
        var totalWeight = 0.0
        var worst = Double.POSITIVE_INFINITY
        var zeroWeight = 0.0

        for (shape in rolloutShapes) {
            var fits = 0
            var bestAfter = Double.NEGATIVE_INFINITY

            for (mask in shape.masks) {
                if ((board and mask) != 0L) {
                    continue
                }

                fits++

                val clear =
                    clearLines(board or mask)

                val score =
                    quickRolloutScore(
                        clear.first,
                        clear.second
                    )

                if (score > bestAfter) {
                    bestAfter = score
                }
            }

            if (fits == 0) {
                zeroWeight += shape.weight
                bestAfter =
                    -9000.0 -
                    shape.weight * 1800.0
            } else {
                // Multiple legal homes for a future piece are safer than
                // having exactly one fragile placement.
                bestAfter +=
                    min(fits, 14) * 48.0
            }

            weighted +=
                bestAfter * shape.weight

            totalWeight += shape.weight

            worst =
                min(
                    worst,
                    bestAfter
                )
        }

        if (totalWeight <= 0.0) {
            return 0.0
        }

        val expected =
            weighted / totalWeight

        // 60% worst-case, 40% expected-case:
        // Block Blast can throw an awkward piece, so the solver must not
        // choose a board that is brilliant on average but dies to one shape.
        return (
            expected * 0.40 +
            worst * 0.60 -
            zeroWeight * 3600.0
        )
    }

    /**
     * Cheap score used inside future lookahead.
     * Intentionally avoids the full future-shape loop to prevent recursion
     * and keep v2.0 responsive.
     */
    private fun quickRolloutScore(
        board: Long,
        clearedLines: Int
    ): Double {
        val occupied = bitCount(board)

        val open3 =
            countEmptyWindows(
                board,
                windows3x3
            )

        val openH5 =
            countEmptyWindows(
                board,
                windows1x5
            )

        val openV5 =
            countEmptyWindows(
                board,
                windows5x1
            )

        val open23 =
            countEmptyWindows(
                board,
                windows2x3
            ) +
            countEmptyWindows(
                board,
                windows3x2
            )

        val isolated =
            isolatedEmptyCells(board)

        val hardPenalty =
            (if (open3 == 0) 5200.0 else 0.0) +
            (if (openH5 == 0) 2400.0 else 0.0) +
            (if (openV5 == 0) 2400.0 else 0.0)

        val overfill =
            max(
                0,
                occupied - 38
            )

        return (
            clearedLines * 1500.0 -
            occupied * 7.0 +
            open3 * 82.0 +
            (openH5 + openV5) * 24.0 +
            open23 * 12.0 -
            isolated * 110.0 -
            overfill * overfill * 22.0 -
            hardPenalty +
            nextBatchSetupPotential(board) * 0.22
        )
    }

    private fun buildRolloutShapes():
        List<FutureShape> {
        val defs = listOf(
            2.20 to cells("0,0;0,1;0,2;0,3;0,4"),
            2.20 to cells("0,0;1,0;2,0;3,0;4,0"),
            2.80 to cells(
                "0,0;0,1;0,2;" +
                "1,0;1,1;1,2;" +
                "2,0;2,1;2,2"
            ),
            1.80 to cells("0,0;0,1;0,2;1,0;1,1;1,2"),
            1.80 to cells("0,0;0,1;1,0;1,1;2,0;2,1"),
            1.65 to cells("0,0;1,0;2,0;2,1;2,2"),
            1.65 to cells("0,0;0,1;0,2;1,0;2,0"),
            1.65 to cells("0,2;1,2;2,0;2,1;2,2"),
            1.65 to cells("0,0;0,1;0,2;1,2;2,2"),
            1.35 to cells("0,0;0,1;0,2;1,1"),
            1.35 to cells("0,1;1,0;1,1;2,1")
        )

        return defs.map {
            (weight, shapeCells) ->

            val piece =
                Piece(
                    shapeCells
                ).normalized()

            FutureShape(
                weight = weight,
                masks =
                    buildCandidates(piece)
                        .map {
                            it.mask
                        }
                        .toLongArray()
            )
        }
    }

    private fun buildCandidates(piece: Piece): List<Candidate> {
        val out = ArrayList<Candidate>(64)

        for (top in 0..(size - piece.height)) {
            for (left in 0..(size - piece.width)) {
                var mask = 0L
                for (cell in piece.cells) {
                    mask = mask or bit(top + cell.r, left + cell.c)
                }
                out += Candidate(top, left, mask)
            }
        }
        return out
    }

    private fun shapeSignature(piece: Piece): Long {
        var mask = 0L
        for (cell in piece.cells) mask = mask or bit(cell.r, cell.c)
        return mask
    }

    /**
     * Completed rows and columns disappear simultaneously after each move.
     */
    private fun clearLines(board: Long): Pair<Long, Int> {
        var clearMask = 0L
        var count = 0

        for (r in 0 until 8) {
            val mask = rowMasks[r]
            if ((board and mask) == mask) {
                clearMask = clearMask or mask
                count++
            }
        }

        for (c in 0 until 8) {
            val mask = colMasks[c]
            if ((board and mask) == mask) {
                clearMask = clearMask or mask
                count++
            }
        }

        return if (count == 0) {
            board to 0
        } else {
            (board and clearMask.inv()) to count
        }
    }

    /**
     * Long-term survival score.
     *
     * Large positive terms:
     * - future piece mobility;
     * - 3x3 and long-line capacity;
     * - one connected empty area.
     *
     * Large negative terms:
     * - any common/difficult future shape with ZERO legal placements;
     * - isolated single-cell pockets;
     * - fragmented empty space.
     */
    private fun evaluateBoard(board: Long): Double {
        val occupied = bitCount(board)
        val isolated = isolatedEmptyCells(board)
        val components = emptyComponents(board)
        val largestEmptyComponent = largestEmptyComponent(board)

        val open3x3 = countEmptyWindows(board, windows3x3)
        val open2x3 = countEmptyWindows(board, windows2x3)
        val open3x2 = countEmptyWindows(board, windows3x2)
        val open2x2 = countEmptyWindows(board, windows2x2)
        val open1x5 = countEmptyWindows(board, windows1x5)
        val open5x1 = countEmptyWindows(board, windows5x1)

        var linePotential = 0
        for (r in 0 until 8) {
            val n = bitCount(board and rowMasks[r])
            linePotential += n * n
        }
        for (c in 0 until 8) {
            val n = bitCount(board and colMasks[c])
            linePotential += n * n
        }

        var mobilityWeighted = 0.0
        var zeroWeighted = 0.0
        var minDangerFits = Int.MAX_VALUE

        for (shape in futureShapes) {
            var fits = 0
            for (mask in shape.masks) {
                if ((board and mask) == 0L) fits++
            }

            mobilityWeighted += shape.weight * min(fits, 18)

            if (fits == 0) {
                zeroWeighted += shape.weight
            }

            if (shape.weight >= 1.8) {
                minDangerFits = min(minDangerFits, fits)
            }
        }

        if (minDangerFits == Int.MAX_VALUE) minDangerFits = 0

        val empties = 64 - occupied
        val componentCoverage =
            if (empties > 0) largestEmptyComponent.toDouble() / empties else 0.0

        val overfill =
            max(0, occupied - 34)

        val hardSpacePenalty =
            (if (open3x3 == 0) 6800.0 else 0.0) +
            (if (open1x5 == 0) 3400.0 else 0.0) +
            (if (open5x1 == 0) 3400.0 else 0.0)

        return (
            // Future survivability dominates the decision.
            mobilityWeighted * 23.0 -
            zeroWeighted * 1650.0 +
            minDangerFits * 70.0 +

            // Keep large usable regions.
            open3x3 * 60.0 +
            (open2x3 + open3x2) * 18.0 +
            open2x2 * 6.0 +
            (open1x5 + open5x1) * 15.0 +
            largestEmptyComponent * 9.0 +
            componentCoverage * 240.0 +

            // Preserve line-building opportunities without becoming greedy.
            linePotential * 0.30 -

            // Strong anti-fragmentation / anti-overfill penalties.
            occupied * 6.0 -
            isolated * 220.0 -
            max(0, components - 1) * 105.0 -
            overfill * overfill * 28.0 -
            hardSpacePenalty
        )
    }

    private fun buildFutureShapes(): List<FutureShape> {
        val defs = listOf(
            // singles / bars
            0.45 to cells("0,0"),
            0.65 to cells("0,0;0,1"),
            0.65 to cells("0,0;1,0"),
            0.85 to cells("0,0;0,1;0,2"),
            0.85 to cells("0,0;1,0;2,0"),
            1.25 to cells("0,0;0,1;0,2;0,3"),
            1.25 to cells("0,0;1,0;2,0;3,0"),
            2.05 to cells("0,0;0,1;0,2;0,3;0,4"),
            2.05 to cells("0,0;1,0;2,0;3,0;4,0"),

            // rectangles / squares
            1.10 to cells("0,0;0,1;1,0;1,1"),
            1.55 to cells("0,0;0,1;0,2;1,0;1,1;1,2"),
            1.55 to cells("0,0;0,1;1,0;1,1;2,0;2,1"),
            2.35 to cells("0,0;0,1;0,2;1,0;1,1;1,2;2,0;2,1;2,2"),

            // L triominoes
            0.95 to cells("0,0;1,0;1,1"),
            0.95 to cells("0,0;0,1;1,0"),
            0.95 to cells("0,0;0,1;1,1"),
            0.95 to cells("0,1;1,0;1,1"),

            // L pentomino-like 3+3 arms used by the game
            1.45 to cells("0,0;1,0;2,0;2,1;2,2"),
            1.45 to cells("0,0;0,1;0,2;1,0;2,0"),
            1.45 to cells("0,2;1,2;2,0;2,1;2,2"),
            1.45 to cells("0,0;0,1;0,2;1,2;2,2"),

            // T / zigzag / plus-ish common awkward shapes
            1.25 to cells("0,0;0,1;0,2;1,1"),
            1.25 to cells("0,1;1,0;1,1;2,1"),
            1.25 to cells("0,1;0,2;1,0;1,1"),
            1.25 to cells("0,0;1,0;1,1;2,1"),
            1.40 to cells("0,1;1,0;1,1;1,2;2,1")
        )

        return defs.map { (weight, shapeCells) ->
            val p = Piece(shapeCells).normalized()
            FutureShape(
                weight = weight,
                masks = buildCandidates(p).map { it.mask }.toLongArray()
            )
        }
    }

    private fun cells(spec: String): Set<Cell> =
        spec.split(";").map { token ->
            val parts = token.split(",")
            Cell(parts[0].toInt(), parts[1].toInt())
        }.toSet()

    private fun buildWindowMasks(height: Int, width: Int): LongArray {
        val out = ArrayList<Long>()
        for (top in 0..(8 - height)) {
            for (left in 0..(8 - width)) {
                var mask = 0L
                for (r in 0 until height) {
                    for (c in 0 until width) {
                        mask = mask or bit(top + r, left + c)
                    }
                }
                out += mask
            }
        }
        return out.toLongArray()
    }

    private fun countEmptyWindows(board: Long, masks: LongArray): Int {
        var count = 0
        for (mask in masks) if ((board and mask) == 0L) count++
        return count
    }

    private fun isolatedEmptyCells(board: Long): Int {
        var count = 0

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if ((board and bit(r, c)) != 0L) continue

                var openNeighbours = 0
                if (r > 0 && (board and bit(r - 1, c)) == 0L) openNeighbours++
                if (r < 7 && (board and bit(r + 1, c)) == 0L) openNeighbours++
                if (c > 0 && (board and bit(r, c - 1)) == 0L) openNeighbours++
                if (c < 7 && (board and bit(r, c + 1)) == 0L) openNeighbours++

                if (openNeighbours <= 1) count++
            }
        }
        return count
    }

    private fun emptyComponents(board: Long): Int {
        var unseen = board.inv()
        var components = 0

        while (unseen != 0L) {
            val seedIndex = java.lang.Long.numberOfTrailingZeros(unseen)
            var frontier = 1L shl seedIndex
            components++

            while (frontier != 0L) {
                unseen = unseen and frontier.inv()
                var next = 0L
                var f = frontier

                while (f != 0L) {
                    val idx = java.lang.Long.numberOfTrailingZeros(f)
                    val r = idx / 8
                    val c = idx % 8

                    if (r > 0) next = next or bit(r - 1, c)
                    if (r < 7) next = next or bit(r + 1, c)
                    if (c > 0) next = next or bit(r, c - 1)
                    if (c < 7) next = next or bit(r, c + 1)

                    f = f and (f - 1)
                }
                frontier = next and unseen
            }
        }
        return components
    }

    private fun largestEmptyComponent(board: Long): Int {
        var unseen = board.inv()
        var best = 0

        while (unseen != 0L) {
            val seedIndex = java.lang.Long.numberOfTrailingZeros(unseen)
            var frontier = 1L shl seedIndex
            var count = 0

            while (frontier != 0L) {
                count += bitCount(frontier)
                unseen = unseen and frontier.inv()

                var next = 0L
                var f = frontier

                while (f != 0L) {
                    val idx = java.lang.Long.numberOfTrailingZeros(f)
                    val r = idx / 8
                    val c = idx % 8

                    if (r > 0) next = next or bit(r - 1, c)
                    if (r < 7) next = next or bit(r + 1, c)
                    if (c > 0) next = next or bit(r, c - 1)
                    if (c < 7) next = next or bit(r, c + 1)

                    f = f and (f - 1)
                }
                frontier = next and unseen
            }
            best = max(best, count)
        }

        return best
    }

    private fun toBits(board: Array<BooleanArray>): Long {
        var bits = 0L
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (board[r][c]) bits = bits or bit(r, c)
            }
        }
        return bits
    }

    private fun bit(r: Int, c: Int): Long =
        1L shl (r * 8 + c)
}
