/*
 * WinlatorHUD v3.8 — Winlator 专用性能监控叠加层
 *
 * 基于 Android View 渲染管线，零 Vulkan layer 依赖，零闪烁。
 * 横向顶部横条 + 竖向侧边紧凑面板，各 4 级密度（精简/标准/详细/MEGA）。
 *
 * v3.8 变更:
 *   - 数据源自检面板：13个数据源绿/黄/红状态指示灯，可视化显示哪些读到/被拦
 *   - 公共 API: toggleSelfCheck() / setSelfCheckEnabled() / buildSelfCheckReport()
 *
 * v3.7 变更（记录扩展 + Demo App + 一键补丁脚本）:
 *   - CSV/JSON 记录加入热节流状态和磁盘 I/O（21列）
 *   - 新增 Demo App 模块（独立可运行，模拟帧率，无需 Winlator）
 *   - patch/patch_winlator_hud.py 一键集成补丁生成器
 *
 * v3.6 变更（参考 Xnick417x/WinNative FrameRating + SC0O8Y/GameNative-Performance，独立实现）：
 *   - 功耗读取升级：BatteryManager 官方 API + 2 个 sysfs 三级回退（修复多数设备显示"-"问题）
 *   - 热节流状态：PowerManager.getCurrentThermalStatus() 六级显示（none→shutdown）
 *   - 帧时间直方图：PC MangoHud 招牌功能，详细/MEGA 模式显示帧时间分布
 *   - 磁盘 I/O：/proc/self/io 读取进程读写速率（尽力而为）
 *
 * v3.5 变更：GPU/CPU/RAM 负载值自动变色 / 仪表盘进度条 / 3套预设主题
 * v3.4 变更：多 Fork 接入指南
 * v3.3 变更：CSV/JSON 性能记录导出
 * v3.2 变更：MEGA 密度级 / 锁定徽章动画 / 可配置外观 / Mali GPU / 温度优先级排序
 * v3.1 变更：配置持久化 / setPresentedFps() 帧生成 / 诊断导出
 *
 * 手势：单击循环密度，双击切换横/竖，拖拽移动，长按1.5s锁定。
 */
