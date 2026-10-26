# WinlatorHUD

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Android](https://img.shields.io/badge/Android-API%2024+-green.svg)](https://developer.android.com)
[![Winlator](https://img.shields.io/badge/Winlator-all%20versions-blue.svg)](https://github.com/brunodev85/winlator)

> 基于 Android View 渲染管线的 Winlator 性能监控叠加层 — 零 Vulkan layer 依赖，零闪烁，单文件自包含。

## 技术架构

WinlatorHUD 采用 Android UI 层叠加方案，在 SurfaceFlinger 合成阶段独立绘制监控面板，不介入 Vulkan/OpenGL 渲染管线。这一架构从根本上规避了 Vulkan layer 注入方案在 Wine present 机制下的帧同步冲突（表现为闪烁、黑屏或闪退）。

### 核心技术栈

| 模块 | 实现方式 |
|------|----------|
| 渲染层 | android.view.View + Canvas 硬件加速绘制 |
| 数据采集 | 独立 HandlerThread，500ms 采样周期，不阻塞渲染线程 |
| 文本渲染 | 预格式化到可复用 StringBuilder，运行时零 GC 分配 |
| 帧统计 | 滑动窗口帧时间队列，支持 1% Low / 0.1% Low / stale 帧检测 |
| 布局引擎 | 横屏双列 / 竖屏单列自适应，位掩码控制元素可见性 |
| 交互系统 | 单击循环密度、双击切换方向、拖拽位移、长按锁定 |

### 数据采集能力

- GPU 子系统：多厂商 sysfs 节点自动发现（Adreno / Mali / PowerVR / MediaTek / Exynos，共 16+ 探测路径），支持使用率、频率、温度、显存三模式探测（kgsl own-pid / sysfs / ActivityManager）
- CPU 子系统：全局使用率 + 每核心频率 + thermal zone 优先级排序
- 内存子系统：/proc/meminfo 实时解析 + Swap 统计
- 电源管理：BatteryManager 状态监听 + 剩余时间指数平滑预测（alpha=0.65）
- 显示子系统：DisplayManager 刷新率实时同步（@120Hz 等）
- 网络子系统：TrafficStats 累计流量统计

## 快速集成

### AAR 依赖（推荐）

从 Releases 下载最新 AAR，放入 app/libs/：

```gradle
dependencies {
    implementation files('libs/WinlatorHUD-2.0.0.aar')
}
```

### 源码集成（零依赖）

复制 WinlatorHUD.java 到目标项目的 com.winlator.hud 包下，无需任何额外配置。

### 初始化

```java
// XServerDisplayActivity.onCreate 中
WinlatorHUD.init(this);

// 渲染循环中（vkQueuePresentKHR / eglSwapBuffers 之后）
WinlatorHUD.recordFrame();

// 可选：设置容器信息
WinlatorHUD.setGameInfo("DXVK", "1920x1080", "Wine 9.0");
```

## 监控指标（24 项）

| 分类 | 指标 |
|------|------|
| 帧统计 | FPS / Frametime / 平均 FPS / 1% Low / 0.1% Low / Frametime 图表 |
| GPU | 使用率 / 温度 / 频率 / 显存 |
| CPU | 使用率 / 温度 / 频率 / 每核心详情 |
| 系统 | 内存 / Swap / 电池 / 电池剩余时间 / 网络 |
| 容器 | 渲染引擎 / 分辨率（含刷新率）/ Wine 版本 / 运行时长 / 温控状态 |

## 密度模式

| 模式 | 触发 | 内容 |
|------|------|------|
| 精简 | 单击 | FPS + GPU + CPU + RAM |
| 标准 | 单击 | 默认 14 项核心指标 |
| 详细 | 单击 | 全部 24 项 |

## 构建与发布

本仓库的 setup-project.yml 同时承担项目初始化和版本发布两个职责：

- 手动触发（Actions -> Run workflow）：初始化项目结构
- 推送 tag（git tag v2.0.0 && git push --tags）：自动编译 AAR 并发布到 Releases

```bash
git tag v2.0.0
git push origin v2.0.0
```

## 兼容性

- Android: API 24 (Android 7.0) 及以上
- Winlator: 全版本（官方 / bionic / glibc / Ludashi / Bannerlator / WinNative 等 fork）
- GPU: 高通 Adreno 完全支持；Mali / PowerVR 部分指标支持

## 版本迭代

| 版本 | 状态 | 说明 |
|------|------|------|
| v2.0.x | 当前稳定版 | 双布局引擎 + 多厂商 GPU 发现 + 零 GC 渲染 |
| v2.1.x | 规划中 | Mali 完整支持 + 自定义主题 |
| v3.0.x | 规划中 | 性能报告导出（CSV/JSON）+ 磁盘 I/O 统计 |

## 许可证

MIT License — 详见 LICENSE

## 致谢

本项目在数据采集策略和交互模式上参考了 Winlator 社区多个开源实现的工程经验。核心渲染架构和帧统计算法为独立实现。

