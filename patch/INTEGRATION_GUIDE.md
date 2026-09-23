# WinlatorHUD 集成补丁指南

## 方式一：源码集成（推荐）

### 步骤 1：复制核心文件
将 `WinlatorHUD.java` 复制到目标项目的 `app/src/main/java/com/winlator/hud/` 目录。

### 步骤 2：修改 XServerDisplayActivity
添加 import:
```java
import com.winlator.hud.WinlatorHUD;
```

在 onCreate 末尾添加:
```java
WinlatorHUD.init(this);
```

在 onDestroy 中添加:
```java
WinlatorHUD.release();
```

### 步骤 3：在渲染循环中记录帧
在 vkQueuePresentKHR 或 eglSwapBuffers 后添加:
```java
WinlatorHUD.recordFrame();
```

## 方式二：AAR 依赖
```bash
./gradlew :winlator-hud:assembleRelease
```
将 AAR 放入 libs/ 目录，在 build.gradle 中添加 implementation files('libs/winlator-hud-release.aar')

## 方式三：Git Patch
```bash
git apply patch/winlator-hud-integration.patch
```

## 验证
1. 编译安装 APK
2. 启动游戏，左上角显示 HUD
3. 单击切换密度，双击切换横竖屏，拖拽移动，长按锁定
