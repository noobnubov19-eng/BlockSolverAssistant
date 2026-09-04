package com.example.blocksolver

import java.lang.Long.bitCount
import kotlin.math.max

class Solver(private val size: Int = 8) {

    private data class Key(
        val board: Long,
        val remainingMask: Int
    )

    private data class Candidate(
        val top: Int,
        val left: Int,
        val mask: Long
    )

    private data class Choice(
        val pieceIndex: Int,
        val candidate: Candidate
    )

    private val rowMasks = LongArray(8) { r ->
        var mask = 0L
        for (c in 0 until 8) {
            mask = mask or bit(r, c)
        }
        mask
    }

    private val colMasks = LongArray(8) { c ->
        var mask = 0L
        for (r in 0 until 8) {
            mask = mask or bit(r, c)
        }
        mask
    }

    private val windows3x3 = buildWindowMasks(3, 3)
    private val windows2x3 = buildWindowMasks(2, 3)
    private val windows3x2 = buildWindowMasks(3, 2)
    private val windows2x2 = buildWindowMasks(2, 2)
    private val windows1x5 = buildWindowMasks(1, 5)
    private val windows5x1 = buildWindowMasks(5, 1)

    fun solve(
        initial: Array<BooleanArray>,
        pieces: List<Piece>
    ): Solution? {
        if (pieces.isEmpty() || pieces.size > 3) return null
        if (pieces.any { it.cells.isEmpty() }) return null

        val normalized = pieces.map { it.normalized() }
        val placements = normalized.map { buildCandidates(it) }
        if (placements.any { it.isEmpty() }) return null

        val signatures = normalized.map { shapeSignature(it) }
        val memo = HashMap<Key, Double>(65536)
        val choices = HashMap<Key, Choice>(65536)

        val startBoard = toBits(initial)
        val allMask = (1 shl normalized.size) - 1

        fun search(board: Long, remainingMask: Int): Double {
            val key = Key(board, remainingMask)
            memo[key]?.let { return it }

            if (remainingMask == 0) {
                val score = evaluateBoard(board)
                memo[key] = score
                return score
            }

            var bestScore = Double.NEGATIVE_INFINITY
            var bestChoice: Choice? = null
            val usedShapes = HashSet<Long>(3)

            for (pieceIndex in normalized.indices) {
                val pieceBit = 1 shl pieceIndex
                if ((remainingMask and pieceBit) == 0) continue

                val signature = signatures[pieceIndex]
                if (!usedShapes.add(signature)) continue

                for (candidate in placements[pieceIndex]) {
                    if ((board and candidate.mask) != 0L) continue

                    val placed = board or candidate.mask
                    val clearResult = clearLines(placed)
                    val nextBoard = clearResult.first
                    val clearedLines = clearResult.second

                    val child = search(
                        nextBoard,
                        remainingMask and pieceBit.inv()
                    )

                    if (child == Double.NEGATIVE_INFINITY) continue

                    val score =
                        child +
                        clearedLines * 1150.0 +
                        if (clearedLines >= 2) 180.0 * clearedLines else 0.0

                    if (score > bestScore) {
                        bestScore = score
                        bestChoice = Choice(pieceIndex, candidate)
                    }
                }
            }

            memo[key] = bestScore
            if (bestChoice != null) {
                choices[key] = bestChoice
            }
            return bestScore
        }

        val bestScore = search(startBoard, allMask)
        if (bestScore == Double.NEGATIVE_INFINITY) return null

        val steps = mutableListOf<Placement>()
        var board = startBoard
        var remainingMask = allMask

        while (remainingMask != 0) {
            val key = Key(board, remainingMask)
            val choice = choices[key] ?: break
            val piece = normalized[choice.pieceIndex]
            val candidate = choice.candidate

            val cells = piece.cells.map {
                Cell(
                    r = candidate.top + it.r,
                    c = candidate.left + it.c
                )
            }.toSet()

            steps += Placement(
                pieceIndex = choice.pieceIndex,
                top = candidate.top,
                left = candidate.left,
                cells = cells
            )

            board = clearLines(board or candidate.mask).first
            remainingMask =
                remainingMask and
                (1 shl choice.pieceIndex).inv()
        }

        return if (steps.isEmpty()) {
            null
        } else {
            Solution(steps, bestScore)
        }
    }

    private fun buildCandidates(piece: Piece): List<Candidate> {
        val out = ArrayList<Candidate>(64)

        for (top in 0..(size - piece.height)) {
            for (left in 0..(size - piece.width)) {
                var mask = 0L
                for (cell in piece.cells) {
                    mask = mask or bit(
                        top + cell.r,
                        left + cell.c
                    )
                }
                out += Candidate(top, left, mask)
            }
        }
        return out
    }

    private fun shapeSignature(piece: Piece): Long {
        var mask = 0L
        for (cell in piece.cells) {
            mask = mask or bit(cell.r, cell.c)
        }
        return mask
    }

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

    private fun evaluateBoard(board: Long): Double {
        val occupied = bitCount(board)
        val isolated = isolatedEmptyCells(board)
        val components = emptyComponents(board)

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

        return (
            -occupied * 8.0 -
            isolated * 125.0 -
            max(0, components - 1) * 28.0 +
            open3x3 * 34.0 +
            (open2x3 + open3x2) * 12.0 +
            open2x2 * 5.0 +
            (open1x5 + open5x1) * 7.0 +
            linePotential * 0.55
        )
    }

    private fun buildWindowMasks(
        height: Int,
        width: Int
    ): LongArray {
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

    private fun countEmptyWindows(
        board: Long,
        masks: LongArray
    ): Int {
        var count = 0
        for (mask in masks) {
            if ((board and mask) == 0L) {
                count++
            }
        }
        return count
    }

    private fun isolatedEmptyCells(board: Long): Int {
        var count = 0

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val here = bit(r, c)
                if ((board and here) != 0L) continue

                var openNeighbours = 0

                if (r > 0 && (board and bit(r - 1, c)) == 0L) openNeighbours++
                if (r < 7 && (board and bit(r + 1, c)) == 0L) openNeighbours++
                if (c > 0 && (board and bit(r, c - 1)) == 0L) openNeighbours++
                if (c < 7 && (board and bit(r, c + 1)) == 0L) openNeighbours++

                if (openNeighbours <= 1) {
                    count++
                }
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

    private fun toBits(board: Array<BooleanArray>): Long {
        var bits = 0L
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                if (board[r][c]) {
                    bits = bits or bit(r, c)
                }
            }
        }
        return bits
    }

    private fun bit(r: Int, c: Int): Long =
        1L shl (r * 8 + c)
}
