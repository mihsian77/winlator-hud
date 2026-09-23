# WinlatorHUD

> Winlator 专用性能监控 HUD —— 单文件自包含，集成 6 个项目的优点，无闪烁

## 项目简介

WinlatorHUD 是一个专为 Winlator 设计的性能监控叠加层。基于 Android View 实现，不依赖 Vulkan layer hook，从根本上解决了 MangoHud 在 Wine 环境下的闪烁、黑屏和闪退问题。

## 集成的 10 项优点

| # | 功能 | 来源项目 |
|---|------|----------|
| 1 | 横屏/竖屏双布局 + 双击切换 | 上游 Ludashi WinlatorHUD |
| 2 | GPU 路径自动发现（16+ 路径） | Bannerlator HudMetrics |
| 3 | FPS stale 检测（1.5s 无帧→0） | Bannerlator FpsCounter |
| 4 | VRAM 三模式探测 | WinNative MangoHudView |
| 5 | 零 GC 文本格式化 | WinNative MangoHudView |
| 6 | 3 种密度模式（精简/标准/详细） | 融合 Bannerlator + 上游 |
| 7 | Thermal zone 优先级排序 | Bannerlator HudMetrics |
| 8 | 显示刷新率（@120Hz） | WinNative MangoHudView |
| 9 | 电池剩余时间平滑计算 | CronyX SensorReader |
| 10 | 拖拽 + 长按锁定 | 通用实现 |

## 快速开始

### 单文件集成（最简单）

1. 复制 `winlator-hud/src/main/java/com/winlator/hud/WinlatorHUD.java` 到你的项目
2. 在 `XServerDisplayActivity.onCreate` 中调用 `WinlatorHUD.init(this)`
3. 在渲染循环中调用 `WinlatorHUD.recordFrame()`

详细步骤见 [patch/INTEGRATION_GUIDE.md](patch/INTEGRATION_GUIDE.md)

## 使用方法

```java
WinlatorHUD.init(this);
WinlatorHUD.recordFrame();
WinlatorHUD.setGameInfo("DXVK", "1920x1080", "Wine 9.0");
```

## 手势操作

| 手势 | 功能 |
|------|------|
| 单击 | 循环密度模式 |
| 双击 | 切换横屏/竖屏 |
| 拖拽 | 移动位置 |
| 长按 3.5s | 锁定/解锁 |

## 构建

```bash
./gradlew :winlator-hud:assembleRelease
```

## 许可证

MIT License
