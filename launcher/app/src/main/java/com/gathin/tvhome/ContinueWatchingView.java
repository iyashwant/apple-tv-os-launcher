package com.gathin.tvhome;

import android.app.Activity;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.ActivityNotFoundException;
import android.database.ContentObserver;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.CancellationSignal;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Home's remote-friendly, optional shelf. It never runs or observes the provider while disabled/paused. */
public final class ContinueWatchingView extends LinearLayout {
    static final int HEIGHT_DP = 228;
    interface Host {
        void changed();
        void focused();
        void leave(int keyCode);
    }
    private final RecyclerView list;
    private final ShelfAdapter adapter = new ShelfAdapter();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ContentObserver observer = new ContentObserver(handler) {
        @Override public void onChange(boolean selfChange) { handler.removeCallbacks(reload); handler.postDelayed(reload, 600); }
    };
    private final Runnable reload = this::load;
    private List<ContinueWatchingRepository.Item> items = Collections.emptyList();
    // Keep outgoing artwork attached until it has faded away; logical items always follow dock focus.
    private List<ContinueWatchingRepository.Item> displayedItems = Collections.emptyList();
    private String displayedPackage;
    private List<ContinueWatchingRepository.Item> allItems = Collections.emptyList();
    private String focusedPackage;
    private final java.util.Map<String, String> appSelections = new java.util.HashMap<>();
    private List<String> hidden = Collections.emptyList();
    private Host host;
    private boolean active, observing, sounds, animations;
    private int generation, selected, focusGeneration;
    private String selectedKey;
    private CancellationSignal cancellation;
    private static final int STILL = 0, ENTER = 1, EXIT = 2, SWAP = 3;
    private int motionPhase;
    private boolean presented;
    private ValueAnimator rowAnimator;

