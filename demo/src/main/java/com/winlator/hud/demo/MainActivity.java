package com.winlator.hud.demo;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import com.winlator.hud.WinlatorHUD;

import java.util.Random;

/**
 * WinlatorHUD 演示 Activity。
 * 无需集成 Winlator，直接安装即可看到 HUD 全部效果。
 * 模拟数据模式：定时调用 recordFrame() 生成虚拟帧率。
 */
public class MainActivity extends AppCompatActivity {

    private Handler simHandler;
    private Runnable simRunnable;
    private boolean simulating = false;
    private final Random random = new Random();
    private int themeIndex = 0;
    private static final int[] THEMES = {WinlatorHUD.THEME_DEFAULT, WinlatorHUD.THEME_GREEN, WinlatorHUD.THEME_AMBER};
    private static final String[] THEME_NAMES = {"默认蓝", "暗夜绿", "暖橙"};

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        TextView status = findViewById(R.id.statusText);
        Button btnInit = findViewById(R.id.btnInit);
        Button btnSim = findViewById(R.id.btnSim);
        Button btnTheme = findViewById(R.id.btnTheme);
        Button btnDensity = findViewById(R.id.btnDensity);
        Button btnRelease = findViewById(R.id.btnRelease);

        btnInit.setOnClickListener(v -> {
            WinlatorHUD.init(this);
            WinlatorHUD.setGameInfo("DXVK", "1920x1080", "Wine 9.0");
            WinlatorHUD.setDxVersion("DX11");
            status.setText("HUD 已启动\n单击循环密度 / 双击切换横竖 / 拖拽移动 / 长按1.5s锁定");
        });

        btnSim.setOnClickListener(v -> {
            if (!simulating) {
                startSimulation();
                btnSim.setText("停止模拟");
                status.setText("模拟帧率中（30-120 FPS 随机波动）...");
            } else {
                stopSimulation();
                btnSim.setText("开始模拟");
                status.setText("模拟已停止");
            }
        });

        btnTheme.setOnClickListener(v -> {
            themeIndex = (themeIndex + 1) % THEMES.length;
            WinlatorHUD.setTheme(THEMES[themeIndex]);
            status.setText("主题：" + THEME_NAMES[themeIndex]);
        });

        btnDensity.setOnClickListener(v -> {
            // 通过模拟单击切换密度（直接调用内部不可达，用手势说明）
            status.setText("请在 HUD 上单击切换密度（精简→标准→详细→MEGA）");
        });

        btnRelease.setOnClickListener(v -> {
            stopSimulation();
            WinlatorHUD.release();
            simulating = false;
            btnSim.setText("开始模拟");
            status.setText("HUD 已释放");
        });
    }

    private void startSimulation() {
        simHandler = new Handler(Looper.getMainLooper());
        simRunnable = new Runnable() {
            @Override
            public void run() {
                // 模拟帧率：基础 60 FPS + 随机波动，偶尔掉帧
                double baseFps = 55 + random.nextDouble() * 30;
                if (random.nextDouble() < 0.05) baseFps *= 0.4; // 5% 概率掉帧
                long frameNs = (long)(1_000_000_000L / baseFps);
                WinlatorHUD.recordFrame();
                // 模拟 presentedFps（帧生成场景）
                if (random.nextDouble() < 0.3) {
                    WinlatorHUD.setPresentedFps((float)(baseFps * 1.8));
                }
                simHandler.postDelayed(this, frameNs / 1_000_000L);
            }
        };
        simHandler.post(simRunnable);
        simulating = true;
    }

    private void stopSimulation() {
        if (simHandler != null && simRunnable != null) {
            simHandler.removeCallbacks(simRunnable);
        }
        simulating = false;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopSimulation();
        WinlatorHUD.release();
    }
}
