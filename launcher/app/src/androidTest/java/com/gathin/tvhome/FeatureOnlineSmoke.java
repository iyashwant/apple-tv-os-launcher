package com.gathin.tvhome;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Instrumentation;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/** Separate online checks: the real Android HTTPS client and city-search UI, restoring all saved data. */
public final class FeatureOnlineSmoke extends Instrumentation {
    private FeatureSettingsActivity screen;
    private int checks;
    @Override public void onCreate(Bundle args) { super.onCreate(args); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle(); int code = Activity.RESULT_OK;
        SharedPreferences prefs = FeaturePreferences.prefs(getTargetContext()); Map<String, ?> original = new HashMap<>(prefs.getAll());
        File cache = new File(getTargetContext().getFilesDir(), "race-calendar.json"); byte[] previous = null; boolean captured = false;
        try {
            if (!android.os.Build.PRODUCT.contains("sdk")) throw new IllegalStateException("Online fixtures require an emulator");
            previous = cache.isFile() ? Files.readAllBytes(cache.toPath()) : null; captured = true;
            JSONObject calendar = RaceCalendarStore.refresh(getTargetContext());
            check(calendar.getJSONArray("races").length() > 0, "Android HTTPS calendar update returns real sessions");
            check(RaceCalendarStore.load(getTargetContext()).getLong("updated_at") == calendar.getLong("updated_at"), "Downloaded calendar is cached");
            boolean denied = false; try { FeatureNetwork.get("https://example.com/"); } catch (SecurityException expected) { denied = true; }
            check(denied, "Public-data client rejects unrelated hosts before making a request");
            screen = (FeatureSettingsActivity) startActivitySync(FeatureSettingsActivity.intent(getTargetContext(), FeatureSettingsActivity.SKY)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); settle();
            runOnMainSync(() -> screen.findViewById(R.id.featureRows).findViewWithTag("location").performClick()); settle();
            AlertDialog search = dialog();
            runOnMainSync(() -> { findInput(search.getWindow().getDecorView()).setText("Chennai, India"); search.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); });
            AlertDialog[] cities = new AlertDialog[1];
            for (int i = 0; i < 160 && cities[0] == null; i++) {
                SystemClock.sleep(100);
                runOnMainSync(() -> { AlertDialog active = dialog(); if (active != null && active != search && active.getListView() != null) cities[0] = active; });
            }
            check(cities[0] != null, "On-TV city search displays real geocoding results");
            runOnMainSync(() -> cities[0].getListView().performItemClick(null, 0, 0)); settle();
            check(prefs.getString("city", "").contains("Chennai"), "Choosing a city saves its readable name");
            double lat = Double.parseDouble(prefs.getString("latitude", "0")), lon = Double.parseDouble(prefs.getString("longitude", "0"));
            check(lat > 12 && lat < 14 && lon > 79 && lon < 81, "City search saves Chennai coordinates for offline sunrise calculation");
            result.putString("stream", "PASS: " + checks + " online feature checks.\n");
        } catch (Throwable failure) {
            code = Activity.RESULT_CANCELED; result.putString("stream", "FAIL after " + checks + " online checks: " + android.util.Log.getStackTraceString(failure));
        } finally {
            if (screen != null) runOnMainSync(screen::finish);
            FeatureSettingsSmoke.restore(prefs, original);
            if (captured) try { if (previous == null) cache.delete(); else Files.write(cache.toPath(), previous); } catch (Exception ignored) { }
        }
        finish(code, result);
    }
    private AlertDialog dialog() { try { Field field=FeatureSettingsActivity.class.getDeclaredField("activeDialog"); field.setAccessible(true); return (AlertDialog)field.get(screen); } catch(Exception e){throw new RuntimeException(e);} }
    private EditText findInput(View view) { if(view instanceof EditText)return(EditText)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){EditText found=findInput(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}return null; }
    private void settle() { waitForIdleSync(); SystemClock.sleep(300); waitForIdleSync(); }
    private void check(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
