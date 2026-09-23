# 常见问题

## Q: HUD 不显示
A: 检查是否调用了 WinlatorHUD.init(this)，以及文件是否在 com.winlator.hud 包下。

## Q: FPS 显示 0
A: 检查是否在渲染循环中调用了 WinlatorHUD.recordFrame()。

## Q: 为什么不用 MangoHud？
A: MangoHud 在 Winlator 下有闪烁、黑屏、配置不生效等问题。WinlatorHUD 用 Android View 替代 Vulkan layer，从根本上解决。

## Q: 支持哪些 Winlator 版本？
A: 所有版本（官方 / bionic / glibc / 各 fork）。

## Q: 需要 root 吗？
A: 不需要。大部分数据通过标准 API 读取。
