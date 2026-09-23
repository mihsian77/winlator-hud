package com.winlator.hud;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.net.TrafficStats;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * WinlatorHUD v2.0 Ultimate —— 自包含单文件性能监控 HUD。
 *
 * <p>集成 6 个 Winlator 项目的优点：</p>
 * <ul>
 *   <li>横屏/竖屏双布局 + 双击切换（上游 Ludashi WinlatorHUD）</li>
 *   <li>GPU 路径自动发现 16+ 路径（Bannerlator HudMetrics）</li>
 *   <li>FPS stale 检测 1.5s 无帧→0（Bannerlator FpsCounter）</li>
 *   <li>VRAM 三模式探测（WinNative MangoHudView）</li>
 *   <li>零 GC 文本格式化（WinNative MangoHudView）</li>
 *   <li>3 种密度模式 精简/标准/详细（融合 Bannerlator + 上游）</li>
 *   <li>Thermal zone 优先级排序（Bannerlator HudMetrics）</li>
 *   <li>显示刷新率 @120Hz（WinNative MangoHudView）</li>
 *   <li>电池剩余时间平滑计算（CronyX SensorReader）</li>
 *   <li>拖拽 + 长按锁定（已有）</li>
 * </ul>
 *
 * <p>使用方法（3 步）：</p>
 * <pre>
 * WinlatorHUD.init(activity);
 * // 在渲染循环中每帧调用
 * WinlatorHUD.recordFrame();
 * // 可选：设置游戏信息
 * WinlatorHUD.setGameInfo("DXVK", "1920x1080", "Wine 9.0");
 * </pre>
 *
 * <p>手势：点击循环密度模式，双击切换横屏/竖屏，拖拽移动，长按 3.5s 锁定。</p>
 */
public class WinlatorHUD extends View {

    // ==================== 版本 ====================
    public static final String VERSION = "2.0.0-Ultimate";

    // ==================== 元素位掩码（参考上游 Ludashi） ====================
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
    public static final int SHOW_NETWORK      = 1 << 17;
    public static final int SHOW_ENGINE       = 1 << 18;
    public static final int SHOW_RESOLUTION   = 1 << 19;
    public static final int SHOW_WINE_VERSION = 1 << 20;
    public static final int SHOW_DURATION     = 1 << 21;
    public static final int SHOW_THROTTLE     = 1 << 22;
    public static final int SHOW_BATTERY_TIME = 1 << 23;

    public static final int SHOW_DEFAULT = SHOW_FPS | SHOW_FRAMETIME | SHOW_AVG_FPS
            | SHOW_1PC_LOW | SHOW_01PC_LOW | SHOW_GRAPH
            | SHOW_GPU_LOAD | SHOW_GPU_TEMP
            | SHOW_CPU_LOAD | SHOW_CPU_TEMP
            | SHOW_RAM | SHOW_BATTERY | SHOW_ENGINE;

    public static final int SHOW_COMPACT = SHOW_FPS | SHOW_GPU_LOAD | SHOW_CPU_LOAD | SHOW_RAM;
    public static final int SHOW_DETAILED = (1 << 24) - 1; // 全部开启

    // ==================== 密度模式 ====================
    public static final int DENSITY_COMPACT  = 0;
    public static final int DENSITY_NORMAL   = 1;
    public static final int DENSITY_DETAILED = 2;
    private static final String[] DENSITY_NAMES = {"精简", "标准", "详细"};

    // ==================== 颜色 ====================
    private static final int C_BG      = 0xCC000000;
    private static final int C_TEXT    = 0xFFFFFFFF;
    private static final int C_GPU     = 0xFF2E9762;
    private static final int C_CPU     = 0xFF2E97CB;
    private static final int C_VRAM    = 0xFFAD64C1;
    private static final int C_RAM     = 0xFFC26693;
    private static final int C_BAT     = 0xFFFF9078;
    private static final int C_ENGINE  = 0xFFEB5B5B;
    private static final int C_NET     = 0xFFE07B85;
    private static final int C_GRAPH   = 0xFF00FF00;
    private static final int C_OUTLINE = 0xFF000000;
    private static final int C_WARN    = 0xFFFDFD09;
    private static final int C_CRIT    = 0xFFB22222;

    // ==================== 尺寸常量 ====================
    private static final float BASE_TEXT_DP = 14f;
    private static final long TICK_MS = 500L;
    private static final int GRAPH_SAMPLES = 200;
    private static final float GRAPH_CEIL_MS = 50f;
    private static final long STALE_FPS_NS = 1_500_000_000L; // 1.5s 无帧→0
    private static final float TAP_SLOP = 20f;
    private static final long DOUBLE_TAP_MS = 300L;
    private static final long LOCK_HOLD_MS = 3500L;

    // ==================== GPU 路径（参考 Bannerlator HudMetrics） ====================
    private static final String[] GPU_USAGE_STATIC_PATHS = {
        "/sys/class/kgsl/kgsl-3d0/gpubusy",
        "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
        "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
        "/sys/class/misc/mali0/device/utilisation",
        "/sys/class/misc/mali0/device/utilization",
        "/sys/class/misc/mali0/device/gpuinfo",
        "/sys/devices/platform/mali/utilization",
        "/sys/kernel/gpu/gpu_busy",
        "/sys/devices/platform/gpusysfs/gpu_busy",
        "/sys/class/misc/pvrsrvkm/device/utilisation",
        "/sys/class/pvr/utilisation",
        "/sys/class/pvr/gpu_utilisation",
        "/sys/class/drm/card0/device/gpu_busy_percent",
        "/sys/class/devfreq/gpu/load",
        "/sys/kernel/ged/hal/gpu_utilization",
        "/sys/module/ged/parameters/gpu_loading"
    };
    private static final String[] GPU_USAGE_FILES = {
        "gpu_busy_percentage", "gpu_busy_percent", "gpu_load",
        "utilisation", "utilization", "load", "gpu_busy", "gpuinfo"
    };
    private static final String[] GPU_NODE_TOKENS = {
        "gpu", "mali", "g3d", "kgsl", "panfrost", "pvr", "powervr", "xclipse", "sgpu"
    };

    // ==================== 静态单例 ====================
    private static volatile WinlatorHUD instance;
    private static volatile boolean initialized;

    // ==================== 游戏信息 ====================
    private volatile String engineName = "";
    private volatile String engineVersion = "";
    private volatile String resolution = "";
    private volatile String wineVersion = "";

    // ==================== 配置 ====================
    private int showMask = SHOW_DEFAULT;
    private int density = DENSITY_NORMAL;
    private boolean vertical = false;
    private boolean locked = false;
    private float scaleFactor = 0.75f;
    private float textAlpha = 1.0f;
    private float bgAlpha = 0.7f;
    private int posX = -1, posY = -1;

    // ==================== FPS 追踪 ====================
    private final FrameTracker frameTracker = new FrameTracker();

    // ==================== 系统指标 ====================
    private final SystemMetrics metrics;

    // ==================== 绘制相关 ====================
    private final Paint bgPaint = new Paint();
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint smallPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint outlinePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint graphPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();

    private float textSize, smallSize, rowH, smallRowH, baseline, smallBaseline;
    private float pad, charW, smallCharW, labelColW, graphH;
    private int panelW = 1, panelH = 1;

