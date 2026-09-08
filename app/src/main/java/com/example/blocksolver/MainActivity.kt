package com.example.blocksolver

import android.Manifest
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Looper
import android.provider.MediaStore
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.tan

class MainActivity : AppCompatActivity(), LocationListener, SensorEventListener {
    private lateinit var webView: WebView
    private lateinit var locationManager: LocationManager
    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var lastHeadingSent = 0L
    private val requestLocationCode = 4201
    private val importRequestCode = 4202

    private val tileRoot by lazy { File(filesDir, "offline_tiles/esri") }
    private val projectFile by lazy { File(filesDir, "project.json") }
    private val downloadCancelled = AtomicBoolean(false)
    @Volatile private var offlineActive = false
    private var offlineExecutor = Executors.newFixedThreadPool(4)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        sensorManager = getSystemService(SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        webView = WebView(this)
        setContentView(webView)

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            allowFileAccess = true
            allowContentAccess = true
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.addJavascriptInterface(AndroidBridge(), "Android")
        webView.webViewClient = TileClient()
        webView.loadUrl("file:///android_asset/index.html")
        ensureLocationPermission()
    }

    override fun onResume() {
        super.onResume()
        startSensorsAndLocation()
    }

    override fun onPause() {
        super.onPause()
        try { locationManager.removeUpdates(this) } catch (_: Exception) {}
        try { sensorManager.unregisterListener(this) } catch (_: Exception) {}
    }

