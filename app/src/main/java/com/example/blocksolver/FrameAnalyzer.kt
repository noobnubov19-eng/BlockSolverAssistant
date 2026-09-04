package com.example.blocksolver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.sqrt
import kotlin.math.roundToInt

/**
 * v1.2 adaptive analyzer.
 *
 * No fixed block color and no "blocks must be brighter" rule.
 * The game can switch to light/dark boards and any piece hue.
 *
 * Board:
 * - samples the interior texture/color of every 8x8 cell;
 * - finds the flattest cell as the current empty-slot reference;
 * - marks a cell occupied when its median RGB is far from that reference
 *   or it has strong block texture/bevel.
 *
 * Tray:
 * - estimates the background RGB separately for each of the three lanes;
 * - finds pixels/cells by COLOR DISTANCE from that local background,
 *   so red/blue/green/purple/pink pieces all work.
 */
class FrameAnalyzer {
    companion object {
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

    private data class Rgb(
        val r: Float,
        val g: Float,
        val b: Float
    )

    private data class CellFeature(
        val color: Rgb,
        val texture: Float
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
        val features = Array(8) {
            arrayOfNulls<CellFeature>(8)
        }

        val cw = rect.width() / 8f
        val ch = rect.height() / 8f

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                features[r][c] = readCellFeature(
                    bitmap,
                    rect.left + c * cw,
                    rect.top + r * ch,
                    cw,
                    ch
                )
            }
        }

