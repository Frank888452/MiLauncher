package com.milauncher.tv;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Launcher minimalista: reloj arriba, una sola fila horizontal de apps.
 * OK = abrir. Mantener OK = fijar/desfijar al inicio de la fila.
 */
public class MainActivity extends Activity {

    private static final String PREFS = "launcher";
    private static final String KEY_PINNED = "pinned";

    private static class Entry {
        String pkg;
        String label;
        ComponentName cn;
        ResolveInfo ri;
        boolean pinned;
    }

    private LinearLayout row;
    private HorizontalScrollView scroll;
    private SharedPreferences prefs;
    private String signature = "";
    private String pendingFocus = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        setContentView(buildRoot());
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // Al apretar Home: volver al principio de la fila.
        if (scroll != null) scroll.scrollTo(0, 0);
        if (row != null && row.getChildCount() > 0) row.getChildAt(0).requestFocus();
    }

    @Override
    public void onBackPressed() {
        // Un launcher no se cierra con Atrás.
    }

    // ---------- Interfaz ----------

    private View buildRoot() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B1220, 0xFF1C2744});
        root.setBackground(bg);
        root.setPadding(dp(56), dp(40), dp(56), dp(32));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("HH:mm");
        time.setTextColor(Color.WHITE);
        time.setTextSize(56);
        time.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        root.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE d 'de' MMMM");
        date.setFormat12Hour("EEEE d 'de' MMMM");
        date.setTextColor(0xFFA9B4CC);
        date.setTextSize(20);
        root.addView(date);

        root.addView(spacer(1f));

        TextView title = new TextView(this);
        title.setText("APLICACIONES");
        title.setTextColor(0xFF7F8CAA);
        title.setTextSize(13);
        title.setLetterSpacing(0.15f);
        title.setPadding(dp(4), 0, 0, dp(6));
        root.addView(title);

        scroll = new HorizontalScrollView(this);
        scroll.setHorizontalScrollBarEnabled(false);
        scroll.setClipToPadding(false);
        scroll.setPadding(dp(4), dp(6), dp(4), dp(6));
        row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        scroll.addView(row, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(spacer(0.5f));

        TextView hint = new TextView(this);
        hint.setText("OK: abrir   ·   Mantener OK: fijar al inicio");
        hint.setTextColor(0xFF5E6A87);
        hint.setTextSize(13);
        root.addView(hint);

        return root;
    }

    private View spacer(float weight) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, weight));
        return v;
    }

    private View makeCard(final Entry e) {
        LinearLayout c = baseCard();
        c.setTag(e.pkg);

        ImageView icon = new ImageView(this);
        icon.setImageDrawable(e.ri.loadIcon(getPackageManager()));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        c.addView(icon, new LinearLayout.LayoutParams(dp(72), dp(72)));
        c.addView(label((e.pinned ? "\u2605 " : "") + e.label));

        c.setOnClickListener(v -> launch(e.cn));
        c.setOnLongClickListener(v -> {
            togglePin(e.pkg);
            return true;
        });
        return c;
    }

    private View makeSettingsCard() {
        LinearLayout c = baseCard();
        ImageView icon = new ImageView(this);
        icon.setImageResource(android.R.drawable.ic_menu_preferences);
        icon.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        c.addView(icon, new LinearLayout.LayoutParams(dp(56), dp(72)));
        c.addView(label("Ajustes"));
        c.setOnClickListener(v -> {
            try {
                Intent i = new Intent(Settings.ACTION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(i);
            } catch (Exception ex) {
                Toast.makeText(this, "No se pudo abrir Ajustes", Toast.LENGTH_SHORT).show();
            }
        });
        return c;
    }

    private LinearLayout baseCard() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setGravity(Gravity.CENTER);
        c.setPadding(dp(8), dp(14), dp(8), dp(10));
        c.setFocusable(true);
        c.setClickable(true);
        c.setBackground(cardBackground());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(132), dp(140));
        lp.rightMargin = dp(14);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView label(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(Color.WHITE);
        t.setTextSize(14);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private Drawable cardBackground() {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_focused}, rect(0x33FFFFFF, true));
        s.addState(new int[]{}, rect(0x14FFFFFF, false));
        return s;
    }

    private GradientDrawable rect(int fill, boolean border) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(dp(14));
        if (border) g.setStroke(dp(3), Color.WHITE);
        return g;
    }

    // ---------- Datos ----------

    private List<Entry> loadEntries() {
        PackageManager pm = getPackageManager();
        Set<String> pinned = prefs.getStringSet(KEY_PINNED, new HashSet<String>());
        Map<String, Entry> map = new LinkedHashMap<>();
        collect(pm, map, Intent.CATEGORY_LEANBACK_LAUNCHER, pinned);
        collect(pm, map, Intent.CATEGORY_LAUNCHER, pinned);

        List<Entry> list = new ArrayList<>(map.values());
        Collections.sort(list, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.pinned != b.pinned) return a.pinned ? -1 : 1;
                return a.label.toLowerCase().compareTo(b.label.toLowerCase());
            }
        });
        return list;
    }

    private void collect(PackageManager pm, Map<String, Entry> map, String category, Set<String> pinned) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(category);
        for (ResolveInfo ri : pm.queryIntentActivities(i, 0)) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
            Entry e = new Entry();
            e.pkg = pkg;
            e.ri = ri;
            e.cn = new ComponentName(pkg, ri.activityInfo.name);
            e.label = String.valueOf(ri.loadLabel(pm));
            e.pinned = pinned.contains(pkg);
            map.put(pkg, e);
        }
    }

    private void refresh() {
        List<Entry> list = loadEntries();
        StringBuilder sb = new StringBuilder();
        for (Entry e : list) sb.append(e.pkg).append(e.pinned ? "*" : "").append(';');
        String sig = sb.toString();
        if (sig.equals(signature) && row.getChildCount() > 0) return;
        signature = sig;

        int focusIndex = row.indexOfChild(row.getFocusedChild());
        row.removeAllViews();
        for (Entry e : list) row.addView(makeCard(e));
        row.addView(makeSettingsCard());

        View target = null;
        if (pendingFocus != null) {
            target = row.findViewWithTag(pendingFocus);
            pendingFocus = null;
        }
        if (target == null) {
            int idx = Math.max(0, Math.min(focusIndex, row.getChildCount() - 1));
            target = row.getChildAt(idx);
        }
        target.requestFocus();
    }

    private void togglePin(String pkg) {
        Set<String> cur = new HashSet<>(prefs.getStringSet(KEY_PINNED, new HashSet<String>()));
        if (!cur.remove(pkg)) cur.add(pkg);
        prefs.edit().putStringSet(KEY_PINNED, cur).apply();
        pendingFocus = pkg;
        refresh();
    }

    private void launch(ComponentName cn) {
        try {
            Intent i = new Intent(Intent.ACTION_MAIN);
            i.setComponent(cn);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(i);
        } catch (Exception ex) {
            Toast.makeText(this, "No se pudo abrir la app", Toast.LENGTH_SHORT).show();
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