    // ==================== 零 GC 文本缓冲区（参考 WinNative） ====================
    private final StringBuilder sbFps = new StringBuilder(8);
    private final StringBuilder sbMs = new StringBuilder(8);
    private final StringBuilder sbAvg = new StringBuilder(8);
    private final StringBuilder sbLow1 = new StringBuilder(8);
    private final StringBuilder sbLow01 = new StringBuilder(8);
    private final StringBuilder sbGpuLoad = new StringBuilder(4);
    private final StringBuilder sbGpuTemp = new StringBuilder(4);
    private final StringBuilder sbGpuClk = new StringBuilder(6);
    private final StringBuilder sbVram = new StringBuilder(6);
    private final StringBuilder sbCpuLoad = new StringBuilder(4);
    private final StringBuilder sbCpuTemp = new StringBuilder(4);
    private final StringBuilder sbCpuClk = new StringBuilder(6);
    private final StringBuilder sbRam = new StringBuilder(6);
    private final StringBuilder sbRamPct = new StringBuilder(4);
    private final StringBuilder sbSwap = new StringBuilder(6);
    private final StringBuilder sbBatPct = new StringBuilder(4);
    private final StringBuilder sbBatTemp = new StringBuilder(4);
    private final StringBuilder sbBatW = new StringBuilder(6);
    private final StringBuilder sbBatTime = new StringBuilder(8);
    private final StringBuilder sbNetRx = new StringBuilder(6);
    private final StringBuilder sbNetTx = new StringBuilder(6);
    private final StringBuilder sbDuration = new StringBuilder(12);
    private final StringBuilder sbMinMax = new StringBuilder(20);

    // ==================== 线程 ====================
    private HandlerThread statsThread;
    private Handler statsHandler;
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private final Runnable tickRunnable = new Runnable() {
        @Override public void run() {
            Handler h = statsHandler;
            if (h == null) return;
            if (getVisibility() == VISIBLE) {
                metrics.update();
                formatAll();
                postInvalidate();
            }
            h.postDelayed(this, TICK_MS);
        }
    };