package com.winlator.hud;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
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
import android.os.SystemClock;
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
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class WinlatorHUD {

    private static final String PREFS_NAME = "winlator_hud_prefs";
    private static final String KEY_DENSITY = "density";
    private static final String KEY_ORIENTATION = "orientation";
    private static final String KEY_OFFSET_X = "offset_x";
    private static final String KEY_BG_ALPHA = "bg_alpha";
    private static final String KEY_OUTLINE = "outline_intensity";

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
    public static final int SHOW_BAT_TIME     = 1 << 24;
    public static final int SHOW_REFRESH_RATE = 1 << 25;
    public static final int SHOW_EXE_NAME     = 1 << 26;
    public static final int SHOW_DX_VERSION   = 1 << 27;

    // ==================== 预设密度 ====================
    public static final int SHOW_COMPACT = SHOW_FPS | SHOW_GPU_LOAD | SHOW_CPU_LOAD | SHOW_RAM | SHOW_BATTERY;

    public static final int SHOW_DEFAULT = SHOW_FPS | SHOW_FRAMETIME | SHOW_AVG_FPS
            | SHOW_1PC_LOW | SHOW_01PC_LOW | SHOW_GRAPH
            | SHOW_GPU_LOAD | SHOW_GPU_TEMP | SHOW_GPU_CLOCK | SHOW_VRAM
            | SHOW_CPU_LOAD | SHOW_CPU_TEMP | SHOW_CPU_CLOCK
            | SHOW_RAM | SHOW_BATTERY | SHOW_BAT_POWER | SHOW_BAT_TIME
            | SHOW_ENGINE | SHOW_EXE_NAME | SHOW_DURATION;

    public static final int SHOW_DETAILED = SHOW_FPS | SHOW_FRAMETIME | SHOW_AVG_FPS
            | SHOW_1PC_LOW | SHOW_01PC_LOW | SHOW_GRAPH
            | SHOW_GPU_LOAD | SHOW_GPU_TEMP | SHOW_GPU_CLOCK | SHOW_VRAM
            | SHOW_CPU_LOAD | SHOW_CPU_TEMP | SHOW_CPU_CLOCK | SHOW_CPU_CORES
            | SHOW_RAM | SHOW_SWAP | SHOW_BATTERY | SHOW_BAT_POWER | SHOW_BAT_TIME
            | SHOW_NETWORK | SHOW_ENGINE | SHOW_RESOLUTION | SHOW_WINE_VERSION
            | SHOW_DURATION | SHOW_REFRESH_RATE | SHOW_EXE_NAME;

    public static final int SHOW_MEGA = SHOW_DETAILED | SHOW_DX_VERSION;

    // ==================== 密度模式 ====================
    public static final int DENSITY_COMPACT  = 0;
    public static final int DENSITY_NORMAL   = 1;
    public static final int DENSITY_DETAILED = 2;
    public static final int DENSITY_MEGA     = 3;
    private static final String[] DENSITY_NAMES = {"精简", "标准", "详细", "MEGA"};

    // ==================== 方向模式 ====================
    public static final int ORIENT_HORIZONTAL = 0;
    public static final int ORIENT_VERTICAL   = 1;
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
    private static final int C_LOCK     = 0xFFFFFFFF;

    // ==================== v3.5 主题 ====================
    public static final int THEME_DEFAULT = 0; // 蓝绿配色（原默认）
    public static final int THEME_GREEN   = 1; // 暗夜绿（参考 Xnick417x PerformanceHud）
    public static final int THEME_AMBER   = 2; // 暖橙（低蓝光，适合夜间）

    private static int sTheme = THEME_DEFAULT;

    // 主题色表：[主题][指标类型]
    // 指标类型：0=GPU, 1=CPU, 2=RAM, 3=BAT, 4=LABEL, 5=TEXT, 6=DIM
    private static final int[][] THEME_COLORS = {
        // THEME_DEFAULT（原配色）
        { 0xFF4CAF50, 0xFF2196F3, 0xFF9C27B0, 0xFFFF9800, 0xFF888888, 0xFFFFFFFF, 0xFFAAAAAA },
        // THEME_GREEN（暗夜绿）
        { 0xFF35D0BA, 0xFF1A9FFF, 0xFF7C4DFF, 0xFFFFB020, 0xFF7A8FA8, 0xFFF0F4FF, 0xFF7A8FA8 },
        // THEME_AMBER（暖橙）
        { 0xFFFFA726, 0xFFEF6C00, 0xFF8D6E63, 0xFFFFCC80, 0xFFBDBDBD, 0xFFFFF8E1, 0xFFBDBDBD },
    };

    /** v3.5+ 获取当前主题的指标颜色 */
    private static int themeColor(int type) {
        return THEME_COLORS[sTheme][type];
    }

    /** v3.5+ 负载值自动变色：绿<60% → 黄<85% → 红≥85% */
    private static int loadColor(int load) {
        if (load < 0) return themeColor(0); // 不可用用主题色
        if (load >= 85) return 0xFFF44336; // 红
        if (load >= 60) return 0xFFFFC107; // 黄
        return 0xFF4CAF50; // 绿
    }

    /** v3.5+ 温度自动变色：绿<60°C → 黄<80°C → 红≥80°C */
    private static int tempColor(int temp) {
        if (temp < 0) return themeColor(0);
        if (temp >= 80) return 0xFFF44336;
        if (temp >= 60) return 0xFFFFC107;
        return 0xFF4CAF50;
    }

    // ==================== 锁定徽章动画时序 ====================
    private static final long FADE_IN_MS = 160;
    private static final long HOLD_TOGGLE_MS = 1500;
    private static final long FADE_OUT_MS = 420;

    // ==================== 单例状态 ====================
    private static HUDView sView;
    private static FrameTracker sTracker;
    private static SystemMetrics sMetrics;
    private static HandlerThread sThread;
    private static Handler sHandler;
    private static boolean sRunning;
    private static SharedPreferences sPrefs;

    private static String sEngine = "";
    private static String sResolution = "";
    private static String sWineVersion = "";
    private static String sDxVersion = "";

    private static volatile float sPresentedFps = 0f;

    // v3.3: 性能记录
    private static RecordingSession sRecording;
    private static long sRecordIntervalMs = 1000;
    private static long sLastRecordTime = 0;

    private static final Runnable sTick = new Runnable() {
        @Override public void run() {
            if (sRunning && sMetrics != null) {
                sMetrics.update();
                if (sView != null) sView.postInvalidate();
                // v3.3: 性能记录采样（IO 线程，零渲染开销）
                if (sRecording != null && sRecording.isActive()) {
                    long now = SystemClock.uptimeMillis();
                    if (now - sLastRecordTime >= sRecordIntervalMs) {
                        sRecording.addPoint(sTracker, sMetrics, sPresentedFps);
                        sLastRecordTime = now;
                    }
                }
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
        sPrefs = activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        int savedDensity = sPrefs.getInt(KEY_DENSITY, -1);
        if (savedDensity >= DENSITY_COMPACT && savedDensity <= DENSITY_MEGA) {
            density = savedDensity;
        }
        int savedOrient = sPrefs.getInt(KEY_ORIENTATION, -1);
        if (savedOrient == ORIENT_HORIZONTAL || savedOrient == ORIENT_VERTICAL) {
            orientation = savedOrient;
        }

        sTracker = new FrameTracker();
        sMetrics = new SystemMetrics(activity);
        sTracker.setMetrics(sMetrics);

        sView = new HUDView(activity);
        sView.setShowMask(showMask);
        sView.setDensity(density);
        sView.setOrientation(orientation);
        sView.restoreOffset(sPrefs.getInt(KEY_OFFSET_X, 0));
        sView.setBgAlpha(sPrefs.getInt(KEY_BG_ALPHA, 0xCC));
        sView.setOutlineIntensity(sPrefs.getFloat(KEY_OUTLINE, 0.4f));
        sTheme = sPrefs.getInt("theme", THEME_DEFAULT);

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

    public static void setPresentedFps(float fps) {
        sPresentedFps = fps > 0 ? fps : 0f;
    }

    public static void setGameInfo(String engine, String resolution, String wineVersion) {
        sEngine = engine != null ? engine : "";
        sResolution = resolution != null ? resolution : "";
        sWineVersion = wineVersion != null ? wineVersion : "";
    }

    /** v3.2+ 设置 DX 版本（MEGA 模式显示） */
    public static void setDxVersion(String dxVersion) {
        sDxVersion = dxVersion != null ? dxVersion : "";
    }

    /** v3.2+ 设置背景透明度（0-255，默认 0xCC） */
    public static void setBgAlpha(int alpha) {
        if (sView != null) sView.setBgAlpha(alpha);
    }

    /** v3.2+ 设置描边强度（0-1，默认 0.4） */
    public static void setOutlineIntensity(float intensity) {
        if (sView != null) sView.setOutlineIntensity(intensity);
    }

    /**
     * v3.5+ 设置主题配色。
     * @param theme THEME_DEFAULT / THEME_GREEN / THEME_AMBER
     */
    public static void setTheme(int theme) {
        if (theme >= 0 && theme < THEME_COLORS.length) {
            sTheme = theme;
            if (sPrefs != null) sPrefs.edit().putInt("theme", theme).apply();
            if (sView != null) sView.postInvalidate();
        }
    }

    /** v3.5+ 获取当前主题 */
    public static int getTheme() { return sTheme; }

    // ==================== v3.3 性能记录 ====================

    /**
     * 开始性能记录。清空之前的记录数据，从当前时刻开始采样。
     * 默认采样间隔 1 秒，可通过 setRecordInterval() 调整。
     * 记录在 IO 线程执行，不影响渲染性能。
     */
    public static void startRecording() {
        sRecording = new RecordingSession();
        sLastRecordTime = 0;
    }

    /**
     * 停止性能记录。记录数据保留，可继续导出。
     * 再次调用 startRecording() 会清空并重新开始。
     */
    public static void stopRecording() {
        if (sRecording != null) sRecording.stop();
    }

    /** 是否正在记录 */
    public static boolean isRecording() {
        return sRecording != null && sRecording.isActive();
    }

    /** 已记录的数据点数量 */
    public static int getRecordingPointCount() {
        return sRecording != null ? sRecording.getPointCount() : 0;
    }

    /** 记录持续时间（毫秒） */
    public static long getRecordingDurationMs() {
        return sRecording != null ? sRecording.getDurationMs() : 0;
    }

    /**
     * 设置记录采样间隔（毫秒），默认 1000ms。
     * 最小 200ms（受 HUD 500ms 刷新周期限制，实际最小约 500ms）。
     */
    public static void setRecordInterval(long intervalMs) {
        sRecordIntervalMs = Math.max(200, intervalMs);
    }

    /**
     * 导出记录为 CSV 文件，保存到应用专属 Files 目录。
     * 文件名格式：winlator-hud-record-YYYYMMDD_HHmmss.csv
     * @return 导出的文件，失败返回 null
     */
    public static File exportRecordingCSV(Context context) {
        if (sRecording == null || sRecording.getPointCount() == 0) return null;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File out = new File(dir, "winlator-hud-record-" + ts + ".csv");
        return sRecording.exportCSV(out) ? out : null;
    }

    /**
     * 导出记录为 JSON 文件，保存到应用专属 Files 目录。
     * 文件名格式：winlator-hud-record-YYYYMMDD_HHmmss.json
     * @return 导出的文件，失败返回 null
     */
    public static File exportRecordingJSON(Context context) {
        if (sRecording == null || sRecording.getPointCount() == 0) return null;
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File out = new File(dir, "winlator-hud-record-" + ts + ".json");
        return sRecording.exportJSON(out) ? out : null;
    }

    /**
     * 导出记录到指定文件（CSV 或 JSON，由文件扩展名决定）。
     * @param file 目标文件，扩展名必须是 .csv 或 .json
     * @return 是否成功
     */
    public static boolean exportRecording(File file) {
        if (sRecording == null || sRecording.getPointCount() == 0) return false;
        String name = file.getName().toLowerCase();
        if (name.endsWith(".json")) return sRecording.exportJSON(file);
        return sRecording.exportCSV(file);
    }

    public static String buildDiagnosticsReport(Context context) {
        SystemMetrics m = new SystemMetrics(context);
        m.update();
        return m.buildDiagnosticsReport(context);
    }

    public static File exportDiagnostics(Context context) {
        String report = buildDiagnosticsReport(context);
        String ts = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        File dir = context.getExternalFilesDir(null);
        if (dir == null) dir = context.getFilesDir();
        File out = new File(dir, "winlator-hud-diag-" + ts + ".txt");
        try {
            FileWriter w = new FileWriter(out);
            w.write(report);
            w.close();
        } catch (Exception e) {
            return null;
        }
        return out;
    }

    // ==================== v3.8 数据源自检 ====================

    /** 切换自检面板显示/隐藏 */
    public static void toggleSelfCheck() {
        if (sView != null) {
            sView.selfCheckMode = !sView.selfCheckMode;
            sView.invalidate();
        }
    }

    /** 设置自检面板是否显示 */
    public static void setSelfCheckEnabled(boolean enabled) {
        if (sView != null) {
            sView.selfCheckMode = enabled;
            sView.invalidate();
        }
    }

    /** 生成文本版自检报告（可用于日志或导出） */
    public static String buildSelfCheckReport(Context context) {
        SystemMetrics m = new SystemMetrics(context);
        m.update();
        StringBuilder sb = new StringBuilder();
        sb.append("=== WinlatorHUD 数据源自检报告 ===\n");
        sb.append("时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append("\n");
        sb.append("设备: ").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append("\n");
        sb.append("Android: ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n\n");
        String[] names = SystemMetrics.SELF_CHECK_NAMES;
        int[] status = m.selfCheckStatus;
        String[] values = m.selfCheckValue;
        int ok = 0, warn = 0, fail = 0;
        for (int i = 0; i < names.length; i++) {
            String s;
            if (status[i] == 0) { s = "OK"; ok++; }
            else if (status[i] == 1) { s = "PARTIAL"; warn++; }
            else { s = "UNAVAILABLE"; fail++; }
            sb.append(String.format("  [%-11s] %-8s = %s\n", s, names[i], values[i] != null ? values[i] : "-"));
        }
        sb.append("\n汇总: ").append(ok).append(" 正常 / ").append(warn).append(" 部分 / ").append(fail).append(" 不可用\n");
        sb.append("注: 标记 UNAVAILABLE 的数据源通常被 SELinux 或系统权限拦截，\n");
        sb.append("    不影响 HUD 正常运行，仅对应数值显示为 \"-\"。\n");
        return sb.toString();
    }

    public static void release() {
        if (sPrefs != null && sView != null) {
            SharedPreferences.Editor ed = sPrefs.edit();
            ed.putInt(KEY_DENSITY, sView.getDensity());
            ed.putInt(KEY_ORIENTATION, sView.getOrientation());
            ed.putInt(KEY_OFFSET_X, sView.getOffsetX());
            ed.putInt(KEY_BG_ALPHA, sView.getBgAlpha());
            ed.putFloat(KEY_OUTLINE, sView.getOutlineIntensity());
            ed.apply();
        }
        sRunning = false;
        sPresentedFps = 0f;
        if (sRecording != null) { sRecording.stop(); sRecording = null; }
        if (sHandler != null) { sHandler.removeCallbacks(sTick); sHandler = null; }
        if (sThread != null) { sThread.quitSafely(); sThread = null; }
        if (sView != null && sView.getParent() != null) {
            ((android.view.ViewGroup) sView.getParent()).removeView(sView);
        }
        sView = null;
        sTracker = null;
        sMetrics = null;
        sPrefs = null;
    }

    // ==================== HUD View ====================

    private static final class HUDView extends View {
        private int showMask = SHOW_DEFAULT;
        private int density = DENSITY_NORMAL;
        private int orientation = ORIENT_HORIZONTAL;
        private boolean locked = false;
        private boolean selfCheckMode = false; // v3.8: 数据源自检面板
        private int bgAlpha = 0xCC;
        private float outlineIntensity = 0.4f;

        private float dp;
        private int textSize;
        private int smallTextSize;
        private int fpsTextSize;
        private int rowH;
        private int smallRowH;
        private int pad;
        private int graphH;

        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bgPaint = new Paint();
        private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint lockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF bgRect = new RectF();
        private final StringBuilder sb = new StringBuilder(64);
        private final float[] graphData = new float[120];
        private int graphIdx = 0;
        private int graphCount = 0;

        // 锁定徽章动画状态
        private long lockFadeStart = 0;
        private boolean lockFadeShowLocked = false;
        private final Runnable lockFadeTick = new Runnable() {
            @Override public void run() {
                long elapsed = SystemClock.uptimeMillis() - lockFadeStart;
                if (elapsed < FADE_IN_MS + HOLD_TOGGLE_MS + FADE_OUT_MS) {
                    invalidate();
                    postDelayed(this, 16);
                }
            }
        };

        // 手势
        private float downX, downY;
        private long downTime;
        private boolean dragging;
        private float offsetX;
        private long lastClickTime;
        private static final long CLICK_TIMEOUT = 250;
        private static final long LONG_PRESS = 1500;
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
            strokePaint.setStyle(Paint.Style.STROKE);
            strokePaint.setStrokeWidth(1);
            lockPaint.setStyle(Paint.Style.FILL);
            setBackgroundColor(Color.TRANSPARENT);
        }

        private int sp(int v) { return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics()); }

        void setShowMask(int mask) { this.showMask = mask; requestLayout(); invalidate(); }
        void setDensity(int d) { this.density = Math.max(DENSITY_COMPACT, Math.min(DENSITY_MEGA, d)); requestLayout(); invalidate(); savePrefs(); }
        void setOrientation(int o) { this.orientation = o; requestLayout(); invalidate(); savePrefs(); }
        void restoreOffset(int x) { this.offsetX = x; invalidate(); }
        void setBgAlpha(int a) { this.bgAlpha = Math.max(0, Math.min(255, a)); invalidate(); savePrefs(); }
        void setOutlineIntensity(float f) { this.outlineIntensity = Math.max(0f, Math.min(1f, f)); invalidate(); savePrefs(); }
        int getDensity() { return density; }
        int getOrientation() { return orientation; }
        int getOffsetX() { return (int)offsetX; }
        int getBgAlpha() { return bgAlpha; }
        float getOutlineIntensity() { return outlineIntensity; }

        private void savePrefs() {
            if (sPrefs != null) {
                SharedPreferences.Editor ed = sPrefs.edit();
                ed.putInt(KEY_DENSITY, density);
                ed.putInt(KEY_ORIENTATION, orientation);
                ed.putInt(KEY_OFFSET_X, (int)offsetX);
                ed.putInt(KEY_BG_ALPHA, bgAlpha);
                ed.putFloat(KEY_OUTLINE, outlineIntensity);
                ed.apply();
            }
        }

        private boolean has(int flag) { return (showMask & flag) != 0; }

        private int fpsColor(double fps) {
            if (fps >= 60) return C_FPS_GOOD;
            if (fps >= 30) return C_FPS_MED;
            return C_FPS_LOW;
        }

        private double displayFps(FrameTracker t) {
            return sPresentedFps > 0 ? sPresentedFps : t.fps;
        }

        private String fpsText(FrameTracker t, boolean detailed) {
            double disp = displayFps(t);
            if (detailed && sPresentedFps > 0 && Math.abs(sPresentedFps - t.fps) > 1.5) {
                return fmt(disp) + " (" + fmt(t.fps) + ")";
            }
            return fmt(disp);
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
                if (density == DENSITY_DETAILED || density == DENSITY_MEGA) rows = 2;
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
                if (has(SHOW_WINE_VERSION) || has(SHOW_DX_VERSION) || has(SHOW_DURATION)) rows++;
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

            // 背景（可配置透明度）
            bgRect.set(offsetX, 0, w, h);
            bgPaint.setColor((bgAlpha << 24) | 0x000000);
            canvas.drawRoundRect(bgRect, 4 * dp, 4 * dp, bgPaint);
            // 描边（可配置强度）
            if (outlineIntensity > 0) {
                int a = (int)(outlineIntensity * 0x33);
                strokePaint.setColor(Color.argb(a, 255, 255, 255));
                canvas.drawRoundRect(bgRect, 4 * dp, 4 * dp, strokePaint);
            }

            canvas.save();
            canvas.translate(offsetX + pad, pad);

            // v3.8: 自检模式优先绘制
            if (selfCheckMode) {
                drawSelfCheck(canvas, m, w - pad * 2);
            } else if (orientation == ORIENT_HORIZONTAL) {
                drawHorizontal(canvas, m, t, w - pad * 2);
            } else {
                drawVertical(canvas, m, t);
            }
            canvas.restore();

            // 锁定徽章
            drawLockBadge(canvas, w, h);
        }

        private void drawLockBadge(Canvas canvas, int w, int h) {
            if (lockFadeStart == 0) return;
            long elapsed = SystemClock.uptimeMillis() - lockFadeStart;
            float alpha;
            if (elapsed < FADE_IN_MS) {
                alpha = elapsed / (float) FADE_IN_MS;
            } else if (elapsed < FADE_IN_MS + HOLD_TOGGLE_MS) {
                alpha = 1f;
            } else if (elapsed < FADE_IN_MS + HOLD_TOGGLE_MS + FADE_OUT_MS) {
                alpha = 1f - (elapsed - FADE_IN_MS - HOLD_TOGGLE_MS) / (float) FADE_OUT_MS;
            } else {
                lockFadeStart = 0;
                return;
            }
            int a = (int)(alpha * 200);
            float cx = w - 14 * dp;
            float cy = h / 2f;
            float r = 8 * dp;
            // 锁体
            lockPaint.setColor(Color.argb(a, 255, 255, 255));
            canvas.drawCircle(cx, cy, r, lockPaint);
            // 锁孔
            lockPaint.setColor(Color.argb(a, 0, 0, 0));
            canvas.drawCircle(cx, cy - 1 * dp, r * 0.25f, lockPaint);
            canvas.drawRect(cx - r * 0.15f, cy - 1 * dp, cx + r * 0.15f, cy + r * 0.35f, lockPaint);
            // 锁定/解锁文字
            paint.setColor(Color.argb(a, 255, 255, 255));
            paint.setTextSize(smallTextSize);
            String txt = lockFadeShowLocked ? "LOCK" : "UNLOCK";
            float tw = paint.measureText(txt);
            canvas.drawText(txt, cx - tw / 2, cy + r + smallTextSize + 2, paint);
        }

        private void triggerLockBadge(boolean locked) {
            lockFadeShowLocked = locked;
            lockFadeStart = SystemClock.uptimeMillis();
            removeCallbacks(lockFadeTick);
            post(lockFadeTick);
        }

        // ==================== v3.8 数据源自检面板 ====================
        private void drawSelfCheck(Canvas canvas, SystemMetrics m, int availW) {
            paint.setTypeface(Typeface.MONOSPACE);
            float y = 0;
            float rowH = textSize + 4 * dp;
            int dotR = (int)(textSize * 0.28f);

            // 标题
            paint.setColor(0xFF64B5F6);
            paint.setTextSize(textSize);
            canvas.drawText("数据源自检 (SELF-CHECK)", 0, y + textSize, paint);
            y += rowH + 2 * dp;

            // 分隔线
            bgPaint.setColor(0x33FFFFFF);
            canvas.drawRect(0, y, availW, y + 1, bgPaint);
            y += 4 * dp;

            // 各数据源状态
            String[] names = SystemMetrics.SELF_CHECK_NAMES;
            int[] status = m.selfCheckStatus;
            String[] values = m.selfCheckValue;
            paint.setTextSize(smallTextSize);

            for (int i = 0; i < names.length; i++) {
                // 状态指示灯
                int color;
                if (status[i] == 0) color = 0xFF4CAF50;       // 绿=正常
                else if (status[i] == 1) color = 0xFFFFC107;   // 黄=部分
                else color = 0xFFF44336;                         // 红=不可用
                bgPaint.setColor(color);
                canvas.drawCircle(dotR, y + smallTextSize / 2f, dotR, bgPaint);

                // 名称
                paint.setColor(0xFFE0E0E0);
                canvas.drawText(names[i], dotR * 2 + 6 * dp, y + smallTextSize, paint);

                // 值（右对齐）
                paint.setColor(status[i] == 2 ? 0xFF888888 : 0xFFFFFFFF);
                String val = values[i] != null ? values[i] : "-";
                float tw = paint.measureText(val);
                canvas.drawText(val, availW - tw, y + smallTextSize, paint);

                y += rowH;
            }

            // 底部提示
            y += 4 * dp;
            paint.setColor(0xFF888888);
            paint.setTextSize(smallTextSize * 0.85f);
            canvas.drawText("WinlatorHUD.toggleSelfCheck() 退出", 0, y + smallTextSize, paint);
        }

        // ==================== 横向横条绘制 ====================
        private void drawHorizontal(Canvas canvas, SystemMetrics m, FrameTracker t, int availW) {
            float x = 0;
            float y = 0;
            paint.setTypeface(Typeface.MONOSPACE);

            if (density == DENSITY_COMPACT) {
                x = drawHudItem(canvas, x, y, "FPS", fpsText(t, false), fpsColor(displayFps(t)), fpsTextSize);
                x = drawHudItem(canvas, x, y, "GPU", m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-", C_GPU);
                x = drawHudItem(canvas, x, y, "CPU", m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-", C_CPU);
                x = drawHudItem(canvas, x, y, "RAM", m.ramPercent + "%", C_RAM);
                x = drawHudItem(canvas, x, y, "BAT", m.batPercent + "%", C_BAT);
            } else if (density == DENSITY_NORMAL) {
                x = drawHudItem(canvas, x, y, "FPS", fpsText(t, true) + " " + fmt1(t.frameTime) + "ms", fpsColor(displayFps(t)), fpsTextSize);
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
                // DETAILED / MEGA: 两行
                paint.setTextSize(textSize);
                x = 0;
                x = drawHudItem(canvas, x, y, "FPS", fpsText(t, true) + " " + fmt1(t.frameTime) + "ms", fpsColor(displayFps(t)), fpsTextSize);
                x = drawHudItem(canvas, x, y, "AVG", fmt(t.avgFps), C_DIM);
                x = drawHudItem(canvas, x, y, "1%", fmt(t.low1), C_DIM);
                x = drawHudItem(canvas, x, y, "0.1%", fmt(t.low01), C_DIM);
                if (has(SHOW_GRAPH)) {
                    float gx = x + pad;
                    float gw = availW - gx - pad;
                    if (gw > 40) drawGraph(canvas, gx, y + rowH - graphH - 2, gw, graphH, t);
                }
                y += rowH;
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
                        (has(SHOW_BAT_TIME) && m.batTimeMin >= 0 ? " " + fmtTime((int)m.batTimeMin) : ""), C_BAT);
                if (has(SHOW_NETWORK) && m.netValid) {
                    x = drawHudItem(canvas, x, y, "NET", fmt1(m.netDownKB) + "↓ " + fmt1(m.netUpKB) + "↑", C_DIM);
                }
                if (has(SHOW_ENGINE) && !sEngine.isEmpty()) {
                    x = drawHudItem(canvas, x, y, "", sEngine, C_DIM);
                }
                if (has(SHOW_EXE_NAME) && !m.exeName.isEmpty()) {
                    x = drawHudItem(canvas, x, y, "", m.exeName, C_DIM);
                }
                // MEGA 专属：DX 版本
                if (density == DENSITY_MEGA && has(SHOW_DX_VERSION) && !sDxVersion.isEmpty()) {
                    x = drawHudItem(canvas, x, y, "DX", sDxVersion, C_DIM);
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
            return lx + w + 10 * dp;
        }

        // ==================== v3.5 仪表盘进度条 ====================

        /**
         * 绘制水平进度条（仪表盘）。
         * @param canvas 画布
         * @param x 左上角 x
         * @param y 左上角 y
         * @param width 总宽度
         * @param fraction 填充比例 0-1（负值不填充）
         * @param color 填充色
         */
        private void drawGaugeBar(Canvas canvas, float x, float y, float width, float fraction, int color) {
            float barH = 3 * dp;
            float radius = barH / 2;
            // 背景轨道
            bgPaint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(x, y, x + width, y + barH, radius, radius, bgPaint);
            // 填充
            if (fraction > 0) {
                float fillW = Math.min(width, width * Math.max(0, Math.min(1, fraction)));
                bgPaint.setColor(color);
                canvas.drawRoundRect(x, y, x + fillW, y + barH, radius, radius, bgPaint);
            }
        }

        // ==================== v3.6 帧时间直方图 ====================

        /**
         * 绘制帧时间直方图（PC MangoHud 招牌功能）。
         * 10 个 bin，0-50ms，每 bin 5ms。柱高表示该区间帧数量占比。
         * 颜色：<16.7ms(60fps) 绿，<33.3ms(30fps) 黄，其余红。
         */
        private void drawHistogram(Canvas canvas, float x, float y, float width, float height, FrameTracker t) {
            int[] hist = t.getHistogram();
            int total = t.getHistTotal();
            if (total < 10) return; // 样本不足不画
            float binW = width / hist.length;
            float maxH = 0;
            for (int v : hist) if (v > maxH) maxH = v;
            if (maxH <= 0) return;
            for (int i = 0; i < hist.length; i++) {
                float h = (hist[i] / maxH) * height;
                float bx = x + i * binW;
                float by = y + height - h;
                // 颜色按帧时间区间
                int color;
                if (i < 4) color = 0xFF4CAF50;       // 0-20ms 绿
                else if (i < 7) color = 0xFFFFC107;   // 20-35ms 黄
                else color = 0xFFF44336;                // 35-50ms 红
                bgPaint.setColor(color);
                canvas.drawRect(bx + 1, by, bx + binW - 1, y + height, bgPaint);
            }
            // 标签
            paint.setColor(C_DIM);
            paint.setTextSize(smallTextSize);
            canvas.drawText("0ms", x, y + height + smallTextSize + 2, paint);
            String rightLabel = "50ms";
            float rw = paint.measureText(rightLabel);
            canvas.drawText(rightLabel, x + width - rw, y + height + smallTextSize + 2, paint);
        }

        // ==================== 竖向竖列绘制 ====================
        private void drawVertical(Canvas canvas, SystemMetrics m, FrameTracker t) {
            float y = 0;
            paint.setTypeface(Typeface.MONOSPACE);
            paint.setTextSize(textSize);

            if (density == DENSITY_COMPACT) {
                y = drawVRow(canvas, y, "FPS", fpsText(t, false), fpsColor(displayFps(t)), fpsTextSize);
                y = drawVRow(canvas, y, "GPU", (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " + (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " + (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-"), C_GPU);
                y = drawVRow(canvas, y, "CPU", (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " + (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " + (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), C_CPU);
                y = drawVRow(canvas, y, "RAM", fmt1(m.ramGib) + "G " + m.ramPercent + "%", C_RAM);
                y = drawVRow(canvas, y, "BAT", m.batPercent + "% " + (m.batTemp >= 0 ? m.batTemp + "°C" : "-"), C_BAT);
            } else if (density == DENSITY_NORMAL) {
                y = drawVRow(canvas, y, "FPS", fpsText(t, true) + "  " + fmt1(t.frameTime) + "ms", fpsColor(displayFps(t)), fpsTextSize);
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
                        (has(SHOW_BAT_TIME) && m.batTimeMin >= 0 ? " " + fmtTime((int)m.batTimeMin) : ""), C_BAT);
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
                // DETAILED / MEGA
                y = drawVRow(canvas, y, "FPS", fpsText(t, true) + "  " + fmt1(t.frameTime) + "ms", fpsColor(displayFps(t)), fpsTextSize);
                if (has(SHOW_GRAPH)) {
                    drawGraph(canvas, 0, y, 160 * dp, graphH, t);
                    y += graphH + 2;
                }
                // v3.6: 帧时间直方图（PC MangoHud 招牌，详细/MEGA 模式）
                if (t.getHistTotal() >= 10) {
                    float histH = 28 * dp;
                    drawHistogram(canvas, 0, y, 160 * dp, histH, t);
                    y += histH + smallTextSize + 4;
                }
                sb.setLength(0);
                sb.append("AVG ").append(fmt(t.avgFps)).append("  1% ").append(fmt(t.low1)).append("  0.1% ").append(fmt(t.low01));
                y = drawVRow(canvas, y, "", sb.toString(), C_DIM, smallTextSize);

                // v3.5: GPU 动态颜色 + 仪表盘进度条
                int gpuCol = loadColor(m.gpuLoad);
                y = drawVRow(canvas, y, "GPU",
                        (m.gpuLoad >= 0 ? m.gpuLoad + "%" : "-") + " " +
                        (m.gpuTemp >= 0 ? m.gpuTemp + "°C" : "-") + " " +
                        (m.gpuClock >= 0 ? m.gpuClock + "MHz" : "-"), gpuCol);
                drawGaugeBar(canvas, 42 * dp, y - 4 * dp, 100 * dp, m.gpuLoad / 100f, gpuCol);
                if (m.vramGib >= 0) y = drawVRow(canvas, y, "VRAM", fmt1(m.vramGib) + " GiB", gpuCol, smallTextSize);

                // v3.5: CPU 动态颜色 + 仪表盘进度条
                int cpuCol = loadColor(m.cpuLoad);
                y = drawVRow(canvas, y, "CPU",
                        (m.cpuLoad >= 0 ? m.cpuLoad + "%" : "-") + " " +
                        (m.cpuTemp >= 0 ? m.cpuTemp + "°C" : "-") + " " +
                        (m.cpuClock >= 0 ? (m.cpuClock / 1000f) + "GHz" : "-"), cpuCol);
                drawGaugeBar(canvas, 42 * dp, y - 4 * dp, 100 * dp, m.cpuLoad / 100f, cpuCol);
                if (has(SHOW_CPU_CORES)) {
                    for (int i = 0; i < Math.min(8, m.coreCount); i++) {
                        if (m.coreClock[i] > 0) {
                            y = drawVRow(canvas, y, "C" + i, m.coreClock[i] + "MHz", cpuCol, smallTextSize);
                        }
                    }
                }

                // v3.5: RAM 动态颜色 + 仪表盘进度条
                int ramCol = loadColor((int)m.ramPercent);
                y = drawVRow(canvas, y, "RAM", fmt1(m.ramGib) + "G " + m.ramPercent + "%", ramCol);
                drawGaugeBar(canvas, 42 * dp, y - 4 * dp, 100 * dp, m.ramPercent / 100f, ramCol);
                if (m.swapGib >= 0) y = drawVRow(canvas, y, "SWP", fmt1(m.swapGib) + "G", ramCol, smallTextSize);

                y = drawVRow(canvas, y, "BAT",
                        m.batPercent + "% " +
                        (m.batTemp >= 0 ? m.batTemp + "°C" : "-") + " " +
                        (m.batPower >= 0 ? fmt1(m.batPower) + "W" : "-") +
                        (m.batTimeMin >= 0 ? " " + fmtTime((int)m.batTimeMin) : ""), C_BAT);
                if (m.netValid) y = drawVRow(canvas, y, "NET", fmt1(m.netDownKB) + "↓ " + fmt1(m.netUpKB) + "↑", C_DIM, smallTextSize);
                // v3.6: 热节流状态（>0 时红色显示）
                if (m.thermalStatus > 0) {
                    int tColor = m.thermalStatus >= 3 ? 0xFFF44336 : (m.thermalStatus >= 2 ? 0xFFFFC107 : 0xFFFF9800);
                    y = drawVRow(canvas, y, "THROTTLE", SystemMetrics.THERMAL_LABELS[m.thermalStatus], tColor, smallTextSize);
                }
                // v3.6: 磁盘 I/O（尽力而为，读不到不显示）
                if (m.diskReadKBs >= 0 || m.diskWriteKBs >= 0) {
                    String diskStr = (m.diskReadKBs >= 0 ? fmt1(m.diskReadKBs) + "↓" : "-") + " " +
                                     (m.diskWriteKBs >= 0 ? fmt1(m.diskWriteKBs) + "↑" : "-");
                    y = drawVRow(canvas, y, "DISK", diskStr, C_DIM, smallTextSize);
                }
                if (has(SHOW_REFRESH_RATE) && m.refreshRate > 0) {
                    y = drawVRow(canvas, y, "DISP", "@" + (int)m.refreshRate + "Hz", C_DIM, smallTextSize);
                }
                if (!sEngine.isEmpty()) y = drawVRow(canvas, y, "", sEngine, C_DIM, smallTextSize);
                if (!m.exeName.isEmpty()) y = drawVRow(canvas, y, "", m.exeName, C_DIM, smallTextSize);
                if (!sWineVersion.isEmpty()) y = drawVRow(canvas, y, "Wine", sWineVersion, C_DIM, smallTextSize);
                // MEGA 专属：DX 版本
                if (density == DENSITY_MEGA && has(SHOW_DX_VERSION) && !sDxVersion.isEmpty()) {
                    y = drawVRow(canvas, y, "DX", sDxVersion, C_DIM, smallTextSize);
                }
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
                x += 42 * dp;
            }
            paint.setColor(color);
            canvas.drawText(value, x, y + rowH - 4, paint);
            return y + rowH;
        }

        // ==================== 波形图 ====================
        private void drawGraph(Canvas canvas, float x, float y, float w, float h, FrameTracker t) {
            bgRect.set(x, y, x + w, y + h);
            bgPaint.setColor(C_GRAPH_BG);
            canvas.drawRoundRect(bgRect, 2, 2, bgPaint);
            bgPaint.setColor((bgAlpha << 24) | 0x000000);

            if (graphCount < 2) return;
            paint.setColor(C_GRAPH);
            paint.setStrokeWidth(1.5f);
            float maxFps = (float) Math.max(t.avgFps * 1.2, 60.0);
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
            switch (ev.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    downX = ev.getRawX();
                    downY = ev.getRawY();
                    downTime = System.currentTimeMillis();
                    dragging = false;
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (!locked && !dragging && Math.abs(ev.getRawX() - downX) > TOUCH_SLOP * dp) {
                        dragging = true;
                    }
                    if (!locked && dragging) {
                        offsetX += ev.getRawX() - downX;
                        downX = ev.getRawX();
                        downY = ev.getRawY();
                        invalidate();
                    }
                    return true;
                case MotionEvent.ACTION_UP:
                    long dt = System.currentTimeMillis() - downTime;
                    if (locked) {
                        // 锁定状态下，长按仍可解锁
                        if (dt >= LONG_PRESS) {
                            locked = false;
                            triggerLockBadge(false);
                            savePrefs();
                        }
                        return true;
                    }
                    if (!dragging && dt < CLICK_TIMEOUT) {
                        long now = System.currentTimeMillis();
                        if (now - lastClickTime < 300) {
                            setOrientation(orientation == ORIENT_HORIZONTAL ? ORIENT_VERTICAL : ORIENT_HORIZONTAL);
                            lastClickTime = 0;
                        } else {
                            setDensity((density + 1) % 4);
                            lastClickTime = now;
                        }
                    } else if (!dragging && dt >= LONG_PRESS) {
                        locked = true;
                        triggerLockBadge(true);
                        savePrefs();
                    } else if (dragging) {
                        savePrefs();
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

        // v3.6: 帧时间直方图（10 bin，0-50ms，每 bin 5ms）
        private static final int HIST_BINS = 10;
        private static final float HIST_MAX_MS = 50f;
        private final int[] histogram = new int[HIST_BINS];
        private int histTotal = 0;

        int[] getHistogram() { return histogram; }
        int getHistTotal() { return histTotal; }

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
                    // v3.6: 更新帧时间直方图
                    int bin = Math.min(HIST_BINS - 1, (int)(dtMs / (HIST_MAX_MS / HIST_BINS)));
                    histogram[bin]++;
                    histTotal++;
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

        int gpuLoad = -1, gpuTemp = -1, gpuClock = -1;
        float vramGib = -1;
        int cpuLoad = -1, cpuTemp = -1, cpuClock = -1;
        float ramGib = 0, ramPercent = 0, swapGib = -1;
        int batPercent = 0, batTemp = -1;
        float batPower = -1, batTimeMin = -1;
        float netDownKB = 0, netUpKB = 0;
        boolean netValid = false;
        private long lastNetRx = 0, lastNetTx = 0, lastNetTime = 0;
        float refreshRate = 0;
        String exeName = "";

        // v3.6: 热节流状态（0=none, 1=light, 2=moderate, 3=severe, 4=critical, 5=emergency, 6=shutdown）
        int thermalStatus = 0;
        private static final String[] THERMAL_LABELS = {"", "LIGHT", "MOD", "SEVERE", "CRIT", "EMERG", "SHUT"};

        // v3.6: 磁盘 I/O（/proc/self/io，尽力而为）
        float diskReadKBs = -1, diskWriteKBs = -1;
        private long lastDiskRead = -1, lastDiskWrite = -1, lastDiskTime = 0;

        private long prevCpuTotal = 0, prevCpuIdle = 0;
        private boolean cpuWarmed = false;
        private int[] maxCoreClock = new int[coreCount];
        private boolean maxClockReady = false;

        private List<String> gpuLoadPaths;
        private List<String> gpuClockPaths;

        // v3.8: 数据源自检状态（0=正常, 1=部分可用, 2=不可用）
        private static final String[] SELF_CHECK_NAMES = {
            "GPU负载", "GPU温度", "GPU频率", "VRAM",
            "CPU负载", "CPU温度", "CPU频率", "内存",
            "电池", "功耗", "热节流", "磁盘IO", "网络"
        };
        private final int[] selfCheckStatus = new int[SELF_CHECK_NAMES.length];
        private final String[] selfCheckValue = new String[SELF_CHECK_NAMES.length];

        /** v3.8: 更新自检状态（在 update() 末尾调用） */
        private void updateSelfCheck() {
            int i = 0;
            selfCheckStatus[i] = gpuLoad >= 0 ? 0 : 2;
            selfCheckValue[i] = gpuLoad >= 0 ? gpuLoad + "%" : "N/A"; i++;
            selfCheckStatus[i] = gpuTemp >= 0 ? 0 : 2;
            selfCheckValue[i] = gpuTemp >= 0 ? gpuTemp + "°C" : "N/A"; i++;
            selfCheckStatus[i] = gpuClock >= 0 ? 0 : 2;
            selfCheckValue[i] = gpuClock >= 0 ? gpuClock + "MHz" : "N/A"; i++;
            selfCheckStatus[i] = vramGib >= 0 ? 0 : 1;
            selfCheckValue[i] = vramGib >= 0 ? fmt1(vramGib) + "G" : "N/A"; i++;
            selfCheckStatus[i] = cpuLoad >= 0 ? 0 : 2;
            selfCheckValue[i] = cpuLoad >= 0 ? cpuLoad + "%" : "N/A"; i++;
            selfCheckStatus[i] = cpuTemp >= 0 ? 0 : 1;
            selfCheckValue[i] = cpuTemp >= 0 ? cpuTemp + "°C" : "N/A"; i++;
            selfCheckStatus[i] = cpuClock >= 0 ? 0 : 2;
            selfCheckValue[i] = cpuClock >= 0 ? (cpuClock/1000f) + "GHz" : "N/A"; i++;
            selfCheckStatus[i] = ramPercent > 0 ? 0 : 2;
            selfCheckValue[i] = ramPercent + "%"; i++;
            selfCheckStatus[i] = batPercent > 0 ? 0 : 2;
            selfCheckValue[i] = batPercent + "%"; i++;
            selfCheckStatus[i] = batPower >= 0 ? 0 : 1;
            selfCheckValue[i] = batPower >= 0 ? fmt1(batPower) + "W" : "N/A"; i++;
            selfCheckStatus[i] = 0;
            selfCheckValue[i] = THERMAL_LABELS[thermalStatus]; i++;
            selfCheckStatus[i] = diskReadKBs >= 0 ? 0 : 1;
            selfCheckValue[i] = diskReadKBs >= 0 ? fmt1(diskReadKBs) + "↓" : "N/A"; i++;
            selfCheckStatus[i] = netValid ? 0 : 1;
            selfCheckValue[i] = netValid ? fmt1(netDownKB) + "↓" : "N/A";
        }

        // v3.2: Mali gpuinfo 状态（delta-ms/wall-ms 需要两次调用间状态）
        private long maliGpuBusyMs = 0;
        private long maliGpuTotalMs = 0;
        private boolean maliReady = false;

        SystemMetrics(Context ctx) {
            this.context = ctx;
            new Thread(() -> {
                for (int i = 0; i < coreCount; i++) {
                    long max = readLong("/sys/devices/system/cpu/cpu" + i + "/cpufreq/cpuinfo_max_freq");
                    maxCoreClock[i] = max > 0 ? (int)(max / 1000) : 2800;
                }
                maxClockReady = true;
            }).start();
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
            readThermal();   // v3.6
            readDiskIO();    // v3.6
            updateSelfCheck(); // v3.8
        }

        private void readGpu() {
            gpuLoad = readGpuLoad();
            gpuClock = readGpuClock();
            gpuTemp = readThermal("gpu", "kgsl", "adreno", "mali", "gp10b", "gm20b");
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
                    // v3.2: Mali 路径
                    "/sys/class/misc/mali0/device/gpu_utilization",
                    "/sys/kernel/gpu/gpu_busy_percentage",
                };
                for (String p : candidates) if (new File(p).canRead()) gpuLoadPaths.add(p);
                try {
                    File dir = new File("/sys/class/devfreq");
                    if (dir.isDirectory()) {
                        File[] devs = dir.listFiles();
                        if (devs != null) for (File d : devs) {
                            String name = d.getName().toLowerCase();
                            if (name.contains("gpu") || name.contains("kgsl") || name.contains("3d") || name.contains("mali")) {
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
            // v3.2: Mali gpuinfo 差分探测
            int mali = readMaliGpuLoad();
            if (mali >= 0) return mali;
            return -1;
        }

        private int readMaliGpuLoad() {
            try {
                String line = readFirstLine("/sys/class/misc/mali0/device/gpuinfo");
                if (line == null) return -1;
                // Mali gpuinfo 格式含 "busy: X ms" 和 "total: Y ms"
                long busy = 0, total = 0;
                String[] parts = line.split("[,\\s]+");
                for (int i = 0; i < parts.length - 1; i++) {
                    if (parts[i].equalsIgnoreCase("busy") || parts[i].equalsIgnoreCase("gpu_busy")) {
                        try { busy = Long.parseLong(parts[i + 1].replaceAll("[^0-9]", "")); } catch (Exception e) { /* ignore */ }
                    }
                    if (parts[i].equalsIgnoreCase("total") || parts[i].equalsIgnoreCase("gpu_total")) {
                        try { total = Long.parseLong(parts[i + 1].replaceAll("[^0-9]", "")); } catch (Exception e) { /* ignore */ }
                    }
                }
                if (maliReady && total > maliGpuTotalMs) {
                    int load = (int)(100 * (busy - maliGpuBusyMs) / (total - maliGpuTotalMs));
                    maliGpuBusyMs = busy;
                    maliGpuTotalMs = total;
                    return Math.max(0, Math.min(100, load));
                }
                maliGpuBusyMs = busy;
                maliGpuTotalMs = total;
                maliReady = true;
            } catch (Exception e) { /* ignore */ }
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
                    // v3.2: Mali
                    "/sys/class/misc/mali0/device/clock",
                    "/sys/class/misc/mali0/device/gpu_clock",
                };
                for (String p : candidates) if (new File(p).canRead()) gpuClockPaths.add(p);
                try {
                    File dir = new File("/sys/class/devfreq");
                    if (dir.isDirectory()) {
                        File[] devs = dir.listFiles();
                        if (devs != null) for (File d : devs) {
                            String name = d.getName().toLowerCase();
                            if (name.contains("gpu") || name.contains("kgsl") || name.contains("3d") || name.contains("mali")) {
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

        private void readCpu() {
            readCpuLoad();
            readCpuClocks();
            // v3.2: 温度优先级排序
            cpuTemp = readThermalPrioritized(
                "cpu-silicon", "cpu-0", "cpu0", "cpu", "soc", "tsens_tz",
                "tsens", "cluster", "big", "little", "cputop", "s5p-tmu"
            );
        }

        private void readCpuLoad() {
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

        private void readRam() {
            try {
                android.app.ActivityManager.MemoryInfo mi = new android.app.ActivityManager.MemoryInfo();
                android.app.ActivityManager am = (android.app.ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                am.getMemoryInfo(mi);
                long used = mi.totalMem - mi.availMem;
                ramGib = used / 1_073_741_824f;
                ramPercent = mi.totalMem > 0 ? (int)(100 * used / mi.totalMem) : 0;
            } catch (Exception e) { ramGib = 0; ramPercent = 0; }
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

        // v3.6: 功耗三级回退（参考 Xnick417x/WinNative FrameRating）
        // 1. BatteryManager 官方 API  2. /sys/class/power_supply/battery/current_now  3. /sys/class/power_supply/bms/current_now
        private float getBatteryCurrentAmps() {
            long currentRaw = 0;
            try {
                BatteryManager bm = (BatteryManager) context.getSystemService(Context.BATTERY_SERVICE);
                if (bm != null) currentRaw = bm.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW);
            } catch (Exception e) { /* fall through */ }
            if (currentRaw == 0 || currentRaw == Long.MIN_VALUE) {
                currentRaw = readLong("/sys/class/power_supply/battery/current_now");
            }
            if (currentRaw == 0 || currentRaw == Long.MIN_VALUE) {
                currentRaw = readLong("/sys/class/power_supply/bms/current_now");
            }
            if (currentRaw == 0 || currentRaw == Long.MIN_VALUE) return -1f;
            long abs = Math.abs(currentRaw);
            // 单位自适应：<20000 视为 mA，否则 µA（参考 Xnick 实现）
            if (abs < 20000) return abs / 1000f;
            return abs / 1_000_000f;
        }

        private void readBattery() {
            try {
                IntentFilter f = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
                Intent battery = context.registerReceiver(null, f);
                if (battery != null) {
                    int level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    batPercent = scale > 0 ? (int)(100 * level / scale) : 0;
                    batTemp = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1);
                    if (batTemp > 100) batTemp = batTemp / 10;
                    int voltage = battery.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
                    // v3.6: 三级回退功耗（替代原 broadcast extra current_now）
                    float amps = getBatteryCurrentAmps();
                    if (voltage > 0 && amps > 0) {
                        batPower = (voltage / 1000f) * amps;
                    } else {
                        batPower = -1;
                    }
                    if (batPower > 0.1 && batPercent < 100) {
                        int capacity = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 4000);
                        float capacityWh = capacity * (voltage / 1000f) / 1000;
                        float remainingWh = capacityWh * batPercent / 100f;
                        int estMin = (int)(remainingWh / batPower * 60);
                        if (batTimeMin < 0) batTimeMin = estMin;
                        else batTimeMin = batTimeMin * 0.65f + estMin * 0.35f;
                    } else {
                        batTimeMin = -1;
                    }
                }
            } catch (Exception e) { /* ignore */ }
        }

        // v3.6: 热节流状态（PowerManager API 29+）
        private void readThermal() {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
                thermalStatus = 0;
                return;
            }
            try {
                android.os.PowerManager pm = (android.os.PowerManager) context.getSystemService(Context.POWER_SERVICE);
                int status = pm != null ? pm.getCurrentThermalStatus() : 0;
                thermalStatus = Math.max(0, Math.min(status, THERMAL_LABELS.length - 1));
            } catch (Exception e) { thermalStatus = 0; }
        }

        // v3.6: 磁盘 I/O（/proc/self/io，尽力而为）
        private void readDiskIO() {
            try {
                BufferedReader br = new BufferedReader(new FileReader("/proc/self/io"));
                long readBytes = -1, writeBytes = -1;
                String line;
                while ((line = br.readLine()) != null) {
                    if (line.startsWith("read_bytes:")) {
                        readBytes = Long.parseLong(line.split(":")[1].trim());
                    } else if (line.startsWith("write_bytes:")) {
                        writeBytes = Long.parseLong(line.split(":")[1].trim());
                    }
                }
                br.close();
                long now = System.currentTimeMillis();
                if (lastDiskTime > 0 && readBytes >= 0 && writeBytes >= 0) {
                    long dt = now - lastDiskTime;
                    if (dt > 0) {
                        diskReadKBs = (readBytes - lastDiskRead) / 1024f / (dt / 1000f);
                        diskWriteKBs = (writeBytes - lastDiskWrite) / 1024f / (dt / 1000f);
                        if (diskReadKBs < 0) diskReadKBs = 0;
                        if (diskWriteKBs < 0) diskWriteKBs = 0;
                    }
                }
                lastDiskRead = readBytes;
                lastDiskWrite = writeBytes;
                lastDiskTime = now;
            } catch (Exception e) { diskReadKBs = -1; diskWriteKBs = -1; }
        }

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

        // v3.2: 温度 zone 优先级排序探测
        private int readThermalPrioritized(String... priorities) {
            try {
                File dir = new File("/sys/class/thermal");
                if (!dir.isDirectory()) return -1;
                File[] zones = dir.listFiles(new FileFilter() {
                    @Override public boolean accept(File f) {
                        return f.getName().startsWith("thermal_zone");
                    }
                });
                if (zones == null) return -1;
                // 按优先级匹配
                for (String kw : priorities) {
                    for (File z : zones) {
                        String type = readFirstLine(z.getAbsolutePath() + "/type");
                        if (type != null && type.trim().toLowerCase().contains(kw.toLowerCase())) {
                            int temp = (int) readLong(z.getAbsolutePath() + "/temp");
                            if (temp > 1000) temp /= 1000;
                            if (temp > 0 && temp < 150) return temp;
                        }
                    }
                }
                // 兜底：第一个合理温度
                for (File z : zones) {
                    int temp = (int) readLong(z.getAbsolutePath() + "/temp");
                    if (temp > 1000) temp /= 1000;
                    if (temp >= 20 && temp <= 100) return temp;
                }
            } catch (Exception e) { /* ignore */ }
            return -1;
        }

        private int readThermal(String... keywords) {
            return readThermalPrioritized(keywords);
        }

        // ==================== 诊断报告 ====================
        String buildDiagnosticsReport(Context ctx) {
            StringBuilder r = new StringBuilder(4096);
            String nl = "\n";
            r.append("=== WinlatorHUD v3.2 诊断报告 ===").append(nl);
            r.append("生成时间: ").append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(new Date())).append(nl);
            r.append("设备型号: ").append(Build.MODEL).append(" (").append(Build.MANUFACTURER).append(")").append(nl);
            r.append("Android版本: ").append(Build.VERSION.RELEASE).append(" (SDK ").append(Build.VERSION.SDK_INT).append(")").append(nl);
            r.append("CPU核心数: ").append(coreCount).append(nl);
            r.append("屏幕刷新率: ").append(refreshRate > 0 ? ((int)refreshRate + " Hz") : "未知").append(nl);
            r.append(nl);

            r.append("--- GPU 探测 ---").append(nl);
            String[] gpuLoadCandidates = {
                "/sys/class/kgsl/kgsl-3d0/gpubusy",
                "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage",
                "/sys/class/kgsl/kgsl-3d0/devfreq/gpu_load",
                "/sys/class/kgsl/kgsl-3d0/load",
                "/sys/kernel/gpu/gpu_busy",
                "/sys/kernel/gpu/gpu_busy_percent",
                "/sys/devices/platform/gpusysfs/gpu_busy",
                "/sys/class/misc/mali0/device/gpu_utilization",
                "/sys/class/misc/mali0/device/gpuinfo",
            };
            r.append("GPU使用率路径:").append(nl);
            for (String p : gpuLoadCandidates) {
                File f = new File(p);
                if (f.exists()) {
                    String val = f.canRead() ? readFirstLine(p) : "存在但不可读";
                    r.append("  [可读] ").append(p).append(" = ").append(val != null ? val.trim() : "?").append(nl);
                }
            }
            if (gpuLoadPaths != null) for (String p : gpuLoadPaths) {
                r.append("  [devfreq] ").append(p).append(nl);
            }
            r.append("Mali gpuinfo差分: ").append(maliReady ? "已就绪" : "未就绪(首次采样)").append(nl);
            r.append("当前GPU使用率: ").append(gpuLoad >= 0 ? gpuLoad + "%" : "读取失败(-1)").append(nl);
            r.append("当前GPU频率: ").append(gpuClock >= 0 ? gpuClock + " MHz" : "读取失败(-1)").append(nl);
            r.append("当前GPU温度: ").append(gpuTemp >= 0 ? gpuTemp + "°C" : "读取失败(-1)").append(nl);
            r.append("当前VRAM: ").append(vramGib >= 0 ? fmt1(vramGib) + " GiB" : "读取失败(-1)").append(nl);
            r.append(nl);

            r.append("--- CPU 探测 ---").append(nl);
            File procStat = new File("/proc/stat");
            r.append("/proc/stat: ").append(procStat.exists() ? (procStat.canRead() ? "存在且可读" : "存在但不可读(Android 7.0+限制)") : "不存在").append(nl);
            r.append("CPU使用率方式: ").append(cpuWarmed ? "/proc/stat差分" : (maxClockReady ? "频率归一化回退" : "未就绪")).append(nl);
            r.append("当前CPU使用率: ").append(cpuLoad >= 0 ? cpuLoad + "%" : "读取失败(-1)").append(nl);
            r.append("当前CPU温度: ").append(cpuTemp >= 0 ? cpuTemp + "°C" : "读取失败(-1)").append(nl);
            r.append("各核心频率:").append(nl);
            for (int i = 0; i < coreCount; i++) {
                r.append("  cpu").append(i).append(": ")
                 .append(coreClock[i] > 0 ? coreClock[i] + " MHz" : "读取失败")
                 .append(" (max: ").append(maxCoreClock[i] > 0 ? maxCoreClock[i] + " MHz" : "未知").append(")").append(nl);
            }
            r.append(nl);

            r.append("--- 温度探测 ---").append(nl);
            try {
                File dir = new File("/sys/class/thermal");
                if (dir.isDirectory()) {
                    File[] zones = dir.listFiles(new FileFilter() {
                        @Override public boolean accept(File f) { return f.getName().startsWith("thermal_zone"); }
                    });
                    if (zones != null) {
                        for (File z : zones) {
                            String type = readFirstLine(z.getAbsolutePath() + "/type");
                            int temp = (int) readLong(z.getAbsolutePath() + "/temp");
                            if (temp > 1000) temp /= 1000;
                            r.append("  ").append(z.getName()).append(": type=")
                             .append(type != null ? type.trim() : "?")
                             .append(", temp=").append(temp).append("°C").append(nl);
                        }
                    }
                }
            } catch (Exception e) { r.append("  无法枚举thermal_zone").append(nl); }
            r.append(nl);

            r.append("--- 电池 ---").append(nl);
            r.append("电量: ").append(batPercent).append("%").append(nl);
            r.append("温度: ").append(batTemp >= 0 ? batTemp + "°C" : "读取失败").append(nl);
            r.append("功率: ").append(batPower >= 0 ? fmt1(batPower) + " W" : "读取失败").append(nl);
            r.append("剩余时间: ").append(batTimeMin >= 0 ? fmtTime((int)batTimeMin) : "估算中或充电中").append(nl);
            r.append(nl);

            r.append("--- 内存 ---").append(nl);
            r.append("已用: ").append(fmt1(ramGib)).append(" GiB (").append(ramPercent).append("%)").append(nl);
            r.append("Swap: ").append(swapGib >= 0 ? fmt1(swapGib) + " GiB" : "读取失败").append(nl);
            r.append(nl);

            r.append("--- 网络 ---").append(nl);
            r.append("状态: ").append(netValid ? "正常" : "未就绪或无流量").append(nl);
            if (netValid) {
                r.append("下载: ").append(fmt1(netDownKB)).append(" KB/s").append(nl);
                r.append("上传: ").append(fmt1(netUpKB)).append(" KB/s").append(nl);
            }
            r.append(nl);

            r.append("--- 进程 ---").append(nl);
            r.append("EXE名称: ").append(exeName.isEmpty() ? "未检测到wine/box64进程" : exeName).append(nl);
            r.append("引擎信息: ").append(sEngine.isEmpty() ? "未设置(setGameInfo)" : sEngine).append(nl);
            r.append("Wine版本: ").append(sWineVersion.isEmpty() ? "未设置" : sWineVersion).append(nl);
            r.append("DX版本: ").append(sDxVersion.isEmpty() ? "未设置(setDxVersion)" : sDxVersion).append(nl);
            r.append(nl);

            r.append("--- 帧生成 ---").append(nl);
            r.append("presentedFps: ").append(sPresentedFps > 0 ? sPresentedFps + " (帧生成模式)" : "未设置(禁用)").append(nl);
            r.append(nl);

            r.append("=== 报告结束 ===").append(nl);
            return r.toString();
        }

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

    // ==================== v3.3 性能记录会话 ====================

    /**
     * 性能记录会话。在 IO 线程采样，存储 17 项指标的时间序列，
     * 支持导出为 CSV（Excel/Origin 可直接打开）或 JSON。
     * 每个数据点用 float[] 存储，timestamp 存在 [0]，毫秒精度。
     */
    private static final class RecordingSession {
        private static final int COL_COUNT = 21;
        private static final String[] COLUMNS = {
            "timestamp_ms", "fps", "frame_time_ms", "avg_fps",
            "low_1pct", "low_01pct", "presented_fps",
            "gpu_load", "gpu_temp", "gpu_clock_mhz",
            "cpu_load", "cpu_temp", "cpu_clock_mhz",
            "ram_percent", "ram_gib", "bat_percent", "bat_power_w",
            "thermal_status", "disk_read_kbs", "disk_write_kbs",
            "session_elapsed_s"
        };

        private final List<float[]> points = new ArrayList<>(1024);
        private volatile boolean active = true;
        private final long startTimeMs = SystemClock.uptimeMillis();
        private long endTimeMs = 0;

        boolean isActive() { return active; }
        void stop() { active = false; endTimeMs = SystemClock.uptimeMillis(); }
        int getPointCount() { return points.size(); }

        long getDurationMs() {
            long end = endTimeMs > 0 ? endTimeMs : SystemClock.uptimeMillis();
            return end - startTimeMs;
        }

        /** 从当前帧追踪器和系统指标采集一个数据点 */
        void addPoint(FrameTracker tracker, SystemMetrics metrics, float presentedFps) {
            if (!active || tracker == null || metrics == null) return;
            float[] p = new float[COL_COUNT];
            long now = SystemClock.uptimeMillis();
            p[0] = (float)(now - startTimeMs); // 相对时间戳（毫秒）
            p[1] = (float) tracker.fps;
            p[2] = (float) tracker.frameTime;
            p[3] = (float) tracker.avgFps;
            p[4] = (float) tracker.low1;
            p[5] = (float) tracker.low01;
            p[6] = presentedFps;
            p[7] = metrics.gpuLoad;
            p[8] = metrics.gpuTemp;
            p[9] = metrics.gpuClock;
            p[10] = metrics.cpuLoad;
            p[11] = metrics.cpuTemp;
            p[12] = metrics.cpuClock;
            p[13] = metrics.ramPercent;
            p[14] = metrics.ramGib;
            p[15] = metrics.batPercent;
            p[16] = metrics.batPower;
            p[17] = metrics.thermalStatus;       // v3.7: 热节流状态
            p[18] = metrics.diskReadKBs;         // v3.7: 磁盘读速率
            p[19] = metrics.diskWriteKBs;        // v3.7: 磁盘写速率
            p[20] = (float)((now - startTimeMs) / 1000.0); // 会话经过秒数
            points.add(p);
        }

        /** 导出为 CSV，第一行为列名 */
        boolean exportCSV(File file) {
            if (points.isEmpty()) return false;
            try {
                FileWriter w = new FileWriter(file);
                // 表头
                StringBuilder sb = new StringBuilder(256);
                for (int i = 0; i < COL_COUNT; i++) {
                    if (i > 0) sb.append(',');
                    sb.append(COLUMNS[i]);
                }
                sb.append('\n');
                w.write(sb.toString());
                // 数据行
                for (float[] p : points) {
                    sb.setLength(0);
                    for (int i = 0; i < COL_COUNT; i++) {
                        if (i > 0) sb.append(',');
                        float v = p[i];
                        if (v < 0) {
                            sb.append("NA"); // 不可用值标记为 NA，Excel 可识别
                        } else if (i == 0 || i == 17) {
                            sb.append((long) v); // 时间戳用整数
                        } else if (v == (long) v && v < 10000) {
                            sb.append((long) v); // 整数值不写小数
                        } else {
                            sb.append(String.format(Locale.US, "%.2f", v));
                        }
                    }
                    sb.append('\n');
                    w.write(sb.toString());
                }
                w.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** 导出为 JSON，包含元信息和数据数组 */
        boolean exportJSON(File file) {
            if (points.isEmpty()) return false;
            try {
                FileWriter w = new FileWriter(file);
                StringBuilder sb = new StringBuilder(points.size() * 128 + 512);
                sb.append("{\n");
                sb.append("  \"version\": \"3.3\",\n");
                sb.append("  \"format\": \"WinlatorHUD recording\",\n");
                sb.append("  \"start_time_ms\": ").append(startTimeMs).append(",\n");
                sb.append("  \"end_time_ms\": ").append(endTimeMs > 0 ? endTimeMs : SystemClock.uptimeMillis()).append(",\n");
                sb.append("  \"duration_ms\": ").append(getDurationMs()).append(",\n");
                sb.append("  \"point_count\": ").append(points.size()).append(",\n");
                sb.append("  \"columns\": [");
                for (int i = 0; i < COL_COUNT; i++) {
                    if (i > 0) sb.append(", ");
                    sb.append('"').append(COLUMNS[i]).append('"');
                }
                sb.append("],\n");
                sb.append("  \"data\": [\n");
                for (int pi = 0; pi < points.size(); pi++) {
                    float[] p = points.get(pi);
                    sb.append("    [");
                    for (int i = 0; i < COL_COUNT; i++) {
                        if (i > 0) sb.append(", ");
                        float v = p[i];
                        if (v < 0) {
                            sb.append("null");
                        } else if (i == 0 || i == 17) {
                            sb.append((long) v);
                        } else if (v == (long) v && v < 10000) {
                            sb.append((long) v);
                        } else {
                            sb.append(String.format(Locale.US, "%.2f", v));
                        }
                    }
                    sb.append(']');
                    if (pi < points.size() - 1) sb.append(',');
                    sb.append('\n');
                }
                sb.append("  ]\n");
                sb.append("}\n");
                w.write(sb.toString());
                w.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