    public ContinueWatchingView(Context context, AttributeSet attrs) {
        super(context, attrs); setOrientation(VERTICAL); setClipChildren(false); setClipToPadding(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        TextView title = new TextView(context); title.setText(R.string.continue_watching); title.setTextColor(Color.WHITE);
        title.setTextSize(22); title.setTypeface(getResources().getFont(R.font.outfit_medium));
        title.setPadding(dp(12), 0, 0, 0); addView(title, new LayoutParams(-1, dp(30)));
        list = new RecyclerView(context); list.setLayoutManager(new LinearLayoutManager(context, RecyclerView.HORIZONTAL, false));
        adapter.setHasStableIds(true);
        list.setAdapter(adapter); list.setItemAnimator(null); list.setHasFixedSize(true); list.setPreserveFocusAfterLayout(false);
        list.setPadding(dp(12), dp(8), dp(12), dp(8)); list.setClipToPadding(false); list.setClipChildren(false);
        list.setOverScrollMode(OVER_SCROLL_NEVER); list.setItemViewCacheSize(2);
        addView(list, new LayoutParams(-1, 0, 1)); setAlpha(0f); setVisibility(GONE);
    }
    void bind(Host host, Config config) { this.host = host; hidden = config.hiddenApps; sounds = config.navigationSounds; animations = config.animations && !config.reducedMotion; }
    boolean hasItems() { return !items.isEmpty(); }
    List<ContinueWatchingRepository.Item> items() { return items; }
    String focusedPackage() { return focusedPackage; }
    void setFocusedPackage(String pkg) {
        if (java.util.Objects.equals(focusedPackage, pkg)) return;
        focusGeneration++;
        if (focusedPackage != null && selectedKey != null) appSelections.put(focusedPackage, selectedKey);
        focusedPackage = pkg; selectedKey = appSelections.get(pkg);
        showItems(allItems);
    }
    void start(boolean enabled) {
        active = enabled && ContinueWatchingRepository.permitted(getContext());
        if (!active) { stop(); showItems(Collections.emptyList()); return; }
        if (!observing) try {
            getContext().getContentResolver().registerContentObserver(ContinueWatchingRepository.WATCH_NEXT, true, observer);
            observing = true;
            getContext().getContentResolver().registerContentObserver(ContinueWatchingRepository.PREVIEW, true, observer); observing = true;
        } catch (RuntimeException ignored) { /* Home resume still refreshes on firmware without notifications. */ }
        load();
    }
    void stop() {
        active = false; generation++; handler.removeCallbacks(reload);
        if (cancellation != null) cancellation.cancel();
        if (observing) { getContext().getContentResolver().unregisterContentObserver(observer); observing = false; }
        present(false, false);
    }
    void close() { stop(); worker.shutdownNow(); }
    private void load() {
        if (!active) return;
        int token = ++generation; if (cancellation != null) cancellation.cancel();
        CancellationSignal signal = new CancellationSignal(); cancellation = signal;
        Context app = getContext().getApplicationContext(); List<String> excluded = new ArrayList<>(hidden);
        worker.execute(() -> {
            ContinueWatchingRepository.Result result = ContinueWatchingRepository.loadForHome(app, excluded, signal);
            handler.post(() -> {
                if (active && token == generation) showItems(result.failed ? allItems : result.items);
            });
        });
    }
    private void showItems(List<ContinueWatchingRepository.Item> value) {
        boolean hadFocus = hasFocus(); String focusedKey = selectedKey;
        allItems = value;
        items = ContinueWatchingRepository.forPackage(allItems, focusedPackage); selected = 0;
        for (int i = 0; i < items.size(); i++) if (items.get(i).key.equals(focusedKey)) { selected = i; break; }
        if (host != null) host.changed();
        if (hadFocus) {
            if (items.isEmpty()) { if (host != null) host.leave(KeyEvent.KEYCODE_BACK); }
            else focus(selected);
        }
    }
    /** Idempotent: Home calls this during layout and dock motion, without restarting an in-flight fade. */
    void present(boolean visible, boolean animate) {
        visible = visible && active && !items.isEmpty();
        presented = visible;
        if (!animate || !animations || !ValueAnimator.areAnimatorsEnabled() || !isAttachedToWindow()) {
            cancelRowMotion(); setLayerType(LAYER_TYPE_NONE, null);
            if (visible) { displayCurrentItems(); setVisibility(VISIBLE); setAlpha(1f); setTranslationY(0f); }
            else finishHidden(false);
            return;
        }
        if (!visible) {
            focusGeneration++;
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            if (getVisibility() != VISIBLE) return;
            if (motionPhase != EXIT) animateRow(EXIT, 0f, dp(10), 200, () -> finishHidden(true));
            return;
        }
        if (!java.util.Objects.equals(displayedPackage, focusedPackage) && !displayedItems.isEmpty()
                && getVisibility() == VISIBLE && getAlpha() > .01f) {
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
            // A second dock move during the fade uses the latest target when the old artwork is gone.
            if (motionPhase != SWAP) animateRow(SWAP, 0f, dp(8), 120, () -> {
                displayCurrentItems(); setTranslationY(dp(12));
                animateRow(ENTER, 1f, 0f, 280, null);
            });
            return;
        }
        displayCurrentItems();
        if (getVisibility() != VISIBLE) { setAlpha(0f); setTranslationY(dp(12)); setVisibility(VISIBLE); }
        if (motionPhase == ENTER || (motionPhase == STILL && getAlpha() == 1f)) return;
        // Reversals continue from the actual alpha/position instead of jumping to a fixed start frame.
        animateRow(ENTER, 1f, 0f, 280, null);
    }
    private void displayCurrentItems() {
        if (displayedItems == items) { setDescendantFocusability(FOCUS_AFTER_DESCENDANTS); return; }
        boolean changedApp = !java.util.Objects.equals(displayedPackage, focusedPackage);
        displayedItems = items; displayedPackage = focusedPackage;
        adapter.notifyDataSetChanged(); setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
        if (changedApp) ((LinearLayoutManager) list.getLayoutManager()).scrollToPositionWithOffset(selected, 0);
    }
    private void animateRow(int phase, float alpha, float y, long duration, Runnable end) {
        cancelRowMotion(); motionPhase = phase;
        // Cache this bounded strip during motion so the TV blends one texture instead of every poster/text layer.
        setLayerType(LAYER_TYPE_HARDWARE, null);
        float fromAlpha = getAlpha(), fromY = getTranslationY();
        ValueAnimator motion = ValueAnimator.ofFloat(0f, 1f); rowAnimator = motion;
        motion.setDuration(Math.max(80, Math.round(duration * Math.abs(alpha - fromAlpha))));
        motion.setInterpolator(new PathInterpolator(.22f, .0f, .2f, 1f));
        motion.addUpdateListener(value -> {
            float fraction = (float) value.getAnimatedValue();
            setAlpha(fromAlpha + (alpha - fromAlpha) * fraction);
            setTranslationY(fromY + (y - fromY) * fraction);
        });
        motion.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                rowAnimator = null; motionPhase = STILL;
                if (end != null) end.run();
                if (rowAnimator == null) setLayerType(LAYER_TYPE_NONE, null);
            }
        });
        motion.start();
    }
    private void cancelRowMotion() {
        if (rowAnimator != null) {
            rowAnimator.removeAllListeners(); rowAnimator.removeAllUpdateListeners(); rowAnimator.cancel(); rowAnimator = null;
        }
        motionPhase = STILL;
    }
    private void finishHidden(boolean notifyHost) {
        focusGeneration++;
        boolean wasVisible = getVisibility() == VISIBLE;
        setAlpha(0f); setTranslationY(dp(12)); setVisibility(GONE);
        setLayerType(LAYER_TYPE_NONE, null);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        if (!displayedItems.isEmpty()) { displayedItems = Collections.emptyList(); displayedPackage = null; adapter.notifyDataSetChanged(); }
        // Immediate hides are already part of the host's layout/transition. Re-entering it here can
        // snap the dock to the expanded position before its own slide has even been constructed.
        if (notifyHost && wasVisible && host != null) host.changed();
    }
    boolean focusRemembered() { if (!presented || items.isEmpty()) return false; focus(Math.min(selected, items.size() - 1)); return true; }
    private void focus(int index) {
        selected = Math.max(0, Math.min(index, items.size() - 1));
        selectedKey = items.get(selected).key;
        int token = ++focusGeneration;
        list.scrollToPosition(selected); list.post(() -> requestCard(token, 0));
    }
    private void requestCard(int token, int attempt) {
        if (token != focusGeneration || !active || !presented || !isShown() || items.isEmpty()) return;
        RecyclerView.ViewHolder holder = java.util.Objects.equals(displayedPackage, focusedPackage)
                ? list.findViewHolderForAdapterPosition(selected) : null;
        if (holder != null && holder.itemView.requestFocusFromTouch()) return;
        if (attempt < 12) list.postOnAnimation(() -> requestCard(token, attempt + 1));
    }
    boolean handle(KeyEvent event) {
        if (!hasFocus()) return false;
        int code = event.getKeyCode();
        if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_DOWN || code == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0 && host != null) { focusGeneration++; host.leave(code); }
            return true;
        }
        if (code == KeyEvent.KEYCODE_DPAD_LEFT || code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                int target = Math.max(0, Math.min(items.size() - 1, selected + (code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1)));
                if (target != selected) { focus(target); if (sounds) playSoundEffect(android.view.SoundEffectConstants.getContantForFocusDirection(code == KeyEvent.KEYCODE_DPAD_RIGHT ? FOCUS_RIGHT : FOCUS_LEFT)); }
            }
            return true;
        }
        return false;
    }
    private void launch(ContinueWatchingRepository.Item item) {
        if (!presented || !item.pkg.equals(focusedPackage) || items.stream().noneMatch(i -> i.key.equals(item.key))) return;
        android.content.Intent intent = ContinueWatchingRepository.resumeIntent(getContext(), item.pkg, item.intent.toUri(android.content.Intent.URI_INTENT_SCHEME));
        if (intent == null) { Toast.makeText(getContext(), R.string.continue_unavailable, Toast.LENGTH_SHORT).show(); load(); return; }
        try { ((Activity) getContext()).startActivity(intent); }
        catch (ActivityNotFoundException | SecurityException e) { Toast.makeText(getContext(), R.string.continue_unavailable, Toast.LENGTH_SHORT).show(); }
    }

    private final class ShelfAdapter extends RecyclerView.Adapter<Card> {
        private final java.util.Map<String, Long> ids = new java.util.HashMap<>();
        @Override public int getItemCount() { return displayedItems.size(); }
        @Override public long getItemId(int position) { return ids.computeIfAbsent(displayedItems.get(position).key, key -> (long) ids.size()); }
        @NonNull @Override public Card onCreateViewHolder(@NonNull ViewGroup parent, int type) { return new Card(); }
        @Override public void onBindViewHolder(@NonNull Card card, int position) {
            ContinueWatchingRepository.Item item = displayedItems.get(position); card.key = item.key;
            card.title.setText(item.title); card.subtitle.setText(item.episode.isEmpty() ? item.appName : item.appName + " · " + item.episode);
            card.remaining.setText(getContext().getString(R.string.continue_remaining, Math.max(1, (item.duration - item.position) / 60_000)));
            card.resize();
            card.progress.setProgress(item.progress()); card.art.setImageDrawable(null); card.art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            try { card.art.setImageDrawable(getContext().getPackageManager().getApplicationIcon(item.pkg)); card.art.setScaleType(ImageView.ScaleType.FIT_CENTER); card.art.setPadding(dp(28), dp(12), dp(28), dp(12)); }
            catch (Exception ignored) { }
            try { card.badge.setImageDrawable(getContext().getPackageManager().getApplicationIcon(item.pkg)); } catch (Exception ignored) { card.badge.setImageDrawable(null); }
            card.itemView.setContentDescription(item.title + ", " + item.appName + ", " + getContext().getString(R.string.continue_remaining, Math.max(1, (item.duration - item.position) / 60_000)));
            card.itemView.setOnClickListener(v -> launch(item)); card.itemView.setSoundEffectsEnabled(sounds);
            ContinueWatchingArtwork.load(getContext(), item, bitmap -> {
                if (!item.key.equals(card.key) || bitmap == null) return;
                card.art.setPadding(0, 0, 0, 0); card.art.setScaleType(ImageView.ScaleType.CENTER_CROP); card.art.setImageBitmap(bitmap);
            });
        }
        @Override public void onViewRecycled(@NonNull Card card) { card.key = null; card.art.setImageDrawable(null); }
    }
    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (list == null || (w == oldw && h == oldh)) return;
        list.post(() -> {
            for (int i = 0; i < list.getChildCount(); i++) {
                RecyclerView.ViewHolder holder = list.getChildViewHolder(list.getChildAt(i));
                if (holder instanceof Card) ((Card) holder).resize();
            }
        });
    }

    private final class Card extends RecyclerView.ViewHolder {
        final ImageView art, badge;
        final TextView title, subtitle, remaining;
        final ProgressBar progress;
        final FrameLayout picture;
        String key;
        Card() {
            super(new LinearLayout(getContext())); LinearLayout card = (LinearLayout) itemView; card.setOrientation(VERTICAL);
            RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(dp(245), -1); lp.setMargins(0, 0, dp(20), 0); card.setLayoutParams(lp);
            card.setFocusable(true); card.setClickable(true); card.setClipChildren(false); card.setClipToPadding(false);
            // Artwork is the card. Metadata sits on the wallpaper, without another framed glass panel.
            picture = new FrameLayout(getContext()); GradientDrawable imageBg = new GradientDrawable(); imageBg.setColor(0xFF161B23); imageBg.setCornerRadius(dp(12));
            picture.setBackground(imageBg); picture.setClipToOutline(true); picture.setElevation(dp(4)); card.addView(picture, new LayoutParams(-1, dp(138)));
            art = new ImageView(getContext()); art.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); picture.addView(art, new FrameLayout.LayoutParams(-1, -1));
            View shade = new View(getContext()); shade.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB3000000}));
            picture.addView(shade, new FrameLayout.LayoutParams(-1, dp(48), Gravity.BOTTOM));
            badge = new ImageView(getContext()); badge.setScaleType(ImageView.ScaleType.FIT_CENTER); badge.setPadding(dp(3), dp(3), dp(3), dp(3));
            GradientDrawable badgeBg = new GradientDrawable(); badgeBg.setColor(0xB3222226); badgeBg.setCornerRadius(dp(7));
            badge.setBackground(badgeBg); badge.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams badgeLayout = new FrameLayout.LayoutParams(dp(28), dp(28), Gravity.END | Gravity.BOTTOM);
            badgeLayout.setMargins(0, 0, dp(9), dp(12)); picture.addView(badge, badgeLayout);
            remaining = text(11, 0xF2FFFFFF); remaining.setTypeface(getResources().getFont(R.font.outfit_medium)); remaining.setGravity(Gravity.BOTTOM);
            remaining.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams timeLayout = new FrameLayout.LayoutParams(-1, dp(28), Gravity.START | Gravity.BOTTOM);
            timeLayout.setMargins(dp(11), 0, dp(44), dp(13)); picture.addView(remaining, timeLayout);
            progress = new ProgressBar(getContext(), null, android.R.attr.progressBarStyleHorizontal); progress.setMax(1000);
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.WHITE)); progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0x66FFFFFF));
            FrameLayout.LayoutParams progressLayout = new FrameLayout.LayoutParams(-1, dp(2), Gravity.BOTTOM);
            progressLayout.setMargins(dp(11), 0, dp(11), dp(7)); picture.addView(progress, progressLayout);
            title = text(16, 0xE6FFFFFF); title.setTypeface(getResources().getFont(R.font.outfit_medium)); title.setPadding(dp(1), dp(4), dp(1), 0); card.addView(title, new LayoutParams(-1, dp(25)));
            subtitle = text(12, 0xB3FFFFFF); subtitle.setPadding(dp(1), 0, dp(1), 0); card.addView(subtitle, new LayoutParams(-1, dp(19)));
            card.setOnFocusChangeListener((v, focused) -> {
                if (focused) {
                    int at = getBindingAdapterPosition(); if (at >= 0 && at < items.size()) { selected = at; selectedKey = items.get(at).key; }
                    if (host != null) host.focused();
                }
                title.setTextColor(focused ? Color.WHITE : 0xE6FFFFFF); subtitle.setTextColor(focused ? 0xE6FFFFFF : 0xB3FFFFFF);
                card.animate().scaleX(focused ? 1.05f : 1f).scaleY(focused ? 1.05f : 1f)
                        .translationY(focused ? -dp(2) : 0).setDuration(animations ? 160 : 0).start();
                picture.setElevation(dp(focused ? 12 : 4));
            });
        }
        void resize() {
            int imageHeight = getHeight() > 0 ? Math.max(dp(52), getHeight() - dp(30 + 16 + 44)) : dp(138);
            int width = Math.round(imageHeight * 16f / 9f);
            ViewGroup.LayoutParams card = itemView.getLayoutParams();
            if (card.width != width) { card.width = width; itemView.setLayoutParams(card); }
            ViewGroup.LayoutParams image = picture.getLayoutParams();
            if (image.height != imageHeight) { image.height = imageHeight; picture.setLayoutParams(image); }
        }
    }
    private TextView text(int size, int color) {
        TextView text = new TextView(getContext()); text.setTextSize(size); text.setTextColor(color); text.setSingleLine();
        text.setEllipsize(TextUtils.TruncateAt.END); text.setTypeface(getResources().getFont(R.font.outfit_regular)); return text;
    }
    private int dp(float value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
