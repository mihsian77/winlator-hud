# API 参考

## 静态方法

| 方法 | 说明 |
|------|------|
| init(Activity) | 默认配置初始化 |
| init(Activity, int mask, int density) | 自定义配置初始化 |
| getInstance() | 获取单例 |
| recordFrame() | 帧记录 |
| setGameInfo(engine, resolution, wineVersion) | 设置容器信息 |
| show() / hide() | 显隐控制 |
| release() | 资源释放 |

## 实例方法

setShowMask(int), getShowMask(), setDensity(int), getDensity(),
setVertical(boolean), isVertical(), setScale(float),
setTextAlpha(float), setBackgroundAlpha(float), setLocked(boolean), resetStats()

## 常量

密度: DENSITY_COMPACT / DENSITY_NORMAL / DENSITY_DETAILED
元素: 24 个 SHOW_* 位掩码常量
版本: VERSION = "2.0.0"
