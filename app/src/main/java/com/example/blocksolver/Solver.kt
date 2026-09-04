package com.example.blocksolver

import kotlin.math.max

class Solver(private val size: Int = 8) {

    fun solve(initial: Array<BooleanArray>, pieces: List<Piece>): Solution? {
        if (pieces.size != 3 || pieces.any { it.cells.isEmpty() }) return null
        var best: Solution? = null

        for (order in permutations(listOf(0, 1, 2))) {
            dfs(initial.deepCopy(), pieces, order, 0, mutableListOf(), 0, bestRef = { candidate ->
                if (best == null || candidate.score > best!!.score) best = candidate
            })
        }
        return best
    }

    private fun dfs(
        board: Array<BooleanArray>,
        pieces: List<Piece>,
        order: List<Int>,
        depth: Int,
        steps: MutableList<Placement>,
        clearedSoFar: Int,
        bestRef: (Solution) -> Unit
    ) {
        if (depth == 3) {
            bestRef(Solution(steps.toList(), evaluate(board, clearedSoFar)))
            return
        }

        val pieceIndex = order[depth]
        val p = pieces[pieceIndex].normalized()
        var hadMove = false

        for (r in 0..(size - p.height)) {
            for (c in 0..(size - p.width)) {
                if (!fits(board, p, r, c)) continue
                hadMove = true
                val next = board.deepCopy()
                val placedCells = p.cells.map { Cell(r + it.r, c + it.c) }.toSet()
                for (cell in placedCells) next[cell.r][cell.c] = true
                val cleared = clearLines(next)
                steps += Placement(pieceIndex, r, c, placedCells)
                dfs(next, pieces, order, depth + 1, steps, clearedSoFar + cleared, bestRef)
                steps.removeAt(steps.lastIndex)
            }
        }

        if (!hadMove) return
    }

    private fun fits(board: Array<BooleanArray>, p: Piece, top: Int, left: Int): Boolean {
        for (cell in p.cells) {
            if (board[top + cell.r][left + cell.c]) return false
        }
        return true
    }

    private fun clearLines(board: Array<BooleanArray>): Int {
        val fullRows = (0 until size).filter { r -> (0 until size).all { c -> board[r][c] } }
        val fullCols = (0 until size).filter { c -> (0 until size).all { r -> board[r][c] } }
        for (r in fullRows) for (c in 0 until size) board[r][c] = false
        for (c in fullCols) for (r in 0 until size) board[r][c] = false
        return fullRows.size + fullCols.size
    }

    private fun evaluate(board: Array<BooleanArray>, cleared: Int): Double {
        val occupied = board.sumOf { row -> row.count { it } }
        val isolated = countIsolatedEmpty(board)
        val components = emptyComponents(board)
        val largestRect = largestEmptyRectangle(board)
        val twoByTwo = countOpen2x2(board)
        val linePotential = linePotential(board)

        return cleared * 1200.0 - occupied * 9.0 - isolated * 110.0 -
            max(0, components - 1) * 30.0 + largestRect * 8.0 +
            twoByTwo * 5.0 + linePotential * 0.8
    }

    private fun countIsolatedEmpty(board: Array<BooleanArray>): Int {
        var n = 0
        val dirs = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        for (r in 0 until size) for (c in 0 until size) {
            if (board[r][c]) continue
            var open = 0
            for ((dr, dc) in dirs) {
                val rr = r + dr; val cc = c + dc
                if (rr in 0 until size && cc in 0 until size && !board[rr][cc]) open++
            }
            if (open <= 1) n++
        }
        return n
    }

    private fun emptyComponents(board: Array<BooleanArray>): Int {
        val seen = Array(size) { BooleanArray(size) }
        val dirs = arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)
        var comps = 0
        for (r in 0 until size) for (c in 0 until size) {
            if (board[r][c] || seen[r][c]) continue
            comps++
            val q = ArrayDeque<Cell>()
            q += Cell(r, c); seen[r][c] = true
            while (q.isNotEmpty()) {
                val cur = q.removeFirst()
                for ((dr, dc) in dirs) {
                    val rr = cur.r + dr; val cc = cur.c + dc
                    if (rr in 0 until size && cc in 0 until size && !board[rr][cc] && !seen[rr][cc]) {
                        seen[rr][cc] = true; q += Cell(rr, cc)
                    }
                }
            }
        }
        return comps
    }

    private fun largestEmptyRectangle(board: Array<BooleanArray>): Int {
        val h = IntArray(size)
        var best = 0
        for (r in 0 until size) {
            for (c in 0 until size) h[c] = if (!board[r][c]) h[c] + 1 else 0
            for (l in 0 until size) {
                var minH = Int.MAX_VALUE
                for (rr in l until size) {
                    minH = minOf(minH, h[rr])
                    best = max(best, minH * (rr - l + 1))
                }
            }
        }
        return best
    }

    private fun countOpen2x2(board: Array<BooleanArray>): Int {
        var n = 0
        for (r in 0 until size - 1) for (c in 0 until size - 1) {
            if (!board[r][c] && !board[r+1][c] && !board[r][c+1] && !board[r+1][c+1]) n++
        }
        return n
    }

    private fun linePotential(board: Array<BooleanArray>): Int {
        var s = 0
        for (r in 0 until size) {
            val k = board[r].count { it }
            s += k * k
        }
        for (c in 0 until size) {
            var k = 0
            for (r in 0 until size) if (board[r][c]) k++
            s += k * k
        }
        return s
    }

    private fun permutations(src: List<Int>): List<List<Int>> = listOf(
        listOf(src[0], src[1], src[2]), listOf(src[0], src[2], src[1]),
        listOf(src[1], src[0], src[2]), listOf(src[1], src[2], src[0]),
        listOf(src[2], src[0], src[1]), listOf(src[2], src[1], src[0])
    )

    private fun Array<BooleanArray>.deepCopy() = Array(size) { this[it].clone() }
}
