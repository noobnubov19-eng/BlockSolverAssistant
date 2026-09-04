package com.example.blocksolver

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

class SolverOverlayView(context: Context) : View(context) {
    private var boardRect: RectF? = null
    private var solution: Solution? = null
    private var statusText: String = "SOLVER ON"

    private val movePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFD740.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 6f
    }

    private val boardPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x6600E5FF.toInt()
        style = Paint.Style.STROKE
        strokeWidth = 2f
    }

    private val badgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCC111111.toInt()
        style = Paint.Style.FILL
    }

    private val numberPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 34f
        textAlign = Paint.Align.CENTER
        style = Paint.Style.FILL
        setShadowLayer(5f, 0f, 0f, 0xFF000000.toInt())
    }

    private val statusPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textSize = 25f
        textAlign = Paint.Align.LEFT
        style = Paint.Style.FILL
    }

    fun showStatus(
        boardRect: RectF?,
        text: String,
        solution: Solution? = null
    ) {
        this.boardRect = boardRect?.let { RectF(it) }
        this.statusText = text
        this.solution = solution
        postInvalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val badge = RectF(14f, 90f, 410f, 140f)
        canvas.drawRoundRect(badge, 16f, 16f, badgePaint)
        canvas.drawText(statusText, 28f, 124f, statusPaint)

        val rect = boardRect ?: return
        canvas.drawRect(rect, boardPaint)

        val step = solution?.steps?.firstOrNull() ?: return
        val cw = rect.width() / 8f
        val ch = rect.height() / 8f

        for (cell in step.cells) {
            val left = rect.left + cell.c * cw + 4f
            val top = rect.top + cell.r * ch + 4f

            canvas.drawRect(
                left,
                top,
                left + cw - 8f,
                top + ch - 8f,
                movePaint
            )
        }

        val anchor = step.cells.minWithOrNull(
            compareBy<Cell> { it.r }.thenBy { it.c }
        )

        if (anchor != null) {
            val x = rect.left + (anchor.c + .5f) * cw
            val y = rect.top + (anchor.r + .66f) * ch
            canvas.drawText("1", x, y, numberPaint)
        }
    }
}
