# API 参考

## 静态方法

- `init(Activity)` - 默认配置初始化
- `init(Activity, int showMask, int density)` - 自定义配置初始化
- `getInstance()` - 获取实例
- `recordFrame()` - 记录一帧
- `setGameInfo(String engine, String resolution, String wineVersion)` - 设置游戏信息
- `show() / hide()` - 显示/隐藏
- `release()` - 释放资源

## 实例方法

- `setShowMask(int)` / `getShowMask()`
- `setDensity(int)` / `getDensity()`
- `setVertical(boolean)` / `isVertical()`
- `setScale(float)`
- `setTextAlpha(float)` / `setBackgroundAlpha(float)`
- `setLocked(boolean)`
- `resetStats()`

## 常量

- 密度: DENSITY_COMPACT / DENSITY_NORMAL / DENSITY_DETAILED
- 元素: SHOW_FPS / SHOW_GPU_LOAD / SHOW_CPU_LOAD / ... (共 24 个)
- 版本: VERSION = "2.0.0-Ultimate"
