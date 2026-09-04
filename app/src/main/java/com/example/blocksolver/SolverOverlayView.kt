package com.example.blocksolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class SolverOverlayView(context: Context) : View(context) {
    private var boardRect: RectF? = null
    private var solution: Solution? = null

    private val paints = listOf(
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00E5FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFD740.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f },
        Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF4081.toInt(); style = Paint.Style.STROKE; strokeWidth = 5f }
    )
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 32f; textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL; setShadowLayer(5f, 0f, 0f, 0xFF000000.toInt())
    }

    fun show(boardRect: RectF, solution: Solution?) {
        this.boardRect = RectF(boardRect)
        this.solution = solution
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val rect = boardRect ?: return
        val sol = solution ?: return
        val cw = rect.width()/8f
        val ch = rect.height()/8f
        sol.steps.forEachIndexed { index, step ->
            val p = paints[index.coerceAtMost(2)]
            for (cell in step.cells) {
                val l = rect.left + cell.c*cw + 4f
                val t = rect.top + cell.r*ch + 4f
                val rr = l + cw - 8f
                val bb = t + ch - 8f
                canvas.drawRect(l,t,rr,bb,p)
            }
            val anchor = step.cells.minWithOrNull(compareBy<Cell> { it.r }.thenBy { it.c })
            if (anchor != null) {
                val x = rect.left + (anchor.c+.5f)*cw
                val y = rect.top + (anchor.r+.65f)*ch
                canvas.drawText((index+1).toString(), x, y, textPaint)
            }
        }
    }
}
