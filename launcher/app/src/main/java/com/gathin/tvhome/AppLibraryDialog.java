package com.gathin.tvhome;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * The App Library: every installed app, TV and phone apps alike, on one frosted shelf over Home. OK adds an app
 * that is not on Home yet (or opens one that is); holding OK offers Open, Add to or Remove from Home, and App info.
 * The panel is Home's own glass: a once-blurred snapshot of what is behind it, with the app-colour light that
 * follows focus on Home's tiles. Nothing is captured or blurred again while it is open.
 */
final class AppLibraryDialog {
    private static final float CARD_FOCUS_SCALE = 1.08f;

    private final Activity activity;
    private final View home;
    private final Predicate<String> onHome;
    private final Runnable changed;
    private final LinearLayout panel;
    private final boolean reducedMotion;
    private final boolean animations;
    private final boolean sounds;
    private final boolean rtl;
    private TextView instructions;
    private int totalApps;
    final PopupWindow popup;
    private final float density;
    private List<ResolveInfo> apps = new ArrayList<>();
    private String selectedPackage;
    private int selectedPosition;
    private int selectedOffset;
    private final Map<String, Long> installTimes = new HashMap<>();
    private final Map<String, String> labels = new HashMap<>();
    private final Map<String, Integer> colours = new HashMap<>();
    private long catalogTime;
    private PopupWindow actionPopup;
    private RecyclerView catalog;
    private FrameLayout shelf;
    private GlassView highlight;
    private SpringAnimation spring;
    private float scrollOffset;
    private boolean highlightShown;
    private final int catalogWidth;

