/*
 * WinlatorHUD v3.0 — Winlator 专用性能监控叠加层
 *
 * 基于 Android View 渲染管线，零 Vulkan layer 依赖，零闪烁。
 * 横向顶部横条 + 竖向侧边紧凑面板，各 3 级密度。
 *
 * 集成方式：
 *   WinlatorHUD.init(activity);
 *   渲染循环中调用 WinlatorHUD.recordFrame();
 *   WinlatorHUD.setGameInfo(engineName, resolution, wineVersion);
 *   WinlatorHUD.release();
 *
 * 手势：单击循环密度，双击切换横/竖，拖拽移动，长按3.5s锁定。
 */
package com.winlator.hud;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileFilter;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

public final class WinlatorHUD {

    // ==================== 显示元素位掩码 ====================
    public static final int SHOW_FPS          = 1 << 0;
    public static final int SHOW_FRAMETIME    = 1 << 1;
    public static final int SHOW_AVG_FPS      = 1 << 2;
    public static final int SHOW_1PC_LOW      = 1 << 3;
    public static final int SHOW_01PC_LOW     = 1 << 4;
    public static final int SHOW_GRAPH        = 1 << 5;
    public static final int SHOW_GPU_LOAD     = 1 << 6;
    public static final int SHOW_GPU_TEMP     = 1 << 7;
    public static final int SHOW_GPU_CLOCK    = 1 << 8;
    public static final int SHOW_VRAM         = 1 << 9;
    public static final int SHOW_CPU_LOAD     = 1 << 10;
    public static final int SHOW_CPU_TEMP     = 1 << 11;
    public static final int SHOW_CPU_CLOCK    = 1 << 12;
    public static final int SHOW_CPU_CORES    = 1 << 13;
    public static final int SHOW_RAM          = 1 << 14;
    public static final int SHOW_SWAP         = 1 << 15;
    public static final int SHOW_BATTERY      = 1 << 16;
    public static final int SHOW_BAT_POWER    = 1 << 17;
    public static final int SHOW_NETWORK      = 1 << 18;
    public static final int SHOW_ENGINE       = 1 << 19;
    public static final int SHOW_RESOLUTION   = 1 << 20;
    public static final int SHOW_WINE_VERSION = 1 << 21;
    public static final int SHOW_DURATION     = 1 << 22;
    public static final int SHOW_THROTTLE     = 1 << 23;
    public static final int SHOW_BAT_TIME     = 1 << 24;
    public static final int SHOW_REFRESH_RATE = 1 << 25;
    public static final int SHOW_EXE_NAME     = 1 << 26;

    // ==================== 预设密度 ====================
    public static final int SHOW_COMPACT = SHOW_FPS | SHOW_GPU_LOAD | SHOW_CPU_LOAD | SHOW_RAM | SHOW_BATTERY;

    public static final int SHOW_DEFAULT = SHOW_FPS | SHOW_FRAMETIME | SHOW_AVG_FPS
            | SHOW_1PC_LOW | SHOW_01PC_LOW | SHOW_GRAPH
            | SHOW_GPU_LOAD | SHOW_GPU_TEMP | SHOW_GPU_CLOCK | SHOW_VRAM
            | SHOW_CPU_LOAD | SHOW_CPU_TEMP | SHOW_CPU_CLOCK
            | SHOW_RAM | SHOW_BATTERY | SHOW_BAT_POWER | SHOW_BAT_TIME
            | SHOW_ENGINE | SHOW_EXE_NAME | SHOW_DURATION;

    public static final int SHOW_DETAILED = (1 << 27) - 1; // 全部开启

    // ==================== 密度模式 ====================
    public static final int DENSITY_COMPACT  = 0;
    public static final int DENSITY_NORMAL   = 1;
    public static final int DENSITY_DETAILED = 2;
    private static final String[] DENSITY_NAMES = {"精简", "标准", "详细"};

    // ==================== 方向模式 ====================
    public static final int ORIENT_HORIZONTAL = 0; // 顶部横条
    public static final int ORIENT_VERTICAL   = 1; // 侧边竖列
    private static final String[] ORIENT_NAMES = {"横条", "竖列"};

    // ==================== 颜色 ====================
    private static final int C_BG       = 0xCC000000;
    private static final int C_BORDER   = 0x33FFFFFF;
    private static final int C_LABEL    = 0xFF888888;
    private static final int C_TEXT     = 0xFFFFFFFF;
    private static final int C_DIM      = 0xFFAAAAAA;
    private static final int C_GPU      = 0xFF4CAF50;
    private static final int C_CPU      = 0xFF2196F3;
    private static final int C_RAM      = 0xFF9C27B0;
    private static final int C_BAT      = 0xFFFF9800;
    private static final int C_FPS_GOOD = 0xFF4CAF50;
    private static final int C_FPS_MED  = 0xFFFFC107;
    private static final int C_FPS_LOW  = 0xFFF44336;
    private static final int C_TEMP_HOT = 0xFFF44336;
    private static final int C_GRAPH    = 0xFF4CAF50;
    private static final int C_GRAPH_BG = 0x224CAF50;

    // ==================== 单例状态 ====================
    private static HUDView sView;
    private static WindowManager sWM;
    private static FrameTracker sTracker;
    private static SystemMetrics sMetrics;
    private static HandlerThread sThread;
    private static Handler sHandler;
    private static boolean sRunning;

    private static String sEngine = "";
    private static String sResolution = "";
    private static String sWineVersion = "";

    private static final Runnable sTick = new Runnable() {
        @Override public void run() {
            if (sRunning && sMetrics != null) {
                sMetrics.update();
                if (sView != null) sView.postInvalidate();
            }
            if (sHandler != null) sHandler.postDelayed(this, 500);
        }
    };

