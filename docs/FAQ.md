# 常见问题

## HUD 不显示
确认 WinlatorHUD.init(this) 在游戏主 Activity 的 onCreate 中调用，且文件位于 com.winlator.hud 包路径。

## FPS 恒为 0
确认在 Vulkan/OpenGL 帧呈现后调用了 WinlatorHUD.recordFrame()。

## GPU 指标显示 N/A
非高通 Adreno GPU 的部分节点需要 root 权限读取。

## 与 MangoHud 的区别
MangoHud 基于 Vulkan layer 注入，在 Wine present 机制下存在帧同步冲突。WinlatorHUD 在 Android UI 层独立绘制，不介入渲染管线。

## 性能开销
独立线程 500ms 采样，文本预格式化零 GC，整体 CPU 开销 < 1%。
