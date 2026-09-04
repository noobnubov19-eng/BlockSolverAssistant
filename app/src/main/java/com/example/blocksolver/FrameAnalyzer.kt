package com.example.blocksolver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Theme-independent screen analyzer.
 *
 * We do NOT classify by hue (green/blue/red/etc).
 * Instead we detect blocks by geometry + local brightness contrast
 * against the board/tray background in the current frame.
 */
class FrameAnalyzer {
    companion object {
        // Calibrated from the user's real 709x1536 screenshots.
        private const val BOARD_LEFT = 42f / 709f
        private const val BOARD_TOP = 364f / 1536f
        private const val BOARD_RIGHT = 667f / 709f
        private const val BOARD_BOTTOM = 988f / 1536f

        private const val TRAY_TOP = 0.705f
        private const val TRAY_BOTTOM = 0.825f
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

    private fun readBoard(
        bitmap: Bitmap,
        rect: RectF
    ): Array<BooleanArray> {
        val out = Array(8) { BooleanArray(8) }
        val cw = rect.width() / 8f
        val ch = rect.height() / 8f

        val values = FloatArray(64)
        var i = 0

        // Read a small patch around every cell center.
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val cx = rect.left + (c + .5f) * cw
                val cy = rect.top + (r + .5f) * ch

                values[i++] = patchBrightness(
                    bitmap,
                    cx.toInt(),
                    cy.toInt(),
                    radius = max(2, (cw * 0.055f).toInt())
                )
            }
        }

        // Empty cells are always among the darkest board centers.
        // Use the lower quartile to estimate the current theme's board background.
        val sorted = values.sorted()
        val sampleCount = max(8, sorted.size / 4)
        val emptyBase = sorted.take(sampleCount).average().toFloat()

        // Filled glossy blocks are substantially brighter than an empty slot.
        // Dynamic threshold adapts across purple/green/blue/etc themes.
        val threshold = (emptyBase + 0.18f).coerceIn(0.48f, 0.82f)

        i = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                out[r][c] = values[i++] > threshold
            }
        }

        return out
    }

    private fun readPieces(bitmap: Bitmap): List<Piece> {
        val w = bitmap.width
        val h = bitmap.height

        val y0 = (h * TRAY_TOP)
            .toInt()
            .coerceIn(0, h - 1)

        val y1 = (h * TRAY_BOTTOM)
            .toInt()
            .coerceIn(y0 + 1, h)

        // Three known piece lanes. They overlap slightly on purpose.
        val ranges = listOf(
            0 until (w * 0.36f).toInt(),
            (w * 0.30f).toInt() until (w * 0.70f).toInt(),
            (w * 0.64f).toInt() until w
        )

        val result = mutableListOf<Piece>()

        for (range in ranges) {
            val x0 = range.first.coerceAtLeast(0)
            val x1 = (range.last + 1).coerceAtMost(w)

            // Estimate this lane's background brightness from many samples.
            // Pieces occupy a minority of the lane, so the lower half is background.
            val laneSamples = ArrayList<Float>(3000)
            for (y in y0 until y1 step 5) {
                for (x in x0 until x1 step 5) {
                    laneSamples += pixelBrightness(
                        bitmap.getPixel(x, y)
                    )
                }
            }

            if (laneSamples.isEmpty()) continue

            laneSamples.sort()
            val baseCount = max(20, laneSamples.size / 2)
            val background = laneSamples
                .take(baseCount)
                .average()
                .toFloat()

            // A block pixel must stand out from the local tray background.
            val pixelThreshold =
                (background + 0.13f)
                    .coerceIn(0.56f, 0.88f)

            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var brightCount = 0

            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    if (
                        pixelBrightness(
                            bitmap.getPixel(x, y)
                        ) > pixelThreshold
                    ) {
                        brightCount++
                        minX = minOf(minX, x)
                        maxX = maxOf(maxX, x)
                        minY = minOf(minY, y)
                        maxY = maxOf(maxY, y)
                    }
                }
            }

            // Reject noise / highlights that are too small to be a piece.
            if (brightCount < 45 || minX == Int.MAX_VALUE) {
                continue
            }

            val boxW = (maxX - minX + 1).toFloat()
            val boxH = (maxY - minY + 1).toFloat()

            // Piece cells are ~screenWidth/20 in the user's game layout.
            val pitch = w / 20f
            val cols =
                (boxW / pitch)
                    .roundToInt()
                    .coerceIn(1, 5)
            val rows =
                (boxH / pitch)
                    .roundToInt()
                    .coerceIn(1, 5)

            val cellThreshold =
                (background + 0.105f)
                    .coerceIn(0.52f, 0.86f)

            val cells = mutableSetOf<Cell>()

            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val cx = (
                        minX +
                            (c + .5f) *
                            boxW / cols
                        )
                        .toInt()
                        .coerceIn(0, w - 1)

                    val cy = (
                        minY +
                            (r + .5f) *
                            boxH / rows
                        )
                        .toInt()
                        .coerceIn(0, h - 1)

                    val value = patchBrightness(
                        bitmap,
                        cx,
                        cy,
                        radius = max(2, (pitch * 0.10f).toInt())
                    )

                    if (value > cellThreshold) {
                        cells += Cell(r, c)
                    }
                }
            }

            // A valid game piece is compact and contains at least one cell.
            if (cells.isNotEmpty() && cells.size <= 9) {
                result += Piece(cells).normalized()
            }
        }

        return result
    }

    /**
     * Theme-independent "brightness":
     * value of the brightest RGB channel, normalized 0..1.
     *
     * No hue or saturation restrictions are used.
     */
    private fun pixelBrightness(color: Int): Float {
        val r = Color.red(color)
        val g = Color.green(color)
        val b = Color.blue(color)
        return max(r, max(g, b)) / 255f
    }

    private fun patchBrightness(
        bitmap: Bitmap,
        cx: Int,
        cy: Int,
        radius: Int
    ): Float {
        val x0 = (cx - radius).coerceAtLeast(0)
        val x1 = (cx + radius).coerceAtMost(bitmap.width - 1)
        val y0 = (cy - radius).coerceAtLeast(0)
        val y1 = (cy + radius).coerceAtMost(bitmap.height - 1)

        var sum = 0f
        var count = 0

        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                sum += pixelBrightness(
                    bitmap.getPixel(x, y)
                )
                count++
            }
        }

        return if (count == 0) {
            0f
        } else {
            sum / count
        }
    }
}
