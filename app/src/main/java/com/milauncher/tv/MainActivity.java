package com.milauncher.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Mi Launcher v3 (estilo Google TV, pensado para poca RAM)
 * - Cada app es UNA sola vista dibujada a mano (en vez de 4): menos memoria y menos trabajo por cuadro.
 * - Íconos reescalados al tamaño exacto, cargados en segundo plano y cacheados.
 * - Tamaño de íconos y zoom al enfocar configurables.
 * - Nada se recalcula al volver al launcher si no cambió nada.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "launcher";
    private static final String KEY_CATS = "cats";      // líneas "id|nombre"
    private static final String KEY_ASSIGN = "assign";  // líneas "paquete|id"
    private static final String KEY_ORDER = "order";    // líneas "paquete"
    private static final String KEY_BG = "bg";          // "p0".."p7" o "img"
    private static final String KEY_SIZE = "size";      // 0 chico, 1 mediano, 2 grande
    private static final String KEY_ZOOM = "zoom";      // boolean

    private static final String[] BG_NAMES = {
            "Azul noche", "Violeta", "Verde bosque", "Rojo vino", "Gris carbón",
            "Atardecer", "Océano", "Negro (el más liviano)"};
    private static final int[][] BG_COLORS = {
            {0xFF0B1220, 0xFF1C2744},
            {0xFF160B24, 0xFF3A1F5C},
            {0xFF08140F, 0xFF173B2A},
            {0xFF1A0A0D, 0xFF4A1620},
            {0xFF121212, 0xFF2A2A2E},
            {0xFF1B1030, 0xFF5A2A4A},
            {0xFF04121C, 0xFF0E4A5C},
            {0xFF000000, 0xFF000000}};
    private static final String[] PRESET_NAMES = {
            "Streaming", "Juegos", "Música", "Herramientas", "Favoritas", "Otros"};
    private static final String[] SIZE_NAMES = {"Chico", "Mediano", "Grande"};
    private static final int[] ICON_DP = {36, 48, 60};

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
    private LinearLayout rowsContainer;
    private ScrollView vscroll;
    private TextView dateView;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("EEEE d 'de' MMMM", Locale.getDefault());

    private final List<Cat> cats = new ArrayList<>();
    private final Map<String, String> assign = new HashMap<>();
    private List<String> order = new ArrayList<>();
    private final Map<String, String> labelCache = new HashMap<>();

    private final Map<String, Bitmap> iconCache = new HashMap<>();
    private final Map<String, TileView> tileViews = new HashMap<>();
    private final Set<String> loading = new HashSet<>();
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    // Pinturas compartidas por todas las tarjetas (una sola instancia de cada una).
    private final Paint tileFill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tileRing = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint tileText = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Paint iconPaint = new Paint(Paint.FILTER_BITMAP_FLAG);

    private int sizeIdx = 1;
    private boolean zoomOn = true;
    private int iconPx, tileW, tileH, labelH, cornerPx, ringPx;

    private Drawable.ConstantState pillBgState;
    private Bitmap bgBitmap;
    private String signature = "";
    private String pendingFocus = null;
    private View firstCardRef = null;

    // ---------- Ciclo de vida ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        pillBgState = pillBackground().getConstantState();
        tileText.setColor(Color.WHITE);
        tileText.setTextAlign(Paint.Align.CENTER);
        tileText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tileRing.setStyle(Paint.Style.STROKE);
        tileRing.setColor(Color.WHITE);
        applyMetrics();
        setContentView(buildRoot());
        applyBackground();
    }

    @Override
    protected void onResume() {
        super.onResume();
        dateView.setText(capitalize(dateFmt.format(new Date())));
        rebuild(false);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Al apretar Home: volver arriba y enfocar la primera app.
        if (firstCardRef != null) firstCardRef.requestFocus();
        vscroll.scrollTo(0, 0);
    }

    @Override
    public void onBackPressed() {
        // Un launcher no se cierra con Atrás.
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_MENU) {
            showMainMenu();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        io.shutdownNow();
        super.onDestroy();
    }

    // ---------- Medidas según el tamaño elegido ----------

    private void applyMetrics() {
        sizeIdx = Math.max(0, Math.min(2, prefs.getInt(KEY_SIZE, 1)));
        zoomOn = prefs.getBoolean(KEY_ZOOM, true);
        int iconDp = ICON_DP[sizeIdx];
        iconPx = dp(iconDp);
        tileH = dp((int) (iconDp * 1.6f));
        tileW = (int) (tileH * 1.35f);
        labelH = dp(24);
        cornerPx = dp(14);
        ringPx = Math.max(2, dp(3));
        tileRing.setStrokeWidth(ringPx);
        tileText.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
    }

    // ---------- Interfaz base ----------

    private View buildRoot() {
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(56), dp(30), dp(56), dp(18));
        root.setClipChildren(false);

        // Barra superior: reloj a la izquierda, "Personalizar" a la derecha.
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.HORIZONTAL);
        top.setGravity(Gravity.CENTER_VERTICAL);

        LinearLayout clock = new LinearLayout(this);
        clock.setOrientation(LinearLayout.VERTICAL);
        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(44);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        clock.addView(time);
        dateView = new TextView(this);
        dateView.setTextColor(0xFFA9B4CC);
        dateView.setTextSize(16);
        clock.addView(dateView);
        top.addView(clock, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView pill = new TextView(this);
        pill.setText("Personalizar");
        pill.setTextColor(Color.WHITE);
        pill.setTextSize(14);
        pill.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        pill.setPadding(dp(22), dp(9), dp(22), dp(9));
        pill.setFocusable(true);
        pill.setClickable(true);
        pill.setBackground(pillBgState.newDrawable());
        pill.setOnClickListener(v -> showMainMenu());
        top.addView(pill, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(top, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Zona de filas (scroll vertical solo si hay muchas categorías).
        vscroll = new ScrollView(this);
        vscroll.setVerticalScrollBarEnabled(false);
        vscroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        vscroll.setClipToPadding(false);
        vscroll.setClipChildren(false);
        vscroll.setPadding(0, dp(14), 0, dp(14));
        rowsContainer = new LinearLayout(this);
        rowsContainer.setOrientation(LinearLayout.VERTICAL);
        rowsContainer.setClipChildren(false);
        vscroll.addView(rowsContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(vscroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        TextView hint = new TextView(this);
        hint.setText("OK: abrir   \u00B7   Mantener OK: mover o cambiar de categoría");
        hint.setTextColor(0xFF5E6A87);
        hint.setTextSize(12);
        root.addView(hint);

        return root;
    }

    // ---------- Construcción de filas ----------

    private void rebuild(boolean force) {
        loadState();
        Map<String, Entry> inst = queryInstalled();
        List<Entry> all = orderedEntries(inst);

        String sig = join(order) + "#" + prefs.getString(KEY_CATS, "") + "#"
                + prefs.getString(KEY_ASSIGN, "") + "#" + prefs.getInt(KEY_SIZE, 1)
                + "#" + prefs.getBoolean(KEY_ZOOM, true);
        if (!force && sig.equals(signature) && rowsContainer.getChildCount() > 0) return;
        signature = sig;

        // Si cambió el tamaño, los íconos cacheados ya no sirven.
        int oldIconPx = iconPx;
        applyMetrics();
        if (iconPx != oldIconPx) {
            iconCache.clear();
            loading.clear();
        }

        // Recordar qué app tenía el foco para devolvérselo.
        String keepFocus = pendingFocus;
        pendingFocus = null;
        if (keepFocus == null) {
            View f = getCurrentFocus();
            if (f instanceof TileView) keepFocus = ((TileView) f).pkg;
        }

        rowsContainer.removeAllViews();
        tileViews.clear();
        firstCardRef = null;

        Set<String> catIds = new HashSet<>();
        for (Cat c : cats) catIds.add(c.id);

        for (Cat c : cats) {
            List<Entry> items = new ArrayList<>();
            for (Entry e : all) if (c.id.equals(assign.get(e.pkg))) items.add(e);
            addSection(c.name, items);
        }
        List<Entry> rest = new ArrayList<>();
        for (Entry e : all) {
            String id = assign.get(e.pkg);
            if (id == null || !catIds.contains(id)) rest.add(e);
        }
        if (!rest.isEmpty() || cats.isEmpty()) addSection("Apps", rest);

        View target = null;
        if (keepFocus != null) target = tileViews.get(keepFocus);
        if (target == null) target = firstCardRef;
        if (target != null) target.requestFocus();
    }

    private void addSection(String title, List<Entry> items) {
        LinearLayout section = new LinearLayout(this);
        section.setOrientation(LinearLayout.VERTICAL);
        section.setClipChildren(false);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(0xFFE3E8F6);
        t.setTextSize(16);
        t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        t.setPadding(dp(8), dp(8), 0, dp(2));
        section.addView(t);

        if (items.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("Vacía. Mantené OK sobre una app y elegí «Mover a otra categoría».");
            empty.setTextColor(0xFF6B7794);
            empty.setTextSize(13);
            empty.setPadding(dp(8), dp(6), 0, dp(16));
            section.addView(empty);
        } else {
            HorizontalScrollView hs = new HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
            hs.setClipToPadding(false);
            hs.setClipChildren(false);
            hs.setPadding(dp(8), dp(10), dp(8), dp(4));
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setClipChildren(false);
            for (int i = 0; i < items.size(); i++) {
                TileView tile = makeTile(items.get(i), items, i);
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

    private TileView makeTile(final Entry e, final List<Entry> rowItems, final int index) {
        TileView v = new TileView(e);
        tileViews.put(e.pkg, v);
        if (firstCardRef == null) firstCardRef = v;
        Bitmap cached = iconCache.get(e.pkg);
        if (cached != null) {
            v.icon = cached;
        } else if (loading.add(e.pkg)) {
            loadIconAsync(e);
        }
        v.setOnClickListener(x -> launch(e.cn));
        v.setOnLongClickListener(x -> {
            showAppMenu(e, rowItems, index);
            return true;
        });
        return v;
    }

    /** Una tarjeta = una sola vista dibujada a mano. */
    private class TileView extends View {
        final String pkg;
        final String shownLabel;
        Bitmap icon;
        private final RectF rect = new RectF();

        TileView(Entry e) {
            super(MainActivity.this);
            pkg = e.pkg;
            shownLabel = TextUtils.ellipsize(e.label, tileText, tileW - dp(4),
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
            rect.set(0, 0, tileW, tileH);
            tileFill.setColor(f ? 0x38FFFFFF : 0x1AFFFFFF);
            c.drawRoundRect(rect, cornerPx, cornerPx, tileFill);
            if (f) {
                float h = ringPx / 2f;
                rect.set(h, h, tileW - h, tileH - h);
                c.drawRoundRect(rect, cornerPx, cornerPx, tileRing);
            }
            if (icon != null) {
                c.drawBitmap(icon, (tileW - iconPx) / 2f, (tileH - iconPx) / 2f, iconPaint);
            }
            if (f) {
                c.drawText(shownLabel, tileW / 2f, tileH + labelH * 0.72f, tileText);
            }
        }

        @Override
        protected void onFocusChanged(boolean gain, int direction, Rect previouslyFocusedRect) {
            super.onFocusChanged(gain, direction, previouslyFocusedRect);
            invalidate();
            if (zoomOn) {
                float s = gain ? 1.07f : 1f;
                animate().scaleX(s).scaleY(s).setDuration(100).start();
            }
        }
    }

    // ---------- Íconos ----------

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
                runOnUiThread(() -> {
                    if (size != iconPx) return; // cambió el tamaño mientras cargaba
                    iconCache.put(e.pkg, b);
                    TileView v = tileViews.get(e.pkg);
                    if (v != null) {
                        v.icon = b;
                        v.invalidate();
                    }
                });
            } catch (Throwable ignored) {
                // Si un ícono falla, la tarjeta queda sin ícono pero con nombre.
            }
        });
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

    private void launch(ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ex) {
            toast("No se pudo abrir la app");
        }
    }

    private void showAppMenu(final Entry e, final List<Entry> rowItems, final int index) {
        String[] items = {"Mover a la izquierda", "Mover a la derecha", "Mover a otra categoría"};
        listDialog(e.label, items, (d, which) -> {
            if (which == 0) moveInRow(e, rowItems, index, -1);
            else if (which == 1) moveInRow(e, rowItems, index, 1);
            else chooseCategoryFor(e);
        });
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
        listDialog("Mover " + e.label + " a…", names.toArray(new String[0]), (d, which) -> {
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

    // ---------- Menú Personalizar ----------

    private void showMainMenu() {
        String[] items = {
                "Nueva categoría", "Renombrar categoría", "Subir / bajar categoría",
                "Eliminar categoría",
                "Tamaño de íconos: " + SIZE_NAMES[sizeIdx],
                "Zoom al enfocar: " + (zoomOn ? "sí" : "no (más liviano)"),
                "Fondo", "Ajustes de Android"};
        listDialog("Personalizar", items, (d, which) -> {
            switch (which) {
                case 0:
                    askCategoryName("Nueva categoría", "", name -> {
                        cats.add(new Cat("c" + System.currentTimeMillis(), name));
                        saveCats();
                        rebuild(true);
                    });
                    break;
                case 1:
                    pickCategory("Renombrar…", c -> askCategoryName("Nuevo nombre", c.name, name -> {
                        c.name = name;
                        saveCats();
                        rebuild(true);
                    }));
                    break;
                case 2:
                    pickCategory("Mover categoría…", this::moveCategoryDialog);
                    break;
                case 3:
                    pickCategory("Eliminar…", this::confirmDeleteCategory);
                    break;
                case 4:
                    listDialog("Tamaño de íconos", SIZE_NAMES, (dd, w) -> {
                        prefs.edit().putInt(KEY_SIZE, w).apply();
                        rebuild(true);
                    });
                    break;
                case 5:
                    prefs.edit().putBoolean(KEY_ZOOM, !zoomOn).apply();
                    rebuild(true);
                    break;
                case 6:
                    showBackgroundMenu();
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

    private void pickCategory(String title, final CatCb cb) {
        if (cats.isEmpty()) {
            toast("Todavía no hay categorías. Creá una primero.");
            return;
        }
        String[] names = new String[cats.size()];
        for (int i = 0; i < cats.size(); i++) names[i] = cats.get(i).name;
        listDialog(title, names, (d, which) -> cb.on(cats.get(which)));
    }

    private void moveCategoryDialog(final Cat c) {
        listDialog(c.name, new String[]{"Subir", "Bajar"}, (d, which) -> {
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
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle("¿Eliminar «" + c.name + "»?")
                .setMessage("Sus apps vuelven a la fila \"Apps\". No se desinstala nada.")
                .setPositiveButton("Eliminar", (d, w) -> {
                    cats.remove(c);
                    saveCats();
                    List<String> drop = new ArrayList<>();
                    for (Map.Entry<String, String> en : assign.entrySet()) {
                        if (en.getValue().equals(c.id)) drop.add(en.getKey());
                    }
                    for (String p : drop) assign.remove(p);
                    saveAssign();
                    rebuild(true);
                })
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void askCategoryName(final String title, final String current, final StringCb cb) {
        final List<String> opts = new ArrayList<>();
        Collections.addAll(opts, PRESET_NAMES);
        opts.add("Escribir otro nombre…");
        listDialog(title, opts.toArray(new String[0]), (d, which) -> {
            if (which < PRESET_NAMES.length) {
                cb.on(PRESET_NAMES[which]);
            } else {
                final EditText et = new EditText(this);
                et.setSingleLine(true);
                et.setTextColor(Color.WHITE);
                et.setText(current);
                new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                        .setTitle(title)
                        .setView(et)
                        .setPositiveButton("Guardar", (dd, w) -> {
                            String n = clean(et.getText().toString());
                            if (n.length() > 0) cb.on(n);
                        })
                        .setNegativeButton("Cancelar", null)
                        .show();
            }
        });
    }

    private String clean(String s) {
        s = s.replace('|', ' ').replace('\n', ' ').trim();
        return s.length() > 24 ? s.substring(0, 24) : s;
    }

    private String capitalize(String s) {
        return s.length() == 0 ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // ---------- Fondo ----------

    private void showBackgroundMenu() {
        String[] items = new String[BG_NAMES.length + 1];
        System.arraycopy(BG_NAMES, 0, items, 0, BG_NAMES.length);
        items[BG_NAMES.length] = "Imagen propia (fondo.jpg)";
        listDialog("Fondo", items, (d, which) -> {
            if (which < BG_NAMES.length) {
                prefs.edit().putString(KEY_BG, "p" + which).apply();
                applyBackground();
            } else {
                File f = findBackgroundFile();
                if (f == null) {
                    File dir = getExternalFilesDir(null);
                    String path = dir != null ? dir.getAbsolutePath() : "(no disponible)";
                    new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                            .setTitle("Imagen propia")
                            .setMessage("Copiá una imagen llamada fondo.jpg (o fondo.png) a esta carpeta, "
                                    + "por ejemplo con Files to TV, y después volvé a elegir esta opción:\n\n" + path)
                            .setPositiveButton("Entendido", null)
                            .show();
                } else {
                    prefs.edit().putString(KEY_BG, "img").apply();
                    applyBackground();
                }
            }
        });
    }

    private File findBackgroundFile() {
        File dir = getExternalFilesDir(null);
        if (dir == null) return null;
        String[] names = {"fondo.jpg", "fondo.jpeg", "fondo.png"};
        for (String n : names) {
            File f = new File(dir, n);
            if (f.exists()) return f;
        }
        return null;
    }

    private void applyBackground() {
        String bg = prefs.getString(KEY_BG, "p0");
        if ("img".equals(bg)) {
            final File f = findBackgroundFile();
            if (f != null) {
                DisplayMetrics dm = getResources().getDisplayMetrics();
                int sw = Math.max(dm.widthPixels, dm.heightPixels);
                int sh = Math.min(dm.widthPixels, dm.heightPixels);
                // Tope de 1280 px de ancho: la GPU lo estira y ahorramos RAM.
                final int w = Math.min(sw, 1280);
                final int h = Math.round(w * (float) sh / sw);
                io.execute(() -> {
                    final Bitmap b = decodeBackground(f, w, h);
                    runOnUiThread(() -> {
                        if (b == null) {
                            toast("No se pudo cargar la imagen de fondo");
                            setPresetBackground(0);
                            return;
                        }
                        Bitmap old = bgBitmap;
                        bgBitmap = b;
                        root.setBackground(new BitmapDrawable(getResources(), b));
                        if (old != null) old.recycle();
                    });
                });
                return;
            }
            bg = "p0";
        }
        int idx = 0;
        try {
            idx = Integer.parseInt(bg.substring(1));
        } catch (Exception ignored) {
        }
        setPresetBackground(idx);
    }

    private void setPresetBackground(int idx) {
        if (idx < 0 || idx >= BG_COLORS.length) idx = 0;
        GradientDrawable g = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, BG_COLORS[idx]);
        Bitmap old = bgBitmap;
        root.setBackground(g);
        bgBitmap = null;
        if (old != null) old.recycle();
    }

    /** Decodifica reducida, recorta al tamaño elegido y oscurece para que se lea el texto. */
    private Bitmap decodeBackground(File f, int w, int h) {
        try {
            String path = f.getAbsolutePath();
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, o);
            if (o.outWidth <= 0 || o.outHeight <= 0) return null;
            int sample = 1;
            while (o.outWidth / (sample * 2) >= w && o.outHeight / (sample * 2) >= h) sample *= 2;
            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            o2.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap src = BitmapFactory.decodeFile(path, o2);
            if (src == null) return null;
            float scale = Math.max((float) w / src.getWidth(), (float) h / src.getHeight());
            Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(out);
            Matrix m = new Matrix();
            m.postScale(scale, scale);
            m.postTranslate((w - src.getWidth() * scale) / 2f, (h - src.getHeight() * scale) / 2f);
            c.drawBitmap(src, m, new Paint(Paint.FILTER_BITMAP_FLAG));
            c.drawColor(0x99000000);
            src.recycle();
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    // ---------- Utilidades de UI ----------

    private Drawable pillBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, pill(0x38FFFFFF, true));
        s.addState(new int[]{}, pill(0x1AFFFFFF, false));
        return s;
    }

    private GradientDrawable pill(int fill, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(40));
        if (border) g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    private void listDialog(String title, String[] items, DialogInterface.OnClickListener l) {
        new AlertDialog.Builder(this, AlertDialog.THEME_HOLO_DARK)
                .setTitle(title)
                .setItems(items, l)
                .setNegativeButton("Cancelar", null)
                .show();
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