    // ==================== 公共 API ====================

    public static void init(Activity activity) {
        init(activity, SHOW_DEFAULT, DENSITY_NORMAL, ORIENT_HORIZONTAL);
    }

    public static void init(Activity activity, int showMask, int density, int orientation) {
        if (sRunning) return;
        sWM = (WindowManager) activity.getSystemService(Context.WINDOW_SERVICE);
        sTracker = new FrameTracker();
        sMetrics = new SystemMetrics(activity);
        sTracker.setMetrics(sMetrics);

        sView = new HUDView(activity);
        sView.setShowMask(showMask);
        sView.setDensity(density);
        sView.setOrientation(orientation);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        FrameLayout decor = (FrameLayout) activity.getWindow().getDecorView();
        decor.addView(sView, lp);

        sThread = new HandlerThread("WinlatorHUD");
        sThread.start();
        sHandler = new Handler(sThread.getLooper());
        sHandler.post(sTick);
        sRunning = true;
    }

    public static void recordFrame() {
        if (sTracker != null) sTracker.recordFrame();
    }

    public static void setGameInfo(String engine, String resolution, String wineVersion) {
        sEngine = engine != null ? engine : "";
        sResolution = resolution != null ? resolution : "";
        sWineVersion = wineVersion != null ? wineVersion : "";
    }

    public static void release() {
        sRunning = false;
        if (sHandler != null) { sHandler.removeCallbacks(sTick); sHandler = null; }
        if (sThread != null) { sThread.quitSafely(); sThread = null; }
        if (sView != null && sView.getParent() != null) {
            ((android.view.ViewGroup) sView.getParent()).removeView(sView);
        }
        sView = null;
        sTracker = null;
        sMetrics = null;
        sWM = null;
    }

    // ==================== HUD View ====================

    private static final class HUDView extends View {
        private int showMask = SHOW_DEFAULT;
        private int density = DENSITY_NORMAL;
        private int orientation = ORIENT_HORIZONTAL;
        private boolean locked = false;

        // 尺寸
        private float dp;
        private int textSize;
        private int smallTextSize;
        private int fpsTextSize;
        private int rowH;
        private int smallRowH;
        private int pad;
        private int graphH;

        // 绘制缓存
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();
        private final RectF bgRect = new RectF();
        private final StringBuilder sb = new StringBuilder(64);
        private final float[] graphData = new float[120];
        private int graphIdx = 0;
        private int graphCount = 0;

        // 手势
        private float downX, downY;
        private long downTime;
        private boolean dragging;
        private float offsetX, offsetY;
        private long lastClickTime;
        private static final long CLICK_TIMEOUT = 250;
        private static final long LONG_PRESS = 3500;
        private static final float TOUCH_SLOP = 16;

        HUDView(Context ctx) {
            super(ctx);
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            dp = dm.density;
            textSize = sp(11);
            smallTextSize = sp(9);
            fpsTextSize = sp(16);
            rowH = (int)(16 * dp);
            smallRowH = (int)(12 * dp);
            pad = (int)(4 * dp);
            graphH = (int)(24 * dp);
            bgPaint.setColor(C_BG);
            bgPaint.setStyle(Paint.Style.FILL);
            setBackgroundColor(Color.TRANSPARENT);
        }

        private int sp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }

        void setShowMask(int mask) { this.showMask = mask; requestLayout(); invalidate(); }
        void setDensity(int d) { this.density = Math.max(DENSITY_COMPACT, Math.min(DENSITY_DETAILED, d)); requestLayout(); invalidate(); }
        void setOrientation(int o) { this.orientation = o; requestLayout(); invalidate(); }

        private boolean has(int flag) { return (showMask & flag) != 0; }

        private int fpsColor(double fps) {
            if (fps >= 60) return C_FPS_GOOD;
            if (fps >= 30) return C_FPS_MED;
            return C_FPS_LOW;
        }

        private int tempColor(int temp) {
            return temp >= 80 ? C_TEMP_HOT : C_TEXT;
        }

        @Override
        protected void onMeasure(int wSpec, int hSpec) {
            int w = MeasureSpec.getSize(wSpec);
            int h = computeHeight();
            setMeasuredDimension(w, h);
        }

