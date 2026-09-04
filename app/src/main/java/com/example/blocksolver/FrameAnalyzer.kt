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
        val colors = Array(64) {
            Rgb(0f, 0f, 0f)
        }

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

                colors[index++] = feature.color
            }
        }

        // The board may be mostly EMPTY or mostly FILLED.
        // So "largest color cluster = empty" is not reliable.
        //
        // Grid/gap pixels always belong to the board theme, never to a block.
        // We find all tight cell-color clusters and select the cluster whose
        // HUE/SATURATION is most compatible with those grid pixels, while also
        // preferring a cluster that contains many cells.
        //
        // This fixes the cyan theme where 40/64 cells can be filled with
        // same-color blocks, and the light-purple theme where the empty board
        // is much brighter than its grid lines.
        val gridReference =
            sampleGridReference(
                bitmap,
                rect,
                cw,
                ch
            )

        val gridHsv = hsv(gridReference)
        val clusterRadius = 0.060f

        var bestReference = colors[0]
        var bestScore = Float.MAX_VALUE

        for (i in colors.indices) {
            var sr = 0f
            var sg = 0f
            var sb = 0f
            var count = 0

            for (j in colors.indices) {
                if (
                    distance(
                        colors[i],
                        colors[j]
                    ) <= clusterRadius
                ) {
                    sr += colors[j].r
                    sg += colors[j].g
                    sb += colors[j].b
                    count++
                }
            }

            if (count == 0) {
                continue
            }

            val center = Rgb(
                sr / count,
                sg / count,
                sb / count
            )

            val centerHsv = hsv(center)

            val rawHueDiff =
                kotlin.math.abs(
                    centerHsv[0] -
                        gridHsv[0]
                )

            val hueDiff =
                minOf(
                    rawHueDiff,
                    1f - rawHueDiff
                )

            val hueWeight =
                minOf(
                    1f,
                    (
                        centerHsv[1] +
                            gridHsv[1]
                        ) / 0.60f
                )

            val score =
                distance(
                    center,
                    gridReference
                ) * 1.00f +
                hueDiff * 0.35f *
                    hueWeight +
                kotlin.math.abs(
                    centerHsv[1] -
                        gridHsv[1]
                ) * 0.10f +
                kotlin.math.abs(
                    centerHsv[2] -
                        gridHsv[2]
                ) * 0.10f

            if (score < bestScore) {
                bestScore = score
                bestReference = center
            }
        }

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
                        bestReference
                    ) > occupiedThreshold

                index++
            }
        }

        return out
    }

    private fun sampleGridReference(
        bitmap: Bitmap,
        rect: RectF,
        cw: Float,
        ch: Float
    ): Rgb {
        val rs = ArrayList<Float>(112)
        val gs = ArrayList<Float>(112)
        val bs = ArrayList<Float>(112)

        // Midpoints of internal vertical grid gaps.
        for (c in 1 until 8) {
            val x =
                (rect.left + c * cw)
                    .toInt()
                    .coerceIn(
                        0,
                        bitmap.width - 1
                    )

            for (r in 0 until 8) {
                val y =
                    (
                        rect.top +
                            (r + 0.5f) * ch
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            bitmap.height - 1
                        )

                val p =
                    rgb(
                        bitmap.getPixel(
                            x,
                            y
                        )
                    )

                rs += p.r
                gs += p.g
                bs += p.b
            }
        }

        // Midpoints of internal horizontal grid gaps.
        for (r in 1 until 8) {
            val y =
                (rect.top + r * ch)
                    .toInt()
                    .coerceIn(
                        0,
                        bitmap.height - 1
                    )

            for (c in 0 until 8) {
                val x =
                    (
                        rect.left +
                            (c + 0.5f) * cw
                        )
                        .toInt()
                        .coerceIn(
                            0,
                            bitmap.width - 1
                        )

                val p =
                    rgb(
                        bitmap.getPixel(
                            x,
                            y
                        )
                    )

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

    private fun hsv(
        color: Rgb
    ): FloatArray {
        val out = FloatArray(3)

        Color.RGBToHSV(
            (color.r * 255f)
                .roundToInt()
                .coerceIn(0, 255),
            (color.g * 255f)
                .roundToInt()
                .coerceIn(0, 255),
            (color.b * 255f)
                .roundToInt()
                .coerceIn(0, 255),
            out
        )

        // Android hue is 0..360. Normalize to 0..1.
        out[0] /= 360f
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

            // Build horizontal/vertical foreground density profiles.
            // This keeps all tiles of a polyomino together even though
            // the visual seams between adjacent glossy blocks are dark.
            val step = 2
            val colCounts = IntArray(
                ((x1 - x0) + step - 1) / step
            )
            val rowCounts = IntArray(
                ((y1 - y0) + step - 1) / step
            )

            var strongCount = 0

            for (y in y0 until y1 step step) {
                val ry = (y - y0) / step

                for (x in x0 until x1 step step) {
                    val d = distance(
                        rgb(bitmap.getPixel(x, y)),
                        background
                    )

                    if (d > 0.105f) {
                        val cx = (x - x0) / step
                        colCounts[cx]++
                        rowCounts[ry]++
                        strongCount++
                    }
                }
            }

            if (strongCount < 35) {
                continue
            }

            val maxCol = colCounts.maxOrNull() ?: 0
            val maxRow = rowCounts.maxOrNull() ?: 0

            val colThreshold = maxOf(
                3,
                (maxCol * 0.24f).roundToInt()
            )

            val rowThreshold = maxOf(
                3,
                (maxRow * 0.24f).roundToInt()
            )

            val colRun = dominantRun(
                colCounts,
                colThreshold
            )

            val rowRun = dominantRun(
                rowCounts,
                rowThreshold
            )

            if (
                colRun == null ||
                rowRun == null
            ) {
                continue
            }

            val minX = x0 + colRun.first * step
            val maxX = x0 + colRun.last * step
            val minY = y0 + rowRun.first * step
            val maxY = y0 + rowRun.last * step

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

            // Decide each inferred tile from coverage across its interior,
            // never from one pixel. This is stable for T/L/J shapes.
            for (r in 0 until rows) {
                for (c in 0 until cols) {
                    val left =
                        minX + c * boxW / cols

                    val right =
                        minX + (c + 1) * boxW / cols

                    val top =
                        minY + r * boxH / rows

                    val bottom =
                        minY + (r + 1) * boxH / rows

                    val marginX =
                        (right - left) * 0.16f

                    val marginY =
                        (bottom - top) * 0.16f

                    val sx0 = (left + marginX).toInt()
                    val sx1 = (right - marginX).toInt()
                    val sy0 = (top + marginY).toInt()
                    val sy1 = (bottom - marginY).toInt()

                    var total = 0
                    var foreground = 0

                    for (yy in sy0..sy1 step 2) {
                        for (xx in sx0..sx1 step 2) {
                            total++

                            val px = xx.coerceIn(0, w - 1)
                            val py = yy.coerceIn(0, h - 1)

                            if (
                                distance(
                                    rgb(bitmap.getPixel(px, py)),
                                    background
                                ) > 0.060f
                            ) {
                                foreground++
                            }
                        }
                    }

                    val coverage =
                        if (total > 0) {
                            foreground.toFloat() / total
                        } else {
                            0f
                        }

                    if (coverage > 0.24f) {
                        cells += Cell(r, c)
                    }
                }
            }

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

    private fun dominantRun(
        counts: IntArray,
        threshold: Int
    ): IntRange? {
        var bestStart = -1
        var bestEnd = -1
        var bestMass = -1
        var i = 0

        // Tiny seams between adjacent blocks can produce a few low bins.
        val allowedGap = 3

        while (i < counts.size) {
            while (
                i < counts.size &&
                counts[i] < threshold
            ) {
                i++
            }

            if (i >= counts.size) {
                break
            }

            val start = i
            var lastActive = i
            var mass = 0
            var gap = 0

            while (i < counts.size) {
                if (counts[i] >= threshold) {
                    mass += counts[i]
                    lastActive = i
                    gap = 0
                } else {
                    gap++

                    if (gap > allowedGap) {
                        break
                    }
                }

                i++
            }

            if (mass > bestMass) {
                bestMass = mass
                bestStart = start
                bestEnd = lastActive
            }

            i = maxOf(i, start + 1)
        }

        return if (
            bestStart >= 0 &&
            bestEnd >= bestStart
        ) {
            bestStart..bestEnd
        } else {
            null
        }
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
