# 集成指南

## 源码集成
1. 复制 WinlatorHUD.java 到 app/src/main/java/com/winlator/hud/
2. XServerDisplayActivity 添加 import com.winlator.hud.WinlatorHUD;
3. onCreate 末尾: WinlatorHUD.init(this);
4. onDestroy 中: WinlatorHUD.release();
5. 渲染循环帧呈现后: WinlatorHUD.recordFrame();

## AAR 集成
1. 下载 Release AAR 放入 app/libs/
2. build.gradle: implementation files('libs/WinlatorHUD-2.0.0.aar')
3. 同源码集成步骤 2-5

## 验证
启动游戏 -> 左上角显示 HUD -> 单击切密度 -> 双击切横竖屏 -> 拖拽移动 -> 长按锁定
