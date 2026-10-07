# WinlatorHUD 多 Fork 接入指南

本文档说明如何将 WinlatorHUD 集成到任意 Winlator 分支（官方 / Bionic / glibc / Ludashi / GameNative / WinNative 等）。

## 概述

WinlatorHUD 是纯 Android View 叠加层，不依赖 Vulkan layer，因此集成方式统一：
1. 在 `XServerDisplayActivity.onCreate()` 中初始化 HUD
2. 在渲染 present 后调用 `recordFrame()`（可选，用于 FPS 统计）
3. 在 `onDestroy()` 中释放 HUD

## 通用接入步骤

### 第 1 步：添加依赖

将 `WinlatorHUD-3.x.x.aar` 放入 `app/libs/`，在 `app/build.gradle` 中添加：

```gradle
dependencies {
    implementation files('libs/WinlatorHUD-3.3.0.aar')
}
```

或直接复制 `WinlatorHUD.java` 到项目的 `com.winlator.hud` 包下（零依赖方式）。

### 第 2 步：初始化 HUD

在 `XServerDisplayActivity.onCreate()` 中，`setContentView()` 之后添加：

```java
import com.winlator.hud.WinlatorHUD;

@Override
protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.xserver_display_activity);

    // 初始化 WinlatorHUD（必须在 setContentView 之后）
    WinlatorHUD.init(this);

    // 可选：设置游戏信息
    WinlatorHUD.setGameInfo("DXVK", "1920x1080", "Wine 9.0");

    // ... 原有代码 ...
}
```

### 第 3 步：释放 HUD

在 `XServerDisplayActivity.onDestroy()` 中添加：

```java
@Override
protected void onDestroy() {
    WinlatorHUD.release();
    super.onDestroy();
    // ... 原有代码 ...
}
```

### 第 4 步（可选）：FPS 统计

如果需要 HUD 显示真实 FPS，需要在渲染 present 后调用 `recordFrame()`。有两种方式：

#### 方式 A：Native 层调用（推荐，精度最高）

在 Wine 的 `wglSwapBuffers` / `vkQueuePresentKHR` / `eglSwapBuffers` 的 JNI 实现中，present 完成后回调 Java 方法：

```c
// 在 native 层 present 后调用
JNIEXPORT void JNICALL Java_com_winlator_XServerDisplayActivity_nativeOnFramePresented(JNIEnv *env, jobject thiz) {
    // 回调到 Java 层
}
```

```java
// Java 层接收回调
public void onFramePresented() {
    WinlatorHUD.recordFrame();
}
```

#### 方式 B：Java Choreographer（无需改 native，精度稍低）

如果不方便修改 native 层，可以用 Android 的 `Choreographer` 监听垂直同步：

```java
private Choreographer.FrameCallback frameCallback = new Choreographer.FrameCallback() {
    @Override
    public void doFrame(long frameTimeNanos) {
        WinlatorHUD.recordFrame();
        Choreographer.getInstance().postFrameCallback(this);
    }
};

// 在 onCreate 中启动
Choreographer.getInstance().postFrameCallback(frameCallback);

// 在 onDestroy 中停止
Choreographer.getInstance().removeFrameCallback(frameCallback);
```

> 注意：方式 B 统计的是屏幕刷新频率，不是游戏渲染 FPS。在开启帧生成（LSFG/FSR FG）时，应配合 `setPresentedFps()` 使用。

## 各 Fork 精确接入点

| Fork | XServerDisplayActivity 路径 | onCreate 行 | onDestroy 行 | 已有 HUD | 容器 ID |
|------|------|------|------|------|------|
| **官方** brunodev85/winlator | `app/src/main/java/com/winlator/XServerDisplayActivity.java` | ~180 | ~460 | 无 | `R.id.FLXServerDisplay` |
| **alexvorxx/winlator** (glibc) | `app/src/main/java/com/winlator/XServerDisplayActivity.java` | 184 | 464 | frameRating | `R.id.FLXServerDisplay` |
| **Ludashi** StevenMXZ/Winlator-Ludashi | `app/src/main/java/com/winlator/cmod/XServerDisplayActivity.java` | 299 | 949 | classicHud / modernHud | `R.id.FLXServerDisplay` |
| **WinNative** WinNative-Emu/WinNative | `app/src/main/runtime/display/XServerDisplayActivity.java` | 1886 | 5124 | XServerDrawerHudPane | 动态创建 `xServerDisplayFrame` |
| **GameNative** utkarshdalal/GameNative | `app/src/main/java/app/gamenative/ui/screen/xserver/XServerScreen.kt` (Compose) | — | — | PerformanceHudView | Compose `AndroidView` |
| **Bionic** (Pipetto-crypto) | `app/src/main/java/com/winlator/XServerDisplayActivity.java` | ~180 | ~460 | 无 | `R.id.FLXServerDisplay` |

> 行号基于 2026-10 检索时的版本，不同版本可能有偏移。请在对应文件中搜索 `onCreate` 和 `onDestroy` 精确定位。

## 各 Fork 注意事项

### alexvorxx (glibc 分支)

- 已有 `frameRating` HUD（第 808 行 `rootView.addView(frameRating)`）
- 方案 A（共存）：直接添加 WinlatorHUD，两个 HUD 同时显示（WinlatorHUD 默认在顶部横条，frameRating 通常在左上角）
- 方案 B（替代）：注释掉 `rootView.addView(frameRating)`，只用 WinlatorHUD
- glibc 环境下 sysfs 路径与 bionic 一致，GPU/CPU 探测无需额外适配

### Ludashi

