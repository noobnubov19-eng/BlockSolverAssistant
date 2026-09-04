package com.example.blocksolver

data class Cell(val r: Int, val c: Int)

data class Piece(val cells: Set<Cell>) {
    val height: Int = (cells.maxOfOrNull { it.r } ?: 0) + 1
    val width: Int = (cells.maxOfOrNull { it.c } ?: 0) + 1

    fun normalized(): Piece {
        if (cells.isEmpty()) return this
        val minR = cells.minOf { it.r }
        val minC = cells.minOf { it.c }
        return Piece(cells.map { Cell(it.r - minR, it.c - minC) }.toSet())
    }
}

data class Placement(
    val pieceIndex: Int,
    val top: Int,
    val left: Int,
    val cells: Set<Cell>
)

data class Solution(val steps: List<Placement>, val score: Double)
