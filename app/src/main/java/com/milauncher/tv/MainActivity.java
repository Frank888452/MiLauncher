package com.milauncher.tv;

import android.app.Activity;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.Uri;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mi Launcher v4 (estilo Google TV, liviano)
 * - Pestañas arriba (Inicio + categorías), fila "Recientes", tarjetas banner 16:9.
 * - Cada tarjeta es UNA sola vista dibujada a mano: borde blanco al enfocar, nombre solo en la enfocada.
 * - Color suave de cada tarjeta y tinte del fondo tomados del color del ícono (sin imágenes extra).
 * - Apps ocultas, ayuda de uso y más fondos.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "launcher";
    private static final String KEY_CATS = "cats";      // líneas "id|nombre"
    private static final String KEY_ASSIGN = "assign";  // líneas "paquete|id"
    private static final String KEY_ORDER = "order";    // líneas "paquete"
    private static final String KEY_RECENT = "recent";  // líneas "paquete" (más reciente primero)
    private static final String KEY_HIDDEN = "hidden"; // líneas "paquete"
    private static final String KEY_TIP = "tip_seen";  // ya vio la ayuda de uso
    private static final String KEY_BG = "bg";          // "p0".."p7"
    private static final String KEY_SIZE = "size";      // 0 chico, 1 mediano, 2 grande
    private static final String KEY_DYN = "dyn";        // fondo que se tiñe con la app enfocada

    private static final int MAX_RECENT = 5;
    private static final int TEXT_PRIMARY = 0xFFFFFFFF;
    private static final int TEXT_SECONDARY = 0xB3FFFFFF;

    private static final String[] BG_NAMES = {
            "Azul noche", "Violeta", "Verde bosque", "Rojo vino", "Gris carbón",
            "Atardecer", "Océano", "Negro (el más liviano)",
            "Aurora", "Medianoche", "Esmeralda", "Rosa neón", "Cobre",
            "Cielo nocturno", "Grafito azulado", "Vino y azul"};
    private static final int[][] BG_COLORS = {
            {0xFF0B1220, 0xFF1C2744},
            {0xFF160B24, 0xFF3A1F5C},
            {0xFF08140F, 0xFF173B2A},
            {0xFF1A0A0D, 0xFF4A1620},
            {0xFF121212, 0xFF2A2A2E},
            {0xFF1B1030, 0xFF5A2A4A},
            {0xFF04121C, 0xFF0E4A5C},
            {0xFF000000, 0xFF000000},
            {0xFF05181C, 0xFF3A2A6B},
            {0xFF02040A, 0xFF101A33},
            {0xFF04140E, 0xFF0F5A45},
            {0xFF1A0714, 0xFF6A1F5C},
            {0xFF1A0F08, 0xFF5C3418},
            {0xFF0A1330, 0xFF1F5A8C},
            {0xFF0E1116, 0xFF283244},
            {0xFF14091F, 0xFF1D3A6B}};
    private static final String[] PRESET_NAMES = {
            "Streaming", "Juegos", "Música", "Herramientas", "Favoritas", "Otros"};
    private static final String[] SIZE_NAMES = {"Chico", "Mediano", "Grande"};
    private static final int[] TILE_DP = {128, 160, 192}; // ancho; el alto sale en 16:9

    private interface StringCb { void on(String s); }
    private interface CatCb { void on(Cat c); }

    private static class Entry {
        String pkg;
        String label;
        ComponentName cn;
    }

    private static class Cat {
        String id;
        String name;
        Cat(String id, String name) { this.id = id; this.name = name; }
    }

    private SharedPreferences prefs;
    private LinearLayout root;
    private LinearLayout tabsRow;
    private LinearLayout rowsContainer;
    private ScrollView vscroll;
    private FocusTextView wifiBtn;

    private final List<Cat> cats = new ArrayList<>();
    private final Map<String, String> assign = new HashMap<>();
    private List<String> order = new ArrayList<>();
    private List<String> recent = new ArrayList<>();
    private List<String> hidden = new ArrayList<>();
    private final Map<String, String> labelCache = new HashMap<>();

    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Map<String, Integer> tintCache = new HashMap<>();
    private final List<TileView> allTiles = new ArrayList<>();
    private final Map<String, View> tabViews = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // Pinturas compartidas por todas las tarjetas.
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tileText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    // Pinturas de los paneles de Ajustes.
    private final TextPaint pOptTitle = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pOptVal = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pOptHead = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint pOptState = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pLine = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<OptionRow> panelRows = new ArrayList<>();
    private boolean tilesDimmed = false;

    private int sizeIdx = 1;
    private boolean dynBg = true;
    private int iconPx, tileW, tileH, labelH, cornerPx, ringPx;

    private Drawable.ConstantState pillState;
    private GradientDrawable bgGradient;
    private int bgIdx = 0;
    private int lastTint = 0;

    private String currentTabId = "";
    private String signature = "";
    private String pendingFocus = null;
    private String pendingTab = null;
    private View firstCardRef = null;

    private ConnectivityManager cm;
    private ConnectivityManager.NetworkCallback netCb;

    // ---------- Ciclo de vida ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pillState = selectorBackground(40).getConstantState();
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        tileText.setColor(TEXT_PRIMARY);
        tileText.setTextAlign(Paint.Align.CENTER);
        tileText.setTypeface(medium);
        tileRing.setStyle(Paint.Style.STROKE);
        tileRing.setColor(Color.WHITE);
        initPanelPaints(medium);
        applyMetrics();
        setContentView(buildRoot());
        applyBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        registerNetwork();
        rebuild(false);
        if (!prefs.getBoolean(KEY_TIP, false)) {
            prefs.edit().putBoolean(KEY_TIP, true).apply();
            root.post(() -> showHelp());
        }
    }

    @Override
    protected void onPause() {
        unregisterNetwork();
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Al apretar Home: volver a "Inicio", arriba y con foco en la primera app.
        if (currentTabId.length() > 0) {
            currentTabId = "";
            rebuild(true);
        } else if (firstCardRef != null) {
            firstCardRef.requestFocus();
        }
        vscroll.scrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Un launcher no se cierra con Atrás.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showSettingsMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------- Medidas ----------

    private void applyMetrics() {
        sizeIdx = Math.max(0, Math.min(2, prefs.getInt(KEY_SIZE, 1)));
        dynBg = prefs.getBoolean(KEY_DYN, true);
        tileW = dp(TILE_DP[sizeIdx]);
        tileH = tileW * 9 / 16;
        iconPx = (int) (tileH * 0.58f);
        labelH = dp(24);
        cornerPx = dp(12);
        ringPx = Math.max(2, dp(2));
        tileRing.setStrokeWidth(ringPx);
        tileText.setTextSize(13 * getResources().getDisplayMetrics().scaledDensity);
    }

    // ---------- Interfaz base ----------

    private View buildRoot() {
        Typeface medium = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(24), dp(56), dp(16));

        // Barra superior: pestañas a la izquierda; Wi-Fi, Ajustes y reloj a la derecha.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        tabsRow = new LinearLayout(this);
        tabsRow.setOrientation(LinearLayout.HORIZONTAL);
        top.addView(tabsRow, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        wifiBtn = new FocusTextView(this);
        wifiBtn.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_wifi, 0, 0, 0);
        wifiBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
        wifiBtn.setBackground(pillState.newDrawable());
        wifiBtn.setOnClickListener(v -> openWifiSettings());
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        wlp.rightMargin = dp(8);
        top.addView(wifiBtn, wlp);

        FocusTextView settings = new FocusTextView(this);
        settings.setText("Ajustes");
        settings.setTextColor(TEXT_PRIMARY);
        settings.setTextSize(14);
        settings.setTypeface(medium);
        settings.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_gear, 0, 0, 0);
        settings.setCompoundDrawablePadding(dp(8));
        settings.setGravity(Gravity.CENTER_VERTICAL);
        settings.setPadding(dp(16), dp(8), dp(18), dp(8));
        settings.setBackground(pillState.newDrawable());
        settings.setOnClickListener(v -> showSettingsMenu());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.rightMargin = dp(16);
        top.addView(settings, slp);

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(TEXT_PRIMARY);
        time.setTextSize(20);
        time.setTypeface(medium);
        top.addView(time);

        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Zona de filas (scroll vertical solo si hay muchas).
        vscroll = new ScrollView(this);
        vscroll.setVerticalScrollBarEnabled(false);
        vscroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        vscroll.setClipToPadding(false);
        vscroll.setPadding(0, dp(20), 0, dp(12));
        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        vscroll.addView(rowsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(vscroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        return root;
    }

    // ---------- Pestañas ----------

    private void buildTabs() {
        tabsRow.removeAllViews();
        tabViews.clear();
        addTab("Inicio", "");
        for (Cat c : cats) addTab(c.name, c.id);
    }

    private void addTab(String label, final String id) {
        boolean active = id.equals(currentTabId);
        FocusTextView t = new FocusTextView(this);
        t.setText(label);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setTextColor(active ? TEXT_PRIMARY : TEXT_SECONDARY);
        t.setSelected(active);
        t.setPadding(dp(18), dp(8), dp(18), dp(8));
        t.setBackground(pillState.newDrawable());
        t.setTag("tab:" + id);
        t.setOnClickListener(v -> {
            if (!id.equals(currentTabId)) {
                currentTabId = id;
                pendingTab = id;
                rebuild(true);
            }
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = dp(6);
        tabsRow.addView(t, lp);
        tabViews.put(id, t);
    }

    // ---------- Construcción de filas ----------

    private void rebuild(boolean force) {
        loadState();
        Map<String, Entry> inst = queryInstalled();
        List<Entry> ordered = orderedEntries(inst);
        Set<String> hid = new HashSet<>(hidden);
        List<Entry> all = new ArrayList<>();
        for (Entry e : ordered) if (!hid.contains(e.pkg)) all.add(e);
        List<Entry> recents = new ArrayList<>();
        for (String p : recent) if (inst.containsKey(p) && !hid.contains(p)) recents.add(inst.get(p));

        boolean tabOk = currentTabId.length() == 0;
        for (Cat c : cats) if (c.id.equals(currentTabId)) tabOk = true;
        if (!tabOk) currentTabId = "";

        String sig = join(order) + "#" + prefs.getString(KEY_CATS, "") + "#"
                + prefs.getString(KEY_ASSIGN, "") + "#" + prefs.getInt(KEY_SIZE, 1)
                + "#" + join(recent) + "#" + currentTabId + "#" + join(hidden);
        if (!force && sig.equals(signature) && rowsContainer.getChildCount() > 0) return;
        signature = sig;

        int oldIconPx = iconPx;
        applyMetrics();
        if (iconPx != oldIconPx) {
            iconCache.clear();
            loading.clear();
        }

        // Recordar dónde estaba el foco.
        String keepFocus = pendingFocus;
        String keepTab = pendingTab;
        pendingFocus = null;
        pendingTab = null;
        View f = getCurrentFocus();
        if (keepFocus == null && keepTab == null && f != null) {
            if (f instanceof TileView && !((TileView) f).recent) {
                keepFocus = ((TileView) f).pkg;
            } else if (f.getTag() instanceof String && ((String) f.getTag()).startsWith("tab:")) {
                keepTab = ((String) f.getTag()).substring(4);
            }
        }

        buildTabs();
        rowsContainer.removeAllViews();
        allTiles.clear();
        firstCardRef = null;
        tilesDimmed = false;

        boolean home = currentTabId.length() == 0;
        Set<String> catIds = new HashSet<>();
        for (Cat c : cats) catIds.add(c.id);

        if (home && !recents.isEmpty()) addSection("Recientes", recents, true);
        for (Cat c : cats) {
            if (!home && !c.id.equals(currentTabId)) continue;
            List<Entry> items = new ArrayList<>();
            for (Entry e : all) if (c.id.equals(assign.get(e.pkg))) items.add(e);
            addSection(c.name, items, false);
        }
        if (home) {
            List<Entry> rest = new ArrayList<>();
            for (Entry e : all) {
                String id = assign.get(e.pkg);
                if (id == null || !catIds.contains(id)) rest.add(e);
            }
            if (!rest.isEmpty() || cats.isEmpty()) addSection("Apps", rest, false);
        }

        View target = null;
        if (keepTab != null) target = tabViews.get(keepTab);
        if (target == null && keepFocus != null) {
            for (TileView t : allTiles) {
                if (!t.recent && t.pkg.equals(keepFocus)) {
                    target = t;
                    break;
                }
            }
        }
        if (target == null) target = firstCardRef;
        if (target == null) target = tabViews.get(currentTabId);
        if (target != null) target.requestFocus();
    }

    private void addSection(String title, List<Entry> items, boolean isRecent) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_SECONDARY);
        t.setTextSize(15);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(8), dp(4), 0, dp(2));
        section.addView(t);

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Vacía. Mantené OK sobre una app y elegí «Mover a otra categoría».");
            empty.setTextColor(TEXT_SECONDARY);
            empty.setTextSize(13);
            empty.setPadding(dp(8), dp(6), 0, dp(16));
            section.addView(empty);
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipToPadding(false);
            hs.setPadding(dp(8), dp(6), dp(8), dp(6));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            for (int i = 0; i < items.size(); i++) {
                TileView tile = makeTile(items.get(i), items, i, isRecent);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.rightMargin = dp(14);
                row.addView(tile, lp);
            }
            hs.addView(row, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            section.addView(hs, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        rowsContainer.addView(section, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    private TileView makeTile(final Entry e, final List<Entry> rowItems, final int index, boolean isRecent) {
        TileView v = new TileView(e, isRecent);
        allTiles.add(v);
        if (firstCardRef == null) firstCardRef = v;
        if (v.icon == null && loading.add(e.pkg)) loadIconAsync(e);
        final boolean rec = isRecent;
        v.setOnClickListener(x -> launch(e));
        v.setOnLongClickListener(x -> {
            if (rec) showRecentMenu(e);
            else showAppMenu(e, rowItems, index);
            return true;
        });
        return v;
    }

    /** Una tarjeta = una sola vista dibujada a mano (banner 16:9). */
    private class TileView extends View {
        final String pkg;
        final String shownLabel;
        final boolean recent;
        Bitmap icon;
        int tint;
        private final RectF rect = new RectF();

        TileView(Entry e, boolean isRecent) {
            super(MainActivity.this);
            pkg = e.pkg;
            recent = isRecent;
            icon = iconCache.get(e.pkg);
            Integer t = tintCache.get(e.pkg);
            tint = t != null ? t : 0;
            shownLabel = TextUtils.ellipsize(e.label, tileText, tileW - dp(8),
                    TextUtils.TruncateAt.END).toString();
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(tileW, tileH + labelH);
        }

        @Override
        protected void onDraw(Canvas c) {
            boolean f = isFocused();
            boolean dim = tilesDimmed && !f;
            int base = tint != 0 ? tint : 0xFF3B4663;
            int alpha = f ? 0xC8 : (dim ? 0x26 : 0x70);
            tileFill.setColor((alpha << 24) | (base & 0x00FFFFFF));
            rect.set(0, 0, tileW, tileH);
            c.drawRoundRect(rect, cornerPx, cornerPx, tileFill);
            if (icon != null) {
                iconPaint.setAlpha(dim ? 90 : 255);
                c.drawBitmap(icon, (tileW - iconPx) / 2f, (tileH - iconPx) / 2f, iconPaint);
            }
            if (f) {
                float h = ringPx / 2f;
                rect.set(h, h, tileW - h, tileH - h);
                c.drawRoundRect(rect, cornerPx, cornerPx, tileRing);
                c.drawText(shownLabel, tileW / 2f, tileH + labelH * 0.72f, tileText);
            }
        }

        @Override
        protected void onFocusChanged(boolean gain, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(gain, direction, previouslyFocusedRect);
            if (gain) {
                setTilesDimmed(true);
                tintBackground(tint);
            }
            invalidate();
        }
    }

    /** Botón/pestaña de texto enfocable. */
    private class FocusTextView extends TextView {
        FocusTextView(Context c) {
            super(c);
            setFocusable(true);
            setClickable(true);
        }

        @Override
        protected void onFocusChanged(boolean gain, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(gain, direction, previouslyFocusedRect);
            if (gain) {
                setTilesDimmed(false);
                tintBackground(0);
            }
        }
    }

    /** Cuando hay una app enfocada, las demás se apagan; al salir de las filas vuelven a la normalidad. */
    private void setTilesDimmed(boolean v) {
        if (v == tilesDimmed) return;
        tilesDimmed = v;
        for (TileView t : allTiles) t.invalidate();
    }

    // ---------- Íconos y color ----------

    private void loadIconAsync(final Entry e) {
        final int size = iconPx;
        final PackageManager pm = getPackageManager();
        io.execute(() -> {
            try {
                Drawable d = pm.getActivityIcon(e.cn);
                final Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
                Canvas cv = new Canvas(b);
                d.setBounds(0, 0, size, size);
                d.draw(cv);
                final int tint = averageColor(b);
                runOnUiThread(() -> {
                    if (size != iconPx) return; // cambió el tamaño mientras cargaba
                    iconCache.put(e.pkg, b);
                    tintCache.put(e.pkg, tint);
                    for (OptionRow r : panelRows) {
                        if (e.pkg.equals(r.o.pkg)) {
                            r.icon = b;
                            r.invalidate();
                        }
                    }
                    for (TileView t : allTiles) {
                        if (t.pkg.equals(e.pkg)) {
                            t.icon = b;
                            t.tint = tint;
                            t.invalidate();
                            if (t.isFocused()) {
                                lastTint = -1;
                                tintBackground(tint);
                            }
                        }
                    }
                });
            } catch (Throwable ignored) {
                // Si un ícono falla, la tarjeta queda sin ícono pero con nombre.
            }
        });
    }

    /** Color promedio del ícono (ignora lo transparente), suavizado hacia un azul oscuro. */
    private int averageColor(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 8, 8, true);
        int[] px = new int[64];
        s.getPixels(px, 0, 8, 0, 0, 8, 8);
        if (s != b) s.recycle();
        long r = 0, g = 0, bl = 0;
        int n = 0;
        for (int p : px) {
            if ((p >>> 24) < 160) continue;
            r += (p >> 16) & 255;
            g += (p >> 8) & 255;
            bl += p & 255;
            n++;
        }
        if (n == 0) return 0;
        int avg = 0xFF000000 | ((int) (r / n) << 16) | ((int) (g / n) << 8) | (int) (bl / n);
        return blend(avg, 0xFF222B42, 0.25f);
    }

    private int blend(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xFF000000 | (r << 16) | (g << 8) | bl;
    }

    /** Tiñe suavemente el degradado de fondo con el color de la app enfocada (0 = sin tinte). */
    private void tintBackground(int color) {
        if (bgGradient == null) return;
        if (!dynBg) color = 0;
        if (color == lastTint) return;
        lastTint = color;
        int[] base = BG_COLORS[bgIdx];
        int[] c = new int[2];
        if (color == 0) {
            c[0] = base[0];
            c[1] = base[1];
        } else {
            c[0] = blend(base[0], color, 0.14f);
            c[1] = blend(base[1], color, 0.30f);
        }
        bgGradient.setColors(c);
    }

    // ---------- Datos ----------

    private void loadState() {
        cats.clear();
        for (String line : readLines(KEY_CATS)) {
            int i = line.indexOf('|');
            if (i > 0) cats.add(new Cat(line.substring(0, i), line.substring(i + 1)));
        }
        assign.clear();
        for (String line : readLines(KEY_ASSIGN)) {
            int i = line.lastIndexOf('|');
            if (i > 0) assign.put(line.substring(0, i), line.substring(i + 1));
        }
        order = readLines(KEY_ORDER);
        recent = readLines(KEY_RECENT);
        hidden = readLines(KEY_HIDDEN);
    }

    private List<String> readLines(String key) {
        List<String> out = new ArrayList<>();
        String s = prefs.getString(key, "");
        if (s.length() == 0) return out;
        for (String l : s.split("\n")) if (l.length() > 0) out.add(l);
        return out;
    }

    private String join(List<String> list) {
        StringBuilder sb = new StringBuilder();
        for (String s : list) sb.append(s).append('\n');
        return sb.toString();
    }

    private void saveCats() {
        StringBuilder sb = new StringBuilder();
        for (Cat c : cats) sb.append(c.id).append('|').append(c.name).append('\n');
        prefs.edit().putString(KEY_CATS, sb.toString()).apply();
    }

    private void saveAssign() {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> en : assign.entrySet()) {
            sb.append(en.getKey()).append('|').append(en.getValue()).append('\n');
        }
        prefs.edit().putString(KEY_ASSIGN, sb.toString()).apply();
    }

    private void saveOrder() {
        prefs.edit().putString(KEY_ORDER, join(order)).apply();
    }

    private void saveHidden() {
        prefs.edit().putString(KEY_HIDDEN, join(hidden)).apply();
    }

    private void saveRecent() {
        prefs.edit().putString(KEY_RECENT, join(recent)).apply();
    }

    private Map<String, Entry> queryInstalled() {
        PackageManager pm = getPackageManager();
        Map<String, Entry> map = new LinkedHashMap<>();
        collect(pm, map, Intent.CATEGORY_LEANBACK_LAUNCHER);
        collect(pm, map, Intent.CATEGORY_LAUNCHER);
        return map;
    }

    private void collect(PackageManager pm, Map<String, Entry> map, String category) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
            Entry e = new Entry();
            e.pkg = pkg;
            e.cn = new ComponentName(pkg, ri.activityInfo.name);
            String label = labelCache.get(pkg);
            if (label == null) {
                label = String.valueOf(ri.loadLabel(pm)); // solo la primera vez
                labelCache.put(pkg, label);
            }
            e.label = label;
            map.put(pkg, e);
        }
    }

    /** Devuelve las apps en el orden guardado; las nuevas van al final (A-Z). */
    private List<Entry> orderedEntries(Map<String, Entry> inst) {
        List<String> ord = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String p : order) {
            if (inst.containsKey(p) && seen.add(p)) ord.add(p);
        }
        List<Entry> fresh = new ArrayList<>();
        for (Entry e : inst.values()) if (!seen.contains(e.pkg)) fresh.add(e);
        Collections.sort(fresh, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                return a.label.toLowerCase().compareTo(b.label.toLowerCase());
            }
        });
        for (Entry e : fresh) ord.add(e.pkg);
        if (!ord.equals(order)) {
            order = ord;
            saveOrder();
        }
        List<Entry> out = new ArrayList<>();
        for (String p : ord) out.add(inst.get(p));
        return out;
    }

    // ---------- Acciones sobre apps ----------

    private void launch(Entry e) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(e.cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
            recent.remove(e.pkg);
            recent.add(0, e.pkg);
            while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
            saveRecent();
        } catch (Exception ex) {
            toast("No se pudo abrir la app");
        }
    }

    private void showAppMenu(final Entry e, final List<Entry> rowItems, final int index) {
        String[] items = {"Mover a otra categoría", "Mover a la derecha", "Mover a la izquierda",
                "Ocultar app", "Información de la app", "Desinstalar"};
        showMenu(e.label, items, (d, which) -> {
            switch (which) {
                case 0: chooseCategoryFor(e); break;
                case 1: moveInRow(e, rowItems, index, 1); break;
                case 2: moveInRow(e, rowItems, index, -1); break;
                case 3: hideApp(e, rowItems, index); break;
                case 4: openAppInfo(e.pkg); break;
                default: uninstall(e.pkg);
            }
        });
    }

    private void showRecentMenu(final Entry e) {
        String[] items = {"Quitar de Recientes", "Ocultar app", "Información de la app", "Desinstalar"};
        showMenu(e.label, items, (d, which) -> {
            if (which == 0) {
                recent.remove(e.pkg);
                saveRecent();
                rebuild(true);
            } else if (which == 1) {
                hideApp(e, null, -1);
            } else if (which == 2) {
                openAppInfo(e.pkg);
            } else {
                uninstall(e.pkg);
            }
        });
    }

    private void hideApp(Entry e, List<Entry> rowItems, int index) {
        if (!hidden.contains(e.pkg)) hidden.add(e.pkg);
        saveHidden();
        if (recent.remove(e.pkg)) saveRecent();
        if (rowItems != null) {
            if (index + 1 < rowItems.size()) pendingFocus = rowItems.get(index + 1).pkg;
            else if (index - 1 >= 0) pendingFocus = rowItems.get(index - 1).pkg;
        }
        toast("«" + e.label + "» oculta. Para mostrarla: Ajustes > Apps ocultas");
        rebuild(true);
    }

    private void showHiddenApps() {
        Map<String, Entry> inst = queryInstalled();
        final List<Entry> list = new ArrayList<>();
        for (String p : hidden) if (inst.containsKey(p)) list.add(inst.get(p));
        if (list.isEmpty()) {
            toast("No hay apps ocultas");
            return;
        }
        List<Opt> ho = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) {
            Opt x = valueOpt(i, list.get(i).label, "Mostrar");
            x.pkg = list.get(i).pkg;
            ho.add(x);
        }
        showOptions("Apps ocultas", ho, (x, r) -> {
            Entry e = list.get(x.id);
            hidden.remove(e.pkg);
            saveHidden();
            pendingFocus = e.pkg;
            rebuild(true);
        });
        for (Entry e : list) {
            if (!iconCache.containsKey(e.pkg) && loading.add(e.pkg)) loadIconAsync(e);
        }
    }

    private void showHelp() {
        showMessage("Cómo se usa",
                "\u2022  OK: abrir la app.\n"
                + "\u2022  Mantener OK sobre una app: mover, cambiar de categoría, ocultar, ver información o desinstalar.\n"
                + "\u2022  Pestañas de arriba: elegí una para ver solo esa categoría. El botón Home vuelve a Inicio.\n"
                + "\u2022  Ajustes: categorías, tamaño de tarjetas, fondo y apps ocultas.");
    }

    private void openAppInfo(String pkg) {
        try {
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", pkg, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            toast("No se pudo abrir la información");
        }
    }

    private void uninstall(String pkg) {
        try {
            Intent i = new Intent(Intent.ACTION_DELETE, Uri.fromParts("package", pkg, null));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            toast("No se pudo desinstalar");
        }
    }

    private void moveInRow(Entry e, List<Entry> rowItems, int index, int dir) {
        int j = index + dir;
        if (j < 0 || j >= rowItems.size()) {
            toast(dir < 0 ? "Ya está primera" : "Ya está última");
            return;
        }
        int a = order.indexOf(e.pkg);
        int b = order.indexOf(rowItems.get(j).pkg);
        if (a < 0 || b < 0) return;
        Collections.swap(order, a, b);
        saveOrder();
        pendingFocus = e.pkg;
        rebuild(true);
    }

    private void chooseCategoryFor(final Entry e) {
        final List<String> names = new ArrayList<>();
        for (Cat c : cats) names.add(c.name);
        names.add("Apps (sin categoría)");
        names.add("+ Nueva categoría…");
        showMenu("Mover " + e.label + " a…", names.toArray(new String[0]), (d, which) -> {
            if (which < cats.size()) {
                assign.put(e.pkg, cats.get(which).id);
                saveAssign();
                pendingFocus = e.pkg;
                rebuild(true);
            } else if (which == cats.size()) {
                assign.remove(e.pkg);
                saveAssign();
                pendingFocus = e.pkg;
                rebuild(true);
            } else {
                askCategoryName("Nueva categoría", "", name -> {
                    Cat c = new Cat("c" + System.currentTimeMillis(), name);
                    cats.add(c);
                    saveCats();
                    assign.put(e.pkg, c.id);
                    saveAssign();
                    pendingFocus = e.pkg;
                    rebuild(true);
                });
            }
        });
    }

    // ---------- Ajustes ----------

    private static final int ID_NEWCAT = 1, ID_RENAME = 2, ID_MOVECAT = 3, ID_DELCAT = 4,
            ID_SIZE = 5, ID_BG = 6, ID_DYN = 7, ID_HIDDEN = 8, ID_HELP = 9, ID_ANDROID = 10;

    private void showSettingsMenu() {
        Map<String, Entry> inst = queryInstalled();
        int hiddenCount = 0;
        for (String p : hidden) if (inst.containsKey(p)) hiddenCount++;

        List<Opt> o = new ArrayList<>();
        o.add(header("Categorías"));
        o.add(action(ID_NEWCAT, "Nueva categoría"));
        o.add(action(ID_RENAME, "Renombrar categoría"));
        o.add(action(ID_MOVECAT, "Subir / bajar categoría"));
        o.add(action(ID_DELCAT, "Eliminar categoría"));
        o.add(header("Apariencia"));
        o.add(valueOpt(ID_SIZE, "Tamaño de tarjetas", SIZE_NAMES[sizeIdx]));
        o.add(valueOpt(ID_BG, "Fondo", BG_NAMES[bgIdx]));
        o.add(toggleOpt(ID_DYN, "Fondo que cambia con la app", dynBg));
        o.add(header("Apps"));
        o.add(valueOpt(ID_HIDDEN, "Apps ocultas", String.valueOf(hiddenCount)));
        o.add(header("Ayuda"));
        o.add(action(ID_HELP, "Cómo se usa"));
        o.add(valueOpt(ID_ANDROID, "Ajustes de Android", ""));

        showOptions("Ajustes", o, (opt, row) -> {
            switch (opt.id) {
                case ID_NEWCAT:
                    askCategoryName("Nueva categoría", "", name -> {
                        cats.add(new Cat("c" + System.currentTimeMillis(), name));
                        saveCats();
                        rebuild(true);
                    });
                    break;
                case ID_RENAME:
                    pickCategory("Renombrar…", c -> askCategoryName("Nuevo nombre", c.name, name -> {
                        c.name = name;
                        saveCats();
                        rebuild(true);
                    }));
                    break;
                case ID_MOVECAT:
                    pickCategory("Mover categoría…", this::moveCategoryDialog);
                    break;
                case ID_DELCAT:
                    pickCategory("Eliminar…", this::confirmDeleteCategory);
                    break;
                case ID_SIZE: {
                    List<Opt> so = new ArrayList<>();
                    for (int i = 0; i < SIZE_NAMES.length; i++) {
                        Opt x = action(i, SIZE_NAMES[i]);
                        x.selected = i == sizeIdx;
                        so.add(x);
                    }
                    showOptions("Tamaño de tarjetas", so, (x, r) -> {
                        prefs.edit().putInt(KEY_SIZE, x.id).apply();
                        rebuild(true);
                    });
                    break;
                }
                case ID_BG:
                    showBackgroundMenu();
                    break;
                case ID_DYN:
                    dynBg = !dynBg;
                    prefs.edit().putBoolean(KEY_DYN, dynBg).apply();
                    lastTint = -1;
                    tintBackground(0);
                    if (row != null) row.toggle();
                    break;
                case ID_HIDDEN:
                    showHiddenApps();
                    break;
                case ID_HELP:
                    showHelp();
                    break;
                default:
                    try {
                        Intent i = new Intent(Settings.ACTION_SETTINGS);
                        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(i);
                    } catch (Exception ex) {
                        toast("No se pudo abrir Ajustes");
                    }
            }
        });
    }

    private void showBackgroundMenu() {
        List<Opt> bo = new ArrayList<>();
        for (int i = 0; i < BG_NAMES.length; i++) {
            Opt x = action(i, BG_NAMES[i]);
            x.swatch = true;
            x.swA = BG_COLORS[i][0];
            x.swB = BG_COLORS[i][1];
            x.selected = i == bgIdx;
            bo.add(x);
        }
        showOptions("Fondo", bo, (x, r) -> {
            prefs.edit().putString(KEY_BG, "p" + x.id).apply();
            applyBackground();
        });
    }

    private void pickCategory(String title, final CatCb cb) {
        if (cats.isEmpty()) {
            toast("Todavía no hay categorías. Creá una primero.");
            return;
        }
        String[] names = new String[cats.size()];
        for (int i = 0; i < cats.size(); i++) names[i] = cats.get(i).name;
        showMenu(title, names, (d, which) -> cb.on(cats.get(which)));
    }

    private void moveCategoryDialog(final Cat c) {
        showMenu(c.name, new String[]{"Subir", "Bajar"}, (d, which) -> {
            int i = cats.indexOf(c);
            int j = which == 0 ? i - 1 : i + 1;
            if (j < 0 || j >= cats.size()) {
                toast(which == 0 ? "Ya es la primera" : "Ya es la última");
                return;
            }
            Collections.swap(cats, i, j);
            saveCats();
            rebuild(true);
        });
    }

    private void confirmDeleteCategory(final Cat c) {
        showMenu("¿Eliminar «" + c.name + "»? Sus apps vuelven a «Apps».",
                new String[]{"Eliminar", "Cancelar"}, (d, which) -> {
                    if (which != 0) return;
                    cats.remove(c);
                    saveCats();
                    List<String> drop = new ArrayList<>();
                    for (Map.Entry<String, String> en : assign.entrySet()) {
                        if (en.getValue().equals(c.id)) drop.add(en.getKey());
                    }
                    for (String p : drop) assign.remove(p);
                    saveAssign();
                    rebuild(true);
                });
    }

    private void askCategoryName(final String title, final String current, final StringCb cb) {
        final List<String> opts = new ArrayList<>();
        Collections.addAll(opts, PRESET_NAMES);
        opts.add("Escribir otro nombre…");
        showMenu(title, opts.toArray(new String[0]), (d, which) -> {
            if (which < PRESET_NAMES.length) cb.on(PRESET_NAMES[which]);
            else showInput(title, current, cb);
        });
    }

    private String clean(String s) {
        s = s.replace('|', ' ').replace('\n', ' ').trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    // ---------- Fondo ----------

    private void applyBackground() {
        int idx = 0;
        try {
            idx = Integer.parseInt(prefs.getString(KEY_BG, "p0").substring(1));
        } catch (Exception ignored) {
        }
        if (idx < 0 || idx >= BG_COLORS.length) idx = 0;
        bgIdx = idx;
        bgGradient = new GradientDrawable(GradientDrawable.Orientation.TL_BR, BG_COLORS[idx].clone());
        root.setBackground(bgGradient);
        lastTint = 0;
    }

    // ---------- Red ----------

    private void registerNetwork() {
        try {
            cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            updateNetwork();
            if (netCb == null) {
                netCb = new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onAvailable(android.net.Network n) { postNetworkUpdate(); }

                    @Override
                    public void onLost(android.net.Network n) { postNetworkUpdate(); }
                };
                NetworkRequest req = new NetworkRequest.Builder()
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build();
                cm.registerNetworkCallback(req, netCb);
            }
        } catch (Throwable ignored) {
            // Sin permiso o sin servicio: el ícono queda como está.
        }
    }

    private void unregisterNetwork() {
        try {
            if (cm != null && netCb != null) cm.unregisterNetworkCallback(netCb);
        } catch (Throwable ignored) {
        }
        netCb = null;
    }

    private void postNetworkUpdate() {
        wifiBtn.postDelayed(() -> updateNetwork(), 400);
    }

    private void updateNetwork() {
        boolean on = false;
        try {
            NetworkInfo ni = cm != null ? cm.getActiveNetworkInfo() : null;
            on = ni != null && ni.isConnected();
        } catch (Throwable ignored) {
        }
        wifiBtn.setAlpha(on ? 1f : 0.35f);
    }

    private void openWifiSettings() {
        try {
            Intent i = new Intent(Settings.ACTION_WIFI_SETTINGS);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception ex) {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ex2) {
                toast("No se pudo abrir los ajustes de red");
            }
        }
    }

    // ---------- Paneles propios (no se parecen a los de Android) ----------

    private static final int K_ACTION = 0, K_TOGGLE = 1, K_VALUE = 2, K_HEADER = 3;
    private static final int ACCENT = 0xFF3DDC97;

    private static class Opt {
        int id;
        int kind;
        String label;
        String value;
        String pkg;
        boolean on;
        boolean selected;
        boolean swatch;
        int swA, swB;

        Opt(int id, int kind, String label) {
            this.id = id;
            this.kind = kind;
            this.label = label;
        }
    }

    private interface PanelCb { void on(Opt o, OptionRow row); }

    private Opt header(String t) { return new Opt(-1, K_HEADER, t); }

    private Opt action(int id, String t) { return new Opt(id, K_ACTION, t); }

    private Opt valueOpt(int id, String t, String v) {
        Opt o = new Opt(id, K_VALUE, t);
        o.value = v;
        return o;
    }

    private Opt toggleOpt(int id, String t, boolean on) {
        Opt o = new Opt(id, K_TOGGLE, t);
        o.on = on;
        return o;
    }

    private void initPanelPaints(Typeface medium) {
        float sd = getResources().getDisplayMetrics().scaledDensity;
        pOptTitle.setColor(TEXT_PRIMARY);
        pOptTitle.setTypeface(medium);
        pOptTitle.setTextSize(16 * sd);
        pOptVal.setColor(TEXT_SECONDARY);
        pOptVal.setTypeface(medium);
        pOptVal.setTextSize(14 * sd);
        pOptVal.setTextAlign(Paint.Align.RIGHT);
        pOptHead.setColor(0x99FFFFFF);
        pOptHead.setTypeface(medium);
        pOptHead.setTextSize(11 * sd);
        pOptHead.setLetterSpacing(0.14f);
        pOptState.setTypeface(medium);
        pOptState.setTextSize(11 * sd);
        pOptState.setTextAlign(Paint.Align.RIGHT);
        pLine.setStyle(Paint.Style.STROKE);
        pLine.setStrokeCap(Paint.Cap.ROUND);
        pLine.setStrokeJoin(Paint.Join.ROUND);
        pLine.setStrokeWidth(Math.max(2, dp(2)));
    }

    /** Una fila de panel = una sola vista dibujada a mano (título, interruptor, valor, ícono o muestra de color). */
    private class OptionRow extends View {
        final Opt o;
        boolean on;
        Bitmap icon;
        String shownTitle = "";
        String headText;
        Paint swPaint;
        private final RectF rect = new RectF();
        private final Rect dst = new Rect();

        OptionRow(Opt opt) {
            super(MainActivity.this);
            o = opt;
            on = opt.on;
            boolean head = opt.kind == K_HEADER;
            setFocusable(!head);
            setClickable(!head);
            if (head) headText = opt.label.toUpperCase();
            if (opt.swatch) {
                swPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                swPaint.setShader(new LinearGradient(0, 0, dp(44), dp(28), opt.swA, opt.swB,
                        Shader.TileMode.CLAMP));
            }
            if (opt.pkg != null) icon = iconCache.get(opt.pkg);
        }

        void toggle() {
            on = !on;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthSpec, int heightSpec) {
            setMeasuredDimension(MeasureSpec.getSize(widthSpec),
                    o.kind == K_HEADER ? dp(34) : dp(56));
        }

        private int leftInset() {
            return dp(18) + ((o.swatch || o.pkg != null) ? dp(58) : 0);
        }

        private int rightReserve() {
            switch (o.kind) {
                case K_TOGGLE:
                    return dp(18 + 50 + 12) + (int) pOptState.measureText("ACTIVADO") + dp(16);
                case K_VALUE:
                    return dp(20 + 16 + 12) + (o.value != null ? (int) pOptVal.measureText(o.value) : 0);
                default:
                    return o.selected ? dp(60) : dp(18);
            }
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            if (o.kind == K_HEADER) return;
            int avail = Math.max(dp(40), w - leftInset() - rightReserve());
            shownTitle = TextUtils.ellipsize(o.label, pOptTitle, avail, TextUtils.TruncateAt.END).toString();
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth();
            int h = getHeight();
            if (o.kind == K_HEADER) {
                c.drawText(headText, dp(6), h - dp(8), pOptHead);
                return;
            }
            boolean f = isFocused();
            float r14 = dp(14);
            tileFill.setColor(f ? 0x40FFFFFF : 0x14FFFFFF);
            rect.set(0, 0, w, h);
            c.drawRoundRect(rect, r14, r14, tileFill);
            if (f) {
                float hh = ringPx / 2f;
                rect.set(hh, hh, w - hh, h - hh);
                c.drawRoundRect(rect, r14, r14, tileRing);
            }

            float cy = h / 2f;
            float x = dp(18);
            if (o.swatch) {
                float top = cy - dp(14);
                c.save();
                c.translate(x, top);
                rect.set(0, 0, dp(44), dp(28));
                c.drawRoundRect(rect, dp(8), dp(8), swPaint);
                pLine.setColor(0x55FFFFFF);
                pLine.setStrokeWidth(Math.max(1, dp(1)));
                c.drawRoundRect(rect, dp(8), dp(8), pLine);
                c.restore();
                pLine.setStrokeWidth(Math.max(2, dp(2)));
                x += dp(58);
            } else if (o.pkg != null) {
                if (icon != null) {
                    int s = dp(36);
                    int left = (int) x + dp(4);
                    int top = (int) (cy - s / 2f);
                    dst.set(left, top, left + s, top + s);
                    iconPaint.setAlpha(255);
                    c.drawBitmap(icon, null, dst, iconPaint);
                }
                x += dp(58);
            }

            float base = cy - (pOptTitle.descent() + pOptTitle.ascent()) / 2f;
            c.drawText(shownTitle, x, base, pOptTitle);

            float right = w - dp(18);
            if (o.kind == K_TOGGLE) {
                float tw = dp(50), th = dp(28);
                float tl = right - tw;
                tileFill.setColor(on ? ACCENT : 0x55FFFFFF);
                rect.set(tl, cy - th / 2f, right, cy + th / 2f);
                c.drawRoundRect(rect, th / 2f, th / 2f, tileFill);
                tileFill.setColor(Color.WHITE);
                c.drawCircle(on ? right - dp(14) : tl + dp(14), cy, dp(11), tileFill);
                pOptState.setColor(on ? ACCENT : 0x99FFFFFF);
                float sb = cy - (pOptState.descent() + pOptState.ascent()) / 2f;
                c.drawText(on ? "ACTIVADO" : "APAGADO", tl - dp(12), sb, pOptState);
            } else if (o.kind == K_VALUE) {
                float x2 = w - dp(20);
                pLine.setColor(0xB3FFFFFF);
                c.drawLine(x2 - dp(6), cy - dp(6), x2, cy, pLine);
                c.drawLine(x2, cy, x2 - dp(6), cy + dp(6), pLine);
                if (o.value != null && o.value.length() > 0) {
                    float vb = cy - (pOptVal.descent() + pOptVal.ascent()) / 2f;
                    c.drawText(o.value, x2 - dp(16), vb, pOptVal);
                }
            } else if (o.selected) {
                float cx = w - dp(30);
                tileFill.setColor(ACCENT);
                c.drawCircle(cx, cy, dp(12), tileFill);
                pLine.setColor(0xFF0B1220);
                c.drawLine(cx - dp(5), cy, cx - dp(1), cy + dp(4), pLine);
                c.drawLine(cx - dp(1), cy + dp(4), cx + dp(5), cy - dp(4), pLine);
            }
        }
    }

    private int[] panelMetrics() {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        int sw = Math.max(dm.widthPixels, dm.heightPixels);
        int sh = Math.min(dm.widthPixels, dm.heightPixels);
        int w = Math.min(dp(820), (int) (sw * 0.74f));
        int maxH = Math.min(w * 9 / 16, (int) (sh * 0.88f));
        return new int[]{w, maxH};
    }

    private Drawable panelBackground() {
        int[] b = BG_COLORS[bgIdx];
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{blend(b[0], 0xFFFFFFFF, 0.07f), blend(b[1], 0xFFFFFFFF, 0.07f)});
        g.setCornerRadius(dp(26));
        g.setStroke(Math.max(1, dp(1)), 0x24FFFFFF);
        return g;
    }

    /** Marco del panel: flotante, 16:9 como máximo, con el color del fondo del launcher. */
    private Dialog panelDialog(String title, View body, int h) {
        int[] m = panelMetrics();
        Dialog d = new Dialog(this);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(24), dp(20), dp(24), dp(18));
        box.setBackground(panelBackground());

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(TEXT_PRIMARY);
        t.setTextSize(22);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        box.addView(t);

        View line = new View(this);
        line.setBackgroundColor(0x1FFFFFFF);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(1)));
        llp.topMargin = dp(12);
        llp.bottomMargin = dp(10);
        box.addView(line, llp);

        box.addView(body, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        d.setContentView(box);

        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            w.setLayout(m[0], h);
            w.setGravity(Gravity.CENTER);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setDimAmount(0.7f);
        }
        return d;
    }

    private void showOptions(String title, List<Opt> opts, final PanelCb cb) {
        int[] m = panelMetrics();
        int content = dp(120);
        for (Opt o : opts) content += o.kind == K_HEADER ? dp(34) : dp(64);
        int h = Math.max((int) (m[1] * 0.6f), Math.min(m[1], content));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setClipToPadding(false);
        sv.setPadding(dp(4), dp(4), dp(4), dp(4));
        sv.addView(list);

        final Dialog d = panelDialog(title, sv, h);
        final List<OptionRow> mine = new ArrayList<>();
        View focusTarget = null;
        for (final Opt o : opts) {
            final OptionRow r = new OptionRow(o);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = o.kind == K_HEADER ? 0 : dp(8);
            list.addView(r, lp);
            panelRows.add(r);
            mine.add(r);
            if (o.kind == K_HEADER) continue;
            r.setOnClickListener(v -> {
                if (o.kind != K_TOGGLE) d.dismiss();
                cb.on(o, r);
            });
            if (o.selected) focusTarget = r;
            else if (focusTarget == null) focusTarget = r;
        }
        d.setOnDismissListener(x -> panelRows.removeAll(mine));
        d.show();
        if (focusTarget != null) focusTarget.requestFocus();
    }

    /** Lista simple de opciones con el mismo estilo (para los menús cortos). */
    private void showMenu(String title, String[] items, final DialogInterface.OnClickListener l) {
        List<Opt> opts = new ArrayList<>();
        for (int i = 0; i < items.length; i++) opts.add(action(i, items[i]));
        showOptions(title, opts, (o, row) -> l.onClick(null, o.id));
    }

    private void showMessage(String title, String text) {
        int[] m = panelMetrics();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(TEXT_SECONDARY);
        tv.setTextSize(15);
        tv.setLineSpacing(0, 1.2f);
        tv.setPadding(dp(6), 0, dp(6), dp(14));
        col.addView(tv);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        final Dialog d = panelDialog(title, sv, (int) (m[1] * 0.85f));
        OptionRow ok = new OptionRow(action(0, "Entendido"));
        ok.setOnClickListener(v -> d.dismiss());
        col.addView(ok, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        d.show();
        ok.requestFocus();
    }

    private void showInput(String title, String current, final StringCb cb) {
        int[] m = panelMetrics();
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        final EditText et = new EditText(this);
        et.setSingleLine(true);
        et.setHint("Nombre");
        et.setHintTextColor(TEXT_SECONDARY);
        et.setTextColor(TEXT_PRIMARY);
        et.setTextSize(18);
        et.setText(current);
        et.setPadding(dp(18), dp(12), dp(18), dp(12));
        et.setBackground(shape(0x1FFFFFFF, false, 14));
        LinearLayout.LayoutParams elp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        elp.bottomMargin = dp(12);
        col.addView(et, elp);
        ScrollView sv = new ScrollView(this);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col);
        final Dialog d = panelDialog(title, sv, (int) (m[1] * 0.85f));

        OptionRow save = new OptionRow(action(0, "Guardar"));
        save.setOnClickListener(v -> {
            String n = clean(et.getText().toString());
            d.dismiss();
            if (n.length() > 0) cb.on(n);
        });
        OptionRow cancel = new OptionRow(action(1, "Cancelar"));
        cancel.setOnClickListener(v -> d.dismiss());
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = dp(8);
        col.addView(save, rlp);
        col.addView(cancel, rlp);
        d.show();
        et.requestFocus();
    }

    private Drawable selectorBackground(int radiusDp) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, shape(0x4DFFFFFF, true, radiusDp));
        s.addState(new int[]{android.R.attr.state_selected}, shape(0x33FFFFFF, false, radiusDp));
        s.addState(new int[]{}, shape(0x00000000, false, radiusDp));
        return s;
    }

    private GradientDrawable shape(int fill, boolean ring, int radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(radiusDp));
        if (ring) g.setStroke(Math.max(2, dp(2)), Color.WHITE);
        return g;
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