- 已有 `classicHud` 和 `modernHud` 两种 HUD（第 1323/1329 行），通过设置切换
- 方案 A（共存）：WinlatorHUD 作为第三方 HUD 叠加，用户可在设置中关闭自带 HUD
- 方案 B（替代）：修改 HUD 切换逻辑，将 WinlatorHUD 作为第三种选项
- Ludashi v4.0+ 用 Jetpack Compose 重写了设置 UI，但 XServerDisplayActivity 仍是原生 View

### WinNative

- 文件很大（5000+ 行），onCreate 在 1886 行
- `xServerDisplayFrame` 是动态创建的 FrameLayout（第 1932 行），不是 XML 布局
- 已有 `XServerDrawerHudPane.kt`（抽屉式 HUD 配置面板）
- 初始化位置：在 `xServerDisplayFrame` 创建并添加到视图树之后
- WinNative 用 Kotlin，调用 Java API 无障碍：
  ```kotlin
  WinlatorHUD.init(this)
  WinlatorHUD.setGameInfo("DXVK", resolution, wineVersion)
  ```

### GameNative

- 用 Jetpack Compose 架构，XServer 显示在 `XServerScreen.kt` 中
- 已有 `PerformanceHudView`（Kotlin 实现，含线程级 CPU/P50-P99 帧时间）
- 集成方式：在 Compose 的 `AndroidView` 中包裹 WinlatorHUD：
  ```kotlin
  AndroidView(factory = { context ->
      WinlatorHUD.init(context as Activity)
      // 返回一个空 View，因为 WinlatorHUD 自己 addView 到 decorView
      View(context)
  })
  ```
- 或直接在 `XServerScreen` 的 `onDispose` 中调用 `WinlatorHUD.release()`

### Bionic 分支

- 与官方结构一致，`XServerDisplayActivity` 在 `com.winlator` 包下
- bionic libc 对 sysfs 读取无限制，GPU/CPU 探测正常
- 注意：bionic 分支可能自带阉割版 MangoHud（Vulkan layer），会导致闪烁。建议在 Wine 配置中禁用 `MANGOHUD=1` 环境变量，改用 WinlatorHUD

## 已有 HUD 的共存/替代决策

| 场景 | 推荐方案 | 原因 |
|------|------|------|
| 分支无自带 HUD | 直接集成 WinlatorHUD | 最简单 |
| 分支有自带 HUD 但用户不满意 | 替代：注释掉自带 HUD 的 addView | 避免双重 HUD 干扰 |
| 分支有自带 HUD 且用户想对比 | 共存：两个 HUD 同时显示 | WinlatorHUD 在顶部横条，自带 HUD 通常在左上角，不重叠 |
| 分支用 Compose | 用 AndroidView 包裹 | Compose 与原生 View 可互操作 |

## 验证方法

集成完成后，按以下步骤验证：

1. **编译通过**：`./gradlew assembleDebug` 无错误
2. **HUD 显示**：启动游戏，屏幕顶部应出现横条 HUD，显示 FPS/GPU/CPU/RAM/BAT
3. **手势交互**：
   - 单击：循环密度（精简→标准→详细→MEGA）
   - 双击：切换横条/竖列
   - 拖拽：移动位置
   - 长按 1.5s：锁定/解锁（显示 LOCK/UNLOCK 徽章）
4. **FPS 统计**：如果调用了 `recordFrame()`，FPS 应随游戏帧率变化；如果没调用，FPS 显示为 0 或不更新
5. **配置持久化**：切换密度/方向后退出重进，应恢复上次设置
6. **诊断导出**：调用 `WinlatorHUD.exportDiagnostics(context)`，检查生成的 txt 文件中 GPU/CPU 路径探测结果

## 常见问题

### Q: HUD 不显示？
A: 检查 `WinlatorHUD.init(this)` 是否在 `setContentView()` 之后调用；检查 Activity 是否继承自 `AppCompatActivity`（WinlatorHUD 用 `activity.getWindow().getDecorView()` 添加 View）。

### Q: FPS 一直显示 0？
A: 需要调用 `WinlatorHUD.recordFrame()`。如果不方便改 native 层，用 Choreographer 方式（见上方方式 B）。

### Q: GPU 使用率显示 -？
A: 调用 `WinlatorHUD.exportDiagnostics(context)` 查看诊断报告，确认设备的 GPU sysfs 路径是否被探测到。Adreno 完全支持，Mali 在 v3.2+ 已支持，PowerVR 可能需要额外路径。

### Q: 与自带 HUD 重叠？
A: 用拖拽移动 WinlatorHUD 位置，或注释掉自带 HUD 的 `addView` 调用。

### Q: 开启帧生成后 FPS 不对？
A: 调用 `WinlatorHUD.setPresentedFps(displayFps)` 传入实际显示 FPS，HUD 会优先显示该值，详细模式下显示 `显示FPS (游戏FPS)`。

### Q: 性能记录怎么用？
A: 
```java
WinlatorHUD.startRecording();
// 游戏运行 30 秒
File csv = WinlatorHUD.exportRecordingCSV(context);
WinlatorHUD.stopRecording();
```
CSV 文件可用 Excel/Origin 打开，绘制帧率曲线、1% low 分析等。

## 版本兼容性

| WinlatorHUD 版本 | 最低 Android API | 兼容 Winlator 版本 |
|------|------|------|
| v3.3.0 | 24 (Android 7.0) | 全版本（官方 6.x+ / Bionic / glibc / Ludashi 4.x / GameNative / WinNative） |
| v3.2.0 | 24 | 全版本 |
| v3.1.0 | 24 | 全版本 |

> WinlatorHUD 不依赖 Wine 版本、Box64/Box86 版本、Turnip 驱动版本，只依赖 Android API 24+。
