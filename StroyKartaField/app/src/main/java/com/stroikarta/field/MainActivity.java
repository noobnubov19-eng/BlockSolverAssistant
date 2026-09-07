package com.stroikarta.field;

import android.Manifest;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.osmdroid.config.Configuration;
import org.osmdroid.tileprovider.cachemanager.CacheManager;
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase;
import org.osmdroid.tileprovider.tilesource.TileSourceFactory;
import org.osmdroid.util.BoundingBox;
import org.osmdroid.util.GeoPoint;
import org.osmdroid.util.MapTileIndex;
import org.osmdroid.views.MapView;
import org.osmdroid.views.overlay.MapEventsOverlay;
import org.osmdroid.views.overlay.MapEventsReceiver;
import org.osmdroid.views.overlay.Marker;
import org.osmdroid.views.overlay.Polyline;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class MainActivity extends AppCompatActivity implements LocationListener {

    private static final int REQ_LOCATION = 3001;
    private static final String PREFS = "stroikarta_field";
    private static final String KEY_DATA = "project_json";
    private static final String KEY_LAYER = "layer";
    private static final String KEY_OFFLINE_ONLY = "offline_only";
    private static final String KEY_CENTER_LAT = "center_lat";
    private static final String KEY_CENTER_LON = "center_lon";
    private static final String KEY_ZOOM = "zoom";

    private static final String[] TYPES = {
            "Дом", "Склад", "Техзона", "Септик", "Скважина", "Опора", "Парковка", "Прочее"
    };
    private static final String[] STATUSES = {
            "Запланирован", "Разметка", "Фундамент", "Стены", "Крыша", "Инженерия", "Отделка", "Готов"
    };

    private final OnlineTileSourceBase satelliteSource = new OnlineTileSourceBase(
            "Satellite_ESRI", 0, 19, 256, "",
            new String[]{"https://server.arcgisonline.com/ArcGIS/rest/services/World_Imagery/MapServer/tile/"},
            "© Esri, Maxar, Earthstar Geographics and contributors") {
        @Override
        public String getTileURLString(long pMapTileIndex) {
            return getBaseUrl()
                    + MapTileIndex.getZoom(pMapTileIndex) + "/"
                    + MapTileIndex.getY(pMapTileIndex) + "/"
                    + MapTileIndex.getX(pMapTileIndex);
        }
    };

    private MapView mapView;
    private FrameLayout root;
    private TextView statusText;
    private TextView navText;
    private Button pathButton;
    private Button layerButton;

    private LocationManager locationManager;
    private Location latestLocation;
    private final ArrayDeque<Location> recentLocations = new ArrayDeque<>();
    private Marker myMarker;
    private boolean centeredOnce = false;

    private final ArrayList<SiteObject> objects = new ArrayList<>();
    private final ArrayList<RouteLine> routes = new ArrayList<>();
    private final ArrayList<Marker> objectMarkers = new ArrayList<>();
    private final ArrayList<Polyline> routeOverlays = new ArrayList<>();

    private boolean drawPathMode = false;
    private final ArrayList<GeoPoint> draftPath = new ArrayList<>();
    private Polyline draftPolyline;

    private boolean gpsTrackMode = false;
    private final ArrayList<GeoPoint> gpsTrackDraft = new ArrayList<>();
    private Polyline gpsTrackPolyline;

    private SiteObject navigationTarget;
    private Polyline navigationLine;

    private String currentLayer = "satellite";
    private boolean offlineOnly = false;
    private String currentTypeFilter = "Все";
    private String currentStatusFilter = "Все";

    private final ActivityResultLauncher<Intent> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) writeBackup(uri);
                }
            });

    private final ActivityResultLauncher<Intent> importLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                    Uri uri = result.getData().getData();
                    if (uri != null) readBackup(uri);
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        configureOsmdroid();
        loadProject();
        buildUi();
        initMap();
        initLocation();
        renderAll();
        updateStatus();
    }

    private void configureOsmdroid() {
        Configuration cfg = Configuration.getInstance();
        cfg.setUserAgentValue(getPackageName() + "/1.0");
        File base = new File(getExternalFilesDir(null), "mapcache");
        File tiles = new File(base, "tiles");
        base.mkdirs();
        tiles.mkdirs();
        cfg.setOsmdroidBasePath(base);
        cfg.setOsmdroidTileCache(tiles);
        cfg.setTileFileSystemCacheMaxBytes(900L * 1024L * 1024L);
        cfg.setTileFileSystemCacheTrimBytes(800L * 1024L * 1024L);
    }

    private void buildUi() {
        root = new FrameLayout(this);
        mapView = new MapView(this);
        root.addView(mapView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout topCard = new LinearLayout(this);
        topCard.setOrientation(LinearLayout.VERTICAL);
        topCard.setPadding(dp(14), dp(10), dp(14), dp(10));
        topCard.setBackground(roundBg(0xEFFFFFFF, 16));
        statusText = new TextView(this);
        statusText.setTextSize(15);
        statusText.setTypeface(Typeface.DEFAULT_BOLD);
        statusText.setTextColor(0xFF17362A);
        navText = new TextView(this);
        navText.setTextSize(13);
        navText.setTextColor(0xFF33443D);
        navText.setVisibility(View.GONE);
        topCard.addView(statusText);
        topCard.addView(navText);
        FrameLayout.LayoutParams topParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        topParams.gravity = Gravity.TOP;
        topParams.setMargins(dp(10), dp(10), dp(10), 0);
        root.addView(topCard, topParams);

        HorizontalScrollView scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(8), dp(8), dp(8), dp(8));
        bottom.setGravity(Gravity.CENTER_VERTICAL);
        bottom.setBackground(roundBg(0xEEFFFFFF, 18));
        scroll.addView(bottom);

        Button my = actionButton("◎ Я здесь");
        my.setOnClickListener(v -> centerOnMe());
        bottom.addView(my);

        Button add = actionButton("＋ Объект здесь");
        add.setOnClickListener(v -> addObjectAtCurrentLocation());
        bottom.addView(add);

        pathButton = actionButton("⌁ Тропа");
        pathButton.setOnClickListener(v -> togglePathMode());
        bottom.addView(pathButton);

        layerButton = actionButton("▣ Спутник");
        layerButton.setOnClickListener(v -> switchLayer());
        bottom.addView(layerButton);

        Button offline = actionButton("⇩ Офлайн");
        offline.setOnClickListener(v -> showOfflineDialog());
        bottom.addView(offline);

        Button menu = actionButton("☰ Ещё");
        menu.setOnClickListener(v -> showMenu());
        bottom.addView(menu);

        FrameLayout.LayoutParams bottomParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bottomParams.gravity = Gravity.BOTTOM;
        bottomParams.setMargins(dp(8), 0, dp(8), dp(10));
        root.addView(scroll, bottomParams);
        setContentView(root);
    }

    private Button actionButton(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setTextSize(13);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setGravity(Gravity.CENTER);
        b.setPadding(dp(12), dp(4), dp(12), dp(4));
        b.setMinHeight(dp(44));
        b.setBackground(roundBg(0xFF1F5D42, 14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(46));
        lp.setMargins(dp(4), 0, dp(4), 0);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable roundBg(int color, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radiusDp));
        return g;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private void initMap() {
        mapView.setMultiTouchControls(true);
        mapView.setTilesScaledToDpi(true);
        mapView.setBuiltInZoomControls(false);
        mapView.setMinZoomLevel(3.0);
        mapView.setMaxZoomLevel(20.0);

        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        currentLayer = p.getString(KEY_LAYER, "satellite");
        offlineOnly = p.getBoolean(KEY_OFFLINE_ONLY, false);
        applyLayer();
        mapView.setUseDataConnection(!offlineOnly);

        double lat = Double.longBitsToDouble(p.getLong(KEY_CENTER_LAT, Double.doubleToLongBits(43.3)));
        double lon = Double.longBitsToDouble(p.getLong(KEY_CENTER_LON, Double.doubleToLongBits(45.7)));
        double zoom = Double.longBitsToDouble(p.getLong(KEY_ZOOM, Double.doubleToLongBits(16.0)));
        mapView.getController().setZoom(zoom);
        mapView.getController().setCenter(new GeoPoint(lat, lon));

        MapEventsOverlay events = new MapEventsOverlay(new MapEventsReceiver() {
            @Override
            public boolean singleTapConfirmedHelper(GeoPoint p) {
                if (drawPathMode) {
                    draftPath.add(new GeoPoint(p));
                    updateDraftPath();
                    Toast.makeText(MainActivity.this,
                            "Точка тропы: " + draftPath.size() + ". Нажми «Сохранить тропу», когда закончишь.",
                            Toast.LENGTH_SHORT).show();
                    return true;
                }
                return false;
            }

            @Override
            public boolean longPressHelper(GeoPoint p) {
                if (!drawPathMode) {
                    showObjectEditor(null, p, false);
                    return true;
                }
                return false;
            }
        });
        mapView.getOverlays().add(events);
    }

    private void applyLayer() {
        if ("osm".equals(currentLayer)) {
            mapView.setTileSource(TileSourceFactory.MAPNIK);
            if (layerButton != null) layerButton.setText("▣ Карта");
        } else {
            mapView.setTileSource(satelliteSource);
            if (layerButton != null) layerButton.setText("▣ Спутник");
        }
        mapView.invalidate();
    }

    private void switchLayer() {
        currentLayer = "satellite".equals(currentLayer) ? "osm" : "satellite";
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAYER, currentLayer).apply();
        applyLayer();
        Toast.makeText(this,
                "satellite".equals(currentLayer) ? "Спутниковый слой" : "Карта дорог OSM",
                Toast.LENGTH_SHORT).show();
    }

    private void initLocation() {
        locationManager = (LocationManager) getSystemService(LOCATION_SERVICE);
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    REQ_LOCATION);
        } else {
            startLocationUpdates();
        }
    }

    private void startLocationUpdates() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) return;
        try {
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 900L, 0.5f, this);
                Location last = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
                if (last != null) onLocationChanged(last);
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 2500L, 2f, this);
            }
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось запустить GPS: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startLocationUpdates();
            } else {
                new AlertDialog.Builder(this)
                        .setTitle("Нужен доступ к геопозиции")
                        .setMessage("Без GPS нельзя ставить дом прямо в той точке, где ты стоишь.")
                        .setPositiveButton("Настройки", (d, w) -> {
                            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:" + getPackageName()));
                            startActivity(i);
                        })
                        .setNegativeButton("Позже", null)
                        .show();
            }
        }
    }

    @Override
    public void onLocationChanged(@NonNull Location location) {
        long ageMs = Math.abs(System.currentTimeMillis() - location.getTime());
        if (ageMs > 120_000) return;
        latestLocation = new Location(location);
        if (!location.hasAccuracy() || location.getAccuracy() <= 50f) {
            recentLocations.addLast(new Location(location));
            while (recentLocations.size() > 12) recentLocations.removeFirst();
        }
        updateMyMarker(location);
        updateNavigation(location);
        if (gpsTrackMode && isGoodTrackFix(location)) addGpsTrackPoint(location);
        if (!centeredOnce && location.getAccuracy() <= 30f) {
            centeredOnce = true;
            mapView.getController().setZoom(18.0);
            mapView.getController().animateTo(new GeoPoint(location.getLatitude(), location.getLongitude()));
        }
        updateStatus();
    }

    private boolean isGoodTrackFix(Location loc) {
        if (loc.hasAccuracy() && loc.getAccuracy() > 18f) return false;
        if (gpsTrackDraft.isEmpty()) return true;
        GeoPoint last = gpsTrackDraft.get(gpsTrackDraft.size() - 1);
        float[] out = new float[1];
        Location.distanceBetween(last.getLatitude(), last.getLongitude(),
                loc.getLatitude(), loc.getLongitude(), out);
        return out[0] >= 2.0f;
    }

    private void addGpsTrackPoint(Location loc) {
        gpsTrackDraft.add(new GeoPoint(loc.getLatitude(), loc.getLongitude()));
        if (gpsTrackPolyline == null) {
            gpsTrackPolyline = new Polyline(mapView);
            gpsTrackPolyline.setColor(0xFFFF7A00);
            gpsTrackPolyline.setWidth(dp(5));
            mapView.getOverlays().add(gpsTrackPolyline);
        }
        gpsTrackPolyline.setPoints(new ArrayList<>(gpsTrackDraft));
        mapView.invalidate();
    }

    private void updateMyMarker(Location location) {
        GeoPoint p = new GeoPoint(location.getLatitude(), location.getLongitude());
        if (myMarker == null) {
            myMarker = new Marker(mapView);
            myMarker.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER);
            myMarker.setTitle("Моя позиция");
            myMarker.setIcon(makeMyLocationDrawable());
            mapView.getOverlays().add(myMarker);
        }
        myMarker.setPosition(p);
        String accuracy = location.hasAccuracy() ? String.format(Locale.US, "±%.0f м", location.getAccuracy()) : "";
        myMarker.setSnippet("GPS " + accuracy);
        mapView.invalidate();
    }

    private BitmapDrawable makeMyLocationDrawable() {
        int s = dp(34);
        Bitmap b = Bitmap.createBitmap(s, s, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setColor(Color.WHITE);
        c.drawCircle(s / 2f, s / 2f, s * 0.46f, p);
        p.setColor(0xFF1976D2);
        c.drawCircle(s / 2f, s / 2f, s * 0.31f, p);
        p.setColor(Color.WHITE);
        c.drawCircle(s / 2f, s / 2f, s * 0.10f, p);
        return new BitmapDrawable(getResources(), b);
    }

    private Location getAveragedLocation() {
        long now = System.currentTimeMillis();
        double sumW = 0, lat = 0, lon = 0, alt = 0;
        float bestAcc = Float.MAX_VALUE;
        int n = 0;
        Iterator<Location> it = recentLocations.descendingIterator();
        while (it.hasNext() && n < 8) {
            Location l = it.next();
            if (Math.abs(now - l.getTime()) > 20_000) continue;
            float acc = l.hasAccuracy() ? Math.max(2f, l.getAccuracy()) : 20f;
            if (acc > 30f) continue;
            double w = 1.0 / (acc * acc);
            sumW += w;
            lat += l.getLatitude() * w;
            lon += l.getLongitude() * w;
            if (l.hasAltitude()) alt += l.getAltitude() * w;
            bestAcc = Math.min(bestAcc, acc);
            n++;
        }
        if (n == 0 || sumW == 0) return latestLocation == null ? null : new Location(latestLocation);
        Location avg = new Location("stroikarta-average");
        avg.setLatitude(lat / sumW);
        avg.setLongitude(lon / sumW);
        avg.setAltitude(alt / sumW);
        avg.setAccuracy(bestAcc);
        avg.setTime(System.currentTimeMillis());
        avg.setElapsedRealtimeNanos(SystemClock.elapsedRealtimeNanos());
        return avg;
    }

    private void centerOnMe() {
        Location l = getAveragedLocation();
        if (l == null) {
            Toast.makeText(this, "Жду GPS. Выйди под открытое небо и подожди несколько секунд.", Toast.LENGTH_LONG).show();
            return;
        }
        mapView.getController().animateTo(new GeoPoint(l.getLatitude(), l.getLongitude()));
        if (mapView.getZoomLevelDouble() < 17) mapView.getController().setZoom(18.0);
    }

    private void addObjectAtCurrentLocation() {
        Location l = getAveragedLocation();
        if (l == null) {
            Toast.makeText(this, "GPS ещё не дал координаты. Подожди несколько секунд.", Toast.LENGTH_LONG).show();
            return;
        }
        GeoPoint p = new GeoPoint(l.getLatitude(), l.getLongitude());
        float acc = l.hasAccuracy() ? l.getAccuracy() : 99f;
        if (acc > 12f) {
            new AlertDialog.Builder(this)
                    .setTitle("Точность GPS сейчас ±" + Math.round(acc) + " м")
                    .setMessage("Для места домика лучше дождаться хотя бы ±5–10 м. Можно поставить точку сейчас или подождать.")
                    .setPositiveButton("Поставить сейчас", (d, w) -> showObjectEditor(null, p, true))
                    .setNegativeButton("Подождать", null)
                    .show();
        } else {
            showObjectEditor(null, p, true);
        }
    }

    private void showObjectEditor(SiteObject existing, GeoPoint point, boolean fromGps) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        scroll.addView(box);

        TextView coord = label(String.format(Locale.US, "Координаты: %.7f, %.7f%s",
                point.getLatitude(), point.getLongitude(), fromGps ? "  • GPS" : ""));
        box.addView(coord);

        box.addView(label("Тип объекта"));
        Spinner type = spinner(TYPES);
        box.addView(type);

        box.addView(label("Название / номер"));
        EditText name = edit(existing == null ? nextDefaultName("Дом") : existing.name, false);
        box.addView(name);

        box.addView(label("Этап строительства"));
        Spinner status = spinner(STATUSES);
        box.addView(status);

        box.addView(label("Заметки"));
        EditText notes = edit(existing == null ? "" : existing.notes, true);
        box.addView(notes);

        if (existing != null) {
            setSpinnerValue(type, TYPES, existing.type);
            setSpinnerValue(status, STATUSES, existing.status);
        }

        type.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (existing == null) {
                    String current = name.getText().toString().trim();
                    if (current.isEmpty() || current.startsWith("Дом ") || current.startsWith("Склад ") || current.startsWith("Объект ")) {
                        name.setText(nextDefaultName(TYPES[position]));
                    }
                }
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "Новый объект" : "Редактировать объект")
                .setView(scroll)
                .setPositiveButton(existing == null ? "Сохранить" : "Обновить", null)
                .setNegativeButton("Отмена", null)
                .setNeutralButton(existing == null ? null : "Удалить", null)
                .create();

        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                String nm = name.getText().toString().trim();
                if (nm.isEmpty()) {
                    name.setError("Укажи номер или название");
                    return;
                }
                if (existing == null) {
                    SiteObject o = new SiteObject();
                    o.id = UUID.randomUUID().toString();
                    o.lat = point.getLatitude();
                    o.lon = point.getLongitude();
                    o.createdAt = System.currentTimeMillis();
                    o.type = (String) type.getSelectedItem();
                    o.name = nm;
                    o.status = (String) status.getSelectedItem();
                    o.notes = notes.getText().toString().trim();
                    objects.add(o);
                    Toast.makeText(this, o.name + " сохранён", Toast.LENGTH_SHORT).show();
                } else {
                    existing.type = (String) type.getSelectedItem();
                    existing.name = nm;
                    existing.status = (String) status.getSelectedItem();
                    existing.notes = notes.getText().toString().trim();
                }
                saveProject();
                renderAll();
                updateStatus();
                dialog.dismiss();
            });
            if (existing != null) {
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setTextColor(0xFFB3261E);
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                    new AlertDialog.Builder(this)
                            .setTitle("Удалить " + existing.name + "?")
                            .setMessage("Точку можно будет вернуть только из резервной копии.")
                            .setPositiveButton("Удалить", (d2, w2) -> {
                                if (navigationTarget == existing) navigationTarget = null;
                                objects.remove(existing);
                                saveProject();
                                renderAll();
                                updateStatus();
                                dialog.dismiss();
                            })
                            .setNegativeButton("Отмена", null)
                            .show();
                });
            }
        });
        dialog.show();
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(13);
        t.setTextColor(0xFF2D3B35);
        t.setPadding(0, dp(9), 0, dp(4));
        return t;
    }

    private EditText edit(String value, boolean multiline) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setTextSize(16);
        e.setPadding(dp(10), dp(7), dp(10), dp(7));
        e.setBackground(roundBg(0xFFF1F5F2, 10));
        if (multiline) {
            e.setMinLines(3);
            e.setGravity(Gravity.TOP);
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        } else {
            e.setSingleLine(true);
        }
        return e;
    }

    private Spinner spinner(String[] values) {
        Spinner s = new Spinner(this);
        ArrayAdapter<String> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, values);
        s.setAdapter(a);
        return s;
    }

    private void setSpinnerValue(Spinner spinner, String[] values, String value) {
        for (int i = 0; i < values.length; i++) {
            if (values[i].equals(value)) {
                spinner.setSelection(i);
                return;
            }
        }
    }

    private String nextDefaultName(String type) {
        int max = 0;
        for (SiteObject o : objects) {
            if (!type.equals(o.type)) continue;
            String digits = o.name.replaceAll("\\D+", "");
            if (!digits.isEmpty()) {
                try { max = Math.max(max, Integer.parseInt(digits)); } catch (Exception ignored) {}
            }
        }
        if ("Дом".equals(type)) return "Дом " + (max + 1);
        if ("Склад".equals(type)) return "Склад " + (max + 1);
        return type + " " + (max + 1);
    }

    private void togglePathMode() {
        if (!drawPathMode) {
            drawPathMode = true;
            draftPath.clear();
            if (draftPolyline != null) mapView.getOverlays().remove(draftPolyline);
            draftPolyline = new Polyline(mapView);
            draftPolyline.setColor(0xFFFFC107);
            draftPolyline.setWidth(dp(5));
            mapView.getOverlays().add(draftPolyline);
            pathButton.setText("✓ Сохранить тропу");
            Toast.makeText(this, "Касайся карты по ходу тропы. Потом нажми «Сохранить тропу».", Toast.LENGTH_LONG).show();
        } else {
            if (draftPath.size() < 2) {
                new AlertDialog.Builder(this)
                        .setTitle("Тропа ещё не нарисована")
                        .setMessage("Нужно минимум две точки. Отменить режим рисования?")
                        .setPositiveButton("Отменить", (d, w) -> cancelPathDraft())
                        .setNegativeButton("Продолжить", null)
                        .show();
                return;
            }
            EditText name = edit("Тропа " + (routes.size() + 1), false);
            new AlertDialog.Builder(this)
                    .setTitle("Название тропы")
                    .setView(name)
                    .setPositiveButton("Сохранить", (d, w) -> {
                        RouteLine r = new RouteLine();
                        r.id = UUID.randomUUID().toString();
                        r.name = name.getText().toString().trim().isEmpty() ? "Тропа " + (routes.size() + 1) : name.getText().toString().trim();
                        r.points.addAll(draftPath);
                        routes.add(r);
                        saveProject();
                        cancelPathDraft();
                        renderAll();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
        }
    }

    private void updateDraftPath() {
        if (draftPolyline != null) {
            draftPolyline.setPoints(new ArrayList<>(draftPath));
            mapView.invalidate();
        }
    }

    private void cancelPathDraft() {
        drawPathMode = false;
        draftPath.clear();
        if (draftPolyline != null) mapView.getOverlays().remove(draftPolyline);
        draftPolyline = null;
        pathButton.setText("⌁ Тропа");
        mapView.invalidate();
    }

    private void toggleGpsTrack() {
        if (!gpsTrackMode) {
            gpsTrackMode = true;
            gpsTrackDraft.clear();
            if (gpsTrackPolyline != null) mapView.getOverlays().remove(gpsTrackPolyline);
            gpsTrackPolyline = null;
            Toast.makeText(this, "Запись GPS-тропы началась. Иди по маршруту; приложение запишет путь.", Toast.LENGTH_LONG).show();
        } else {
            gpsTrackMode = false;
            if (gpsTrackDraft.size() < 2) {
                if (gpsTrackPolyline != null) mapView.getOverlays().remove(gpsTrackPolyline);
                gpsTrackPolyline = null;
                Toast.makeText(this, "Слишком мало точек — трек не сохранён", Toast.LENGTH_SHORT).show();
                return;
            }
            EditText name = edit("GPS тропа " + (routes.size() + 1), false);
            new AlertDialog.Builder(this)
                    .setTitle("Сохранить записанный путь")
                    .setView(name)
                    .setPositiveButton("Сохранить", (d, w) -> {
                        RouteLine r = new RouteLine();
                        r.id = UUID.randomUUID().toString();
                        r.name = name.getText().toString().trim();
                        if (r.name.isEmpty()) r.name = "GPS тропа " + (routes.size() + 1);
                        r.points.addAll(gpsTrackDraft);
                        routes.add(r);
                        gpsTrackDraft.clear();
                        if (gpsTrackPolyline != null) mapView.getOverlays().remove(gpsTrackPolyline);
                        gpsTrackPolyline = null;
                        saveProject();
                        renderAll();
                    })
                    .setNegativeButton("Не сохранять", (d, w) -> {
                        gpsTrackDraft.clear();
                        if (gpsTrackPolyline != null) mapView.getOverlays().remove(gpsTrackPolyline);
                        gpsTrackPolyline = null;
                        mapView.invalidate();
                    })
                    .show();
        }
        updateStatus();
    }

    private void renderAll() {
        for (Marker m : objectMarkers) mapView.getOverlays().remove(m);
        for (Polyline p : routeOverlays) mapView.getOverlays().remove(p);
        objectMarkers.clear();
        routeOverlays.clear();

        for (RouteLine r : routes) {
            Polyline line = new Polyline(mapView);
            line.setPoints(new ArrayList<>(r.points));
            line.setWidth(dp(5));
            line.setColor(0xFF8B5A2B);
            line.setTitle(r.name);
            line.setOnClickListener((polyline, map, eventPos) -> {
                showRouteDialog(r);
                return true;
            });
            mapView.getOverlays().add(line);
            routeOverlays.add(line);
        }

        for (SiteObject o : objects) {
            if (!"Все".equals(currentTypeFilter) && !currentTypeFilter.equals(o.type)) continue;
            if (!"Все".equals(currentStatusFilter) && !currentStatusFilter.equals(o.status)) continue;
            Marker m = new Marker(mapView);
            m.setPosition(new GeoPoint(o.lat, o.lon));
            m.setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM);
            m.setTitle(o.name);
            m.setSnippet(o.type + " • " + o.status);
            m.setIcon(makeObjectDrawable(o));
            m.setOnMarkerClickListener((marker, map) -> {
                showObjectActions(o);
                return true;
            });
            mapView.getOverlays().add(m);
            objectMarkers.add(m);
        }

        if (myMarker != null) {
            mapView.getOverlays().remove(myMarker);
            mapView.getOverlays().add(myMarker);
        }
        if (navigationLine != null) {
            mapView.getOverlays().remove(navigationLine);
            mapView.getOverlays().add(navigationLine);
        }
        if (draftPolyline != null) {
            mapView.getOverlays().remove(draftPolyline);
            mapView.getOverlays().add(draftPolyline);
        }
        if (gpsTrackPolyline != null) {
            mapView.getOverlays().remove(gpsTrackPolyline);
            mapView.getOverlays().add(gpsTrackPolyline);
        }
        mapView.invalidate();
    }

    private BitmapDrawable makeObjectDrawable(SiteObject o) {
        int w = dp(54), h = dp(64);
        Bitmap b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int color = statusColor(o.status);
        p.setColor(Color.WHITE);
        c.drawCircle(w / 2f, dp(26), dp(23), p);
        p.setColor(color);
        c.drawCircle(w / 2f, dp(26), dp(20), p);
        Path tri = new Path();
        tri.moveTo(w / 2f - dp(9), dp(43));
        tri.lineTo(w / 2f + dp(9), dp(43));
        tri.lineTo(w / 2f, dp(61));
        tri.close();
        c.drawPath(tri, p);

        p.setColor(Color.WHITE);
        p.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
        String label = shortLabel(o);
        p.setTextSize(label.length() > 3 ? dp(11) : dp(14));
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float y = dp(26) - (fm.ascent + fm.descent) / 2f;
        c.drawText(label, w / 2f, y, p);
        return new BitmapDrawable(getResources(), b);
    }

    private String shortLabel(SiteObject o) {
        String digits = o.name.replaceAll("\\D+", "");
        if (!digits.isEmpty() && digits.length() <= 3) return digits;
        switch (o.type) {
            case "Дом": return "Д";
            case "Склад": return "С";
            case "Септик": return "СП";
            case "Скважина": return "СК";
            case "Опора": return "О";
            case "Техзона": return "Т";
            default: return "•";
        }
    }

    private int statusColor(String status) {
        switch (status) {
            case "Готов": return 0xFF1B7F4B;
            case "Отделка": return 0xFF388E3C;
            case "Инженерия": return 0xFF00838F;
            case "Крыша": return 0xFF1976D2;
            case "Стены": return 0xFF5E35B1;
            case "Фундамент": return 0xFFF57C00;
            case "Разметка": return 0xFFFFB300;
            default: return 0xFF616161;
        }
    }

    private void showObjectActions(SiteObject o) {
        Location l = getAveragedLocation();
        String distance = "";
        if (l != null) {
            float[] d = new float[1];
            Location.distanceBetween(l.getLatitude(), l.getLongitude(), o.lat, o.lon, d);
            distance = "\nРасстояние: " + formatDistance(d[0]);
        }
        String msg = o.type + " • " + o.status + distance;
        if (!o.notes.isEmpty()) msg += "\n\n" + o.notes;
        new AlertDialog.Builder(this)
                .setTitle(o.name)
                .setMessage(msg)
                .setPositiveButton("Вести к объекту", (d, w) -> startNavigation(o))
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Изменить", (d, w) -> showObjectEditor(o, new GeoPoint(o.lat, o.lon), false))
                .show();
    }

    private void startNavigation(SiteObject o) {
        navigationTarget = o;
        if (navigationLine == null) {
            navigationLine = new Polyline(mapView);
            navigationLine.setWidth(dp(4));
            navigationLine.setColor(0xFFE53935);
        }
        if (!mapView.getOverlays().contains(navigationLine)) mapView.getOverlays().add(navigationLine);
        if (latestLocation != null) updateNavigation(latestLocation);
        navText.setVisibility(View.VISIBLE);
        Toast.makeText(this, "Навигация к " + o.name, Toast.LENGTH_SHORT).show();
    }

    private void stopNavigation() {
        navigationTarget = null;
        if (navigationLine != null) mapView.getOverlays().remove(navigationLine);
        navigationLine = null;
        navText.setVisibility(View.GONE);
        mapView.invalidate();
    }

    private void updateNavigation(Location l) {
        if (navigationTarget == null) return;
        GeoPoint from = new GeoPoint(l.getLatitude(), l.getLongitude());
        GeoPoint to = new GeoPoint(navigationTarget.lat, navigationTarget.lon);
        if (navigationLine == null) {
            navigationLine = new Polyline(mapView);
            navigationLine.setWidth(dp(4));
            navigationLine.setColor(0xFFE53935);
            mapView.getOverlays().add(navigationLine);
        }
        ArrayList<GeoPoint> pts = new ArrayList<>();
        pts.add(from); pts.add(to);
        navigationLine.setPoints(pts);
        float[] out = new float[2];
        Location.distanceBetween(l.getLatitude(), l.getLongitude(), navigationTarget.lat, navigationTarget.lon, out);
        navText.setText("→ " + navigationTarget.name + "  •  " + formatDistance(out[0]) + "  •  азимут " + Math.round(out[1]) + "°");
        navText.setVisibility(View.VISIBLE);
        mapView.invalidate();
    }

    private String formatDistance(float meters) {
        if (meters < 1000f) return Math.round(meters) + " м";
        return String.format(Locale.US, "%.2f км", meters / 1000f);
    }

    private void showRouteDialog(RouteLine r) {
        new AlertDialog.Builder(this)
                .setTitle(r.name)
                .setMessage("Точек: " + r.points.size() + "\nДлина: " + formatDistance(routeLength(r.points)))
                .setPositiveButton("Показать целиком", (d, w) -> fitPoints(r.points))
                .setNegativeButton("Закрыть", null)
                .setNeutralButton("Удалить", (d, w) -> {
                    routes.remove(r);
                    saveProject();
                    renderAll();
                    updateStatus();
                })
                .show();
    }

    private float routeLength(List<GeoPoint> points) {
        float total = 0;
        for (int i = 1; i < points.size(); i++) {
            float[] d = new float[1];
            GeoPoint a = points.get(i - 1), b = points.get(i);
            Location.distanceBetween(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude(), d);
            total += d[0];
        }
        return total;
    }

    private void fitPoints(List<GeoPoint> pts) {
        if (pts == null || pts.isEmpty()) return;
        BoundingBox bb = BoundingBox.fromGeoPoints(pts);
        mapView.post(() -> mapView.zoomToBoundingBox(bb, true, dp(70), 19.0, 500L));
    }

    private void showOfflineDialog() {
        if (!"satellite".equals(currentLayer)) {
            new AlertDialog.Builder(this)
                    .setTitle("Офлайн-пакет")
                    .setMessage("Для массовой офлайн-загрузки переключись на спутниковый слой. Стандартный OSM-сервер запрещает bulk-загрузку тайлов.")
                    .setPositiveButton("Переключить на спутник", (d, w) -> {
                        currentLayer = "satellite";
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putString(KEY_LAYER, currentLayer).apply();
                        applyLayer();
                        showOfflineDialog();
                    })
                    .setNegativeButton("Отмена", null)
                    .show();
            return;
        }

        BoundingBox bb = mapView.getBoundingBox();
        String[] options = {
                "Текущий экран • Z15–18 (быстро)",
                "Текущий экран • Z15–19 (детально)",
                "Текущий экран • Z14–18 (шире)",
                offlineOnly ? "Включить интернет-карту" : "Только офлайн (без сети)"
        };
        new AlertDialog.Builder(this)
                .setTitle("Офлайн-карта")
                .setMessage("Сначала приблизь карту так, чтобы на экране была вся нужная территория. Затем скачай её.")
                .setItems(options, (d, which) -> {
                    if (which == 3) {
                        offlineOnly = !offlineOnly;
                        mapView.setUseDataConnection(!offlineOnly);
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit().putBoolean(KEY_OFFLINE_ONLY, offlineOnly).apply();
                        Toast.makeText(this, offlineOnly ? "Режим: только офлайн" : "Интернет-карта включена", Toast.LENGTH_SHORT).show();
                        updateStatus();
                        return;
                    }
                    int minZ = which == 2 ? 14 : 15;
                    int maxZ = which == 1 ? 19 : 18;
                    startOfflineDownload(bb, minZ, maxZ);
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void startOfflineDownload(BoundingBox bb, int minZ, int maxZ) {
        try {
            CacheManager manager = new CacheManager(mapView);
            int count = manager.possibleTilesInArea(bb, minZ, maxZ);
            if (count > 7000) {
                new AlertDialog.Builder(this)
                        .setTitle("Область слишком большая")
                        .setMessage("Получается около " + count + " тайлов. Приблизь карту к территории стройки и повтори — так загрузка будет надёжнее.")
                        .setPositiveButton("Понятно", null)
                        .show();
                return;
            }
            ProgressDialog progress = new ProgressDialog(this);
            progress.setTitle("Скачиваю спутник офлайн");
            progress.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
            progress.setIndeterminate(false);
            progress.setCancelable(false);
            progress.setMax(Math.max(1, count));
            progress.setMessage("0 / " + count + " тайлов");
            progress.show();
            manager.downloadAreaAsyncNoUI(this, bb, minZ, maxZ, new CacheManager.CacheManagerCallback() {
                @Override public void onTaskComplete() {
                    progress.dismiss();
                    Toast.makeText(MainActivity.this,
                            "Офлайн-карта готова. Перед поездкой включи авиарежим и проверь эту область.",
                            Toast.LENGTH_LONG).show();
                }
                @Override public void updateProgress(int p, int currentZoomLevel, int zoomMin, int zoomMax) {
                    progress.setProgress(p);
                    progress.setMessage(p + " / " + count + " • Z" + currentZoomLevel);
                }
                @Override public void downloadStarted() { }
                @Override public void setPossibleTilesInArea(int total) { progress.setMax(Math.max(1, total)); }
                @Override public void onTaskFailed(int errors) {
                    progress.dismiss();
                    Toast.makeText(MainActivity.this,
                            "Загрузка завершилась с ошибками: " + errors + ". Повтори при стабильном интернете.",
                            Toast.LENGTH_LONG).show();
                }
            });
        } catch (Exception e) {
            Toast.makeText(this, "Не удалось начать загрузку: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void showMenu() {
        String trackLabel = gpsTrackMode ? "■ Остановить запись GPS-тропы" : "● Записать тропу по GPS";
        String[] items = {
                "Объекты и прогресс",
                "Фильтры",
                trackLabel,
                "Показать все объекты",
                navigationTarget == null ? "Навигация: не выбрана" : "Остановить навигацию",
                "Экспорт резервной копии",
                "Импорт резервной копии",
                "Справка перед поездкой"
        };
        new AlertDialog.Builder(this)
                .setTitle("СтройКарта")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 0: showObjectsAndProgress(); break;
                        case 1: showFilters(); break;
                        case 2: toggleGpsTrack(); break;
                        case 3: fitAllObjects(); break;
                        case 4: if (navigationTarget != null) stopNavigation(); break;
                        case 5: exportBackup(); break;
                        case 6: importBackup(); break;
                        case 7: showHelp(); break;
                    }
                })
                .show();
    }

    private void showObjectsAndProgress() {
        if (objects.isEmpty()) {
            new AlertDialog.Builder(this)
                    .setTitle("Объекты")
                    .setMessage("Пока нет объектов. Встань в нужное место и нажми «Объект здесь».")
                    .setPositiveButton("OK", null)
                    .show();
            return;
        }
        ArrayList<SiteObject> sorted = new ArrayList<>(objects);
        sorted.sort((a, b) -> a.name.compareToIgnoreCase(b.name));
        String[] rows = new String[sorted.size() + 1];
        rows[0] = progressSummary();
        for (int i = 0; i < sorted.size(); i++) {
            SiteObject o = sorted.get(i);
            rows[i + 1] = o.name + "  —  " + o.status;
        }
        new AlertDialog.Builder(this)
                .setTitle("Объекты: " + objects.size())
                .setItems(rows, (d, which) -> {
                    if (which == 0) return;
                    SiteObject o = sorted.get(which - 1);
                    mapView.getController().animateTo(new GeoPoint(o.lat, o.lon));
                    mapView.getController().setZoom(19.0);
                    showObjectActions(o);
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private String progressSummary() {
        int homes = 0, ready = 0;
        for (SiteObject o : objects) {
            if ("Дом".equals(o.type)) {
                homes++;
                if ("Готов".equals(o.status)) ready++;
            }
        }
        return "ИТОГО: домов " + homes + " • готово " + ready + " • в работе " + Math.max(0, homes - ready);
    }

    private void showFilters() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(18), dp(8), dp(18), dp(8));
        box.addView(label("Тип"));
        String[] typeValues = new String[TYPES.length + 1];
        typeValues[0] = "Все";
        System.arraycopy(TYPES, 0, typeValues, 1, TYPES.length);
        Spinner type = spinner(typeValues);
        setSpinnerValue(type, typeValues, currentTypeFilter);
        box.addView(type);
        box.addView(label("Статус"));
        String[] statusValues = new String[STATUSES.length + 1];
        statusValues[0] = "Все";
        System.arraycopy(STATUSES, 0, statusValues, 1, STATUSES.length);
        Spinner status = spinner(statusValues);
        setSpinnerValue(status, statusValues, currentStatusFilter);
        box.addView(status);
        new AlertDialog.Builder(this)
                .setTitle("Фильтры карты")
                .setView(box)
                .setPositiveButton("Применить", (d, w) -> {
                    currentTypeFilter = (String) type.getSelectedItem();
                    currentStatusFilter = (String) status.getSelectedItem();
                    renderAll();
                    updateStatus();
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void fitAllObjects() {
        if (objects.isEmpty()) {
            Toast.makeText(this, "Объектов пока нет", Toast.LENGTH_SHORT).show();
            return;
        }
        ArrayList<GeoPoint> pts = new ArrayList<>();
        for (SiteObject o : objects) pts.add(new GeoPoint(o.lat, o.lon));
        fitPoints(pts);
    }

    private void showHelp() {
        String text = "ПЕРЕД ВЫЕЗДОМ В ГОРЫ:\n\n"
                + "1. Открой спутник, приблизь всю территорию и нажми «Офлайн» → Z15–19.\n"
                + "2. После загрузки включи авиарежим и проверь, что спутник остаётся на экране.\n"
                + "3. Сделай экспорт резервной копии проекта.\n\n"
                + "В ЛЕСУ:\n"
                + "• «Объект здесь» ставит точку по GPS.\n"
                + "• Смотри точность ± метров вверху; для домика лучше дождаться ≤10 м.\n"
                + "• Долгое нажатие на карту ставит запланированный объект вручную.\n"
                + "• «Тропа» рисуется касаниями карты.\n"
                + "• В «Ещё» можно записать фактическую тропу по GPS.\n\n"
                + "Важно: обычный GPS телефона — не геодезический прибор. Для разбивки фундамента используй рулетку/тахеометр/RTK, а карту — для уверенного поиска места.";
        new AlertDialog.Builder(this)
                .setTitle("Полевой режим")
                .setMessage(text)
                .setPositiveButton("Понял", null)
                .show();
    }

    private void updateStatus() {
        int homes = 0, ready = 0;
        for (SiteObject o : objects) {
            if ("Дом".equals(o.type)) {
                homes++;
                if ("Готов".equals(o.status)) ready++;
            }
        }
        String gps = "GPS: ждём";
        if (latestLocation != null) {
            gps = latestLocation.hasAccuracy() ? "GPS ±" + Math.round(latestLocation.getAccuracy()) + " м" : "GPS ✓";
        }
        String layer = "satellite".equals(currentLayer) ? "спутник" : "карта";
        String mode = offlineOnly ? " • OFFLINE" : "";
        String track = gpsTrackMode ? " • запись трека" : "";
        statusText.setText("Домов " + homes + " • готово " + ready + " • " + gps + " • " + layer + mode + track);
    }

    private void exportBackup() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm", Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE, "StroyKarta_backup_" + stamp + ".json");
        exportLauncher.launch(i);
    }

    private void importBackup() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        importLauncher.launch(i);
    }

    private void writeBackup(Uri uri) {
        try (OutputStream os = getContentResolver().openOutputStream(uri)) {
            if (os == null) throw new IllegalStateException("Нет доступа к файлу");
            os.write(projectJson().toString(2).getBytes(StandardCharsets.UTF_8));
            Toast.makeText(this, "Резервная копия сохранена", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка экспорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void readBackup(Uri uri) {
        try (BufferedReader br = new BufferedReader(new InputStreamReader(
                getContentResolver().openInputStream(uri), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) sb.append(line).append('\n');
            JSONObject json = new JSONObject(sb.toString());
            parseProject(json);
            saveProject();
            renderAll();
            updateStatus();
            fitAllObjects();
            Toast.makeText(this, "Проект восстановлен", Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Ошибка импорта: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private void loadProject() {
        String raw = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_DATA, "");
        if (raw.isEmpty()) return;
        try {
            parseProject(new JSONObject(raw));
        } catch (Exception ignored) { }
    }

    private void parseProject(JSONObject root) throws JSONException {
        objects.clear();
        routes.clear();
        JSONArray arr = root.optJSONArray("objects");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject j = arr.getJSONObject(i);
                SiteObject o = new SiteObject();
                o.id = j.optString("id", UUID.randomUUID().toString());
                o.type = j.optString("type", "Дом");
                o.name = j.optString("name", "Объект");
                o.status = j.optString("status", "Запланирован");
                o.notes = j.optString("notes", "");
                o.lat = j.getDouble("lat");
                o.lon = j.getDouble("lon");
                o.createdAt = j.optLong("createdAt", System.currentTimeMillis());
                objects.add(o);
            }
        }
        JSONArray rr = root.optJSONArray("routes");
        if (rr != null) {
            for (int i = 0; i < rr.length(); i++) {
                JSONObject j = rr.getJSONObject(i);
                RouteLine r = new RouteLine();
                r.id = j.optString("id", UUID.randomUUID().toString());
                r.name = j.optString("name", "Тропа");
                JSONArray pts = j.optJSONArray("points");
                if (pts != null) {
                    for (int k = 0; k < pts.length(); k++) {
                        JSONArray a = pts.getJSONArray(k);
                        r.points.add(new GeoPoint(a.getDouble(0), a.getDouble(1)));
                    }
                }
                if (r.points.size() >= 2) routes.add(r);
            }
        }
    }

    private JSONObject projectJson() throws JSONException {
        JSONObject root = new JSONObject();
        root.put("version", 1);
        root.put("savedAt", System.currentTimeMillis());
        JSONArray arr = new JSONArray();
        for (SiteObject o : objects) {
            JSONObject j = new JSONObject();
            j.put("id", o.id);
            j.put("type", o.type);
            j.put("name", o.name);
            j.put("status", o.status);
            j.put("notes", o.notes);
            j.put("lat", o.lat);
            j.put("lon", o.lon);
            j.put("createdAt", o.createdAt);
            arr.put(j);
        }
        root.put("objects", arr);
        JSONArray rr = new JSONArray();
        for (RouteLine r : routes) {
            JSONObject j = new JSONObject();
            j.put("id", r.id);
            j.put("name", r.name);
            JSONArray pts = new JSONArray();
            for (GeoPoint p : r.points) {
                JSONArray a = new JSONArray();
                a.put(p.getLatitude());
                a.put(p.getLongitude());
                pts.put(a);
            }
            j.put("points", pts);
            rr.put(j);
        }
        root.put("routes", rr);
        return root;
    }

    private void saveProject() {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_DATA, projectJson().toString())
                    .apply();
        } catch (JSONException e) {
            Toast.makeText(this, "Не удалось сохранить проект", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (mapView != null) mapView.onResume();
        if (locationManager != null && ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                == PackageManager.PERMISSION_GRANTED) startLocationUpdates();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveProject();
        if (mapView != null) {
            SharedPreferences.Editor e = getSharedPreferences(PREFS, MODE_PRIVATE).edit();
            e.putLong(KEY_CENTER_LAT, Double.doubleToRawLongBits(mapView.getMapCenter().getLatitude()));
            e.putLong(KEY_CENTER_LON, Double.doubleToRawLongBits(mapView.getMapCenter().getLongitude()));
            e.putLong(KEY_ZOOM, Double.doubleToRawLongBits(mapView.getZoomLevelDouble()));
            e.apply();
            mapView.onPause();
        }
        if (locationManager != null) {
            try { locationManager.removeUpdates(this); } catch (Exception ignored) { }
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (mapView != null) mapView.onDetach();
    }

    private static class SiteObject {
        String id;
        String type;
        String name;
        String status;
        String notes;
        double lat;
        double lon;
        long createdAt;
    }

    private static class RouteLine {
        String id;
        String name;
        final ArrayList<GeoPoint> points = new ArrayList<>();
    }
}
