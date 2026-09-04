package com.example.blocksolver

import kotlin.math.max

class Solver(private val size: Int = 8) {

    private data class SearchState(
        val board: Array<BooleanArray>,
        val remainingMask: Int,
        val steps: List<Placement>,
        val cleared: Int,
        val quickScore: Double
    )

    companion object {
        // Keeps the solver fast on a phone while still looking far enough ahead.
        private const val BEAM_WIDTH = 700
    }

    fun solve(
        initial: Array<BooleanArray>,
        pieces: List<Piece>
    ): Solution? {
        if (pieces.size != 3 || pieces.any { it.cells.isEmpty() }) {
            return null
        }

        var frontier = listOf(
            SearchState(
                board = initial.deepCopy(),
                remainingMask = 0b111,
                steps = emptyList(),
                cleared = 0,
                quickScore = quickEvaluate(initial, 0)
            )
        )

        repeat(3) {
            val next = ArrayList<SearchState>(BEAM_WIDTH * 8)

            for (state in frontier) {
                for (pieceIndex in 0 until 3) {
                    if ((state.remainingMask and (1 shl pieceIndex)) == 0) {
                        continue
                    }

                    val piece = pieces[pieceIndex].normalized()

                    for (r in 0..(size - piece.height)) {
                        for (c in 0..(size - piece.width)) {
                            if (!fits(state.board, piece, r, c)) {
                                continue
                            }

                            val board = state.board.deepCopy()
                            val placedCells = piece.cells
                                .map { Cell(r + it.r, c + it.c) }
                                .toSet()

                            for (cell in placedCells) {
                                board[cell.r][cell.c] = true
                            }

                            val clearedNow = clearLines(board)
                            val clearedTotal = state.cleared + clearedNow

                            next += SearchState(
                                board = board,
                                remainingMask = state.remainingMask and
                                    (1 shl pieceIndex).inv(),
                                steps = state.steps + Placement(
                                    pieceIndex = pieceIndex,
                                    top = r,
                                    left = c,
                                    cells = placedCells
                                ),
                                cleared = clearedTotal,
                                quickScore = quickEvaluate(
                                    board,
                                    clearedTotal
                                )
                            )
                        }
                    }
                }
            }

            if (next.isEmpty()) {
                return null
            }

            frontier = if (next.size <= BEAM_WIDTH) {
                next
            } else {
                next
                    .sortedByDescending { it.quickScore }
                    .take(BEAM_WIDTH)
            }
        }

        val best = frontier.maxByOrNull {
            finalEvaluate(it.board, it.cleared)
        } ?: return null

        return Solution(
            steps = best.steps,
            score = finalEvaluate(best.board, best.cleared)
        )
    }

    private fun fits(
        board: Array<BooleanArray>,
        piece: Piece,
        top: Int,
        left: Int
    ): Boolean {
        for (cell in piece.cells) {
            if (board[top + cell.r][left + cell.c]) {
                return false
            }
        }
        return true
    }

    private fun clearLines(
        board: Array<BooleanArray>
    ): Int {
        val fullRows = BooleanArray(size)
        val fullCols = BooleanArray(size)

        var rowCount = 0
        var colCount = 0

        for (r in 0 until size) {
            var full = true
            for (c in 0 until size) {
                if (!board[r][c]) {
                    full = false
                    break
                }
            }
            if (full) {
                fullRows[r] = true
                rowCount++
            }
        }

        for (c in 0 until size) {
            var full = true
            for (r in 0 until size) {
                if (!board[r][c]) {
                    full = false
                    break
                }
            }
            if (full) {
                fullCols[c] = true
                colCount++
            }
        }

        if (rowCount == 0 && colCount == 0) {
            return 0
        }

        for (r in 0 until size) {
            for (c in 0 until size) {
                if (fullRows[r] || fullCols[c]) {
                    board[r][c] = false
                }
            }
        }

        return rowCount + colCount
    }

