package com.example.blocksolver

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import kotlin.math.abs

class FrameAnalyzer {
    companion object {
        private const val BOARD_LEFT = 40f / 720f
        private const val BOARD_TOP = 362f / 1600f
        private const val BOARD_RIGHT = 668f / 720f
        private const val BOARD_BOTTOM = 990f / 1600f
        private const val TRAY_TOP = 1080f / 1600f
        private const val TRAY_BOTTOM = 1270f / 1600f
    }

    data class Analysis(
        val board: Array<BooleanArray>,
        val pieces: List<Piece>,
        val boardRect: RectF
    )

    fun analyze(bitmap: Bitmap): Analysis {
        val w = bitmap.width.toFloat()
        val h = bitmap.height.toFloat()
        val boardRect = RectF(BOARD_LEFT*w, BOARD_TOP*h, BOARD_RIGHT*w, BOARD_BOTTOM*h)
        val board = readBoard(bitmap, boardRect)
        val pieces = readPieces(bitmap, RectF(0f, TRAY_TOP*h, w, TRAY_BOTTOM*h))
        return Analysis(board, pieces, boardRect)
    }

    private fun readBoard(bitmap: Bitmap, rect: RectF): Array<BooleanArray> {
        val out = Array(8) { BooleanArray(8) }
        val cw = rect.width() / 8f
        val ch = rect.height() / 8f
        for (r in 0 until 8) for (c in 0 until 8) {
            val cx = (rect.left + (c + .5f) * cw).toInt().coerceIn(0, bitmap.width-1)
            val cy = (rect.top + (r + .5f) * ch).toInt().coerceIn(0, bitmap.height-1)
            out[r][c] = isGreenish(bitmap.getPixel(cx, cy))
        }
        return out
    }

    private data class Box(var l:Int, var t:Int, var r:Int, var b:Int, var count:Int = 0) {
        val cx get() = (l+r)/2f
        val cy get() = (t+b)/2f
        val width get() = r-l+1
        val height get() = b-t+1
    }

    private fun readPieces(bitmap: Bitmap, tray: RectF): List<Piece> {
        val step = 2
        val x0 = tray.left.toInt().coerceAtLeast(0)
        val y0 = tray.top.toInt().coerceAtLeast(0)
        val x1 = tray.right.toInt().coerceAtMost(bitmap.width)
        val y1 = tray.bottom.toInt().coerceAtMost(bitmap.height)

        val gw = (x1-x0 + step-1)/step
        val gh = (y1-y0 + step-1)/step
        val mask = Array(gh) { BooleanArray(gw) }
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val x = x0 + gx*step
            val y = y0 + gy*step
            if (x < bitmap.width && y < bitmap.height) mask[gy][gx] = isGreenish(bitmap.getPixel(x,y))
        }

        val seen = Array(gh) { BooleanArray(gw) }
        val blobs = mutableListOf<Box>()
        val dirs = arrayOf(1 to 0,-1 to 0,0 to 1,0 to -1)

        for (gy in 0 until gh) for (gx in 0 until gw) {
            if (!mask[gy][gx] || seen[gy][gx]) continue
            val q = ArrayDeque<Pair<Int,Int>>()
            q += gx to gy
            seen[gy][gx] = true
            var minX=gx; var maxX=gx; var minY=gy; var maxY=gy; var cnt=0

            while(q.isNotEmpty()) {
                val (xx,yy)=q.removeFirst()
                cnt++
                minX=minOf(minX,xx); maxX=maxOf(maxX,xx)
                minY=minOf(minY,yy); maxY=maxOf(maxY,yy)

                for((dx,dy) in dirs){
                    val nx=xx+dx; val ny=yy+dy
                    if(nx in 0 until gw && ny in 0 until gh && mask[ny][nx] && !seen[ny][nx]){
                        seen[ny][nx]=true
                        q += nx to ny
                    }
                }
            }

            val box = Box(
                x0+minX*step,
                y0+minY*step,
                x0+(maxX+1)*step-1,
                y0+(maxY+1)*step-1,
                cnt
            )

            if (box.width in 12..60 && box.height in 12..60 && cnt > 20) blobs += box
        }

        val groups = Array(3) { mutableListOf<Box>() }
        for (b in blobs) {
            val idx = when {
                b.cx < bitmap.width * .36f -> 0
                b.cx < bitmap.width * .66f -> 1
                else -> 2
            }
            groups[idx] += b
        }

        return groups.mapNotNull { boxesToPiece(it) }
    }

    private fun boxesToPiece(boxes: List<Box>): Piece? {
        if (boxes.isEmpty()) return null
        val xs = cluster(boxes.map { it.cx })
        val ys = cluster(boxes.map { it.cy })
        if (xs.isEmpty() || ys.isEmpty()) return null

        val cells = boxes.map { b ->
            val c = xs.indices.minBy { abs(xs[it] - b.cx) }
            val r = ys.indices.minBy { abs(ys[it] - b.cy) }
            Cell(r,c)
        }.toSet()

        return Piece(cells).normalized()
    }

    private fun cluster(values: List<Float>): List<Float> {
        if (values.isEmpty()) return emptyList()
        val sorted = values.sorted()
        val out = mutableListOf<MutableList<Float>>()

        for (v in sorted) {
            val last = out.lastOrNull()
            if (last == null || abs(last.average().toFloat() - v) > 12f) {
                out += mutableListOf(v)
            } else {
                last += v
            }
        }

        return out.map { it.average().toFloat() }
    }

    private fun isGreenish(color: Int): Boolean {
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        return hsv[0] in 70f..170f && hsv[1] > .22f && hsv[2] > .18f
    }
}
