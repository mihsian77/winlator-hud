# 配置指南

## 初始化配置

```java
// 默认配置
WinlatorHUD.init(activity);

// 自定义元素
int showMask = WinlatorHUD.SHOW_FPS | WinlatorHUD.SHOW_GPU_LOAD | WinlatorHUD.SHOW_CPU_LOAD;
WinlatorHUD.init(activity, showMask, WinlatorHUD.DENSITY_NORMAL);
```

## 密度模式

| 模式 | 常量 | 说明 |
|------|------|------|
| 精简 | DENSITY_COMPACT | FPS / GPU / CPU / RAM |
| 标准 | DENSITY_NORMAL | 默认配置 |
| 详细 | DENSITY_DETAILED | 全部 24 个元素 |

## 元素常量

SHOW_FPS, SHOW_FRAMETIME, SHOW_AVG_FPS, SHOW_1PC_LOW, SHOW_01PC_LOW, SHOW_GRAPH,
SHOW_GPU_LOAD, SHOW_GPU_TEMP, SHOW_GPU_CLOCK, SHOW_VRAM,
SHOW_CPU_LOAD, SHOW_CPU_TEMP, SHOW_CPU_CLOCK, SHOW_CPU_CORES,
SHOW_RAM, SHOW_SWAP, SHOW_BATTERY, SHOW_BATTERY_TIME, SHOW_NETWORK,
SHOW_ENGINE, SHOW_RESOLUTION, SHOW_WINE_VERSION, SHOW_DURATION, SHOW_THROTTLE

## 运行时调整

```java
WinlatorHUD hud = WinlatorHUD.getInstance();
hud.setDensity(WinlatorHUD.DENSITY_COMPACT);
hud.setVertical(true);
hud.setScale(1.0f);
hud.setLocked(true);
hud.resetStats();
```
