# WinlatorHUD

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Android-API%2024+-green.svg)](https://developer.android.com)
[![Winlator](https://img.shields.io/badge/Winlator-all%20versions-blue.svg)](https://github.com/brunodev85/winlator)
[![Version](https://img.shields.io/badge/version-3.0.0-brightgreen.svg)]()

> 基于 Android View 渲染管线的 Winlator 性能监控叠加层 — 零 Vulkan layer 依赖，零闪烁，单文件自包含。

## 技术架构

WinlatorHUD 采用 Android UI 层叠加方案，在 SurfaceFlinger 合成阶段独立绘制监控面板，不介入 Vulkan/OpenGL 渲染管线。这一架构从根本上规避了 Vulkan layer 注入方案在 Wine present 机制下的帧同步冲突（表现为闪烁、黑屏或闪退）。

### 核心技术栈

| 模块 | 实现方式 |
|------|----------|
| 渲染层 | android.view.View + Canvas 硬件加速绘制 |
| 数据采集 | 独立 HandlerThread，500ms 采样周期，不阻塞渲染线程 |
| 文本渲染 | 预格式化到可复用 StringBuilder，运行时零 GC 分配 |
| 帧统计 | 滑动窗口帧时间队列（240 帧），支持 1% Low / 0.1% Low / stale 帧检测 |
| 布局引擎 | 横向顶部横条 / 竖向侧边紧凑面板，位掩码控制元素可见性 |
| 交互系统 | 单击循环密度、双击切换方向、拖拽位移、长按 3.5s 锁定 |

## 显示模式

### 横向（顶部横条）

数值从左到右横向排列，铺满屏幕顶部，最小化对游戏画面的遮挡。

| 级别 | 内容 | 适用场景 |
|------|------|----------|
| **L1 极简** | FPS / GPU% / CPU% / RAM% / BAT% | 录屏、竞技、不遮挡画面 |
| **L2 标准** | FPS+ms / GPU%+°C+MHz / CPU%+°C+GHz / RAM / BAT%+°C+W / 1% Low | 日常游戏主力 |
| **L3 详细** | 行1：FPS+ms+AVG+1%+0.1%+帧时间波形图<br>行2：GPU全项+CPU全项+RAM+SWP+BAT全项+NET+引擎+EXE名+时长 | 调试、跑分、性能分析 |

### 竖向（侧边紧凑面板）

左上角左对齐面板，标签固定宽度，信息密度高。

| 级别 | 内容 | 适用场景 |
|------|------|----------|
| **L1 极简** | FPS / GPU%+°C+MHz / CPU%+°C+GHz / RAM / BAT%+°C | 录屏、轻量监控 |
| **L2 标准** | FPS+ms+波形图 / AVG+1%+0.1% / GPU / VRAM / CPU / RAM / SWP / BAT+剩余时间 / NET / 引擎+EXE+时长 | 日常游戏主力 |
| **L3 详细** | L2 全部 + CPU 每核心频率 + 屏幕刷新率 + Wine 版本 + 全部 thermal zone | 调试、深度分析 |

### 交互操作

| 操作 | 效果 |
|------|------|
| 单击 HUD | 循环切换密度模式（L1 → L2 → L3） |
| 双击 HUD | 横向横条 ↔ 竖向面板 切换 |
| 拖拽 HUD | 移动位置 |
| 长按 3.5 秒 | 锁定/解锁位置 |

## 数据采集能力

### GPU 子系统
- 多厂商 sysfs 节点自动发现（Adreno / Mali / PowerVR / MediaTek / Exynos，含 devfreq 通用探测）
- 使用率、温度、频率、显存三模式探测（kgsl own-pid / sysfs 单文件 / ActivityManager）

### CPU 子系统
- 全局使用率（/proc/stat，失败时自动回退到频率归一化估算）
- 每核心频率（sysfs cpufreq）
- thermal zone 优先级排序（按类型匹配 cpu/soc/tsens）

### 内存子系统
- /proc/meminfo 实时解析 + Swap 统计
- ActivityManager 获取系统内存状态

### 电源管理
- BatteryManager 状态监听
- 实时功率计算（电压 × 电流）
- 剩余时间指数平滑预测（alpha=0.65）

### 网络子系统
- TrafficStats 累计流量统计
- 上下行速率实时计算（KB/s）

### Winlator 转译信息
- 当前运行 EXE 名称（从 wine/box64 进程命令行提取）
- DXVK / Vulkan 引擎信息（通过 setGameInfo 注入）
- Wine 版本（通过 setGameInfo 注入）
- 屏幕刷新率（DisplayManager 实时同步）

### 视觉反馈
- FPS 数值颜色动态变化：≥60 绿 / 30-59 黄 / <30 红
- 温度 ≥80°C 变红警告
- 帧时间波形图（横向 L3 + 竖向 L2/L3）

## 快速集成

### 方式一：AAR 依赖（开发者）

从 Releases 下载最新 AAR，放入 `app/libs/`：

```gradle
dependencies {
    implementation files('libs/WinlatorHUD-3.0.0.aar')
}
```

```java
// 在主 Activity onCreate 中初始化
WinlatorHUD.init(this);

// 可选：注入游戏信息
WinlatorHUD.setGameInfo("DXVK 2.4.1", "1920x1080", "Wine 9.0");

// 在渲染循环中每帧调用
WinlatorHUD.recordFrame();

// 退出时释放
WinlatorHUD.release();
```

### 方式二：DEX + MT 管理器（普通用户）

从 Releases 下载 `WinlatorHUD-x.x.x.dex`：

1. 用 MT 管理器打开目标 Winlator APK，将 dex 合并为 classes2.dex
2. 在主 Activity `onCreate` 末尾插入：
   ```smali
   invoke-static {p0}, Lcom/winlator/hud/WinlatorHUD;->init(Landroid/content/Context;)V
   ```
3. 在渲染循环中插入：
   ```smali
   invoke-static {}, Lcom/winlator/hud/WinlatorHUD;->recordFrame()V
   ```
4. 保存签名后安装

详细步骤见 [docs/MT_MANAGER_GUIDE.md](docs/MT_MANAGER_GUIDE.md)。

### 方式三：源码集成

复制 `WinlatorHUD.java` 到目标项目的 `com.winlator.hud` 包下，无需任何额外配置。

## API 参考

```java
// 初始化（必须在 Activity 主线程调用）
WinlatorHUD.init(Activity activity);
WinlatorHUD.init(Activity activity, int showMask, int density, int orientation);

// 帧记录（在渲染循环中每帧调用）
WinlatorHUD.recordFrame();

// 注入游戏信息（可选）
WinlatorHUD.setGameInfo(String engine, String resolution, String wineVersion);

// 释放资源
WinlatorHUD.release();
```

常量定义见源码，`showMask` 支持位掩码组合（`SHOW_FPS | SHOW_GPU_LOAD | ...`）。

## 开源协议与来源

本项目基于 **MIT License** 开源。

### 核心实现

核心渲染架构（Android View + Canvas + HandlerThread）、帧统计算法（滑动窗口 + 百分位计算）、布局引擎（横向横条/竖向紧凑）、sysfs 探测框架均为**独立实现**，不基于任何现有项目的代码 fork。

### 设计思路参考

以下项目的工程经验为本项目的**数据采集策略和交互模式**提供了参考（仅参考设计思路，未复制代码）：

| 项目 | 参考方向 |
|------|----------|
| [Winlator-Ludashi](https://github.com/StevenMXZ/Winlator-Ludashi) | 横/竖双布局、密度模式框架 |
| [Bannerlator](https://github.com/The412Banner/Bannerlator) | GPU 多路径 sysfs 探测、FPS stale 检测 |
| [WinNative](https://github.com/WinNative-Emu/WinNative) | VRAM 三模式探测、零 GC 文本格式化思路 |
| [CronyX](https://github.com/REF4IK/CronyX-) | 电池剩余时间指数平滑预测 |
| [GameNative](https://github.com/utkarshdalal/GameNative) | 拖拽+长按锁定交互设计 |

### 致谢

感谢 Winlator 社区所有开发者的开源贡献。本项目旨在为 Winlator 玩家提供一个稳定、无闪烁、可定制的性能监控方案。

## 兼容性

- **Android**：API 24+（Android 7.0+）
- **Winlator**：全版本兼容（官方主版 / bionic / glibc / ludashi 等分支）
- **GPU**：Adreno 完全支持，Mali / PowerVR 基础支持
- **CPU**：ARM64 通用支持

## 构建

```bash
# 克隆
git clone https://github.com/hao728/winlator-hud.git
cd winlator-hud

# 编译 AAR
gradle :winlator-hud:assembleRelease

# 产物位置
# winlator-hud/build/outputs/aar/winlator-hud-release.aar
```

CI 自动构建：push tag `v*` 或在 Actions 页面手动触发，自动编译 AAR + DEX 并发布到 Releases。

## 版本历史

- **v3.0.0**：横向顶部横条 + 竖向紧凑面板重设计；修复 CPU/GPU 数据采集；新增电池功率、网络速率、EXE 名称；FPS 动态颜色；竖向波形图
- **v2.0.0**：初始版本，Android View 方案，6 种显示模式，24 项指标

## License

MIT License — 详见 [LICENSE](LICENSE) 文件。