        private int computeHeight() {
            if (orientation == ORIENT_HORIZONTAL) {
                int rows = 1;
                if (density == DENSITY_DETAILED) rows = 2;
                if (has(SHOW_GRAPH) && density != DENSITY_COMPACT) rows++;
                return rows * rowH + pad * 2;
            } else {
                int rows = 0;
                if (has(SHOW_FPS)) rows++;
                if (has(SHOW_GRAPH) && density != DENSITY_COMPACT) rows += 2;
                if (has(SHOW_AVG_FPS) || has(SHOW_1PC_LOW) || has(SHOW_01PC_LOW)) rows++;
                if (has(SHOW_GPU_LOAD) || has(SHOW_GPU_TEMP) || has(SHOW_GPU_CLOCK)) rows++;
                if (has(SHOW_VRAM)) rows++;
                if (has(SHOW_CPU_LOAD) || has(SHOW_CPU_TEMP) || has(SHOW_CPU_CLOCK)) rows++;
                if (has(SHOW_CPU_CORES)) rows += Math.min(8, sMetrics != null ? sMetrics.coreCount : 8);
                if (has(SHOW_RAM)) rows++;
                if (has(SHOW_SWAP)) rows++;
                if (has(SHOW_BATTERY) || has(SHOW_BAT_POWER) || has(SHOW_BAT_TIME)) rows++;
                if (has(SHOW_NETWORK)) rows++;
                if (has(SHOW_ENGINE) || has(SHOW_EXE_NAME)) rows++;
                if (has(SHOW_RESOLUTION) || has(SHOW_REFRESH_RATE)) rows++;
                if (has(SHOW_WINE_VERSION) || has(SHOW_DURATION)) rows++;
                return rows * rowH + pad * 2;
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (sMetrics == null || sTracker == null) return;
            SystemMetrics m = sMetrics;
            FrameTracker t = sTracker;

            int w = getWidth();
            int h = getHeight();

            // 背景
            bgRect.set(offsetX, 0, w, h);
            canvas.drawRoundRect(bgRect, 4 * dp, 4 * dp, bgPaint);

            canvas.save();
            canvas.translate(offsetX + pad, pad);

            if (orientation == ORIENT_HORIZONTAL) {
                drawHorizontal(canvas, m, t, w - pad * 2);
            } else {
                drawVertical(canvas, m, t);
            }
            canvas.restore();
        }

        // ==================== 横向横条绘制 ====================
        private void drawHorizontal(Canvas canvas, SystemMetrics m, FrameTracker t, int availW) {
            float x = 0;
            float y = 0;
            paint.setTypeface(Typeface.MONOSPACE);

            if (density == DENSITY_COMPACT) {
                // L1: FPS GPU CPU RAM BAT
                x = drawHudItem(canvas, x, y, "FPS", fmt(t.fps), fpsColor(t.fps), fpsTextSize);
                x = drawHudItem(canvas, x, y, "GPU", m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-", C_GPU);
                x = drawHudItem(canvas, x, y, "CPU", m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-", C_CPU);
                x = drawHudItem(canvas, x, y, "RAM", m.ramPercent + "%", C_RAM);
                x = drawHudItem(canvas, x, y, "BAT", m.batPercent + "%", C_BAT);
            } else if (density == DENSITY_NORMAL) {
                // L2: FPS+ms GPU全 CPU全 RAM BAT+W 1%
                x = drawHudItem(canvas, x, y, "FPS", fmt(t.fps) + " " + fmt1(t.frameTime) + "ms", fpsColor(t.fps), fpsTextSize);
                x = drawHudItem(canvas, x, y, "GPU",
                        (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " +
                        (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " +
                        (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-"), C_GPU);
                x = drawHudItem(canvas, x, y, "CPU",
                        (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " +
                        (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " +
                        (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), C_CPU);
                x = drawHudItem(canvas, x, y, "RAM", fmt1(m.ramGib) + "G", C_RAM);
                x = drawHudItem(canvas, x, y, "BAT",
                        m.batPercent + "% " +
                        (m.batTemp >= 0 ? m.batTemp + "°C" : "-") + " " +
                        (m.batPower >= 0 ? fmt1(m.batPower) + "W" : "-"), C_BAT);
                x = drawHudItem(canvas, x, y, "1%", fmt(t.low1), C_DIM);
            } else {
                // L3 详细: 两行
                // 行1: FPS+ms AVG 1% 0.1% + 波形图
                paint.setTextSize(textSize);
                x = 0;
                x = drawHudItem(canvas, x, y, "FPS", fmt(t.fps) + " " + fmt1(t.frameTime) + "ms", fpsColor(t.fps), fpsTextSize);
                x = drawHudItem(canvas, x, y, "AVG", fmt(t.avgFps), C_DIM);
                x = drawHudItem(canvas, x, y, "1%", fmt(t.low1), C_DIM);
                x = drawHudItem(canvas, x, y, "0.1%", fmt(t.low01), C_DIM);
                // 波形图占剩余空间
                if (has(SHOW_GRAPH)) {
                    float gx = x + pad;
                    float gw = availW - gx - pad;
                    if (gw > 40) drawGraph(canvas, gx, y + rowH - graphH - 2, gw, graphH, t);
                }
                y += rowH;
                // 行2: GPU全 CPU全 RAM+SWP BAT全 NET 引擎 EXE 时长
                x = 0;
                x = drawHudItem(canvas, x, y, "GPU",
                        (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " +
                        (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " +
                        (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-") + " " +
                        (m.vramGib >= 0 ? fmt1(m.vramGib) + "G" : "-"), C_GPU);
                x = drawHudItem(canvas, x, y, "CPU",
                        (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " +
                        (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " +
                        (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), C_CPU);
                x = drawHudItem(canvas, x, y, "RAM", fmt1(m.ramGib) + "G" + (has(SHOW_SWAP) && m.swapGib >= 0 ? " S:" + fmt1(m.swapGib) + "G" : ""), C_RAM);
                x = drawHudItem(canvas, x, y, "BAT",
                        m.batPercent + "% " +
                        (m.batTemp >= 0 ? m.batTemp + "°C" : "-") + " " +
                        (m.batPower >= 0 ? fmt1(m.batPower) + "W" : "-") +
                        (has(SHOW_BAT_TIME) && m.batTimeMin >= 0 ? " " + fmtTime(m.batTimeMin) : ""), C_BAT);
                if (has(SHOW_NETWORK) && m.netValid) {
                    x = drawHudItem(canvas, x, y, "NET", fmt1(m.netDownKB) + "↓ " + fmt1(m.netUpKB) + "↑", C_DIM);
                }
                if (has(SHOW_ENGINE) && !sEngine.isEmpty()) {
                    x = drawHudItem(canvas, x, y, "", sEngine, C_DIM);
                }
                if (has(SHOW_EXE_NAME) && !m.exeName.isEmpty()) {
                    x = drawHudItem(canvas, x, y, "", m.exeName, C_DIM);
                }
                if (has(SHOW_DURATION)) {
                    x = drawHudItem(canvas, x, y, "", fmtDuration(t.elapsedSec), C_DIM);
                }
            }
        }

        private float drawHudItem(Canvas canvas, float x, float y, String label, String value, int color) {
            return drawHudItem(canvas, x, y, label, value, color, textSize);
        }

        private float drawHudItem(Canvas canvas, float x, float y, String label, String value, int color, int size) {
            paint.setTextSize(size);
            float lx = x;
            if (!label.isEmpty()) {
                paint.setColor(C_LABEL);
                canvas.drawText(label, lx, y + rowH - 4, paint);
                lx += paint.measureText(label) + 3 * dp;
            }
            paint.setColor(color);
            canvas.drawText(value, lx, y + rowH - 4, paint);
            float w = paint.measureText(value);
            return lx + w + 10 * dp; // 间距
        }

        // ==================== 竖向竖列绘制 ====================
        private void drawVertical(Canvas canvas, SystemMetrics m, FrameTracker t) {
            float y = 0;
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(textSize);

            if (density == DENSITY_COMPACT) {
                // L1: FPS GPU CPU RAM BAT
                y = drawVRow(canvas, y, "FPS", fmt(t.fps), fpsColor(t.fps), fpsTextSize);
                y = drawVRow(canvas, y, "GPU", (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " + (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " + (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-"), C_GPU);
                y = drawVRow(canvas, y, "CPU", (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " + (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " + (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), C_CPU);
                y = drawVRow(canvas, y, "RAM", fmt1(m.ramGib) + "G " + m.ramPercent + "%", C_RAM);
                y = drawVRow(canvas, y, "BAT", m.batPercent + "% " + (m.batTemp >= 0 ? m.batTemp + "°C" : "-"), C_BAT);
            } else if (density == DENSITY_NORMAL) {
                // L2 标准
                y = drawVRow(canvas, y, "FPS", fmt(t.fps) + "  " + fmt1(t.frameTime) + "ms", fpsColor(t.fps), fpsTextSize);
                if (has(SHOW_GRAPH)) {
                    drawGraph(canvas, 0, y, 140 * dp, graphH, t);
                    y += graphH + 2;
                }
                if (has(SHOW_AVG_FPS) || has(SHOW_1PC_LOW) || has(SHOW_01PC_LOW)) {
                    sb.setLength(0);
                    if (has(SHOW_AVG_FPS)) sb.append("AVG ").append(fmt(t.avgFps)).append("  ");
                    if (has(SHOW_1PC_LOW)) sb.append("1% ").append(fmt(t.low1)).append("  ");
                    if (has(SHOW_01PC_LOW)) sb.append("0.1% ").append(fmt(t.low01));
                    y = drawVRow(canvas, y, "", sb.toString(), C_DIM, smallTextSize);
                }
                y = drawVRow(canvas, y, "GPU",
                        (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " +
                        (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " +
                        (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-"), C_GPU);
                if (has(SHOW_VRAM) && m.vramGib >= 0) {
                    y = drawVRow(canvas, y, "VRAM", fmt1(m.vramGib) + " GiB", C_GPU, smallTextSize);
                }
                y = drawVRow(canvas, y, "CPU",
                        (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " +
                        (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " +
                        (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), C_CPU);
                y = drawVRow(canvas, y, "RAM", fmt1(m.ramGib) + "G " + m.ramPercent + "%", C_RAM);
                if (has(SHOW_SWAP) && m.swapGib >= 0) {
                    y = drawVRow(canvas, y, "SWP", fmt1(m.swapGib) + "G", C_RAM, smallTextSize);
                }
                y = drawVRow(canvas, y, "BAT",
                        m.batPercent + "% " +
                        (m.batTemp >= 0 ? m.batTemp + "°C" : "-") + " " +
                        (m.batPower >= 0 ? fmt1(m.batPower) + "W" : "-") +
                        (has(SHOW_BAT_TIME) && m.batTimeMin >= 0 ? " " + fmtTime(m.batTimeMin) : ""), C_BAT);
                if (has(SHOW_NETWORK) && m.netValid) {
                    y = drawVRow(canvas, y, "NET", fmt1(m.netDownKB) + "↓ " + fmt1(m.netUpKB) + "↑", C_DIM, smallTextSize);
                }
                if (has(SHOW_ENGINE) && !sEngine.isEmpty()) {
                    y = drawVRow(canvas, y, "", sEngine, C_DIM, smallTextSize);
                }
                if (has(SHOW_EXE_NAME) && !m.exeName.isEmpty()) {
                    y = drawVRow(canvas, y, "", m.exeName, C_DIM, smallTextSize);
                }
                if (has(SHOW_DURATION)) {
                    y = drawVRow(canvas, y, "", fmtDuration(t.elapsedSec), C_DIM, smallTextSize);
                }
            } else {
                // L3 详细
                y = drawVRow(canvas, y, "FPS", fmt(t.fps) + "  " + fmt1(t.frameTime) + "ms", fpsColor(t.fps), fpsTextSize);
                if (has(SHOW_GRAPH)) {
                    drawGraph(canvas, 0, y, 160 * dp, graphH, t);
                    y += graphH + 2;
                }
                sb.setLength(0);
                sb.append("AVG ").append(fmt(t.avgFps)).append("  1% ").append(fmt(t.low1)).append("  0.1% ").append(fmt(t.low01));
                y = drawVRow(canvas, y, "", sb.toString(), C_DIM, smallTextSize);

                y = drawVRow(canvas, y, "GPU",
                        (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " +
                        (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " +
                        (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-"), C_GPU);
                if (m.vramGib >= 0) y = drawVRow(canvas, y, "VRAM", fmt1(m.vramGib) + " GiB", C_GPU, smallTextSize);

                y = drawVRow(canvas, y, "CPU",
                        (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " +
                        (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " +
                        (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), C_CPU);
                // 每核心
                if (has(SHOW_CPU_CORES)) {
                    for (int i = 0; i < Math.min(8, m.coreCount); i++) {
                        if (m.coreClock[i] > 0) {
                            y = drawVRow(canvas, y, "C" + i, m.coreClock[i] + "MHz", C_CPU, smallTextSize);
                        }
                    }
                }

                y = drawVRow(canvas, y, "RAM", fmt1(m.ramGib) + "G " + m.ramPercent + "%", C_RAM);
                if (m.swapGib >= 0) y = drawVRow(canvas, y, "SWP", fmt1(m.swapGib) + "G", C_RAM, smallTextSize);

                y = drawVRow(canvas, y, "BAT",
                        m.batPercent + "% " +
                        (m.batTemp >= 0 ? m.batTemp + "°C" : "-") + " " +
                        (m.batPower >= 0 ? fmt1(m.batPower) + "W" : "-") +
                        (m.batTimeMin >= 0 ? " " + fmtTime(m.batTimeMin) : ""), C_BAT);
                if (m.netValid) y = drawVRow(canvas, y, "NET", fmt1(m.netDownKB) + "↓ " + fmt1(m.netUpKB) + "↑", C_DIM, smallTextSize);
                if (has(SHOW_REFRESH_RATE) && m.refreshRate > 0) {
                    y = drawVRow(canvas, y, "DISP", "@" + (int)m.refreshRate + "Hz", C_DIM, smallTextSize);
                }
                if (!sEngine.isEmpty()) y = drawVRow(canvas, y, "", sEngine, C_DIM, smallTextSize);
                if (!m.exeName.isEmpty()) y = drawVRow(canvas, y, "", m.exeName, C_DIM, smallTextSize);
                if (!sWineVersion.isEmpty()) y = drawVRow(canvas, y, "Wine", sWineVersion, C_DIM, smallTextSize);
                y = drawVRow(canvas, y, "", fmtDuration(t.elapsedSec), C_DIM, smallTextSize);
            }
        }

        private float drawVRow(Canvas canvas, float y, String label, String value, int color) {
            return drawVRow(canvas, y, label, value, color, textSize);
        }

        private float drawVRow(Canvas canvas, float y, String label, String value, int color, int size) {
            paint.setTextSize(size);
            float x = 0;
            if (!label.isEmpty()) {
                paint.setColor(C_LABEL);
                canvas.drawText(label, x, y + rowH - 4, paint);
                x += 42 * dp; // 固定标签宽度，左对齐
            }
            paint.setColor(color);
            canvas.drawText(value, x, y + rowH - 4, paint);
            return y + rowH;
        }

        // ==================== 波形图 ====================
        private void drawGraph(Canvas canvas, float x, float y, float w, float h, FrameTracker t) {
            // 背景
            bgRect.set(x, y, x + w, y + h);
            bgPaint.setColor(C_GRAPH_BG);
            canvas.drawRoundRect(bgRect, 2, 2, bgPaint);
            bgPaint.setColor(C_BG);

            // 数据
            if (graphCount < 2) return;
            paint.setColor(C_GRAPH);
            paint.setStrokeWidth(1.5f);
            float maxFps = Math.max(t.avgFps * 1.2f, 60);
            float stepX = w / Math.min(graphCount, graphData.length);
            float lastX = x, lastY = y + h;
            for (int i = 0; i < Math.min(graphCount, graphData.length); i++) {
                int idx = (graphIdx - graphCount + i + graphData.length) % graphData.length;
                float v = graphData[idx];
                float px = x + i * stepX;
                float py = y + h - (v / maxFps) * h;
                py = Math.max(y, Math.min(y + h, py));
                if (i > 0) canvas.drawLine(lastX, lastY, px, py, paint);
                lastX = px; lastY = py;
            }
            paint.setStrokeWidth(1);
        }

        void pushGraph(double fps) {
            graphData[graphIdx] = (float) fps;
            graphIdx = (graphIdx + 1) % graphData.length;
            if (graphCount < graphData.length) graphCount++;
        }

        // ==================== 手势 ====================
        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (locked) return false;
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    downTime = System.currentTimeMillis();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!dragging && Math.abs(ev.getRawX() - downX) > TOUCH_SLOP * dp) {
                        dragging = true;
                    }
                    if (dragging) {
                        offsetX += ev.getRawX() - downX;
                        offsetY += ev.getRawY() - downY;
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    long dt = System.currentTimeMillis() - downTime;
                    if (!dragging && dt < CLICK_TIMEOUT) {
                        long now = System.currentTimeMillis();
                        if (now - lastClickTime < 300) {
                            // 双击：切换方向
                            setOrientation(orientation == ORIENT_HORIZONTAL ? ORIENT_VERTICAL : ORIENT_HORIZONTAL);
                            lastClickTime = 0;
                        } else {
                            // 单击：循环密度
                            setDensity((density + 1) % 3);
                            lastClickTime = now;
                        }
                    } else if (!dragging && dt >= LONG_PRESS) {
                        locked = true;
                    }
                    return true;
            }
            return false;
        }
    }

    // ==================== 格式化工具 ====================
    private static String fmt(double v) {
        if (v < 0) return "-";
        if (v >= 1000) return String.format(Locale.US, "%.0f", v);
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmt1(double v) {
        if (v < 0) return "-";
        return String.format(Locale.US, "%.1f", v);
    }

    private static String fmtTime(int min) {
        if (min < 0) return "-";
        int h = min / 60;
        int m = min % 60;
        return h > 0 ? h + "h" + m + "m" : m + "m";
    }

    private static String fmtDuration(long sec) {
        int h = (int)(sec / 3600);
        int m = (int)((sec % 3600) / 60);
        int s = (int)(sec % 60);
        return h > 0 ? String.format(Locale.US, "%02d:%02d:%02d", h, m, s)
                     : String.format(Locale.US, "%02d:%02d", m, s);
    }

    // ==================== 帧追踪器 ====================
    private static final class FrameTracker {
        private static final int WINDOW = 240;
        private final double[] frameTimes = new double[WINDOW];
        private int ftIdx = 0;
        private int ftCount = 0;
        private long lastFrameNs = 0;
        private long startMs = System.currentTimeMillis();

        double fps = 0;
        double avgFps = 0;
        double low1 = 0;
        double low01 = 0;
        double frameTime = 0;
        long elapsedSec = 0;
        private SystemMetrics metrics;

        void setMetrics(SystemMetrics m) { this.metrics = m; }

        synchronized void recordFrame() {
            long now = System.nanoTime();
            if (lastFrameNs > 0) {
                double dtMs = (now - lastFrameNs) / 1_000_000.0;
                if (dtMs > 0 && dtMs < 1000) {
                    frameTimes[ftIdx] = dtMs;
                    ftIdx = (ftIdx + 1) % WINDOW;
                    if (ftCount < WINDOW) ftCount++;
                    frameTime = dtMs;
                    fps = 1000.0 / dtMs;
                    if (sView != null) sView.pushGraph(fps);
                }
            }
            lastFrameNs = now;
            elapsedSec = (System.currentTimeMillis() - startMs) / 1000;
            calcStats();
        }

        private void calcStats() {
            if (ftCount < 2) { avgFps = fps; low1 = fps; low01 = fps; return; }
            double[] sorted = new double[ftCount];
            System.arraycopy(frameTimes, 0, sorted, 0, ftCount);
            Arrays.sort(sorted);
            double sum = 0;
            for (double v : sorted) sum += v;
            avgFps = 1000.0 / (sum / ftCount);
            int p1 = Math.max(0, (int)(ftCount * 0.99));
            int p01 = Math.max(0, (int)(ftCount * 0.999));
            low1 = 1000.0 / sorted[Math.min(p1, ftCount - 1)];
            low01 = 1000.0 / sorted[Math.min(p01, ftCount - 1)];
        }
    }

    // ==================== 系统指标采集 ====================
    private static final class SystemMetrics {
        private final Context context;
        int coreCount = Runtime.getRuntime().availableProcessors();
        int[] coreClock = new int[coreCount];

        // GPU
        int gpuLoad = -1, gpuTemp = -1, gpuClock = -1;
        float vramGib = -1;
        // CPU
        int cpuLoad = -1, cpuTemp = -1, cpuClock = -1;
        // 内存
        float ramGib = 0, ramPercent = 0, swapGib = -1;
        // 电池
        int batPercent = 0, batTemp = -1;
        float batPower = -1, batTimeMin = -1;
        // 网络
        float netDownKB = 0, netUpKB = 0;
        boolean netValid = false;
        private long lastNetRx = 0, lastNetTx = 0, lastNetTime = 0;
        // 显示
        float refreshRate = 0;
        // 进程
        String exeName = "";

        // CPU 使用率历史
        private long prevCpuTotal = 0, prevCpuIdle = 0;
        private boolean cpuWarmed = false;
        private int[] maxCoreClock = new int[coreCount];
        private boolean maxClockReady = false;

        // GPU 路径缓存
        private List<String> gpuLoadPaths;
        private List<String> gpuClockPaths;

        SystemMetrics(Context ctx) {
            this.context = ctx;
            // 预读最大频率
            new Thread(() -> {
                for (int i = 0; i < coreCount; i++) {
                    long max = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                    maxCoreClock[i] = max > 0 ? (int)(max / 1000) : 2800;
                }
                maxClockReady = true;
            }).start();
            // 刷新率
            try {
                WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
                refreshRate = wm.getDefaultDisplay().getRefreshRate();
            } catch (Exception e) { refreshRate = 0; }
        }

        synchronized void update() {
            readGpu();
            readCpu();
            readRam();
            readBattery();
            readNetwork();
            readExeName();
        }

        // ---- GPU ----
        private void readGpu() {
            gpuLoad = readGpuLoad();
            gpuClock = readGpuClock();
            gpuTemp = readThermal("gpu", "kgsl", "adreno");
            vramGib = readVram();
        }

        private int readGpuLoad() {
            if (gpuLoadPaths == null) {
                gpuLoadPaths = new ArrayList<>();
                String[] candidates = {
                    "/sys/class/kgsl/kgsl-3d0/gpubusy",
                    "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                    "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                    "/sys/class/kgsl/kgsl-3d0/load",
                    "/sys/kernel/gpu/gpu_busy",
                    "/sys/kernel/gpu/gpu_busy_percent",
                    "/sys/devices/platform/gpusysfs/gpu_busy",
                    "/sys/class/devfreq/gpufreq/load",
                };
                for (String p : candidates) if (new File(p).canRead()) gpuLoadPaths.add(p);
                // devfreq 通用探测
                try {
                    File dir = new File("/sys/class/devfreq");
                    if (dir.isDirectory()) {
                        File[] devs = dir.listFiles();
                        if (devs != null) for (File d : devs) {
                            String name = d.getName().toLowerCase();
                            if (name.contains("gpu") || name.contains("kgsl") || name.contains("3d")) {
                                String p = d.getAbsolutePath() + "/load";
                                if (new File(p).canRead()) gpuLoadPaths.add(p);
                                p = d.getAbsolutePath() + "/gpu_busy";
                                if (new File(p).canRead()) gpuLoadPaths.add(p);
                            }
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }
            for (String p : gpuLoadPaths) {
                try {
                    String line = readFirstLine(p);
                    if (line == null) continue;
                    line = line.trim();
                    // gpubusy 格式: "busy total"
                    if (line.contains(" ")) {
                        String[] parts = line.split("\\s+");
                        if (parts.length >= 2) {
                            long busy = Long.parseLong(parts[0]);
                            long total = Long.parseLong(parts[1]);
                            if (total > 0) return (int)(100 * busy / total);
                        }
                    } else {
                        int v = Integer.parseInt(line.replace("%", "").trim());
                        if (v >= 0 && v <= 100) return v;
                    }
                } catch (Exception e) { /* try next */ }
            }
            return -1;
        }

        private int readGpuClock() {
            if (gpuClockPaths == null) {
                gpuClockPaths = new ArrayList<>();
                String[] candidates = {
                    "/sys/class/kgsl/kgsl-3d0/gpuclk",
                    "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                    "/sys/class/kgsl/kgsl-3d0/gpu_clock",
                    "/sys/class/kgsl/kgsl-3d0/clock_mhz",
                    "/sys/kernel/gpu/gpu_clock",
                    "/sys/kernel/gpu/gpuclk",
                    "/sys/devices/platform/gpusysfs/gpu_clock",
                };
                for (String p : candidates) if (new File(p).canRead()) gpuClockPaths.add(p);
                // devfreq 通用
                try {
                    File dir = new File("/sys/class/devfreq");
                    if (dir.isDirectory()) {
                        File[] devs = dir.listFiles();
                        if (devs != null) for (File d : devs) {
                            String name = d.getName().toLowerCase();
                            if (name.contains("gpu") || name.contains("kgsl") || name.contains("3d")) {
                                String p = d.getAbsolutePath() + "/cur_freq";
                                if (new File(p).canRead()) gpuClockPaths.add(p);
                            }
                        }
                    }
                } catch (Exception e) { /* ignore */ }
            }
            for (String p : gpuClockPaths) {
                try {
                    long raw = readLong(p);
                    if (raw <= 0) continue;
                    if (raw > 10_000_000) return (int)(raw / 1_000_000);
                    if (raw > 10_000) return (int)(raw / 1_000);
                    return (int) raw;
                } catch (Exception e) { /* try next */ }
            }
            return -1;
        }

        private float readVram() {
            // 方式1: kgsl per-process
            try {
                File dir = new File("/sys/class/kgsl/kgsl/proc");
                if (dir.isDirectory()) {
                    File[] procs = dir.listFiles();
                    if (procs != null) {
                        long total = 0;
                        for (File proc : procs) {
                            long v = readLong(proc.getAbsolutePath() + "/gpumem_mapped");
                            if (v > 0) total += v;
                        }
                        if (total > 0) return total / 1_073_741_824f;
                    }
                }
            } catch (Exception e) { /* ignore */ }
            // 方式2: 单文件
            String[] paths = {
                "/sys/class/kgsl/kgsl-3d0/gpumem",
                "/sys/class/kgsl/kgsl-3d0/gpumem_mapped",
            };
            for (String p : paths) {
                long v = readLong(p);
                if (v > 0) return v / 1_073_741_824f;
            }
            return -1;
        }

        // ---- CPU ----
        private void readCpu() {
            readCpuLoad();
            readCpuClocks();
            cpuTemp = readThermal("cpu", "soc", "tsens_tz", "cpu0", "cpu1", "cpuss");
        }

        private void readCpuLoad() {
            // 方式1: /proc/stat（Android 7.0+ 可能受限）
            try {
                String line = readFirstLine("/proc/stat");
                if (line != null && line.startsWith("cpu ")) {
                    String[] parts = line.trim().split("\\s+");
                    long total = 0, idle = 0;
                    for (int i = 1; i < parts.length; i++) total += Long.parseLong(parts[i]);
                    idle = Long.parseLong(parts[4]);
                    if (cpuWarmed && prevCpuTotal > 0) {
                        long dt = total - prevCpuTotal;
                        long di = idle - prevCpuIdle;
                        if (dt > 0) {
                            cpuLoad = (int)(100 * (dt - di) / dt);
                            prevCpuIdle = idle;
                            prevCpuTotal = total;
                            cpuWarmed = true;
                            return;
                        }
                    }
                    prevCpuIdle = idle;
                    prevCpuTotal = total;
                    cpuWarmed = true;
                }
            } catch (Exception e) { /* fall through */ }

            // 方式2: 用频率归一化近似（当前频率/最大频率的平均值）
            if (maxClockReady) {
                int sum = 0, counted = 0;
                for (int i = 0; i < coreCount; i++) {
                    if (coreClock[i] > 0 && maxCoreClock[i] > 0) {
                        sum += (int)(100 * coreClock[i] / maxCoreClock[i]);
                        counted++;
                    }
                }
                if (counted > 0) {
                    cpuLoad = Math.min(100, sum / counted);
                    return;
                }
            }
            cpuLoad = -1;
        }

        private void readCpuClocks() {
            long sum = 0;
            int counted = 0;
            for (int i = 0; i < coreCount; i++) {
                long freq = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                if (freq > 0) {
                    int mhz = (int)(freq / 1000);
                    coreClock[i] = mhz;
                    sum += mhz;
                    counted++;
                } else {
                    coreClock[i] = -1;
                }
            }
            cpuClock = counted > 0 ? (int)(sum / counted) : -1;
        }

        // ---- 内存 ----
        private void readRam() {
            try {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                am.getMemoryInfo(mi);
                long used = mi.totalMem - mi.availMem;
                ramGib = used / 1_073_741_824f;
                ramPercent = mi.totalMem > 0 ? (int)(100 * used / mi.totalMem) : 0;
            } catch (Exception e) { ramGib = 0; ramPercent = 0; }
            // Swap
            try {
                BufferedReader br = new BufferedReader(new FileReader("/proc/meminfo"));
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("SwapTotal:")) {
                        long total = parseKb(line);
                        line = br.readLine();
                        if (line != null && line.startsWith("SwapFree:")) {
                            long free = parseKb(line);
                            swapGib = (total - free) / 1_048_576f;
                        }
                        break;
                    }
                }
                br.close();
            } catch (Exception e) { swapGib = -1; }
        }

        private long parseKb(String line) {
            String[] parts = line.trim().split("\\s+");
            return parts.length >= 2 ? Long.parseLong(parts[1]) : 0;
        }

        // ---- 电池 ----
        private void readBattery() {
            try {
                IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent battery = context.registerReceiver(null, f);
                if (battery != null) {
                    int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batPercent = scale > 0 ? (int)(100 * level / scale) : 0;
                    batTemp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (batTemp > 100) batTemp = batTemp / 10; // 单位 0.1°C
                    // 功率 = 电压 × 电流
                    int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1); // mV
                    int current = battery.getIntExtra(BatteryManager.EXTRA_CURRENT_NOW, 0); // μA
                    if (voltage > 0) {
                        batPower = (voltage / 1000f) * (current / 1_000_000f); // V * A = W
                        if (batPower < 0) batPower = -batPower; // 放电时电流为负
                    }
                    // 剩余时间估算（指数平滑）
                    if (batPower > 0.1 && batPercent < 100) {
                        int capacity = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 4000); // mAh 近似
                        float capacityWh = capacity * (voltage / 1000f) / 1000; // Wh
                        float remainingWh = capacityWh * batPercent / 100f;
                        int estMin = (int)(remainingWh / batPower * 60);
                        if (batTimeMin < 0) batTimeMin = estMin;
                        else batTimeMin = batTimeMin * 0.65f + estMin * 0.35f; // 指数平滑
                    } else {
                        batTimeMin = -1;
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }

        // ---- 网络 ----
        private void readNetwork() {
            try {
                long rx = android.net.TrafficStats.getTotalRxBytes();
                long tx = android.net.TrafficStats.getTotalTxBytes();
                long now = System.currentTimeMillis();
                if (lastNetTime > 0) {
                    long dt = now - lastNetTime;
                    if (dt > 0) {
                        netDownKB = (rx - lastNetRx) / 1024f / (dt / 1000f);
                        netUpKB = (tx - lastNetTx) / 1024f / (dt / 1000f);
                        netValid = true;
                    }
                }
                lastNetRx = rx;
                lastNetTx = tx;
                lastNetTime = now;
            } catch (Exception e) { netValid = false; }
        }

        // ---- EXE 名称（从 wine/box64 进程命令行提取）----
        private void readExeName() {
            try {
                File procDir = new File("/proc");
                File[] procs = procDir.listFiles(new FileFilter() {
                    @Override public boolean accept(File f) {
                        return f.isDirectory() && f.getName().matches("\\d+");
                    }
                });
                if (procs == null) return;
                String found = "";
                for (File p : procs) {
                    try {
                        String cmdline = readFirstLine(p.getAbsolutePath() + "/cmdline");
                        if (cmdline == null) continue;
                        cmdline = cmdline.replace('\0', ' ').trim();
                        if (cmdline.contains("wine") || cmdline.contains("box64") || cmdline.contains("box86")) {
                            // 提取 .exe 名称
                            int exeIdx = cmdline.toLowerCase().indexOf(".exe");
                            if (exeIdx > 0) {
                                int start = cmdline.lastIndexOf(' ', exeIdx) + 1;
                                int end = exeIdx + 4;
                                if (start < end) {
                                    found = cmdline.substring(start, end);
                                    break;
                                }
                            }
                        }
                    } catch (Exception e) { /* skip */ }
                }
                if (!found.isEmpty()) exeName = found;
            } catch (Exception e) { /* ignore */ }
        }

        // ---- Thermal 通用探测 ----
        private int readThermal(String... keywords) {
            try {
                File dir = new File("/sys/class/thermal");
                if (!dir.isDirectory()) return -1;
                File[] zones = dir.listFiles(new FileFilter() {
                    @Override public boolean accept(File f) {
                        return f.getName().startsWith("thermal_zone");
                    }
                });
                if (zones == null) return -1;
                // 先按类型匹配
                for (File z : zones) {
                    String type = readFirstLine(z.getAbsolutePath() + "/type");
                    if (type != null) {
                        type = type.trim().toLowerCase();
                        for (String kw : keywords) {
                            if (type.contains(kw.toLowerCase())) {
                                int temp = (int) readLong(z.getAbsolutePath() + "/temp");
                                if (temp > 1000) temp /= 1000;
                                if (temp > 0 && temp < 120) return temp;
                            }
                        }
                    }
                }
                // 兜底：找第一个合理温度
                for (File z : zones) {
                    int temp = (int) readLong(z.getAbsolutePath() + "/temp");
                    if (temp > 1000) temp /= 1000;
                    if (temp >= 20 && temp <= 100) return temp;
                }
            } catch (Exception e) { /* ignore */ }
            return -1;
        }

        // ---- 文件读取工具 ----
        private static String readFirstLine(String path) {
            try {
                BufferedReader br = new BufferedReader(new FileReader(path), 256);
                String line = br.readLine();
                br.close();
                return line;
            } catch (Exception e) { return null; }
        }

        private static long readLong(String path) {
            try {
                String line = readFirstLine(path);
                if (line != null) return Long.parseLong(line.trim());
            } catch (Exception e) { /* ignore */ }
            return -1;
        }
    }
}