    AppLibraryDialog(Activity activity, View home, boolean animations, boolean reducedMotion, boolean sounds,
                     Predicate<String> onHome, Runnable changed, Runnable dismissed) {
        this.activity = activity;
        this.home = home;
        this.animations = animations;
        this.rtl = activity.getResources().getBoolean(R.bool.rtl_app_grid);
        this.reducedMotion = reducedMotion;
        this.sounds = sounds;
        this.onHome = onHome;
        this.changed = changed;
        density = activity.getResources().getDisplayMetrics().density;
        panel = new LinearLayout(activity);
        panel.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18), dp(14), dp(18), dp(16));
        // Until the panel has a place on screen to blur, the plain glass material stands in.
        panel.setBackground(new GlassPanelDrawable(density, 32));
        panel.setClipChildren(false);
        panel.setClipToPadding(false);
        View decor = activity.getWindow().getDecorView();
        catalogWidth = Math.min(dp(880), Math.max(dp(240), decor.getWidth() - dp(96)));
        popup = new PopupWindow(panel, catalogWidth, dp(316), true);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setOutsideTouchable(true);
        popup.setAnimationStyle(0);
        popup.setOnDismissListener(() -> { dismissActions(); stopHighlight(); dismissed.run(); });
    }

    void close() { dismissActions(); popup.dismiss(); }

    void show() {
        refresh();
        popup.showAtLocation(activity.getWindow().getDecorView(), Gravity.CENTER, 0, 0);
        View container = panel.getRootView();
        if (container.getLayoutParams() instanceof WindowManager.LayoutParams) {
            WindowManager.LayoutParams params = (WindowManager.LayoutParams) container.getLayoutParams();
            params.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
            params.dimAmount = 0.45f;
            activity.getWindowManager().updateViewLayout(container, params);
        }
        // Frost the panel once it has its place: blur the bit of Home it covers, then never again.
        panel.post(() -> {
            if (!popup.isShowing() || panel.getWidth() <= 0) return;
            int[] at = new int[2], origin = new int[2];
            panel.getLocationOnScreen(at);
            home.getLocationOnScreen(origin);
            int x = at[0] - origin[0], y = at[1] - origin[1];
            panel.setBackground(new FrostedGlassDrawable(home,
                    new Rect(x, y, x + panel.getWidth(), y + panel.getHeight()), density, 32));
        });
        panel.setAlpha(0f);
        panel.setScaleX(0.96f);
        panel.setScaleY(0.96f);
        panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(animations ? (reducedMotion ? 120 : 220) : 0)
                .setInterpolator(new DecelerateInterpolator(1.6f)).start();
    }

    /** Packages with a leanback entry, from the last refresh. */
    private final java.util.Set<String> tvPackages = new java.util.HashSet<>();

    /** Called by the activity's existing package receiver; an uninstall cannot leave a stale action. */
    void refresh() {
        dismissActions();
        PackageManager pm = activity.getPackageManager();
        Map<String, ResolveInfo> unique = new LinkedHashMap<>();
        tvPackages.clear();
        // Prefer the TV entry when an app declares both categories.
        for (String category : new String[]{Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER}) {
            for (ResolveInfo info : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(category), 0)) {
                if (info.activityInfo == null || !info.activityInfo.exported) continue;
                String pkg = info.activityInfo.packageName;
                if (Intent.CATEGORY_LEANBACK_LAUNCHER.equals(category)) tvPackages.add(pkg);
                if (!pkg.equals(activity.getPackageName()) && !unique.containsKey(pkg)) unique.put(pkg, info);
            }
        }
        totalApps = unique.size();
        apps = new ArrayList<>(unique.values());
        installTimes.clear();
        labels.clear();
        Map<String, Boolean> onHomeNow = new HashMap<>();
        for (ResolveInfo app : apps) {
            String pkg = app.activityInfo.packageName;
            onHomeNow.put(pkg, onHome.test(pkg));
            label(app);
            try { installTimes.put(pkg, pm.getPackageInfo(pkg, 0).firstInstallTime); }
            catch (PackageManager.NameNotFoundException | RuntimeException unavailable) { installTimes.put(pkg, 0L); }
        }
        catalogTime = System.currentTimeMillis();
        Collator collator = Collator.getInstance();
        apps.sort((a, b) -> {
            String pa = a.activityInfo.packageName, pb = b.activityInfo.packageName;
            int priority = LibraryApps.comparePriority(onHomeNow.get(pa), installTimes.get(pa),
                    onHomeNow.get(pb), installTimes.get(pb), catalogTime);
            if (priority != 0) return priority;
            int byLabel = collator.compare(label(a), label(b));
            return byLabel != 0 ? byLabel : pa.compareTo(pb);
        });
        showCatalog();
    }

    private String label(ResolveInfo app) {
        String pkg = app.activityInfo.packageName;
        String cached = labels.get(pkg);
        if (cached != null) return cached;
        String value;
        try { value = Cards.cleanLabel(String.valueOf(app.loadLabel(activity.getPackageManager())), pkg); }
        catch (RuntimeException unavailable) { value = pkg; }
        labels.put(pkg, value);
        return value;
    }

    private void stopHighlight() {
        if (spring != null) spring.cancel();
        if (catalog != null) catalog.stopScroll();
        catalog = null;
        highlightShown = false;
    }

    /** Puts Home's focus light around the focused card, in the app's own colour, and glides it there. */
    private void highlight(View card, int colour, boolean animate) {
        if (highlight == null || card.getParent() == null) return;
        Rect rect = new Rect(0, 0, card.getWidth(), card.getHeight());
        shelf.offsetDescendantRectToMyCoords(card, rect);
        float pad = GlassView.HALO_INSET_DP * density;
        float w = card.getWidth() * CARD_FOCUS_SCALE + 2 * pad, h = card.getHeight() * CARD_FOCUS_SCALE + 2 * pad;
        ViewGroup.LayoutParams lp = highlight.getLayoutParams();
        if (lp.width != (int) w || lp.height != (int) h) {
            lp.width = (int) w;
            lp.height = (int) h;
            highlight.setLayoutParams(lp);
        }
        highlight.setTint(colour);
        highlight.setTranslationY(rect.exactCenterY() - h / 2f);
        float x = rect.exactCenterX() - w / 2f + scrollOffset;
        if (!highlightShown || !animate || !animations) {
            spring.cancel();
            highlight.setTranslationX(x - scrollOffset);
        } else spring.animateToFinalPosition(x);
        highlightShown = true;
        highlight.animate().alpha(1f).setDuration(animations ? 160 : 0).start();
    }

    private void resize(int width, int height) {
        popup.setWidth(width);
        popup.setHeight(height);
        if (popup.isShowing()) popup.update(width, height);
    }

    private void showCatalog() {
        stopHighlight();
        resize(catalogWidth, dp(316));
        panel.removeAllViews();
        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(10), 0, dp(10), 0);
        TextView title = text(activity.getString(R.string.app_library), 26, Color.WHITE, R.font.outfit_regular);
        header.addView(title, new LinearLayout.LayoutParams(0, dp(40), 1));
        title.setGravity(Gravity.CENTER_VERTICAL);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        TextView count = text(activity.getResources().getQuantityString(
                R.plurals.library_app_count, totalApps, totalApps), 13, 0x8CFFFFFF, R.font.outfit_medium);
        count.setLetterSpacing(0.08f);
        count.setAllCaps(true);
        count.setSingleLine(true);
        header.addView(count, new LinearLayout.LayoutParams(-2, -2));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(44)));
        instructions = text(activity.getString(R.string.library_hint_add), 13, 0x99FFFFFF, R.font.outfit_regular);
        instructions.setSingleLine(true);
        instructions.setEllipsize(TextUtils.TruncateAt.END);
        instructions.setPadding(dp(10), dp(2), dp(10), dp(4));
        panel.addView(instructions, new LinearLayout.LayoutParams(-1, dp(26)));
        panel.setFocusableInTouchMode(apps.isEmpty());
        if (apps.isEmpty()) {
            instructions.setVisibility(View.GONE);
            TextView empty = text(activity.getString(R.string.library_empty), 18, 0xD9FFFFFF, R.font.outfit_regular);
            empty.setPadding(dp(10), dp(12), dp(10), dp(8));
            panel.addView(empty, new LinearLayout.LayoutParams(-1, -2));
            panel.requestFocus();
            return;
        }
        shelf = new FrameLayout(activity);
        // The light uses physical x coordinates; only the catalogue mirrors its item order.
        shelf.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
        shelf.setClipChildren(false);
        shelf.setClipToPadding(false);
        highlight = new GlassView(activity, null);
        highlight.setRadius(dp(24) * CARD_FOCUS_SCALE);
        highlight.setAlpha(0f);
        highlight.setFocusable(false);
        highlight.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        // Behind the cards, like Home's light behind its tiles: the artwork itself stays untouched.
        shelf.addView(highlight, new FrameLayout.LayoutParams(dp(160), dp(196)));
        RecyclerView list = new RecyclerView(activity);
        list.setLayoutDirection(rtl ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        catalog = list;
        list.setLayoutManager(new LinearLayoutManager(activity, RecyclerView.HORIZONTAL, false) {
            @Override protected boolean isLayoutRTL() { return rtl; }
        });
        list.setItemAnimator(null);
        list.setOverScrollMode(View.OVER_SCROLL_NEVER);
        list.setClipToPadding(false);
        list.setClipChildren(false);
        list.setPadding(dp(16), dp(14), dp(16), dp(14));
        list.setAdapter(new Catalog());
        shelf.addView(list, new FrameLayout.LayoutParams(-1, -1));
        scrollOffset = 0;
        spring = new SpringAnimation(highlight, new FloatPropertyCompat<View>("libraryScrollX") {
            @Override public float getValue(View view) { return view.getTranslationX() + scrollOffset; }
            @Override public void setValue(View view, float value) { view.setTranslationX(value - scrollOffset); }
        });
        spring.setSpring(new SpringForce().setStiffness(reducedMotion ? 1400f : 320f)
                .setDampingRatio(reducedMotion ? 0.95f : 0.72f));
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                scrollOffset += dx;
                highlight.setTranslationX(highlight.getTranslationX() - dx);
            }
        });
        panel.addView(shelf, new LinearLayout.LayoutParams(-1, 0, 1));
        int position = Math.min(selectedPosition, apps.size() - 1);
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).activityInfo.packageName.equals(selectedPackage)) { position = i; break; }
        }
        ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(position, selectedOffset);
        final int target = position;
        list.post(() -> {
            if (catalog != list || !popup.isShowing()) return;
            RecyclerView.ViewHolder holder = list.findViewHolderForAdapterPosition(target);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void dismissActions() {
        PopupWindow menu = actionPopup;
        actionPopup = null;
        if (menu != null) menu.dismiss();
    }

    private void restoreCardFocus() {
        if (catalog == null || !popup.isShowing()) return;
        catalog.post(() -> {
            if (catalog == null || !popup.isShowing() || actionPopup != null) return;
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) holder.itemView.requestFocus();
        });
    }

    private void changeHome(ResolveInfo app, boolean add) {
        if (!LibraryApps.save(activity, app.activityInfo.packageName, add)) {
            Toast.makeText(activity, R.string.library_save_failed, Toast.LENGTH_LONG).show();
            return;
        }
        selectedOffset = 0;
        if (add && catalog != null) {
            RecyclerView.ViewHolder holder = catalog.findViewHolderForAdapterPosition(selectedPosition);
            if (holder != null) {
                View card = holder.itemView;
                RecyclerView.LayoutParams params = (RecyclerView.LayoutParams) card.getLayoutParams();
                selectedOffset = rtl
                        ? catalog.getWidth() - catalog.getPaddingRight()
                            - catalog.getLayoutManager().getDecoratedRight(card) - params.rightMargin
                        : catalog.getLayoutManager().getDecoratedLeft(card)
                            - params.leftMargin - catalog.getPaddingLeft();
            }
            // Advance through missing shortcuts instead of following the newly checked app
            // into the Home group. Preserve its screen slot when the catalogue is rebuilt.
            int next = LibraryApps.nextFocus(selectedPosition, apps.size(),
                    index -> !onHome.test(apps.get(index).activityInfo.packageName));
            if (next >= 0) {
                selectedPackage = apps.get(next).activityInfo.packageName;
                selectedPosition = next;
            }
        }
        dismissActions();
        changed.run();
        refresh();
    }

    private void open(ResolveInfo app) {
        close();
        Intent intent = new Intent(Intent.ACTION_MAIN).setComponent(
                new ComponentName(app.activityInfo.packageName, app.activityInfo.name));
        // TV apps keep the leanback launch intent, so an app already running resumes instead of restarting.
        if (tvPackages.contains(app.activityInfo.packageName)) intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try { activity.startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
        }
    }

    /** Hold OK on a card: Home's frosted app menu beside it, with the same rows and glyphs as a tile's. */
    private void showActions(ResolveInfo app, View anchor) {
        dismissActions();
        selectedPackage = app.activityInfo.packageName;
        String pkg = selectedPackage;
        if (spring != null) spring.cancel();
        LinearLayout rows = new LinearLayout(activity);
        rows.setOrientation(LinearLayout.VERTICAL);
        rows.setPadding(dp(8), dp(8), dp(8), dp(8));
        TextView name = text(label(app), 13, 0xCFFFFFFF, R.font.outfit_medium);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        name.setPadding(dp(14), dp(8), dp(14), dp(12));
        rows.addView(name);
        int width = dp(264);
        PopupWindow menu = new PopupWindow(rows, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        actionPopup = menu;
        menu.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        menu.setOutsideTouchable(true);
        menu.setAnimationStyle(0);
        menu.setElevation(dp(16));
        menu.setOnDismissListener(() -> {
            if (actionPopup == menu) actionPopup = null;
            restoreCardFocus();
        });
        boolean onHomeNow = onHome.test(pkg);
        TextView first = addAction(rows, R.string.library_open, R.drawable.ic_quick_apps, () -> open(app));
        addAction(rows, onHomeNow ? R.string.library_remove_shortcut : R.string.library_add_home,
                onHomeNow ? R.drawable.ic_quick_close : R.drawable.ic_home_settings, () -> changeHome(app, !onHomeNow));
        addAction(rows, R.string.library_app_info, R.drawable.ic_settings, () -> {
            close();
            try {
                activity.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (ActivityNotFoundException | SecurityException e) {
                Toast.makeText(activity, activity.getString(R.string.cant_open, label(app)), Toast.LENGTH_SHORT).show();
            }
        });
        rows.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.UNSPECIFIED);
        int height = rows.getMeasuredHeight();
        menu.setHeight(height);
        // Beside the card, on whichever side has room, like a tile's menu on Home. Screen coordinates: the
        // card lives in the library's own window, not in Home's.
        int[] at = new int[2];
        anchor.getLocationOnScreen(at);
        Rect card = new Rect(at[0], at[1], at[0] + Math.round(anchor.getWidth() * anchor.getScaleX()),
                at[1] + Math.round(anchor.getHeight() * anchor.getScaleY()));
        int[] origin = new int[2], window = new int[2];
        home.getLocationOnScreen(origin);
        home.getLocationInWindow(window);
        int inset = dp(16), gap = dp(12);
        boolean right = origin[0] + home.getWidth() - inset - card.right - gap >= width;
        int x = right ? card.right + gap : Math.max(origin[0] + inset, card.left - gap - width);
        int y = Math.max(origin[1] + inset, Math.min(card.centerY() - height / 2,
                origin[1] + home.getHeight() - inset - height));
        rows.setBackground(new FrostedGlassDrawable(layered(),
                new Rect(x - origin[0], y - origin[1], x - origin[0] + width, y - origin[1] + height), density, 26));
        menu.showAtLocation(activity.getWindow().getDecorView(), Gravity.TOP | Gravity.LEFT,
                x - origin[0] + window[0], y - origin[1] + window[1]);
        rows.setAlpha(0f);
        rows.setPivotX(right ? 0 : width);
        rows.setPivotY(height / 2f);
        rows.setScaleX(.94f);
        rows.setScaleY(.94f);
        rows.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(animations ? 180 : 0)
                .setInterpolator(new DecelerateInterpolator()).start();
        first.requestFocus();
    }

    /**
     * Home with the library panel over it, as one view to capture: the menu's glass then blurs what is really
     * behind it, the shelf included, not only Home under the library's window.
     */
    private View layered() {
        return new View(activity) {
            @Override protected void onDraw(Canvas canvas) {
                home.draw(canvas);
                int[] origin = new int[2], at = new int[2];
                home.getLocationOnScreen(origin);
                panel.getLocationOnScreen(at);
                int save = canvas.save();
                canvas.translate(at[0] - origin[0], at[1] - origin[1]);
                panel.draw(canvas);
                canvas.restoreToCount(save);
            }
        };
    }

    private TextView addAction(LinearLayout rows, int title, int icon, Runnable action) {
        TextView row = text(activity.getString(title), 16, Color.WHITE, R.font.outfit_medium);
        row.setTextColor(activity.getResources().getColorStateList(R.color.app_action_text, activity.getTheme()));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinHeight(dp(50));
        row.setPadding(dp(14), dp(10), dp(14), dp(10));
        Drawable glyph = activity.getDrawable(icon).mutate();
        glyph.setBounds(0, 0, dp(20), dp(20));
        row.setCompoundDrawablesRelative(glyph, null, null, null);
        row.setCompoundDrawablePadding(dp(12));
        row.setCompoundDrawableTintList(activity.getResources().getColorStateList(R.color.app_action_text, activity.getTheme()));
        row.setBackgroundResource(R.drawable.glass_menu_row);
        row.setFocusable(true);
        row.setClickable(true);
        row.setSoundEffectsEnabled(sounds);
        row.setOnClickListener(v -> action.run());
        // The release of the long press that opened this menu must not activate its first row.
        final boolean[] pressed = {false};
        row.setOnKeyListener((v, key, event) -> {
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER || key == KeyEvent.KEYCODE_NUMPAD_ENTER) {
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    if (event.getRepeatCount() == 0) pressed[0] = true;
                    return true;
                }
                boolean activate = pressed[0] && !event.isCanceled();
                pressed[0] = false;
                if (activate) action.run();
                return true;
            }
            int index = rows.indexOfChild(v);
            return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_LEFT
                    || key == KeyEvent.KEYCODE_DPAD_RIGHT || key == KeyEvent.KEYCODE_DPAD_UP && index == 1
                    || key == KeyEvent.KEYCODE_DPAD_DOWN && index == rows.getChildCount() - 1);
        });
        rows.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private TextView text(String value, int size, int color, int font) {
        TextView view = new TextView(activity);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setTypeface(activity.getResources().getFont(font));
        view.setLetterSpacing(0.01f);
        view.setIncludeFontPadding(false);
        return view;
    }

    private int dp(int value) { return Math.round(value * density); }

    /** The focus light's colour for an app: the vivid colour of its icon, as Home samples it for a tile. */
    private int colour(ResolveInfo app, Drawable icon) {
        String pkg = app.activityInfo.packageName;
        Integer cached = colours.get(pkg);
        if (cached != null) return cached;
        int value = AppAdapter.vividColor(icon);
        colours.put(pkg, value);
        return value;
    }

    private final class Catalog extends RecyclerView.Adapter<Row> {
        @NonNull @Override public Row onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            FrameLayout row = new FrameLayout(activity);
            RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(dp(136), dp(172));
            params.setMargins(dp(8), dp(2), dp(8), dp(2));
            row.setLayoutParams(params);
            row.setPadding(dp(10), dp(16), dp(10), dp(10));
            // The badge sits in the corner, partly over the padding.
            row.setClipToPadding(false);
            row.setFocusableInTouchMode(true);
            row.setClickable(true);
            row.setSoundEffectsEnabled(sounds);
            row.setDefaultFocusHighlightEnabled(false);
            row.setBackground(new GlassPanelDrawable(density, 24));
            LinearLayout content = new LinearLayout(activity);
            content.setOrientation(LinearLayout.VERTICAL);
            content.setGravity(Gravity.CENTER_HORIZONTAL);
            row.addView(content, new FrameLayout.LayoutParams(-1, -1));
            ImageView icon = new ImageView(activity);
            icon.setId(R.id.icon);
            icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            content.addView(icon, new LinearLayout.LayoutParams(dp(64), dp(64)));
            TextView name = text("", 15, Color.WHITE, R.font.outfit_medium);
            name.setId(R.id.title);
            name.setGravity(Gravity.CENTER);
            name.setMaxLines(2);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(-1, dp(40));
            nameParams.topMargin = dp(10);
            content.addView(name, nameParams);
            TextView status = text("", 11, 0x99FFFFFF, R.font.outfit_medium);
            status.setId(R.id.status);
            status.setGravity(Gravity.CENTER);
            status.setMaxLines(2);
            status.setLetterSpacing(0.04f);
            content.addView(status, new LinearLayout.LayoutParams(-1, -2));
            View check = new View(activity);
            check.setId(R.id.dot);
            FrameLayout.LayoutParams badge = new FrameLayout.LayoutParams(dp(18), dp(18), Gravity.TOP | Gravity.END);
            badge.setMargins(0, dp(-6), dp(-2), 0);
            row.addView(check, badge);
            return new Row(row);
        }

        @Override public void onBindViewHolder(@NonNull Row holder, int position) {
            ResolveInfo app = apps.get(position);
            String pkg = app.activityInfo.packageName;
            ImageView icon = holder.itemView.findViewById(R.id.icon);
            Drawable artwork;
            try { artwork = app.loadIcon(activity.getPackageManager()); }
            catch (RuntimeException unavailable) { artwork = activity.getDrawable(R.drawable.ic_apps); }
            icon.setImageDrawable(artwork);
            final Drawable sampled = artwork;
            TextView name = holder.itemView.findViewById(R.id.title);
            name.setText(label(app));
            name.setEllipsize(TextUtils.TruncateAt.END);
            TextView status = holder.itemView.findViewById(R.id.status);
            boolean onHomeNow = onHome.test(pkg);
            boolean recent = LibraryApps.recent(installTimes.getOrDefault(pkg, 0L), catalogTime);
            status.setText(onHomeNow ? R.string.library_on_home : recent ? R.string.library_recent : R.string.library_add_home);
            status.setVisibility(View.VISIBLE);
            View check = holder.itemView.findViewById(R.id.dot);
            check.getLayoutParams().width = dp(onHomeNow ? 18 : 8);
            check.getLayoutParams().height = dp(onHomeNow ? 18 : 8);
            if (onHomeNow) check.setBackgroundResource(R.drawable.library_check);
            else check.setBackground(activity.getDrawable(R.drawable.library_new_dot));
            check.setVisibility(onHomeNow || recent ? View.VISIBLE : View.GONE);
            View card = holder.itemView;
            boolean focused = card.isFocused();
            card.setScaleX(focused ? CARD_FOCUS_SCALE : 1f);
            card.setScaleY(focused ? CARD_FOCUS_SCALE : 1f);
            card.setOnClickListener(v -> {
                if (onHome.test(pkg)) open(app);
                else changeHome(app, true);
            });
            card.setOnLongClickListener(v -> { showActions(app, v); return true; });
            card.setOnFocusChangeListener((v, gained) -> {
                float scale = gained ? CARD_FOCUS_SCALE : 1f;
                v.animate().cancel();
                if (animations) {
                    v.animate().scaleX(scale).scaleY(scale).translationZ(gained ? 4 * density : 0f)
                            .setDuration(reducedMotion ? 100 : 200)
                            .setInterpolator(new DecelerateInterpolator()).start();
                } else {
                    v.setScaleX(scale);
                    v.setScaleY(scale);
                }
                if (gained) {
                    selectedPackage = pkg;
                    instructions.setText(onHome.test(pkg)
                            ? R.string.library_hint_open : R.string.library_hint_add);
                    int colour = colour(app, sampled);
                    v.post(() -> { if (v.hasFocus() && catalog != null) highlight(v, colour, true); });
                    int index = holder.getBindingAdapterPosition();
                    if (index != RecyclerView.NO_POSITION) selectedPosition = index;
                }
            });
            card.setOnKeyListener((v, key, event) -> {
                int index = holder.getBindingAdapterPosition();
                return event.getAction() == KeyEvent.ACTION_DOWN && (key == KeyEvent.KEYCODE_DPAD_UP
                        || key == KeyEvent.KEYCODE_DPAD_DOWN
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_RIGHT : key == KeyEvent.KEYCODE_DPAD_LEFT) && index == 0
                        || (rtl ? key == KeyEvent.KEYCODE_DPAD_LEFT : key == KeyEvent.KEYCODE_DPAD_RIGHT) && index == apps.size() - 1);
            });
        }

        @Override public int getItemCount() { return apps.size(); }
    }

    private static final class Row extends RecyclerView.ViewHolder {
        Row(View view) { super(view); }
    }
}