        // Empty slots are visually much flatter than glossy game blocks.
        // Use the flattest cell as the reference color for THIS board theme.
        var ref = features[0][0]!!
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val f = features[r][c]!!
                if (f.texture < ref.texture) {
                    ref = f
                }
            }
        }

        val out = Array(8) { BooleanArray(8) }

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val f = features[r][c]!!
                val colorDelta = distance(f.color, ref.color)

                // Color distance catches solid/dark blocks on a light board.
                // Texture catches glossy/beveled blocks whose center color
                // happens to be closer to the board theme.
                out[r][c] =
                    colorDelta > 0.105f ||
                    f.texture > max(0.022f, ref.texture + 0.014f)
            }
        }

        return out
    }

    private fun readCellFeature(
        bitmap: Bitmap,
        left: Float,
        top: Float,
        cw: Float,
        ch: Float
    ): CellFeature {
        val colors = ArrayList<Rgb>(25)

        // Stay away from the cell border and sample a 5x5 interior grid.
        // Median color makes our own white "1" overlay almost irrelevant.
        val coords = floatArrayOf(
            0.18f, 0.34f, 0.50f, 0.66f, 0.82f
        )

        for (fy in coords) {
            for (fx in coords) {
                val x = (left + fx * cw)
                    .toInt()
                    .coerceIn(0, bitmap.width - 1)

                val y = (top + fy * ch)
                    .toInt()
                    .coerceIn(0, bitmap.height - 1)

                colors += rgb(bitmap.getPixel(x, y))
            }
        }

        val rs = colors.map { it.r }.sorted()
        val gs = colors.map { it.g }.sorted()
        val bs = colors.map { it.b }.sorted()
        val mid = colors.size / 2

        val median = Rgb(
            rs[mid],
            gs[mid],
            bs[mid]
        )

        val deviations = colors
            .map { distance(it, median) }
            .sorted()

        // 75th percentile: ignores a few overlay/text pixels,
        // but still sees the bevel/texture across a real block.
        val texture = deviations[
            ((deviations.size - 1) * 0.75f)
                .roundToInt()
                .coerceIn(0, deviations.lastIndex)
        ]

        return CellFeature(
            color = median,
            texture = texture
        )
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

        // Strict lanes prevent one long piece from being detected twice.
        val ranges = listOf(
            (w * 0.04f).toInt() until (w * 0.32f).toInt(),
            (w * 0.32f).toInt() until (w * 0.64f).toInt(),
            (w * 0.64f).toInt() until (w * 0.96f).toInt()
        )

        val result = mutableListOf<Piece>()

        for (range in ranges) {
            val x0 = range.first.coerceAtLeast(0)
            val x1 = (range.last + 1).coerceAtMost(w)

            val background = estimateLaneBackground(
                bitmap,
                x0,
                x1,
                y0,
                y1
            )

            // First find the visible bounding box by RGB distance
            // from the lane's own current-theme background.
            var minX = Int.MAX_VALUE
            var maxX = Int.MIN_VALUE
            var minY = Int.MAX_VALUE
            var maxY = Int.MIN_VALUE
            var foregroundCount = 0

            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    val d = distance(
                        rgb(bitmap.getPixel(x, y)),
                        background
                    )

                    if (d > 0.075f) {
                        foregroundCount++
                        minX = minOf(minX, x)
                        maxX = maxOf(maxX, x)
                        minY = minOf(minY, y)
                        maxY = maxOf(maxY, y)
                    }
                }
            }

            // Empty lane / tiny compression noise.
            if (
                foregroundCount < 45 ||
                minX == Int.MAX_VALUE
            ) {
                continue
            }

            val boxW = (maxX - minX + 1).toFloat()
            val boxH = (maxY - minY + 1).toFloat()

            val pitch = w / 20f

            val cols = (boxW / pitch)
                .roundToInt()
                .coerceIn(1, 5)

            val rows = (boxH / pitch)
                .roundToInt()
                .coerceIn(1, 5)

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

                    val sample = patchMedianRgb(
                        bitmap,
                        cx,
                        cy,
                        max(2, (pitch * 0.10f).toInt())
                    )

                    if (
                        distance(sample, background) >
                        0.070f
                    ) {
                        cells += Cell(r, c)
                    }
                }
            }

            if (
                cells.isNotEmpty() &&
                cells.size <= 9
            ) {
                result += Piece(cells).normalized()
            }
        }

        return result
    }

    private fun estimateLaneBackground(
        bitmap: Bitmap,
        x0: Int,
        x1: Int,
        y0: Int,
        y1: Int
    ): Rgb {
        val rs = ArrayList<Float>()
        val gs = ArrayList<Float>()
        val bs = ArrayList<Float>()

        for (y in y0 until y1 step 6) {
            for (x in x0 until x1 step 6) {
                val p = rgb(bitmap.getPixel(x, y))
                rs += p.r
                gs += p.g
                bs += p.b
            }
        }

        rs.sort()
        gs.sort()
        bs.sort()

        val mid = rs.size / 2

        return Rgb(
            rs[mid],
            gs[mid],
            bs[mid]
        )
    }

    private fun patchMedianRgb(
        bitmap: Bitmap,
        cx: Int,
        cy: Int,
        radius: Int
    ): Rgb {
        val rs = ArrayList<Float>()
        val gs = ArrayList<Float>()
        val bs = ArrayList<Float>()

        val x0 = (cx - radius).coerceAtLeast(0)
        val x1 = (cx + radius)
            .coerceAtMost(bitmap.width - 1)

        val y0 = (cy - radius).coerceAtLeast(0)
        val y1 = (cy + radius)
            .coerceAtMost(bitmap.height - 1)

        for (y in y0..y1 step 2) {
            for (x in x0..x1 step 2) {
                val p = rgb(bitmap.getPixel(x, y))
                rs += p.r
                gs += p.g
                bs += p.b
            }
        }

        rs.sort()
        gs.sort()
        bs.sort()

        val mid = rs.size / 2

        return Rgb(
            rs[mid],
            gs[mid],
            bs[mid]
        )
    }

    private fun rgb(color: Int): Rgb =
        Rgb(
            Color.red(color) / 255f,
            Color.green(color) / 255f,
            Color.blue(color) / 255f
        )

    private fun distance(a: Rgb, b: Rgb): Float {
        val dr = a.r - b.r
        val dg = a.g - b.g
        val db = a.b - b.b

        return sqrt(
            dr * dr +
            dg * dg +
            db * db
        )
    }
}
