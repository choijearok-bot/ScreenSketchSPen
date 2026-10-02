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

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(28), dp(32), dp(28), dp(24));
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setBackgroundColor(Color.rgb(248,249,252));

        TextView title = new TextView(this);
        title.setText("Screen Sketch S Pen v1.4");
        title.setTextSize(26);
        title.setTextColor(Color.rgb(25,28,35));
        title.setGravity(Gravity.CENTER);
        root.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView desc = new TextView(this);
        desc.setText(
                "갤럭시탭 화면 위에 S펜으로 그립니다.\n\n" +
                "• PEN ON: S펜 드로잉\n" +
                "• PEN OFF: 툴바/그림은 유지하고 아래 앱 조작\n" +
                "• S펜 측면 버튼: 누르는 동안 지우개\n" +
                "• SAVE PNG: 현재 화면 + 주석 이미지 저장\n" +
                "• SAVE PDF: 현재 화면 + 주석 PDF 저장\n" +
                "• PRINT: 시스템 인쇄 / PDF 저장\n\n" +
                "자동 보존:\n" +
                "• PEN OFF로 바꿔도 툴바와 그림 유지\n" +
                "• 서비스가 재시작돼도 마지막 그림 자동 복원\n\n" +
                "완전 종료:\n" +
                "• 빨간 EXIT APP을 두 번 눌러 종료\n" +
                "• 알림창에서도 '앱 완전 종료' 가능");
        desc.setTextSize(16);
        desc.setTextColor(Color.DKGRAY);
        desc.setPadding(0, dp(22), 0, dp(26));
        root.addView(desc, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        permissionButton = makeButton("1. 화면 위 표시 권한 허용");
        permissionButton.setOnClickListener(v -> requestOverlayPermission());
        root.addView(permissionButton);

        startButton = makeButton("2. 드로잉 시작");
        startButton.setOnClickListener(v -> startOverlay());
        root.addView(startButton);

        Button stopButton = makeButton("앱 완전 종료 (툴바 제거)");
        stopButton.setOnClickListener(v -> stopService(new Intent(this, OverlayDrawingService.class)));
        root.addView(stopButton);

        setContentView(root);
        requestNotificationsIfNeeded();
    }

    @Override protected void onResume() {
        super.onResume();
        boolean ok = Settings.canDrawOverlays(this);
        permissionButton.setText(ok ? "✓ 화면 위 표시 권한 허용됨" : "1. 화면 위 표시 권한 허용");
        startButton.setEnabled(ok);
    }

    private Button makeButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(16);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        lp.setMargins(0,0,0,dp(14));
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
        Toast.makeText(this, "툴바가 유지됩니다. PEN ON/OFF로 입력만 전환하세요.", Toast.LENGTH_SHORT).show();
        moveTaskToBack(true);
    }

    private void requestNotificationsIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 10);
        }
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }
}
