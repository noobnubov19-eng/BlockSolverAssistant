package com.example.blocksolver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.roundToInt

class FrameAnalyzer {
    companion object {
        // Calibrated from the real 709x1536 screenshot sent from the phone.
        private const val BOARD_LEFT = 42f / 709f
        private const val BOARD_TOP = 364f / 1536f
        private const val BOARD_RIGHT = 667f / 709f
        private const val BOARD_BOTTOM = 988f / 1536f

        // Piece tray zone.
        private const val TRAY_TOP = 0.71f
        private const val TRAY_BOTTOM = 0.82f
    }

    data class Analysis(
        val board: Array<BooleanArray>,
        val pieces: List<Piece>,
        val boardRect: RectF
    )

    fun analyze(bitmap: Bitmap): Analysis {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()

        val boardRect = RectF(
            BOARD_LEFT * w,
            BOARD_TOP * h,
            BOARD_RIGHT * w,
            BOARD_BOTTOM * h
        )

        return Analysis(
            board = readBoard(bitmap, boardRect),
            pieces = readPieces(bitmap),
            boardRect = boardRect
        )
    }

    private fun readBoard(bitmap: Bitmap, rect: RectF): Array<BooleanArray> {
        val out = Array(8) { BooleanArray(8) }
        val cw = rect.width() / 8f
        val ch = rect.height() / 8f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cx = (rect.left + (c + .5f) * cw)
                    .toInt()
                    .coerceIn(0, bitmap.width - 1)

                val cy = (rect.top + (r + .5f) * ch)
                    .toInt()
                    .coerceIn(0, bitmap.height - 1)

                out[r][c] = isBlockGreen(bitmap.getPixel(cx, cy))
            }
        }

        return out
    }

    private fun readPieces(bitmap: Bitmap): List<Piece> {
        val w = bitmap.width
        val h = bitmap.height
        val y0 = (h * TRAY_TOP).toInt().coerceIn(0, h - 1)
        val y1 = (h * TRAY_BOTTOM).toInt().coerceIn(y0 + 1, h)

        // Slightly overlapping thirds so edge pixels don't get lost.
        val ranges = listOf(
            0 until (w * 0.36f).toInt(),
            (w * 0.30f).toInt() until (w * 0.70f).toInt(),
            (w * 0.64f).toInt() until w
        )

        val result = mutableListOf<Piece>()

        for (range in ranges) {
            val x0 = range.first.coerceAtLeast(0)
            val x1 = (range.last + 1).coerceAtMost(w)

            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var found = false

            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    if (isBlockGreen(bitmap.getPixel(x, y))) {
                        found = true
                        minX = minOf(minX, x)
                        maxX = maxOf(maxX, x)
                        minY = minOf(minY, y)
                        maxY = maxOf(maxY, y)
                    }
                }
            }

            if (!found) continue

            // Individual piece cell is about screenWidth/20 on this game layout.
            val pitch = w / 20f
            val boxW = (maxX - minX + 1).toFloat()
            val boxH = (maxY - minY + 1).toFloat()

            val cols = (boxW / pitch).roundToInt().coerceIn(1, 5)
            val rows = (boxH / pitch).roundToInt().coerceIn(1, 5)

            val cells = mutableSetOf<Cell>()

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cx = (
                        minX + (c + .5f) * boxW / cols
                    ).toInt().coerceIn(0, w - 1)

                    val cy = (
                        minY + (r + .5f) * boxH / rows
                    ).toInt().coerceIn(0, h - 1)

                    if (isBlockGreen(bitmap.getPixel(cx, cy))) {
                        cells += Cell(r, c)
                    }
                }
            }

            if (cells.isNotEmpty()) {
                result += Piece(cells).normalized()
            }
        }

        return result
    }

    private fun isBlockGreen(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)

        // Color-independent block detector.
        // The game changes themes: blocks can be green, cyan, purple, blue, etc.
        // Real blocks are bright and saturated; board/tray background is darker.
        return hsv[1] > 0.42f &&
            hsv[2] > 0.66f
    }
}
