package com.gathin.tvhome;

import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Optional upstream capabilities use the fork's existing header, race panel and lifecycle. */
final class IntegrationController {
    private final MainActivity home;
    private final View root;
    private final Config config;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long rotationStart = SystemClock.elapsedRealtime();
    private final List<View> headerButtons = new ArrayList<>();
    private final GameDay gameDay;
    private TextView lunar, vpn, pager;
    private boolean active;
    private Intent pending;
    private long deadline, lastEndedRead;
    private final Runnable startWhenOnline = new Runnable() {
        @Override public void run() {
            if (!active || pending == null) return;
            if (!OpenOnStart.opensNow(OpenOnStart.online(home), SystemClock.elapsedRealtime(), deadline)) {
                handler.postDelayed(this, 500); return;
            }
            Intent launch = pending; pending = null; launch(launch);
        }
    };
    private final Runnable turn = this::refreshRace;

    IntegrationController(MainActivity home, View root, Config config) {
        this.home=home; this.root=root; this.config=config;
        View greeting=root.findViewById(R.id.greeting);
        if (!config.showGreeting) greeting.setVisibility(View.GONE);
        TextView date=root.findViewById(R.id.date);
        if (config.headerWhite) date.setTextColor(Color.WHITE);
        ViewGroup dates=(ViewGroup)date.getParent();
        if (config.dateBelowClock && dates.getParent() instanceof LinearLayout) {
            LinearLayout clockRow=(LinearLayout)dates.getParent();
            ViewGroup left=(ViewGroup)clockRow.getParent();
            clockRow.removeView(dates);
            left.addView(dates,new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT));
            for (int i=0;i<clockRow.getChildCount();i++)
                if (clockRow.getChildAt(i).getClass()==View.class) clockRow.getChildAt(i).setVisibility(View.GONE);
        }
        if (config.lunarDate) {
            lunar=new TextView(home); lunar.setTextColor(0xB3FFFFFF); lunar.setTextSize(12);
            lunar.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
            lunar.setSingleLine(true); dates.addView(lunar);
        }
        LinearLayout buttons=root.findViewById(R.id.topButtons);
        for (String pkg:config.topBarApps) {
            Intent launch=OpenOnStart.intent(home.getPackageManager(),pkg);
            if (launch==null) continue;
            TextView button=button();
            try {
                Drawable icon=home.getPackageManager().getApplicationIcon(pkg).mutate();
                int size=dp(26);icon.setBounds(0,0,size,size);
                button.setCompoundDrawables(icon,null,null,null);
                button.setContentDescription(home.getPackageManager().getApplicationLabel(
                        home.getPackageManager().getApplicationInfo(pkg,0)));
            } catch (PackageManager.NameNotFoundException ignored) {continue;}
            button.setOnClickListener(v -> {
                String action=config.launchActions.get(pkg);
                String input=LaunchActions.inputId(action);
                if (input!=null) home.openInput(input);
                else launch(LaunchActions.select(pkg,config.launchActions,launch,(p,a)-> {
                    Intent i=LaunchActions.intent(p,a);
                    return i.resolveActivity(home.getPackageManager())!=null?i:null;
                }));
            });
            buttons.addView(button,0); headerButtons.add(button);
        }
        if (config.vpnPill) {
            vpn=button(); vpn.setText("VPN");
            vpn.setOnClickListener(v -> {
                Intent i=config.vpnApp.isEmpty()?new Intent(android.provider.Settings.ACTION_VPN_SETTINGS)
                        :OpenOnStart.intent(home.getPackageManager(),config.vpnApp);
                if(i!=null) launch(i);
            });
            buttons.addView(vpn,Math.max(0,buttons.getChildCount()-1));headerButtons.add(vpn);
        }
        ComponentName inputs=new ComponentName(home,InputsRequestActivity.class);
        PackageManager pm=home.getPackageManager();
        int want=config.handleViewInputs?PackageManager.COMPONENT_ENABLED_STATE_ENABLED:PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
        if(pm.getComponentEnabledSetting(inputs)!=want)
            pm.setComponentEnabledSetting(inputs,want,PackageManager.DONT_KILL_APP);
        gameDay=GameDay.create(home,config.gameDay,handler,this::refreshRace);
        if(config.fadeRows) TopFade.attach(root.findViewById(R.id.grid),dp(48));
    }

    private TextView button() {
        TextView v=new TextView(home,null,0,R.style.TopButton);
        LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,dp(42));
        params.setMarginStart(dp(10));v.setLayoutParams(params);
        v.setId(View.generateViewId());v.setSoundEffectsEnabled(config.navigationSounds);
        v.setOnKeyListener((view,code,event)->home.handleTopBarDirection(view,event));
        return v;
    }
    List<View> extraButtons(){return headerButtons;}
    private int dp(int n){return Math.round(n*home.getResources().getDisplayMetrics().density);}
    private void launch(Intent intent){
        try {home.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));}
        catch(android.content.ActivityNotFoundException|SecurityException ignored) { }
    }
    void resume() {
        active=true;
        refreshHeader();
        if(gameDay!=null) gameDay.start();
        handler.post(turn);
        if(MainActivity.ACTION_VIEW_INPUTS.equals(home.getIntent().getAction())) {
            home.getIntent().setAction(Intent.ACTION_MAIN);
            root.postDelayed(()->{if(active)home.showInputs();},150);
        } else startAtBoot();
    }
    void pause(){active=false;pending=null;handler.removeCallbacksAndMessages(null);if(gameDay!=null)gameDay.stop();}
    void close(){pause();if(gameDay!=null)gameDay.shutdown();}
    void refreshHeader(){
        if(lunar!=null) lunar.setText(Lunar.today(home.getResources(),Lunar.offsetFor(config.lunarUtcOffset,Locale.getDefault())));
        if(vpn==null && !config.wifiStatusColor && !config.wifiStatusWarnings)return;
        ConnectivityManager cm=home.getSystemService(ConnectivityManager.class);
        NetworkCapabilities caps=cm==null?null:cm.getNetworkCapabilities(cm.getActiveNetwork());
        boolean online=caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        boolean secure=caps!=null&&caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN);
        if(vpn!=null){
            vpn.setVisibility(secure||!config.vpnApp.isEmpty()?View.VISIBLE:View.GONE);
            vpn.setText(secure?"VPN":home.getString(R.string.vpn_off));
            vpn.setTextColor(secure?0xFF63D6A0:0xFFFF6961);
        }
        if(config.wifiStatusColor||config.wifiStatusWarnings){
            TextView network=root.findViewById(R.id.btnWifi);
            int tint=online?0xFF63D6A0:config.wifiStatusWarnings?(caps==null?0xFFFF6961:0xFFFFB453):Color.WHITE;
            network.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(tint));
        }
    }
    void refreshRace(){
        if(!active || gameDay==null)return;
        handler.removeCallbacks(turn);
        long now=System.currentTimeMillis();
        long ended=0;
        for(GameDay.Turn t:gameDay.turns())if(t.endMs<=now)ended=Math.max(ended,t.endMs);
        if(ended>lastEndedRead){lastEndedRead=ended;gameDay.read();}
        List<GameDay.Turn> turns=GameDay.merge(gameDay.turns(),RaceWeekend.at(home.currentRaces(),Instant.ofEpochMilli(now)),now);
        if(!turns.isEmpty()){
            int selected=GameDay.current(turns.size(),SystemClock.elapsedRealtime()-rotationStart,gameDay.turnSeconds());
            GameDay.Turn t=turns.get(selected);
            if(t.f1) home.updateRace();
            else {
                root.findViewById(R.id.racePanel).setVisibility(View.VISIBLE);
                ((TextView)root.findViewById(R.id.raceKicker)).setText(t.topLine);
                ((TextView)root.findViewById(R.id.raceHeadline)).setText(GameDay.live(t,now)
                        ?home.getString(R.string.game_live):t.word+" "+RaceWeekend.countdown(home.getResources(),GameDay.until(t,now)));
                ((TextView)root.findViewById(R.id.raceSessions)).setText("");
            }
            if(pager==null){pager=new TextView(home);pager.setTextSize(10);pager.setTextColor(0xB3FFFFFF);
                ((ViewGroup)root.findViewById(R.id.raceSessions).getParent()).addView(pager);}
            StringBuilder dots=new StringBuilder();for(int i=0;i<turns.size();i++)dots.append(i==selected?"●  ":"·  ");
            pager.setText(turns.size()>1?dots:"");
        } else {home.updateRace();if(pager!=null)pager.setText("");}
        handler.postDelayed(turn,GameDay.delayToNextTurn(SystemClock.elapsedRealtime()-rotationStart,gameDay.turnSeconds()));
    }
    private void startAtBoot(){
        String pkg=config.bootToLiveTv?config.liveTvPackage:config.openOnStartChoice
                ?home.getSharedPreferences(Config.SETTINGS_NAME,0).getString(OpenOnStart.KEY,""):"";
        SharedPreferences boot=home.getSharedPreferences(OpenOnStart.BOOT_PREFS,0);
        int count=OpenOnStart.boot(home);
        if(!OpenOnStart.due(count,boot.getInt(OpenOnStart.BOOT_KEY,-1),pkg,home.getPackageName()))return;
        Intent i=config.bootToLiveTv?home.liveTvIntent():OpenOnStart.intent(home.getPackageManager(),pkg);
        if(i==null||!boot.edit().putInt(OpenOnStart.BOOT_KEY,count).commit())return;
        if(config.openOnStartNetworkWait>0&&!OpenOnStart.online(home)){
            pending=i;deadline=SystemClock.elapsedRealtime()+config.openOnStartNetworkWait*1000L;
            handler.postDelayed(startWhenOnline,500);
        }else launch(i);
    }
}
