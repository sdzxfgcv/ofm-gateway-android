package com.ofm.gateway;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private TextView statusView;
    private TextView infoView;
    private Button toggleButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        int pad = 48;
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF8F9FA);

        TextView title = new TextView(this);
        title.setText("OFM Gateway");
        title.setTextSize(28);
        title.setTextColor(0xFF202124);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("OpenAI-compatible local gateway for the free tier.\nNo opencode installation required.");
        sub.setTextSize(14);
        sub.setPadding(0, 16, 0, 40);
        sub.setTextColor(0xFF5F6368);
        root.addView(sub);

        statusView = new TextView(this);
        statusView.setTextSize(18);
        root.addView(statusView);

        infoView = new TextView(this);
        infoView.setTextSize(13);
        infoView.setPadding(0, 24, 0, 40);
        infoView.setTextColor(0xFF3C4043);
        root.addView(infoView);

        toggleButton = new Button(this);
        toggleButton.setTextSize(16);
        toggleButton.setOnClickListener(v -> toggle());
        root.addView(toggleButton);

        setContentView(root);
        refresh();

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 7);
        }
    }

    private void toggle() {
        SharedPreferences sp = getSharedPreferences("ofm_gateway", Context.MODE_PRIVATE);
        String key = sp.getString("api_key", "");
        String base = "http://127.0.0.1:" + GatewayService.PORT;
        Intent i = new Intent(this, GatewayService.class);
        if ("running".equals(statusView.getText().toString())) {
            i.putExtra("action", GatewayService.ACTION_STOP);
            stopService(i);
        } else {
            i.putExtra("action", GatewayService.ACTION_START);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(i); else startService(i);
            Toast.makeText(this, "Base URL " + base + "\nKey " + key, Toast.LENGTH_LONG).show();
        }
        new android.os.Handler().postDelayed(this::refresh, 600);
    }

    private void refresh() {
        SharedPreferences sp = getSharedPreferences("ofm_gateway", Context.MODE_PRIVATE);
        String key = sp.getString("api_key", "");
        boolean running = isServiceRunning();
        statusView.setText(running ? "running" : "stopped");
        statusView.setTextColor(running ? 0xFF188038 : 0xFFC5221F);
        toggleButton.setText(running ? "Stop gateway" : "Start gateway");
        String curl = "curl " + "http://127.0.0.1:" + GatewayService.PORT + "/v1/chat/completions \\\n"
                + "  -H \"Authorization: Bearer " + key + "\" \\\n"
                + "  -H \"Content-Type: application/json\" \\\n"
                + "  -d '{\"model\":\"opencode/cat-jam-free\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}'";
        infoView.setText("Base URL: http://127.0.0.1:" + GatewayService.PORT + "/v1\n"
                + "Key: " + key + "\n\n" + curl);
    }

    private boolean isServiceRunning() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        if (am == null) return false;
        for (android.app.ActivityManager.RunningServiceInfo info : am.getRunningServices(Integer.MAX_VALUE)) {
            if (GatewayService.class.getName().equals(info.service.getClassName())) return true;
        }
        return false;
    }
}
