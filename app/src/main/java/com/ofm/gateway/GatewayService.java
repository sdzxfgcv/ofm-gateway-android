package com.ofm.gateway;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class GatewayService extends Service {
    public static final String ACTION_START = "start";
    public static final String ACTION_STOP = "stop";
    public static final int PORT = 18900;
    private static final String CHANNEL_ID = "ofm-gateway";
    private static final String PREFS = "ofm_gateway";

    private GatewayServer server;

    @Override
    public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? ACTION_START : intent.getStringExtra("action");
        if (ACTION_STOP.equals(action)) {
            stopGateway();
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        startGateway();
        startForeground(1, buildNotification());
        return START_STICKY;
    }

    private void startGateway() {
        if (server != null) return;
        SharedPreferences sp = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String key = sp.getString("api_key", null);
        if (key == null || key.isEmpty()) {
            key = "ofm_" + Fingerprint.hex(Fingerprint.randomBytes(16));
            sp.edit().putString("api_key", key).apply();
        }
        String session = sp.getString("session", null);
        if (session == null || session.isEmpty()) {
            session = Fingerprint.canonicalSessionID("ofm-gateway-" + System.currentTimeMillis());
            sp.edit().putString("session", session).apply();
        }
        String contextText = readAsset("ofm-agent-context.txt");
        String contracts = readAsset("ofm-tool-contracts.json");
        server = new GatewayServer(PORT, key, session, contextText, contracts);
        try {
            server.start();
        } catch (java.io.IOException e) {
            server = null;
        }
    }

    private void stopGateway() {
        if (server != null) {
            server.stop();
            server = null;
        }
    }

    @Override
    public void onDestroy() {
        stopGateway();
        super.onDestroy();
    }

    private String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getAssets().open(name), StandardCharsets.UTF_8))) {
            StringBuilder s = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) s.append(line).append('\n');
            return s.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel c = new NotificationChannel(CHANNEL_ID, "OFM Gateway", NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.createNotificationChannel(c);
        }
    }

    private Notification buildNotification() {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) b = new Notification.Builder(this, CHANNEL_ID);
        else b = new Notification.Builder(this);
        return b.setSmallIcon(android.R.drawable.stat_sys_upload)
                .setContentTitle("OFM Gateway running")
                .setContentText("Listening on http://127.0.0.1:" + PORT)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
