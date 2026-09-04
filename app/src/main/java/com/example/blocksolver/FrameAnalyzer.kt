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
        val colors = Array(64) { Rgb(0f, 0f, 0f) }
        val textures = FloatArray(64)

        val cw = rect.width() / 8f
        val ch = rect.height() / 8f

        var index = 0
        for (r in 0 until 8) {
            for (c in 0 until 8) {
                val feature = readCellFeature(
                    bitmap,
                    rect.left + c * cw,
                    rect.top + r * ch,
                    cw,
                    ch
                )
                colors[index] = feature.color
                textures[index] = feature.texture
                index++
            }
        }

        // IMPORTANT:
        // Do not use texture to decide occupancy.
        // Some later themes have a textured empty board and raw MediaProjection
        // preserves that texture more strongly than a saved JPEG screenshot.
        //
        // Instead find the densest RGB cluster among the 64 cell medians.
        // Empty slots share almost the same median color even when the theme changes,
        // while occupied blocks are colored outliers.
        val clusterRadius = 0.060f

        var bestIndex = 0
        var bestCount = -1
        var bestTexture = Float.MAX_VALUE

        for (i in colors.indices) {
            var count = 0

            for (j in colors.indices) {
                if (
                    distance(colors[i], colors[j]) <=
                    clusterRadius
                ) {
                    count++
                }
            }

            if (
                count > bestCount ||
                (
                    count == bestCount &&
                    textures[i] < bestTexture
                )
            ) {
                bestIndex = i
                bestCount = count
                bestTexture = textures[i]
            }
        }

        // Average only the dense cluster to get the current empty-cell reference.
        var sr = 0f
        var sg = 0f
        var sb = 0f
        var n = 0

        for (color in colors) {
            if (
                distance(color, colors[bestIndex]) <=
                clusterRadius
            ) {
                sr += color.r
                sg += color.g
                sb += color.b
                n++
            }
        }

        val emptyReference =
            if (n > 0) {
                Rgb(
                    sr / n,
                    sg / n,
                    sb / n
                )
            } else {
                colors[bestIndex]
            }

        // Empty slots in all captured themes are extremely tightly clustered.
        // 0.095 leaves a large safety margin while still separating colored blocks.
        val occupiedThreshold = 0.095f

        val out = Array(8) {
            BooleanArray(8)
        }

        index = 0

        for (r in 0 until 8) {
            for (c in 0 until 8) {
                out[r][c] =
                    distance(
                        colors[index],
                        emptyReference
                    ) > occupiedThreshold

                index++
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

            // Use only STRONG color outliers to define the piece bounding box.
            // This avoids tray texture/shadows enlarging or shrinking the shape.
            val strongPoints = ArrayList<Pair<Int, Int>>()

            for (y in y0 until y1 step 2) {
                for (x in x0 until x1 step 2) {
                    val d = distance(
                        rgb(bitmap.getPixel(x, y)),
                        background
                    )

                    if (d > 0.115f) {
                        strongPoints += x to y
                    }
                }
            }

            if (strongPoints.size < 35) {
                continue
            }

            val xs = strongPoints.map { it.first }.sorted()
            val ys = strongPoints.map { it.second }.sorted()

            // Ignore a tiny fraction of isolated glow/shadow pixels.
            val trimX = (xs.size * 0.015f)
                .toInt()
                .coerceIn(0, max(0, xs.size / 8))

            val trimY = (ys.size * 0.015f)
                .toInt()
                .coerceIn(0, max(0, ys.size / 8))

            val minX = xs[trimX]
            val maxX = xs[xs.lastIndex - trimX]
            val minY = ys[trimY]
            val maxY = ys[ys.lastIndex - trimY]

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

            // IMPORTANT:
            // Do not decide a cell from ONE center pixel.
            // Count foreground coverage over the whole interior of each inferred cell.
            // This fixes L-pieces where one arm could be missed by a single sample.
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val left = minX + c * boxW / cols
                    val right = minX + (c + 1) * boxW / cols
                    val top = minY + r * boxH / rows
                    val bottom = minY + (r + 1) * boxH / rows

                    val marginX = (right - left) * 0.16f
                    val marginY = (bottom - top) * 0.16f

                    val sx0 = (left + marginX).toInt()
                    val sx1 = (right - marginX).toInt()
                    val sy0 = (top + marginY).toInt()
                    val sy1 = (bottom - marginY).toInt()

                    var total = 0
                    var foreground = 0

                    for (y in sy0..sy1 step 2) {
                        for (x in sx0..sx1 step 2) {
                            total++

                            if (
                                distance(
                                    rgb(bitmap.getPixel(
                                        x.coerceIn(0, w - 1),
                                        y.coerceIn(0, h - 1)
                                    )),
                                    background
                                ) > 0.065f
                            ) {
                                foreground++
                            }
                        }
                    }

                    val ratio =
                        if (total > 0) {
                            foreground.toFloat() / total
                        } else {
                            0f
                        }

                    if (ratio > 0.22f) {
                        cells += Cell(r, c)
                    }
                }
            }

            // Keep only plausible, connected game pieces.
            if (
                cells.isNotEmpty() &&
                cells.size <= 9 &&
                isConnected(cells)
            ) {
                result += Piece(cells).normalized()
            }
        }

        return result
    }

    private fun isConnected(cells: Set<Cell>): Boolean {
        if (cells.isEmpty()) return false

        val seen = mutableSetOf<Cell>()
        val queue = ArrayDeque<Cell>()

        val first = cells.first()
        seen += first
        queue.addLast(first)

        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()

            val neighbours = arrayOf(
                Cell(cur.r - 1, cur.c),
                Cell(cur.r + 1, cur.c),
                Cell(cur.r, cur.c - 1),
                Cell(cur.r, cur.c + 1)
            )

            for (next in neighbours) {
                if (
                    next in cells &&
                    next !in seen
                ) {
                    seen += next
                    queue.addLast(next)
                }
            }
        }

        return seen.size == cells.size
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
