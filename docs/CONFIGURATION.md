# 配置指南

## 初始化

```java
WinlatorHUD.init(activity);

int mask = WinlatorHUD.SHOW_FPS | WinlatorHUD.SHOW_GPU_LOAD
         | WinlatorHUD.SHOW_CPU_LOAD | WinlatorHUD.SHOW_RAM;
WinlatorHUD.init(activity, mask, WinlatorHUD.DENSITY_NORMAL);
```

## 密度模式

| 模式 | 常量 | 指标数量 |
|------|------|----------|
| 精简 | DENSITY_COMPACT | 4 项 |
| 标准 | DENSITY_NORMAL | 14 项 |
| 详细 | DENSITY_DETAILED | 24 项 |

## 元素位掩码

帧统计: SHOW_FPS, SHOW_FRAMETIME, SHOW_AVG_FPS, SHOW_1PC_LOW, SHOW_01PC_LOW, SHOW_GRAPH
GPU: SHOW_GPU_LOAD, SHOW_GPU_TEMP, SHOW_GPU_CLOCK, SHOW_VRAM
CPU: SHOW_CPU_LOAD, SHOW_CPU_TEMP, SHOW_CPU_CLOCK, SHOW_CPU_CORES
系统: SHOW_RAM, SHOW_SWAP, SHOW_BATTERY, SHOW_BATTERY_TIME, SHOW_NETWORK
容器: SHOW_ENGINE, SHOW_RESOLUTION, SHOW_WINE_VERSION, SHOW_DURATION, SHOW_THROTTLE

## 运行时控制

```java
WinlatorHUD hud = WinlatorHUD.getInstance();
hud.setDensity(WinlatorHUD.DENSITY_COMPACT);
hud.setVertical(true);
hud.setScale(0.9f);
hud.setLocked(true);
hud.resetStats();
```