    // ==================== 触摸状态 ====================
    private int activePointerId = -1;
    private float touchOffsetX, touchOffsetY, downRawX, downRawY;
    private long downTime;
    private long lastTapTime = 0;
    private boolean dragging;
    private final Runnable lockRunnable = new Runnable() {
        @Override public void run() {
            locked = true;
            activePointerId = -1;
            try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Exception ignored) {}
            postInvalidate();
        }
    };

    private final long sessionStartMs = SystemClock.elapsedRealtime();

    // ==================== 静态 API ====================

    public static WinlatorHUD init(Activity activity) {
        return init(activity, SHOW_DEFAULT, DENSITY_NORMAL);
    }

    public static WinlatorHUD init(Activity activity, int showMask, int density) {
        if (activity == null) throw new IllegalArgumentException("activity must not be null");
        synchronized (WinlatorHUD.class) {
            if (initialized) return instance;
            WinlatorHUD hud = new WinlatorHUD(activity, showMask, density);
            ViewGroup root = (ViewGroup) activity.getWindow().getDecorView();
            root.addView(hud);
            instance = hud;
            initialized = true;
            return hud;
        }
    }

    public static WinlatorHUD getInstance() { return instance; }

    public static void recordFrame() {
        WinlatorHUD h = instance;
        if (h != null) h.frameTracker.recordFrame(System.nanoTime());
    }

    public static void setGameInfo(String engine, String resolution, String wineVersion) {
        setGameInfo(engine, null, resolution, wineVersion);
    }

    public static void setGameInfo(String engine, String engineVersion, String resolution, String wineVersion) {
        WinlatorHUD h = instance;
        if (h != null) {
            h.engineName = engine != null ? engine : "";
            h.engineVersion = engineVersion != null ? engineVersion : "";
            h.resolution = resolution != null ? resolution : "";
            h.wineVersion = wineVersion != null ? wineVersion : "";
            h.postInvalidate();
        }
    }

    public static void show() { setVisible(true); }
    public static void hide() { setVisible(false); }

    public static void setVisible(boolean visible) {
        WinlatorHUD h = instance;
        if (h != null) h.setVisibility(visible ? VISIBLE : GONE);
    }

    public static boolean isInitialized() { return initialized; }

    public static void release() {
        synchronized (WinlatorHUD.class) {
            WinlatorHUD h = instance;
            if (h != null && h.getParent() != null) {
                ((ViewGroup) h.getParent()).removeView(h);
            }
            instance = null;
            initialized = false;
        }
    }

    // ==================== 构造函数 ====================

    public WinlatorHUD(Context context, int showMask, int density) {
        super(context);
        this.showMask = showMask;
        this.density = density;
        this.metrics = new SystemMetrics(context);

        Typeface mono;
        try {
            mono = Typeface.create(Typeface.createFromFile("/system/fonts/DroidSansMono.ttf"), Typeface.BOLD);
        } catch (Exception e) {
            mono = Typeface.create("monospace", Typeface.BOLD);
        }
        valuePaint.setTypeface(mono);
        valuePaint.setColor(C_TEXT);
        labelPaint.setTypeface(mono);
        smallPaint.setTypeface(mono);
        smallPaint.setColor(C_TEXT);
        outlinePaint.setTypeface(mono);
        outlinePaint.setColor(C_OUTLINE);
        outlinePaint.setStyle(Paint.Style.STROKE);
        graphPaint.setColor(C_GRAPH);
        graphPaint.setStyle(Paint.Style.STROKE);

        setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setElevation(1000f);
        applyScale();
        applyAlphas();
        computeLayout();
        setVisibility(VISIBLE);
    }

    // ==================== 配置方法 ====================

    public void setShowMask(int mask) { this.showMask = mask; computeLayout(); postInvalidate(); }
    public int getShowMask() { return showMask; }

    public void setDensity(int density) {
        this.density = Math.max(DENSITY_COMPACT, Math.min(DENSITY_DETAILED, density));
        applyDensityMask();
        computeLayout();
        postInvalidate();
    }
    public int getDensity() { return density; }

    public void setVertical(boolean vertical) {
        this.vertical = vertical;
        computeLayout();
        postInvalidate();
    }
    public boolean isVertical() { return vertical; }

    public void setScale(float scale) {
        scaleFactor = Math.max(0.5f, Math.min(1.5f, scale));
        applyScale();
        computeLayout();
        postInvalidate();
    }

    public void setTextAlpha(float alpha) {
        textAlpha = Math.max(0f, Math.min(1f, alpha));
        applyAlphas();
        postInvalidate();
    }

    public void setBackgroundAlpha(float alpha) {
        bgAlpha = Math.max(0f, Math.min(1f, alpha));
        applyAlphas();
        postInvalidate();
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        if (locked) { removeCallbacks(lockRunnable); activePointerId = -1; }
        postInvalidate();
    }

    public void resetStats() { frameTracker.reset(); }

    private void applyDensityMask() {
        switch (density) {
            case DENSITY_COMPACT:  showMask = SHOW_COMPACT; break;
            case DENSITY_NORMAL:   showMask = SHOW_DEFAULT; break;
            case DENSITY_DETAILED: showMask = SHOW_DETAILED; break;
        }
    }

    private void applyScale() {
        float density = getResources().getDisplayMetrics().density;
        textSize = BASE_TEXT_DP * density * scaleFactor;
        smallSize = textSize * 0.55f;
        valuePaint.setTextSize(textSize);
        labelPaint.setTextSize(textSize);
        smallPaint.setTextSize(smallSize);
        outlinePaint.setStrokeWidth(Math.max(1.5f, textSize * 0.08f));
        graphPaint.setStrokeWidth(Math.max(1.5f, textSize * 0.07f));

        Paint.FontMetrics fm = valuePaint.getFontMetrics();
        rowH = (fm.descent - fm.ascent) * 1.02f;
        baseline = -fm.ascent;
        Paint.FontMetrics sfm = smallPaint.getFontMetrics();
        smallRowH = (sfm.descent - sfm.ascent) * 1.1f;
        smallBaseline = -sfm.ascent;
        pad = textSize * 0.25f;
        charW = valuePaint.measureText("0");
        smallCharW = smallPaint.measureText("0");
        graphH = textSize * 2.0f;
        labelColW = charW * 4.2f;
    }

    private void applyAlphas() {
        int bg = Math.round(bgAlpha * 255f);
        bgPaint.setColor((bg << 24) | (C_BG & 0x00FFFFFF));
        int text = Math.round(textAlpha * 255f);
        valuePaint.setAlpha(text);
        labelPaint.setAlpha(text);
        smallPaint.setAlpha(text);
        graphPaint.setAlpha(text);
        outlinePaint.setAlpha(text);
    }

    // ==================== 布局计算 ====================

    private boolean has(int flag) { return (showMask & flag) != 0; }

    private void computeLayout() {
        if (vertical) {
            computeVerticalLayout();
        } else {
            computeHorizontalLayout();
        }
    }

    private void computeHorizontalLayout() {
        float w = 0f;
        int rows = 0;
        int smallRows = 0;

        if (has(SHOW_GPU_LOAD) || has(SHOW_GPU_TEMP) || has(SHOW_GPU_CLOCK)) {
            rows++;
            float rw = labelColW
                + (has(SHOW_GPU_LOAD) ? cellW(3,1) : 0)
                + (has(SHOW_GPU_TEMP) ? cellW(3,2) : 0)
                + (has(SHOW_GPU_CLOCK) ? cellW(4,3) : 0);
            w = Math.max(w, rw);
        }
        if (has(SHOW_CPU_LOAD) || has(SHOW_CPU_TEMP) || has(SHOW_CPU_CLOCK)) {
            rows++;
            float rw = labelColW
                + (has(SHOW_CPU_LOAD) ? cellW(3,1) : 0)
                + (has(SHOW_CPU_TEMP) ? cellW(3,2) : 0)
                + (has(SHOW_CPU_CLOCK) ? cellW(4,3) : 0);
            w = Math.max(w, rw);
        }
        if (has(SHOW_CPU_CORES)) {
            rows += metrics.coreCount;
            w = Math.max(w, labelColW + cellW(3,1) + cellW(4,3));
        }
        if (has(SHOW_VRAM)) { rows++; w = Math.max(w, labelColW + cellW(4,3)); }
        if (has(SHOW_RAM)) { rows++; w = Math.max(w, labelColW + cellW(4,3) + cellW(3,1)); }
        if (has(SHOW_SWAP)) { rows++; w = Math.max(w, labelColW + cellW(4,3)); }
        if (has(SHOW_NETWORK)) { rows++; w = Math.max(w, labelColW + cellW(4,2) + cellW(4,2)); }
        if (has(SHOW_BATTERY) || has(SHOW_BATTERY_TIME)) {
            rows++;
            float rw = labelColW + cellW(3,1) + cellW(3,2);
            if (has(SHOW_BATTERY)) rw += cellW(4,1);
            w = Math.max(w, rw);
        }

        rows++; // FPS 行
        w = Math.max(w, labelColW + cellW(4,3) + cellW(4,2));

        if (has(SHOW_AVG_FPS)) { rows++; w = Math.max(w, labelColW + cellW(4,3)); }
        if (has(SHOW_1PC_LOW)) { rows++; w = Math.max(w, labelColW + cellW(4,3)); }
        if (has(SHOW_01PC_LOW)) { rows++; w = Math.max(w, labelColW + cellW(4,3)); }

        if (has(SHOW_ENGINE) && !engineName.isEmpty()) { smallRows++; w = Math.max(w, smallCharW * (engineName.length() + 8)); }
        if (has(SHOW_RESOLUTION) && !resolution.isEmpty()) { smallRows++; w = Math.max(w, smallCharW * (resolution.length() + 12)); }
        if (has(SHOW_WINE_VERSION) && !wineVersion.isEmpty()) { smallRows++; w = Math.max(w, smallCharW * wineVersion.length()); }
        if (has(SHOW_DURATION)) { smallRows++; w = Math.max(w, smallCharW * 16); }
        if (has(SHOW_THROTTLE) && metrics.throttleStatus > 0) { smallRows++; }

        w = Math.max(w, charW * 13f);
        float h = rows * rowH + smallRows * smallRowH;
        if (has(SHOW_GRAPH)) h += smallRowH + graphH + pad * 0.5f;

        panelW = (int) Math.ceil(w + pad * 2);
        panelH = (int) Math.ceil(h + pad * 2);
    }

    private void computeVerticalLayout() {
        // 竖屏：单列，每个分类占 1-2 行
        float w = 0f;
        int rows = 0;

        if (has(SHOW_GPU_LOAD) || has(SHOW_GPU_TEMP) || has(SHOW_GPU_CLOCK)) {
            rows += 2; // 标签行 + 数据行
            w = Math.max(w, smallCharW * 12);
        }
        if (has(SHOW_CPU_LOAD) || has(SHOW_CPU_TEMP) || has(SHOW_CPU_CLOCK)) {
            rows += 2;
            w = Math.max(w, smallCharW * 12);
        }
        if (has(SHOW_VRAM)) { rows += 2; w = Math.max(w, smallCharW * 10); }
        if (has(SHOW_RAM)) { rows += 2; w = Math.max(w, smallCharW * 12); }
        if (has(SHOW_BATTERY) || has(SHOW_BATTERY_TIME)) { rows += 2; w = Math.max(w, smallCharW * 14); }
        if (has(SHOW_FPS) || has(SHOW_FRAMETIME)) { rows += 2; w = Math.max(w, smallCharW * 12); }
        if (has(SHOW_AVG_FPS)) { rows++; w = Math.max(w, smallCharW * 10); }
        if (has(SHOW_1PC_LOW)) { rows++; w = Math.max(w, smallCharW * 10); }
        if (has(SHOW_01PC_LOW)) { rows++; w = Math.max(w, smallCharW * 10); }
        if (has(SHOW_ENGINE) && !engineName.isEmpty()) { rows++; w = Math.max(w, smallCharW * engineName.length()); }
        if (has(SHOW_DURATION)) { rows++; w = Math.max(w, smallCharW * 12); }

        if (has(SHOW_GRAPH)) rows += 3; // 标签 + 图表

        panelW = (int) Math.ceil(w + pad * 2);
        panelH = (int) Math.ceil(rows * smallRowH + pad * 2);
    }

    private float cellW(int valueChars, int unitChars) {
        return charW * valueChars + smallCharW * (unitChars + 0.2f);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(panelW, panelH);
    }

    // ==================== 绘制 ====================

    @Override
    protected void onDraw(Canvas canvas) {
        bgRect.set(0, 0, panelW, panelH);
        canvas.drawRoundRect(bgRect, pad, pad, bgPaint);

        if (vertical) {
            drawVertical(canvas);
        } else {
            drawHorizontal(canvas);
        }

        // 锁定指示器
        if (locked) {
            smallPaint.setColor(C_WARN);
            canvas.drawText("🔒", pad, panelH - pad, smallPaint);
        }
    }

    private void drawHorizontal(Canvas canvas) {
        float y = pad;

        if (has(SHOW_GPU_LOAD) || has(SHOW_GPU_TEMP) || has(SHOW_GPU_CLOCK)) {
            float x = drawLabel(canvas, "GPU", C_GPU, y);
            if (has(SHOW_GPU_LOAD)) x = drawCell(canvas, sbGpuLoad, "%", x, y, 3);
            if (has(SHOW_GPU_TEMP)) x = drawCell(canvas, sbGpuTemp, "°C", x, y, 3);
            if (has(SHOW_GPU_CLOCK)) drawCell(canvas, sbGpuClk, "MHz", x, y, 4);
            y += rowH;
        }
        if (has(SHOW_CPU_LOAD) || has(SHOW_CPU_TEMP) || has(SHOW_CPU_CLOCK)) {
            float x = drawLabel(canvas, "CPU", C_CPU, y);
            if (has(SHOW_CPU_LOAD)) x = drawCell(canvas, sbCpuLoad, "%", x, y, 3);
            if (has(SHOW_CPU_TEMP)) x = drawCell(canvas, sbCpuTemp, "°C", x, y, 3);
            if (has(SHOW_CPU_CLOCK)) drawCell(canvas, sbCpuClk, "MHz", x, y, 4);
            y += rowH;
        }
        if (has(SHOW_CPU_CORES)) {
            for (int i = 0; i < metrics.coreCount; i++) {
                float x = drawLabel(canvas, "C" + i, C_CPU, y);
                x = drawCellInt(canvas, metrics.coreLoad[i], "%", x, y, 3);
                drawCellInt(canvas, metrics.coreClock[i], "MHz", x, y, 4);
                y += rowH;
            }
        }
        if (has(SHOW_VRAM)) {
            float x = drawLabel(canvas, "VRAM", C_VRAM, y);
            drawCell(canvas, sbVram, "GiB", x, y, 4);
            y += rowH;
        }
        if (has(SHOW_RAM)) {
            float x = drawLabel(canvas, "RAM", C_RAM, y);
            x = drawCell(canvas, sbRam, "GiB", x, y, 4);
            drawCell(canvas, sbRamPct, "%", x, y, 3);
            y += rowH;
        }
        if (has(SHOW_SWAP)) {
            float x = drawLabel(canvas, "SWP", C_RAM, y);
            drawCell(canvas, sbSwap, "GiB", x, y, 4);
            y += rowH;
        }
        if (has(SHOW_NETWORK)) {
            float x = drawLabel(canvas, "NET", C_NET, y);
            x = drawCell(canvas, sbNetRx, "K↓", x, y, 4);
            drawCell(canvas, sbNetTx, "K↑", x, y, 4);
            y += rowH;
        }
        if (has(SHOW_BATTERY) || has(SHOW_BATTERY_TIME)) {
            float x = drawLabel(canvas, "BAT", C_BAT, y);
            if (has(SHOW_BATTERY)) {
                x = drawCell(canvas, sbBatPct, "%", x, y, 3);
                x = drawCell(canvas, sbBatTemp, "°C", x, y, 3);
                x = drawCell(canvas, sbBatW, "W", x, y, 4);
            }
            if (has(SHOW_BATTERY_TIME)) {
                drawSmall(canvas, sbBatTime.toString(), x, y + baseline, C_BAT);
            }
            y += rowH;
        }

        // FPS 行
        {
            float x = drawLabel(canvas, "FPS", C_TEXT, y);
            x = drawCell(canvas, sbFps, "FPS", x, y, 4);
            if (has(SHOW_FRAMETIME)) drawCell(canvas, sbMs, "ms", x, y, 4);
            y += rowH;
        }

        if (has(SHOW_AVG_FPS)) { float x = drawLabel(canvas, "AVG", C_TEXT, y); drawCell(canvas, sbAvg, "FPS", x, y, 4); y += rowH; }
        if (has(SHOW_1PC_LOW)) { float x = drawLabel(canvas, "1%", C_TEXT, y); drawCell(canvas, sbLow1, "FPS", x, y, 4); y += rowH; }
        if (has(SHOW_01PC_LOW)) { float x = drawLabel(canvas, "0.1%", C_TEXT, y); drawCell(canvas, sbLow01, "FPS", x, y, 4); y += rowH; }

        if (has(SHOW_ENGINE) && !engineName.isEmpty()) {
            String eng = engineName + (engineVersion.isEmpty() ? "" : " " + engineVersion);
            drawSmall(canvas, eng, pad, y + smallBaseline, C_ENGINE);
            y += smallRowH;
        }
        if (has(SHOW_RESOLUTION) && !resolution.isEmpty()) {
            String res = resolution + getRefreshRateSuffix();
            drawSmall(canvas, res, pad, y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_WINE_VERSION) && !wineVersion.isEmpty()) {
            drawSmall(canvas, wineVersion, pad, y + smallBaseline, C_ENGINE);
            y += smallRowH;
        }
        if (has(SHOW_DURATION)) {
            drawSmall(canvas, sbDuration.toString(), pad, y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_THROTTLE) && metrics.throttleStatus > 0) {
            String[] txt = {"", "throttle: light", "throttle: moderate", "throttle: severe",
                "throttle: critical", "throttle: emergency", "throttle: shutdown"};
            int idx = Math.min(metrics.throttleStatus, txt.length - 1);
            int color = metrics.throttleStatus >= 3 ? C_CRIT : C_WARN;
            drawSmall(canvas, txt[idx], pad, y + smallBaseline, color);
            y += smallRowH;
        }

        if (has(SHOW_GRAPH)) {
            drawSmall(canvas, "Frametime", pad, y + smallBaseline, C_ENGINE);
            float mmW = smallPaint.measureText(sbMinMax.toString());
            drawSmall(canvas, sbMinMax.toString(), panelW - pad - mmW, y + smallBaseline, C_TEXT);
            y += smallRowH + pad * 0.5f;
            drawGraph(canvas, pad, y, panelW - pad * 2, graphH);
        }
    }

    private void drawVertical(Canvas canvas) {
        float y = pad;
        float cx = panelW / 2f;

        if (has(SHOW_GPU_LOAD) || has(SHOW_GPU_TEMP) || has(SHOW_GPU_CLOCK)) {
            drawSmallCentered(canvas, "GPU", y + smallBaseline, C_GPU);
            y += smallRowH;
            StringBuilder sb = new StringBuilder();
            if (has(SHOW_GPU_LOAD)) sb.append(sbGpuLoad).append("% ");
            if (has(SHOW_GPU_TEMP)) sb.append(sbGpuTemp).append("°C ");
            if (has(SHOW_GPU_CLOCK)) sb.append(sbGpuClk).append("MHz");
            drawSmallCentered(canvas, sb.toString(), y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_CPU_LOAD) || has(SHOW_CPU_TEMP) || has(SHOW_CPU_CLOCK)) {
            drawSmallCentered(canvas, "CPU", y + smallBaseline, C_CPU);
            y += smallRowH;
            StringBuilder sb = new StringBuilder();
            if (has(SHOW_CPU_LOAD)) sb.append(sbCpuLoad).append("% ");
            if (has(SHOW_CPU_TEMP)) sb.append(sbCpuTemp).append("°C ");
            if (has(SHOW_CPU_CLOCK)) sb.append(sbCpuClk).append("MHz");
            drawSmallCentered(canvas, sb.toString(), y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_VRAM)) {
            drawSmallCentered(canvas, "VRAM", y + smallBaseline, C_VRAM);
            y += smallRowH;
            drawSmallCentered(canvas, sbVram + " GiB", y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_RAM)) {
            drawSmallCentered(canvas, "RAM", y + smallBaseline, C_RAM);
            y += smallRowH;
            drawSmallCentered(canvas, sbRam + "GiB " + sbRamPct + "%", y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_BATTERY) || has(SHOW_BATTERY_TIME)) {
            drawSmallCentered(canvas, "BAT", y + smallBaseline, C_BAT);
            y += smallRowH;
            StringBuilder sb = new StringBuilder();
            if (has(SHOW_BATTERY)) sb.append(sbBatPct).append("% ").append(sbBatTemp).append("°C ");
            if (has(SHOW_BATTERY_TIME)) sb.append(sbBatTime);
            drawSmallCentered(canvas, sb.toString(), y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_FPS) || has(SHOW_FRAMETIME)) {
            drawSmallCentered(canvas, "FPS", y + smallBaseline, C_TEXT);
            y += smallRowH;
            StringBuilder sb = new StringBuilder();
            sb.append(sbFps);
            if (has(SHOW_FRAMETIME)) sb.append(" ").append(sbMs).append("ms");
            drawSmallCentered(canvas, sb.toString(), y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_AVG_FPS)) { drawSmallCentered(canvas, "AVG " + sbAvg + " FPS", y + smallBaseline, C_TEXT); y += smallRowH; }
        if (has(SHOW_1PC_LOW)) { drawSmallCentered(canvas, "1% " + sbLow1 + " FPS", y + smallBaseline, C_TEXT); y += smallRowH; }
        if (has(SHOW_01PC_LOW)) { drawSmallCentered(canvas, "0.1% " + sbLow01 + " FPS", y + smallBaseline, C_TEXT); y += smallRowH; }
        if (has(SHOW_ENGINE) && !engineName.isEmpty()) {
            drawSmallCentered(canvas, engineName, y + smallBaseline, C_ENGINE);
            y += smallRowH;
        }
        if (has(SHOW_DURATION)) {
            drawSmallCentered(canvas, sbDuration.toString(), y + smallBaseline, C_TEXT);
            y += smallRowH;
        }
        if (has(SHOW_GRAPH)) {
            drawSmallCentered(canvas, "Frametime", y + smallBaseline, C_ENGINE);
            y += smallRowH;
            drawGraph(canvas, pad, y, panelW - pad * 2, graphH);
        }
    }

    private float drawLabel(Canvas canvas, String text, int color, float rowTop) {
        float y = rowTop + baseline;
        outlinePaint.setTextSize(textSize);
        canvas.drawText(text, pad, y, outlinePaint);
        labelPaint.setColor(color);
        canvas.drawText(text, pad, y, labelPaint);
        return pad + labelColW;
    }

    private float drawCell(Canvas canvas, CharSequence text, String unit, float x, float rowTop, int valueChars) {
        return drawCellText(canvas, text.toString(), unit, x, rowTop, valueChars);
    }

    private float drawCellInt(Canvas canvas, int value, String unit, float x, float rowTop, int valueChars) {
        String text = value < 0 ? "-" : String.valueOf(value);
        return drawCellText(canvas, text, unit, x, rowTop, valueChars);
    }

    private float drawCellText(Canvas canvas, String text, String unit, float x, float rowTop, int valueChars) {
        float y = rowTop + baseline;
        float vx = x + charW * valueChars - text.length() * charW;
        outlinePaint.setTextSize(textSize);
        canvas.drawText(text, vx, y, outlinePaint);
        canvas.drawText(text, vx, y, valuePaint);
        float ux = x + charW * valueChars + smallCharW * 0.1f;
        drawSmall(canvas, unit, ux, y, C_TEXT);
        return x + cellW(valueChars, unit.length());
    }

    private void drawSmall(Canvas canvas, String text, float x, float y, int color) {
        outlinePaint.setTextSize(smallSize);
        canvas.drawText(text, x, y, outlinePaint);
        smallPaint.setColor(color);
        canvas.drawText(text, x, y, smallPaint);
    }

    private void drawSmallCentered(Canvas canvas, String text, float y, int color) {
        float w = smallPaint.measureText(text);
        float x = (panelW - w) / 2f;
        drawSmall(canvas, text, x, y, color);
    }

    private void drawGraph(Canvas canvas, float left, float top, float width, float height) {
        float[] data = frameTracker.getGraphData();
        if (data.length < 2) return;
        float stepX = width / (GRAPH_SAMPLES - 1);
        float startX = left + (GRAPH_SAMPLES - data.length) * stepX;
        float prevX = startX;
        float prevY = graphY(data[0], top, height);
        for (int i = 1; i < data.length; i++) {
            float cx = startX + i * stepX;
            float cy = graphY(data[i], top, height);
            canvas.drawLine(prevX, prevY, cx, cy, graphPaint);
            prevX = cx;
            prevY = cy;
        }
    }

    private static float graphY(float ms, float top, float height) {
        if (ms > GRAPH_CEIL_MS - 0.1f) ms = GRAPH_CEIL_MS - 0.1f;
        if (ms < 0f) ms = 0f;
        return top + height - (ms / GRAPH_CEIL_MS) * height;
    }

    private String getRefreshRateSuffix() {
        try {
            WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                float hz = wm.getDefaultDisplay().getRefreshRate();
                if (hz > 0f) return " @" + Math.round(hz) + "Hz";
            }
        } catch (Exception ignored) {}
        return "";
    }

    // ==================== 零 GC 文本格式化（参考 WinNative） ====================

    private void formatAll() {
        formatInt(sbFps, Math.round(frameTracker.getCurrentFps()));
        formatTenths(sbMs, frameTracker.getCurrentFrametime());
        formatInt(sbAvg, Math.round(frameTracker.getAverageFps()));
        formatInt(sbLow1, Math.round(frameTracker.getOnePercentLow()));
        formatInt(sbLow01, Math.round(frameTracker.getZeroPointOnePercentLow()));

        formatInt(sbGpuLoad, metrics.gpuLoad);
        formatInt(sbGpuTemp, metrics.gpuTemp);
        formatInt(sbGpuClk, metrics.gpuClock);
        formatTenths(sbVram, metrics.vramGib);

        formatInt(sbCpuLoad, metrics.cpuLoad);
        formatInt(sbCpuTemp, metrics.cpuTemp);
        formatInt(sbCpuClk, metrics.cpuClock);

        formatTenths(sbRam, metrics.ramGib);
        formatInt(sbRamPct, metrics.ramPercent);
        formatTenths(sbSwap, metrics.swapGib);

        formatInt(sbBatPct, metrics.batteryPercent);
        formatInt(sbBatTemp, metrics.batteryTemp);
        formatTenths(sbBatW, metrics.batteryWatts);
        sbBatTime.setLength(0);
        sbBatTime.append(metrics.batteryTimeText);

        formatInt(sbNetRx, metrics.networkRxKbs);
        formatInt(sbNetTx, metrics.networkTxKbs);

        // Duration
        long elapsed = SystemClock.elapsedRealtime() - sessionStartMs;
        long totalSec = elapsed / 1000;
        sbDuration.setLength(0);
        sbDuration.append(String.format(Locale.US, "%02d:%02d:%02d",
            totalSec / 3600, (totalSec / 60) % 60, totalSec % 60));

        // Graph min/max
        sbMinMax.setLength(0);
        sbMinMax.append("min:").append(String.format(Locale.US, "%.1f", frameTracker.getMinFrametime()))
            .append(" max:").append(String.format(Locale.US, "%.1f", frameTracker.getMaxFrametime()));

        computeLayout();
        int w = panelW, h = panelH;
        post(() -> {
            if (getMeasuredWidth() != w || getMeasuredHeight() != h) requestLayout();
        });
    }

    private static void formatInt(StringBuilder sb, int value) {
        sb.setLength(0);
        if (value < 0) sb.append('-');
        else sb.append(value);
    }

    private static void formatTenths(StringBuilder sb, float value) {
        sb.setLength(0);
        if (value < 0f) sb.append('-');
        else if (value >= 100f) sb.append(Math.round(value));
        else sb.append(String.format(Locale.US, "%.1f", value));
    }

    // ==================== 触摸交互 ====================

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (locked) return false;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                activePointerId = event.getPointerId(0);
                touchOffsetX = getX() - event.getRawX();
                touchOffsetY = getY() - event.getRawY();
                downRawX = event.getRawX();
                downRawY = event.getRawY();
                downTime = SystemClock.elapsedRealtime();
                dragging = false;
                bringToFront();
                postDelayed(lockRunnable, LOCK_HOLD_MS);
                return true;

            case MotionEvent.ACTION_MOVE:
                if (activePointerId != -1) {
                    if (Math.abs(event.getRawX() - downRawX) > TAP_SLOP
                        || Math.abs(event.getRawY() - downRawY) > TAP_SLOP) {
                        dragging = true;
                    }
                    if (dragging) {
                        removeCallbacks(lockRunnable);
                        setX(event.getRawX() + touchOffsetX);
                        setY(event.getRawY() + touchOffsetY);
                        clampToParent();
                    }
                    return true;
                }
                break;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                removeCallbacks(lockRunnable);
                if (activePointerId != -1) {
                    if (dragging) {
                        clampToParent();
                        posX = (int) getX();
                        posY = (int) getY();
                    } else {
                        // 点击：检测双击
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastTapTime < DOUBLE_TAP_MS) {
                            // 双击：切换横屏/竖屏
                            vertical = !vertical;
                            computeLayout();
                            requestLayout();
                            lastTapTime = 0;
                        } else {
                            // 单击：循环密度模式
                            density = (density + 1) % 3;
                            applyDensityMask();
                            computeLayout();
                            requestLayout();
                            lastTapTime = now;
                        }
                        postInvalidate();
                    }
                    activePointerId = -1;
                    return true;
                }
                return false;
        }
        return false;
    }

    private void clampToParent() {
        View parent = (View) getParent();
        if (parent == null || parent.getWidth() <= 0 || parent.getHeight() <= 0) return;
        float maxX = Math.max(0f, parent.getWidth() - getWidth());
        float maxY = Math.max(0f, parent.getHeight() - getHeight());
        setX(Math.max(0f, Math.min(getX(), maxX)));
        setY(Math.max(0f, Math.min(getY(), maxY)));
    }

    // ==================== 生命周期 ====================

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        statsThread = new HandlerThread("WinlatorHUD-Stats");
        statsThread.start();
        statsHandler = new Handler(statsThread.getLooper());
        statsHandler.post(tickRunnable);
        if (posX >= 0 && posY >= 0) {
            post(() -> { setX(posX); setY(posY); });
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(lockRunnable);
        if (statsHandler != null) statsHandler.removeCallbacksAndMessages(null);
        if (statsThread != null) statsThread.quitSafely();
        statsHandler = null;
        statsThread = null;
        super.onDetachedFromWindow();
    }

    // ==================== FrameTracker 内部类 ====================

    private static final class FrameTracker {
        private static final int FPS_WINDOW = 1024;
        private static final int LOWS_WINDOW = 5000;
        private static final int GRAPH_SIZE = 200;

        private final Object lock = new Object();
        private final long[] fpsStamps = new long[FPS_WINDOW];
        private int fpsStart, fpsCount;
        private final float[] lowsMs = new float[LOWS_WINDOW];
        private int lowsIndex, lowsCount;
        private final float[] graphMs = new float[GRAPH_SIZE];
        private int graphIndex, graphCount;
        private long lastFrameNano;
        private float currentMs;

        void recordFrame(long timestampNanos) {
            synchronized (lock) {
                if (lastFrameNano != 0 && timestampNanos - lastFrameNano > STALE_FPS_NS) {
                    resetLocked();
                }
                if (lastFrameNano != 0) {
                    float ms = (timestampNanos - lastFrameNano) / 1_000_000f;
                    if (ms > 0f && ms < 1000f) {
                        currentMs = ms;
                        lowsMs[lowsIndex] = ms;
                        lowsIndex = (lowsIndex + 1) % LOWS_WINDOW;
                        if (lowsCount < LOWS_WINDOW) lowsCount++;
                        graphMs[graphIndex] = ms;
                        graphIndex = (graphIndex + 1) % GRAPH_SIZE;
                        if (graphCount < GRAPH_SIZE) graphCount++;
                    }
                }
                lastFrameNano = timestampNanos;
                int idx = (fpsStart + fpsCount) % FPS_WINDOW;
                if (fpsCount == FPS_WINDOW) fpsStart = (fpsStart + 1) % FPS_WINDOW;
                else fpsCount++;
                fpsStamps[idx] = timestampNanos;
            }
        }

        float getCurrentFps() {
            synchronized (lock) {
                // Stale 检测：1.5s 无帧返回 0
                if (fpsCount > 0) {
                    long last = fpsStamps[(fpsStart + fpsCount - 1) % FPS_WINDOW];
                    if (System.nanoTime() - last > STALE_FPS_NS) return 0f;
                }
                if (fpsCount <= 1) return 0f;
                long now = System.nanoTime();
                long oldest = now - 1_000_000_000L;
                while (fpsCount > 0 && fpsStamps[fpsStart] < oldest) {
                    fpsStart = (fpsStart + 1) % FPS_WINDOW;
                    fpsCount--;
                }
                if (fpsCount <= 1) return 0f;
                long first = fpsStamps[fpsStart];
                long last = fpsStamps[(fpsStart + fpsCount - 1) % FPS_WINDOW];
                if (last <= first) return 0f;
                return (fpsCount - 1) * 1_000_000_000f / (last - first);
            }
        }

        float getAverageFps() {
            synchronized (lock) {
                if (lowsCount == 0) return 0f;
                float sum = 0f;
                for (int i = 0; i < lowsCount; i++) sum += lowsMs[i];
                if (sum <= 0f) return 0f;
                return lowsCount * 1000f / sum;
            }
        }

        float getOnePercentLow() { return getPercentileLow(0.01f); }
        float getZeroPointOnePercentLow() { return getPercentileLow(0.001f); }

        private float getPercentileLow(float percentile) {
            synchronized (lock) {
                if (lowsCount == 0) return 0f;
                float[] sorted = new float[lowsCount];
                System.arraycopy(lowsMs, 0, sorted, 0, lowsCount);
                Arrays.sort(sorted);
                int idx = (int) (percentile * lowsCount) - 1;
                if (idx < 0) idx = 0;
                float ft = sorted[lowsCount - 1 - idx];
                return ft > 0f ? 1000f / ft : 0f;
            }
        }

        float getCurrentFrametime() { synchronized (lock) { return currentMs; } }

        float getMinFrametime() {
            synchronized (lock) {
                if (graphCount == 0) return 0f;
                float min = Float.MAX_VALUE;
                for (int i = 0; i < graphCount; i++) if (graphMs[i] < min) min = graphMs[i];
                return min == Float.MAX_VALUE ? 0f : min;
            }
        }

        float getMaxFrametime() {
            synchronized (lock) {
                if (graphCount == 0) return 0f;
                float max = 0f;
                for (int i = 0; i < graphCount; i++) if (graphMs[i] > max) max = graphMs[i];
                return max;
            }
        }

        float[] getGraphData() {
            synchronized (lock) {
                float[] result = new float[graphCount];
                for (int i = 0; i < graphCount; i++) {
                    result[i] = graphMs[(graphIndex - graphCount + i + GRAPH_SIZE) % GRAPH_SIZE];
                }
                return result;
            }
        }

        void reset() { synchronized (lock) { resetLocked(); } }

        private void resetLocked() {
            fpsStart = 0; fpsCount = 0;
            lowsIndex = 0; lowsCount = 0;
            graphIndex = 0; graphCount = 0;
            lastFrameNano = 0; currentMs = 0f;
        }
    }

    // ==================== SystemMetrics 内部类 ====================

    private static final class SystemMetrics {
        int gpuLoad = -1, gpuTemp = -1, gpuClock = -1;
        float vramGib = -1f;
        int cpuLoad = -1, cpuTemp = -1, cpuClock = -1;
        int[] coreLoad, coreClock;
        final int coreCount;
        float ramGib = -1f;
        int ramPercent = -1;
        float swapGib = -1f;
        int batteryPercent = -1, batteryTemp = -1;
        float batteryWatts = -1f;
        String batteryTimeText = "--:--";
        int networkRxKbs = -1, networkTxKbs = -1;
        int throttleStatus = -1;

        private final Context context;
        private final BatteryManager batteryManager;
        private final IntentFilter batteryFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);

        private long prevRxBytes = -1, prevTxBytes = -1, prevNetTime;
        private long prevCpuIdle, prevCpuTotal;
        private boolean cpuWarmedUp;

        private List<String> gpuLoadPathsCache;
        private List<String[]> thermalZonesCache;
        private long lastMaliGpuInfoMs = -1;
        private long lastMaliGpuInfoWallMs = 0;

        // VRAM 三模式（参考 WinNative）
        private int vramMode = -1; // -1=探测, 1=kgsl own-pid, 2=单文件, 3=ActivityManager
        private String vramPath;

        // 电池剩余时间平滑（参考 CronyX）
        private Double smoothedBatteryHours = null;
        private static final double RUNTIME_SMOOTH_OLD = 0.65;
        private static final double RUNTIME_SMOOTH_NEW = 0.35;
        private static final double MAX_RUNTIME_HOURS = 72.0;

        SystemMetrics(Context context) {
            this.context = context.getApplicationContext();
            this.batteryManager = (BatteryManager) this.context.getSystemService(Context.BATTERY_SERVICE);
            this.coreCount = Math.min(Runtime.getRuntime().availableProcessors(), 10);
            this.coreLoad = new int[coreCount];
            this.coreClock = new int[coreCount];
            for (int i = 0; i < coreCount; i++) { coreLoad[i] = -1; coreClock[i] = -1; }
        }

        void update() {
            readGpuLoad();
            readGpuTemp();
            readGpuClock();
            readVram();
            readCpuLoad();
            readCpuTemp();
            readCpuClocks();
            readRam();
            readSwap();
            readBattery();
            readNetwork();
            readThrottle();
        }

        // ---- GPU 使用率（参考 Bannerlator：16+ 路径 + 动态扫描） ----
        private void readGpuLoad() {
            int load = -1;
            for (String path : discoverGpuLoadPaths()) {
                load = readGpuUsageSample(path);
                if (load >= 0) break;
            }
            gpuLoad = load >= 0 ? Math.min(load, 100) : -1;
        }

        private List<String> discoverGpuLoadPaths() {
            if (gpuLoadPathsCache != null) return gpuLoadPathsCache;
            LinkedHashSet<String> paths = new LinkedHashSet<>();
            for (String p : GPU_USAGE_STATIC_PATHS) {
                File f = new File(p);
                if (f.exists() && f.canRead()) paths.add(p);
            }
            // 动态扫描 devfreq 节点
            File[] devfreqDirs = {new File("/sys/class/devfreq"), new File("/sys/devices/platform")};
            for (File root : devfreqDirs) {
                File[] nodes = root.listFiles();
                if (nodes == null) continue;
                for (File node : nodes) {
                    String name = node.getName().toLowerCase(Locale.US);
                    boolean isGpu = false;
                    for (String token : GPU_NODE_TOKENS) {
                        if (name.contains(token)) { isGpu = true; break; }
                    }
                    if (!isGpu) continue;
                    for (String fileName : GPU_USAGE_FILES) {
                        File candidate = new File(node, fileName);
                        if (candidate.exists() && candidate.canRead()) {
                            paths.add(candidate.getAbsolutePath());
                        }
                    }
                }
            }
            gpuLoadPathsCache = new ArrayList<>(paths);
            return gpuLoadPathsCache;
        }

        private int readGpuUsageSample(String path) {
            String fileName = path.substring(path.lastIndexOf('/') + 1);
            try {
                if ("gpubusy".equals(fileName)) {
                    String raw = readFirstLine(path);
                    if (raw != null) {
                        String[] parts = raw.trim().split("\\s+");
                        if (parts.length >= 2) {
                            long busy = Long.parseLong(parts[0]);
                            long total = Long.parseLong(parts[1]);
                            if (total > 0) return (int) ((100 * busy) / total);
                        }
                    }
                }
                if ("gpuinfo".equals(fileName)) {
                    // Mali gpuinfo: 需要时间差计算
                    return readMaliDeltaGpuLoad(path);
                }
                // 纯百分比格式
                String raw = readFirstLine(path);
                if (raw != null) {
                    for (String tok : raw.trim().split("\\s+")) {
                        String digits = tok.replaceAll("[^0-9]", "");
                        if (!digits.isEmpty()) {
                            int v = Integer.parseInt(digits);
                            if (v >= 0 && v <= 100) return v;
                        }
                    }
                }
            } catch (Exception ignored) {}
            return -1;
        }

        private int readMaliDeltaGpuLoad(String path) {
            try {
                String line = readNthLine(path, 1);
                if (line == null) return -1;
                String[] toks = line.trim().split("\\s+");
                if (toks.length < 2) return -1;
                long gpuTime = Long.parseLong(toks[0].replaceAll("[^0-9]", ""));
                long now = SystemClock.elapsedRealtime();
                if (lastMaliGpuInfoMs >= 0) {
                    long wallDelta = now - lastMaliGpuInfoWallMs;
                    long gpuDelta = gpuTime - lastMaliGpuInfoMs;
                    if (wallDelta > 0 && gpuDelta >= 0) {
                        int load = (int) (100 * gpuDelta / wallDelta);
                        lastMaliGpuInfoMs = gpuTime;
                        lastMaliGpuInfoWallMs = now;
                        return Math.min(load, 100);
                    }
                }
                lastMaliGpuInfoMs = gpuTime;
                lastMaliGpuInfoWallMs = now;
            } catch (Exception ignored) {}
            return -1;
        }

        // ---- GPU 温度（thermal zone 优先级排序） ----
        private void readGpuTemp() {
            gpuTemp = readThermalTemp("gpu");
        }

        private void readCpuTemp() {
            cpuTemp = readThermalTemp("cpu");
        }

        private int readThermalTemp(String type) {
            for (String[] zone : discoverThermalZones()) {
                String zoneType = zone[0].toLowerCase(Locale.US);
                if (zoneType.contains(type) || zoneType.contains("soc") || zoneType.contains("tsens")) {
                    try {
                        String raw = readFirstLine(zone[1]);
                        if (raw != null) {
                            int v = Integer.parseInt(raw.trim());
                            int celsius = v > 1000 ? (v + 500) / 1000 : v;
                            if (celsius >= 1 && celsius <= 150) return celsius;
                        }
                    } catch (Exception ignored) {}
                }
            }
            return -1;
        }

        private List<String[]> discoverThermalZones() {
            if (thermalZonesCache != null) return thermalZonesCache;
            List<String[]> zones = new ArrayList<>();
            File[] roots = {new File("/sys/class/thermal"), new File("/sys/devices/virtual/thermal")};
            for (File root : roots) {
                File[] list = root.listFiles((dir, name) ->
                    name.startsWith("thermal_zone") && new File(dir, name).isDirectory());
                if (list == null) continue;
                for (File zone : list) {
                    try {
                        String type = readFirstLine(new File(zone, "type").getAbsolutePath());
                        File temp = new File(zone, "temp");
                        if (type != null && temp.exists() && temp.canRead()) {
                            zones.add(new String[]{type.trim(), temp.getAbsolutePath()});
                        }
                    } catch (Exception ignored) {}
                }
            }
            thermalZonesCache = zones;
            return zones;
        }

        // ---- GPU 频率 ----
        private void readGpuClock() {
            String[] paths = {
                "/sys/class/kgsl/kgsl-3d0/gpuclk",
                "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq",
                "/sys/class/kgsl/kgsl-3d0/gpu_clock",
                "/sys/class/kgsl/kgsl-3d0/clock_mhz",
                "/sys/kernel/gpu/gpu_clock"
            };
            for (String path : paths) {
                long raw = readLong(path);
                if (raw > 0) {
                    if (raw > 10_000_000) gpuClock = (int) (raw / 1_000_000);
                    else if (raw > 10_000) gpuClock = (int) (raw / 1_000);
                    else gpuClock = (int) raw;
                    return;
                }
            }
            gpuClock = -1;
        }

        // ---- VRAM 三模式探测（参考 WinNative） ----
        private void readVram() {
            if (vramMode == -1) {
                vramMode = 3;
                if (readOwnPidsGpuMem() >= 0) vramMode = 1;
                else {
                    String[] candidates = {
                        "/sys/class/kgsl/kgsl/page_alloc",
                        "/sys/class/kgsl/kgsl-3d0/page_alloc",
                        "/sys/kernel/gpu/gpu_memory"
                    };
                    for (String path : candidates) {
                        if (readLong(path) > 0) { vramPath = path; vramMode = 2; break; }
                    }
                }
            }
            long bytes = -1;
            if (vramMode == 1) bytes = readOwnPidsGpuMem();
            else if (vramMode == 2) bytes = readLong(vramPath);
            if (bytes < 0) {
                try {
                    android.os.Debug.MemoryInfo mi = new android.os.Debug.MemoryInfo();
                    android.os.Debug.getMemoryInfo(mi);
                    String kb = mi.getMemoryStat("summary.graphics");
                    if (kb != null) bytes = Long.parseLong(kb) * 1024L;
                } catch (Exception ignored) {}
            }
            vramGib = bytes >= 0 ? bytes / 1_073_741_824f : -1f;
        }

        private long readOwnPidsGpuMem() {
            String[] names = new File("/proc").list();
            if (names == null) return -1;
            int myUid = android.os.Process.myUid();
            long total = 0;
            boolean any = false;
            for (String name : names) {
                if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
                try {
                    if (android.system.Os.stat("/proc/" + name).st_uid != myUid) continue;
                } catch (Exception e) { continue; }
                long v = readLong("/sys/class/kgsl/kgsl/proc/" + name + "/gpumem_mapped");
                if (v >= 0) { total += v; any = true; }
            }
            return any ? total : -1;
        }

        // ---- CPU 使用率 ----
        private void readCpuLoad() {
            try {
                long idle = 0, total = 0;
                String line = readFirstLine("/proc/stat");
                if (line != null && line.startsWith("cpu ")) {
                    String[] parts = line.trim().split("\\s+");
                    for (int i = 1; i < parts.length; i++) total += Long.parseLong(parts[i]);
                    idle = Long.parseLong(parts[4]);
                }
                if (cpuWarmedUp && prevCpuTotal > 0) {
                    long dt = total - prevCpuTotal;
                    long di = idle - prevCpuIdle;
                    if (dt > 0) cpuLoad = (int) (100 * (dt - di) / dt);
                }
                prevCpuIdle = idle;
                prevCpuTotal = total;
                cpuWarmedUp = true;
            } catch (Exception e) { cpuLoad = -1; }
        }

        // ---- CPU 频率 ----
        private void readCpuClocks() {
            long sum = 0;
            int counted = 0;
            for (int i = 0; i < coreCount; i++) {
                long freq = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                if (freq > 0) {
                    int mhz = (int) (freq / 1000);
                    coreClock[i] = mhz;
                    sum += mhz;
                    counted++;
                } else coreClock[i] = -1;
            }
            cpuClock = counted > 0 ? (int) (sum / counted) : -1;
        }

        // ---- 内存 ----
        private void readRam() {
            try {
                ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
                ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                am.getMemoryInfo(mi);
                long used = mi.totalMem - mi.availMem;
                ramGib = used / 1_073_741_824f;
                ramPercent = mi.totalMem > 0 ? (int) (100 * used / mi.totalMem) : -1;
            } catch (Exception e) { ramGib = -1f; ramPercent = -1; }
        }

        // ---- Swap ----
        private void readSwap() {
            try (BufferedReader reader = new BufferedReader(new FileReader("/proc/meminfo"))) {
                long swapTotal = -1, swapFree = -1;
                String line;
                while ((line = reader.readLine()) != null && (swapTotal < 0 || swapFree < 0)) {
                    if (line.startsWith("SwapTotal:")) swapTotal = parseKb(line);
                    else if (line.startsWith("SwapFree:")) swapFree = parseKb(line);
                }
                swapGib = swapTotal >= 0 && swapFree >= 0 ? (swapTotal - swapFree) / 1_048_576f : -1f;
            } catch (Exception e) { swapGib = -1f; }
        }

        private static long parseKb(String line) {
            String digits = line.replaceAll("[^0-9]", "");
            return digits.isEmpty() ? -1 : Long.parseLong(digits);
        }

        // ---- 电池（含剩余时间平滑，参考 CronyX） ----
        private void readBattery() {
            try {
                int pct = batteryManager != null
                    ? batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) : -1;
                batteryPercent = pct > 0 ? pct : -1;

                long currentUa = batteryManager != null
                    ? batteryManager.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) : 0;
                float amps = -1f;
                if (currentUa != 0 && currentUa != Long.MIN_VALUE) {
                    long abs = Math.abs(currentUa);
                    amps = abs < 20_000 ? abs / 1000f : abs / 1_000_000f;
                }

                Intent intent = context.registerReceiver(null, batteryFilter);
                int mv = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0) : 0;
                batteryWatts = mv > 0 && amps > 0f ? (mv / 1000f) * amps : -1f;

                int tenths = intent != null ? intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) : 0;
                batteryTemp = tenths > 0 ? tenths / 10 : -1;

                // 电池剩余时间（指数平滑）
                boolean charging = intent != null && intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    == BatteryManager.BATTERY_STATUS_CHARGING;
                if (!charging && batteryPercent > 0 && amps > 0f) {
                    long chargeCounter = readSysfsLong("/sys/class/power_supply/battery/charge_counter");
                    if (chargeCounter > 0) {
                        double hours = chargeCounter / 1000.0 / (amps * 1000.0);
                        hours = Math.min(hours, MAX_RUNTIME_HOURS);
                        if (smoothedBatteryHours == null) smoothedBatteryHours = hours;
                        else smoothedBatteryHours = RUNTIME_SMOOTH_OLD * smoothedBatteryHours
                            + RUNTIME_SMOOTH_NEW * hours;
                        batteryTimeText = formatHours(smoothedBatteryHours);
                    } else if (batteryPercent > 0 && amps > 0f) {
                        double hours = (batteryPercent / 100.0 * 5000) / (amps * 1000.0);
                        hours = Math.min(hours, MAX_RUNTIME_HOURS);
                        if (smoothedBatteryHours == null) smoothedBatteryHours = hours;
                        else smoothedBatteryHours = RUNTIME_SMOOTH_OLD * smoothedBatteryHours
                            + RUNTIME_SMOOTH_NEW * hours;
                        batteryTimeText = formatHours(smoothedBatteryHours);
                    }
                } else {
                    smoothedBatteryHours = null;
                    batteryTimeText = charging ? "充电中" : "--:--";
                }
            } catch (Exception e) {
                batteryPercent = -1; batteryWatts = -1f; batteryTemp = -1;
            }
        }

        private static String formatHours(double hours) {
            if (hours < 0 || hours > MAX_RUNTIME_HOURS) return "--:--";
            int h = (int) hours;
            int m = (int) ((hours - h) * 60);
            return String.format(Locale.US, "%dh%02dm", h, m);
        }

        private long readSysfsLong(String path) {
            try {
                String raw = readFirstLine(path);
                if (raw != null) {
                    String digits = raw.trim().replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) return Long.parseLong(digits);
                }
            } catch (Exception ignored) {}
            return -1;
        }

        // ---- 网络 ----
        private void readNetwork() {
            try {
                long rx = TrafficStats.getTotalRxBytes();
                long tx = TrafficStats.getTotalTxBytes();
                long now = SystemClock.elapsedRealtime();
                if (rx == TrafficStats.UNSUPPORTED || tx == TrafficStats.UNSUPPORTED) {
                    networkRxKbs = -1; networkTxKbs = -1; return;
                }
                if (prevRxBytes >= 0 && now > prevNetTime) {
                    long elapsed = now - prevNetTime;
                    networkRxKbs = (int) ((rx - prevRxBytes) * 1000 / elapsed / 1024);
                    networkTxKbs = (int) ((tx - prevTxBytes) * 1000 / elapsed / 1024);
                }
                prevRxBytes = rx; prevTxBytes = tx; prevNetTime = now;
            } catch (Exception e) { networkRxKbs = -1; networkTxKbs = -1; }
        }

        // ---- 温控 ----
        private void readThrottle() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { throttleStatus = 0; return; }
            try {
                PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                int status = pm != null ? pm.getCurrentThermalStatus() : 0;
                throttleStatus = Math.max(0, Math.min(status, 6));
            } catch (Exception e) { throttleStatus = 0; }
        }

        // ---- 工具方法 ----
        private static String readFirstLine(String path) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                return reader.readLine();
            } catch (Exception e) { return null; }
        }

        private static String readNthLine(String path, int n) {
            try (BufferedReader reader = new BufferedReader(new FileReader(path))) {
                String line;
                int i = 0;
                while ((line = reader.readLine()) != null) {
                    if (i == n) return line;
                    i++;
                }
            } catch (Exception ignored) {}
            return null;
        }

        private static long readLong(String path) {
            try {
                String raw = readFirstLine(path);
                if (raw != null) {
                    String digits = raw.trim().replaceAll("[^0-9]", "");
                    if (!digits.isEmpty()) return Long.parseLong(digits);
                }
            } catch (Exception ignored) {}
            return -1;
        }
    }
}