    private fun quickEvaluate(
        board: Array<BooleanArray>,
        cleared: Int
    ): Double {
        var occupied = 0
        var isolated = 0
        var open2x2 = 0
        var linePotential = 0

        for (r in 0 until size) {
            var rowFilled = 0
            for (c in 0 until size) {
                if (board[r][c]) {
                    occupied++
                    rowFilled++
                }
            }
            linePotential += rowFilled * rowFilled
        }

        for (c in 0 until size) {
            var colFilled = 0
            for (r in 0 until size) {
                if (board[r][c]) {
                    colFilled++
                }
            }
            linePotential += colFilled * colFilled
        }

        for (r in 0 until size) {
            for (c in 0 until size) {
                if (board[r][c]) continue

                var open = 0
                if (r > 0 && !board[r - 1][c]) open++
                if (r < size - 1 && !board[r + 1][c]) open++
                if (c > 0 && !board[r][c - 1]) open++
                if (c < size - 1 && !board[r][c + 1]) open++

                if (open <= 1) {
                    isolated++
                }
            }
        }

        for (r in 0 until size - 1) {
            for (c in 0 until size - 1) {
                if (
                    !board[r][c] &&
                    !board[r + 1][c] &&
                    !board[r][c + 1] &&
                    !board[r + 1][c + 1]
                ) {
                    open2x2++
                }
            }
        }

        return cleared * 1200.0 -
            occupied * 9.0 -
            isolated * 105.0 +
            open2x2 * 5.0 +
            linePotential * 0.8
    }

    private fun finalEvaluate(
        board: Array<BooleanArray>,
        cleared: Int
    ): Double {
        val base = quickEvaluate(board, cleared)
        val components = emptyComponents(board)
        val largestRect = largestEmptyRectangle(board)

        return base -
            max(0, components - 1) * 30.0 +
            largestRect * 8.0
    }

    private fun emptyComponents(
        board: Array<BooleanArray>
    ): Int {
        val seen = Array(size) { BooleanArray(size) }
        val q = ArrayDeque<Cell>()
        var comps = 0

        for (r in 0 until size) {
            for (c in 0 until size) {
                if (board[r][c] || seen[r][c]) {
                    continue
                }

                comps++
                seen[r][c] = true
                q.addLast(Cell(r, c))

                while (q.isNotEmpty()) {
                    val cur = q.removeFirst()

                    val r0 = cur.r
                    val c0 = cur.c

                    if (
                        r0 > 0 &&
                        !board[r0 - 1][c0] &&
                        !seen[r0 - 1][c0]
                    ) {
                        seen[r0 - 1][c0] = true
                        q.addLast(Cell(r0 - 1, c0))
                    }

                    if (
                        r0 < size - 1 &&
                        !board[r0 + 1][c0] &&
                        !seen[r0 + 1][c0]
                    ) {
                        seen[r0 + 1][c0] = true
                        q.addLast(Cell(r0 + 1, c0))
                    }

                    if (
                        c0 > 0 &&
                        !board[r0][c0 - 1] &&
                        !seen[r0][c0 - 1]
                    ) {
                        seen[r0][c0 - 1] = true
                        q.addLast(Cell(r0, c0 - 1))
                    }

                    if (
                        c0 < size - 1 &&
                        !board[r0][c0 + 1] &&
                        !seen[r0][c0 + 1]
                    ) {
                        seen[r0][c0 + 1] = true
                        q.addLast(Cell(r0, c0 + 1))
                    }
                }
            }
        }

        return comps
    }

    private fun largestEmptyRectangle(
        board: Array<BooleanArray>
    ): Int {
        val heights = IntArray(size)
        var best = 0

        for (r in 0 until size) {
            for (c in 0 until size) {
                heights[c] =
                    if (!board[r][c]) heights[c] + 1 else 0
            }

            for (left in 0 until size) {
                var minHeight = Int.MAX_VALUE

                for (right in left until size) {
                    minHeight = minOf(
                        minHeight,
                        heights[right]
                    )

                    best = max(
                        best,
                        minHeight * (right - left + 1)
                    )
                }
            }
        }

        return best
    }

    private fun Array<BooleanArray>.deepCopy():
        Array<BooleanArray> =
        Array(size) { this[it].clone() }
}
