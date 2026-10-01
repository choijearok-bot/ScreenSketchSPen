package com.example.screensketch;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {
    private Button permissionButton;
    private Button startButton;
    private Button stopButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(32), dp(28), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(248, 249, 252));

        TextView title = new TextView(this);
        title.setText("Screen Sketch S Pen");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(25, 28, 35));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(this);
        desc.setText("갤럭시탭 화면 위에 S펜으로 그립니다.\n\n떠 있는 버튼 사용법:\n• 한 번 탭: DRAW / TOUCH 전환\n• 길게 누름: 12색 팔레트\n• S펜 측면 버튼을 누른 채 문지르기: 지우개\n\nTOUCH로 바꿔도 그려둔 선은 그대로 유지됩니다.");
        desc.setTextSize(17);
        desc.setTextColor(Color.DKGRAY);
        desc.setPadding(0, dp(22), 0, dp(26));
        root.addView(desc, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        permissionButton = makeButton("1. 화면 위 표시 권한 허용");
        permissionButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permissionButton);

        startButton = makeButton("2. 드로잉 시작");
        startButton.setOnClickListener(v -> startOverlay());
        root.addView(startButton);

        stopButton = makeButton("드로잉 종료");
        stopButton.setOnClickListener(v -> stopService(new Intent(this, OverlayDrawingService.class)));
        root.addView(stopButton);

        setContentView(root);
        requestNotificationsIfNeeded();
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        permissionButton.setText(ok ? "✓ 화면 위 표시 권한 허용됨" : "1. 화면 위 표시 권한 허용");
        startButton.setEnabled(ok);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0, 0, 0, dp(14));
        b.setLayoutParams(lp);
        return b;
    }

    private void requestOverlayPermission() {
        Intent intent = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + getPackageName()));
        startActivity(intent);
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "먼저 '다른 앱 위에 표시' 권한을 허용해 주세요.", Toast.LENGTH_LONG).show();
            requestOverlayPermission();
            return;
        }
        Intent intent = new Intent(this, OverlayDrawingService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) startForegroundService(intent);
        else startService(intent);
        Toast.makeText(this, "드로잉 버튼이 화면 위에 표시됩니다.", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }
}
