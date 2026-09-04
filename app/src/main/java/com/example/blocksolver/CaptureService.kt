package com.example.blocksolver

import android.app.*
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.graphics.RectF
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class CaptureService : Service() {
    companion object {
        const val ACTION_START = "solver.START"
        const val ACTION_STOP = "solver.STOP"
        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_RESULT_DATA = "resultData"

        private const val CHANNEL_ID = "solver_capture"
        private const val NOTIFICATION_ID = 7
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private var overlayView: SolverOverlayView? = null
    private var windowManager: WindowManager? = null

    private val analyzer = FrameAnalyzer()
    private val solver = Solver(8)
    private val worker = Executors.newSingleThreadExecutor()
    private val busy = AtomicBoolean(false)

    private var lastAnalyzeMs = 0L
    private var lastHash = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()

            ACTION_START -> {
                createChannel()
                startForegroundCompat()

                val code = intent.getIntExtra(
                    EXTRA_RESULT_CODE,
                    Activity.RESULT_CANCELED
                )

                @Suppress("DEPRECATION")
                val data = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(
                        EXTRA_RESULT_DATA,
                        Intent::class.java
                    )
                } else {
                    intent.getParcelableExtra(EXTRA_RESULT_DATA)
                }

                if (code == Activity.RESULT_OK && data != null) {
                    startProjection(code, data)
                }
            }
        }

        return START_NOT_STICKY
    }

    private fun startForegroundCompat() {
        val stopIntent = Intent(
            this,
            CaptureService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val stopPi = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val notification = NotificationCompat.Builder(
            this,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("Block Solver работает")
            .setContentText("Анализ поля и подсказки поверх игры")
            .setOngoing(true)
            .addAction(0, "Стоп", stopPi)
            .build()

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(
                NOTIFICATION_ID,
                notification
            )
        }
    }

    private fun startProjection(
        resultCode: Int,
        data: Intent
    ) {
        val wm = getSystemService(
            Context.WINDOW_SERVICE
        ) as WindowManager

        windowManager = wm

        val bounds = if (Build.VERSION.SDK_INT >= 30) {
            wm.currentWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            val d = resources.displayMetrics
            android.graphics.Rect(
                0,
                0,
                d.widthPixels,
                d.heightPixels
            )
        }

        val width = bounds.width()
        val height = bounds.height()
        val density = resources.displayMetrics.densityDpi

        addOverlay(width, height)
        overlayView?.showStatus(
            null,
            "v1.0 SURVIVAL • захват запущен"
        )

        val mgr = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        val projection = mgr.getMediaProjection(
            resultCode,
            data
        )

        mediaProjection = projection

        projection.registerCallback(
            object : MediaProjection.Callback() {
                override fun onStop() {
                    stopSelf()
                }
            },
            null
        )

        val reader = ImageReader.newInstance(
            width,
            height,
            PixelFormat.RGBA_8888,
            2
        )

        imageReader = reader

        reader.setOnImageAvailableListener(
            { r ->
                val now = System.currentTimeMillis()

                if (
                    now - lastAnalyzeMs < 90 ||
                    !busy.compareAndSet(false, true)
                ) {
                    r.acquireLatestImage()?.close()
                    return@setOnImageAvailableListener
                }

                lastAnalyzeMs = now

                val image = r.acquireLatestImage()
                    ?: run {
                        busy.set(false)
                        return@setOnImageAvailableListener
                    }

                try {
                    val plane = image.planes[0]
                    val buffer = plane.buffer
                    val pixelStride = plane.pixelStride
                    val rowStride = plane.rowStride

                    val rowPadding =
                        rowStride - pixelStride * width

                    val paddedWidth =
                        width + rowPadding / pixelStride

                    val temp = Bitmap.createBitmap(
                        paddedWidth,
                        height,
                        Bitmap.Config.ARGB_8888
                    )

                    temp.copyPixelsFromBuffer(buffer)

                    val bmp = Bitmap.createBitmap(
                        temp,
                        0,
                        0,
                        width,
                        height
                    )

                    temp.recycle()

                    worker.execute {
                        try {
                            processFrame(bmp)
                        } finally {
                            bmp.recycle()
                            busy.set(false)
                        }
                    }
                } finally {
                    image.close()
                }
            },
            null
        )

        virtualDisplay = projection.createVirtualDisplay(
            "BlockSolverCapture",
            width,
            height,
            density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            reader.surface,
            null,
            null
        )
    }

    private fun processFrame(bitmap: Bitmap) {
        val analysis = analyzer.analyze(bitmap)
        val overlayRect = toOverlayRect(
            analysis.boardRect
        )

        if (analysis.pieces.isEmpty()) {
            overlayView?.post {
                overlayView?.showStatus(
                    overlayRect,
                    "v1.0 SURVIVAL • ждём фигуры",
                    null
                )
            }
            lastHash = ""
            return
        }

        val hash = stateHash(
            analysis.board,
            analysis.pieces
        )

        if (hash == lastHash) {
            return
        }

        lastHash = hash

        val solution = solver.solve(
            analysis.board,
            analysis.pieces
        )

        overlayView?.post {
            if (solution == null) {
                overlayView?.showStatus(
                    overlayRect,
                    "v1.0 SURVIVAL • хода нет",
                    null
                )
            } else {
                overlayView?.showStatus(
                    overlayRect,
                    "v1.0 SURVIVAL • BEST • ${analysis.pieces.joinToString("-") { it.cells.size.toString() }}",
                    solution
                )
            }
        }
    }

    private fun toOverlayRect(
        captureRect: RectF
    ): RectF {
        // On this Samsung, TYPE_APPLICATION_OVERLAY starts below
        // the status bar, while MediaProjection captures the whole display.
        // Convert full-screen capture Y coordinates to overlay-local Y.
        val statusBarHeight = getStatusBarHeight().toFloat()

        return RectF(
            captureRect.left,
            captureRect.top - statusBarHeight,
            captureRect.right,
            captureRect.bottom - statusBarHeight
        )
    }

    private fun getStatusBarHeight(): Int {
        val id = resources.getIdentifier(
            "status_bar_height",
            "dimen",
            "android"
        )

        return if (id > 0) {
            resources.getDimensionPixelSize(id)
        } else {
            0
        }
    }

    private fun stateHash(
        board: Array<BooleanArray>,
        pieces: List<Piece>
    ): String {
        val b = buildString {
            for (r in board) {
                for (v in r) {
                    append(if (v) '1' else '0')
                }
            }
        }

        val p = pieces.joinToString("|") { pc ->
            pc.cells
                .sortedWith(
                    compareBy<Cell> { it.r }
                        .thenBy { it.c }
                )
                .joinToString(",") {
                    "${it.r}:${it.c}"
                }
        }

        return "$b#$p"
    }

    private fun addOverlay(
        width: Int,
        height: Int
    ) {
        val view = SolverOverlayView(this)
        overlayView = view

        val params = WindowManager.LayoutParams(
            width,
            height,
            WindowManager.LayoutParams
                .TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams
                .FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams
                    .FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams
                    .FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }

        windowManager?.addView(
            view,
            params
        )
    }

    override fun onDestroy() {
        overlayView?.let { v ->
            runCatching {
                windowManager?.removeView(v)
            }
        }

        overlayView = null

        imageReader?.close()
        imageReader = null

        virtualDisplay?.release()
        virtualDisplay = null

        mediaProjection?.stop()
        mediaProjection = null

        worker.shutdownNow()

        super.onDestroy()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(
                NotificationManager::class.java
            )

            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Screen solver",
                    NotificationManager.IMPORTANCE_LOW
                )
            )
        }
    }
}