    override fun onDestroy() {
        downloadCancelled.set(true)
        try { offlineExecutor.shutdownNow() } catch (_: Exception) {}
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("Android")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun ensureLocationPermission() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
                requestLocationCode
            )
        } else {
            startSensorsAndLocation()
        }
    }

    private fun startSensorsAndLocation() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 800L, 0.25f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
        try {
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 1800L, 0.5f, this, Looper.getMainLooper())
            }
        } catch (_: Exception) {}
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    override fun onLocationChanged(location: Location) {
        val provider = location.provider ?: "gps"
        val ageMs = max(0L, (android.os.SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos) / 1_000_000L)
        val altitude = if (location.hasAltitude()) location.altitude else 0.0
        val speed = if (location.hasSpeed()) location.speed else 0f
        val js = String.format(
            Locale.US,
            "window.onNativeLocation&&window.onNativeLocation(%.8f,%.8f,%.2f,%.2f,%s,%d,%.2f);",
            location.latitude, location.longitude, location.accuracy, altitude,
            JSONObject.quote(provider), ageMs, speed
        )
        runJs(js)
    }

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {
        runJs("window.onProviderDisabled&&window.onProviderDisabled(" + JSONObject.quote(provider) + ");")
    }
    @Deprecated("Deprecated in Android")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return
        val now = System.currentTimeMillis()
        if (now - lastHeadingSent < 180) return
        lastHeadingSent = now
        val matrix = FloatArray(9)
        val orientation = FloatArray(3)
        SensorManager.getRotationMatrixFromVector(matrix, event.values)
        SensorManager.getOrientation(matrix, orientation)
        var azimuth = Math.toDegrees(orientation[0].toDouble()).toFloat()
        if (azimuth < 0) azimuth += 360f
        runJs(String.format(Locale.US, "window.onNativeHeading&&window.onNativeHeading(%.1f);", azimuth))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == requestLocationCode) {
            if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) startSensorsAndLocation()
            else toast("Разреши точную геолокацию: без неё «Точка здесь» не работает")
        }
    }

    private fun runJs(script: String) {
        if (!::webView.isInitialized) return
        webView.post { webView.evaluateJavascript(script, null) }
    }

    private fun toast(text: String) {
        runOnUiThread { Toast.makeText(this, text, Toast.LENGTH_LONG).show() }
    }

    private inner class TileClient : WebViewClient() {
        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val uri = request?.url ?: return null
            if (uri.scheme == "https" && uri.host == "stroykarta.local") {
                val p = uri.pathSegments
                if (p.size >= 5 && p[0] == "tiles" && p[1] == "esri") {
                    val z = p[2].toIntOrNull() ?: return missing()
                    val x = p[3].toIntOrNull() ?: return missing()
                    val y = p[4].substringBefore('.').toIntOrNull() ?: return missing()
                    val bytes = tileBytes(z, x, y)
                    return if (bytes != null) WebResourceResponse("image/jpeg", null, ByteArrayInputStream(bytes)) else missing()
                }
            }
            return super.shouldInterceptRequest(view, request)
        }

        private fun missing(): WebResourceResponse {
            return WebResourceResponse(
                "text/plain", "UTF-8", 404, "Not Found",
                mapOf("Access-Control-Allow-Origin" to "*"),
                ByteArrayInputStream(ByteArray(0))
            )
        }
    }

    private fun tileFile(z: Int, x: Int, y: Int): File {
        return File(tileRoot, z.toString() + "/" + x.toString() + "/" + y.toString() + ".jpg")
    }

    private fun tileBytes(z: Int, x: Int, y: Int): ByteArray? {
        val file = tileFile(z, x, y)
        if (file.exists() && file.length() > 100) {
            return try { file.readBytes() } catch (_: Exception) { null }
        }
        val remote = "https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/" +
            z.toString() + "/" + y.toString() + "/" + x.toString()
        var connection: HttpURLConnection? = null
        return try {
            connection = URL(remote).openConnection() as HttpURLConnection
            connection.connectTimeout = 9000
            connection.readTimeout = 12000
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("User-Agent", "StroyKarta/0.9 Android")
            connection.connect()
            if (connection.responseCode !in 200..299) return null
            val data = connection.inputStream.use { it.readBytes() }
            if (data.size > 100) {
                try {
                    file.parentFile?.mkdirs()
                    val tmp = File(file.absolutePath + ".tmp")
                    tmp.writeBytes(data)
                    if (file.exists()) file.delete()
                    tmp.renameTo(file)
                } catch (_: Exception) {}
            }
            data
        } catch (_: Exception) {
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun lonToTileX(lon: Double, zoom: Int): Int {
        val n = 2.0.pow(zoom.toDouble())
        return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n.toInt() - 1)
    }

    private fun latToTileY(lat: Double, zoom: Int): Int {
        val safeLat = lat.coerceIn(-85.05112878, 85.05112878)
        val latRad = safeLat * PI / 180.0
        val n = 2.0.pow(zoom.toDouble())
        return floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n)
            .toInt().coerceIn(0, n.toInt() - 1)
    }

    private data class TileTask(val z: Int, val x: Int, val y: Int)

    private fun buildTileTasks(lat: Double, lon: Double, radiusMeters: Int, minZoom: Int, maxZoom: Int): List<TileTask> {
        val latDelta = radiusMeters / 111320.0
        val lonScale = max(0.15, cos(lat * PI / 180.0))
        val lonDelta = radiusMeters / (111320.0 * lonScale)
        val minLat = (lat - latDelta).coerceAtLeast(-85.0)
        val maxLat = (lat + latDelta).coerceAtMost(85.0)
        val minLon = (lon - lonDelta).coerceAtLeast(-179.999)
        val maxLon = (lon + lonDelta).coerceAtMost(179.999)
        val tasks = ArrayList<TileTask>()
        for (z in minZoom..maxZoom) {
            val x1 = lonToTileX(minLon, z)
            val x2 = lonToTileX(maxLon, z)
            val y1 = latToTileY(maxLat, z)
            val y2 = latToTileY(minLat, z)
            for (x in min(x1, x2)..max(x1, x2)) {
                for (y in min(y1, y2)..max(y1, y2)) tasks.add(TileTask(z, x, y))
            }
        }
        return tasks
    }

    private fun startOfflineDownload(lat: Double, lon: Double, radius: Int, minZoom: Int, maxZoom: Int): String {
        if (offlineActive) return JSONObject().put("ok", false).put("error", "Загрузка уже идёт").toString()
        if (lat !in -85.0..85.0 || lon !in -180.0..180.0) {
            return JSONObject().put("ok", false).put("error", "Нет корректной GPS-точки").toString()
        }
        val rz = radius.coerceIn(250, 5000)
        val z1 = minZoom.coerceIn(12, 20)
        val z2 = maxZoom.coerceIn(z1, 20)
        val tasks = buildTileTasks(lat, lon, rz, z1, z2)
        if (tasks.isEmpty()) return JSONObject().put("ok", false).put("error", "Нет тайлов").toString()
        if (tasks.size > 18000) {
            return JSONObject().put("ok", false)
                .put("error", "Область слишком большая: " + tasks.size + " тайлов. Уменьши радиус или качество.")
                .put("total", tasks.size).toString()
        }

        offlineActive = true
        downloadCancelled.set(false)
        try { offlineExecutor.shutdownNow() } catch (_: Exception) {}
        offlineExecutor = Executors.newFixedThreadPool(4)
        runOnUiThread { window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

        val done = AtomicInteger(0)
        val ok = AtomicInteger(0)
        val failed = AtomicInteger(0)
        val total = tasks.size

        for (task in tasks) {
            offlineExecutor.submit {
                if (!downloadCancelled.get()) {
                    val f = tileFile(task.z, task.x, task.y)
                    val good = (f.exists() && f.length() > 100) || tileBytes(task.z, task.x, task.y) != null
                    if (good) ok.incrementAndGet() else failed.incrementAndGet()
                }
                val d = done.incrementAndGet()
                val finish = d == total || downloadCancelled.get()
                if (finish) {
                    offlineActive = false
                    runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
                }
                if (finish || d % 10 == 0 || d < 10) {
                    runJs(
                        "window.onOfflineProgress&&window.onOfflineProgress(" +
                            d + "," + total + "," + ok.get() + "," + failed.get() + "," + finish + ");"
                    )
                }
            }
        }

        return JSONObject().put("ok", true).put("total", total).put("radius", rz)
            .put("minZoom", z1).put("maxZoom", z2).toString()
    }

    private fun folderStats(file: File): Pair<Long, Int> {
        if (!file.exists()) return Pair(0L, 0)
        if (file.isFile) return Pair(file.length(), 1)
        var bytes = 0L
        var count = 0
        file.listFiles()?.forEach {
            val s = folderStats(it)
            bytes += s.first
            count += s.second
        }
        return Pair(bytes, count)
    }

    private fun clearTiles() {
        try { tileRoot.deleteRecursively() } catch (_: Exception) {}
        tileRoot.mkdirs()
        runJs("window.onCacheChanged&&window.onCacheChanged();")
    }

    private fun saveProject(json: String) {
        try {
            val tmp = File(filesDir, "project.tmp")
            tmp.writeText(json, Charsets.UTF_8)
            if (projectFile.exists()) projectFile.delete()
            if (!tmp.renameTo(projectFile)) {
                projectFile.writeText(json, Charsets.UTF_8)
                tmp.delete()
            }
        } catch (e: Exception) {
            toast("Не удалось сохранить проект: " + (e.message ?: "ошибка"))
        }
    }

    private fun exportProject(json: String) {
        try {
            val name = "StroyKarta_backup_" + System.currentTimeMillis() + ".json"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, "application/json")
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/СтройКарта")
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("Не удалось создать файл")
                contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray(Charsets.UTF_8)) }
                    ?: throw IllegalStateException("Не удалось открыть файл")
                toast("Копия сохранена в Загрузки/СтройКарта")
            } else {
                val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS) ?: filesDir
                val out = File(dir, name)
                out.writeText(json, Charsets.UTF_8)
                toast("Копия сохранена: " + out.absolutePath)
            }
        } catch (e: Exception) {
            toast("Ошибка резервной копии: " + (e.message ?: "неизвестно"))
        }
    }

    private fun chooseImport() {
        runOnUiThread {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
            }
            startActivityForResult(intent, importRequestCode)
        }
    }

    @Deprecated("Deprecated in Android")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == importRequestCode && resultCode == RESULT_OK) {
            val uri: Uri = data?.data ?: return
            try {
                val text = contentResolver.openInputStream(uri)?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                    ?: throw IllegalStateException("Пустой файл")
                runJs("window.importFromNative&&window.importFromNative(" + JSONObject.quote(text) + ");")
            } catch (e: Exception) {
                toast("Не удалось импортировать: " + (e.message ?: "ошибка"))
            }
        }
    }

    inner class AndroidBridge {
        @JavascriptInterface fun saveProject(json: String) = this@MainActivity.saveProject(json)
        @JavascriptInterface fun loadProject(): String = try {
            if (projectFile.exists()) projectFile.readText(Charsets.UTF_8) else ""
        } catch (_: Exception) { "" }
        @JavascriptInterface fun startOffline(lat: Double, lon: Double, radius: Int, minZoom: Int, maxZoom: Int): String =
            startOfflineDownload(lat, lon, radius, minZoom, maxZoom)
        @JavascriptInterface fun cancelOffline() {
            downloadCancelled.set(true)
            try { offlineExecutor.shutdownNow() } catch (_: Exception) {}
            offlineActive = false
            runOnUiThread { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        }
        @JavascriptInterface fun cacheStats(): String {
            val s = folderStats(tileRoot)
            return JSONObject().put("bytes", s.first).put("tiles", s.second).toString()
        }
        @JavascriptInterface fun clearOfflineTiles() = clearTiles()
        @JavascriptInterface fun exportBackup(json: String) = exportProject(json)
        @JavascriptInterface fun chooseImport() = this@MainActivity.chooseImport()
        @JavascriptInterface fun toast(message: String) = this@MainActivity.toast(message.take(300))
        @JavascriptInterface fun requestLocationPermission() = runOnUiThread { ensureLocationPermission() }
    }
}
