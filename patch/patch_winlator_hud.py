#!/usr/bin/env python3
"""
WinlatorHUD 一键集成补丁生成器 v1.0

自动检测任意 Winlator fork 的 XServerDisplayActivity，生成集成补丁。
无需手动找行号，脚本自动定位 onCreate / onDestroy 并插入调用。

用法：
    python3 patch_winlator_hud.py /path/to/winlator-project

输出：
    - winlator-hud-auto.patch  （可直接 git apply 的补丁）
    - PATCH_NOTES.md            （集成说明 + 验证方法）

支持的 fork：
    官方 brunodev85 / Bionic / glibc (alexvorxx) / Ludashi / WinNative / GameNative 等
"""

import os
import sys
import re
from pathlib import Path

HUD_INIT_CODE = """
        // === WinlatorHUD 集成（自动生成）===
        try {
            Class<?> hudClass = Class.forName("com.winlator.hud.WinlatorHUD");
            hudClass.getMethod("init", android.app.Activity.class).invoke(null, this);
            hudClass.getMethod("setGameInfo", String.class, String.class, String.class)
                .invoke(null, "Auto", "0x0", "Wine");
        } catch (Exception e) {
            android.util.Log.w("WinlatorHUD", "init failed: " + e.getMessage());
        }
        // === WinlatorHUD 集成结束 ===
"""

HUD_RELEASE_CODE = """
        // === WinlatorHUD 集成（自动生成）===
        try {
            Class<?> hudClass = Class.forName("com.winlator.hud.WinlatorHUD");
            hudClass.getMethod("release").invoke(null);
        } catch (Exception e) {
            android.util.Log.w("WinlatorHUD", "release failed: " + e.getMessage());
        }
        // === WinlatorHUD 集成结束 ===
"""

def find_activity_file(project_root):
    """搜索 XServerDisplayActivity.java 文件"""
    candidates = []
    for root, dirs, files in os.walk(project_root):
        # 跳过 build 和 .git
        dirs[:] = [d for d in dirs if d not in ('build', '.git', '.gradle')]
        for f in files:
            if f == 'XServerDisplayActivity.java':
                candidates.append(os.path.join(root, f))
    return candidates

def find_method(lines, method_name):
    """找到方法的行号（0-indexed）和缩进"""
    pattern = re.compile(r'^(\s*)(public|protected|private)?\s*void\s+' + re.escape(method_name) + r'\s*\(')
    for i, line in enumerate(lines):
        if pattern.match(line):
            indent = len(line) - len(line.lstrip())
            return i, indent
    return None, None

def find_super_call(lines, method_start, super_method):
    """找到 super.xxx() 调用的行号"""
    for i in range(method_start, min(method_start + 20, len(lines))):
        if 'super.' + super_method in lines[i]:
            return i
    return method_start  # 没找到就用方法起始行

def generate_patch(activity_path, lines):
    """生成补丁内容"""
    patches = []
    rel_path = os.path.relpath(activity_path)

    # 1. 在 onCreate 的 super.onCreate 后插入 init
    onCreate_line, onCreate_indent = find_method(lines, 'onCreate')
    if onCreate_line is not None:
        super_line = find_super_call(lines, onCreate_line, 'onCreate')
        insert_line = super_line + 1
        patches.append(('A', rel_path, insert_line, HUD_INIT_CODE))
    else:
        print(f"  [警告] 未找到 onCreate 方法")

    # 2. 在 onDestroy 的 super.onDestroy 前插入 release
    onDestroy_line, onDestroy_indent = find_method(lines, 'onDestroy')
    if onDestroy_line is not None:
        super_line = find_super_call(lines, onDestroy_line, 'onDestroy')
        insert_line = super_line  # 在 super.onDestroy 之前
        patches.append(('A', rel_path, insert_line, HUD_RELEASE_CODE))
    else:
        print(f"  [警告] 未找到 onDestroy 方法")

    return patches

def write_patch_file(patches, output_path):
    """写成 unified diff 格式"""
    with open(output_path, 'w') as f:
        for ptype, filepath, lineno, code in patches:
            f.write(f"--- a/{filepath}\n")
            f.write(f"+++ b/{filepath}\n")
            f.write(f"@@ -{lineno},0 +{lineno},{code.count(chr(10))} @@\n")
            for cline in code.strip().split('\n'):
                f.write(f"+{cline}\n")
            f.write("\n")

def write_notes(patches, output_path, activity_path):
    """写集成说明"""
    with open(output_path, 'w') as f:
        f.write("# WinlatorHUD 自动集成补丁说明\n\n")
        f.write(f"**检测到的 Activity**: `{activity_path}`\n\n")
        f.write("## 使用方法\n\n")
        f.write("```bash\n")
        f.write("# 1. 将 WinlatorHUD AAR 放入 app/libs/\n")
        f.write("cp WinlatorHUD-3.x.x.aar app/libs/\n\n")
        f.write("# 2. 在 app/build.gradle 中添加依赖\n")
        f.write("echo \"implementation files('libs/WinlatorHUD-3.x.x.aar')\" >> app/build.gradle\n\n")
        f.write("# 3. 应用补丁\n")
        f.write("git apply winlator-hud-auto.patch\n\n")
        f.write("# 4. 编译运行\n")
        f.write("./gradlew assembleDebug\n")
        f.write("```\n\n")
        f.write("## 补丁内容\n\n")
        for ptype, filepath, lineno, code in patches:
            action = "插入" if ptype == 'A' else "修改"
            f.write(f"- **{action}** `{filepath}` 第 {lineno} 行\n")
        f.write("\n## 验证方法\n\n")
        f.write("1. 启动游戏，屏幕顶部应出现 HUD 横条\n")
        f.write("2. 单击循环密度（精简→标准→详细→MEGA）\n")
        f.write("3. 双击切换横条/竖列\n")
        f.write("4. 拖拽移动位置，长按 1.5s 锁定\n")
        f.write("5. 如 HUD 不显示，检查 logcat 中 `WinlatorHUD` 标签的错误\n\n")
        f.write("## 回滚方法\n\n")
        f.write("```bash\n")
        f.write("git apply -R winlator-hud-auto.patch\n")
        f.write("```\n")

def main():
    if len(sys.argv) < 2:
        print("用法: python3 patch_winlator_hud.py /path/to/winlator-project")
        sys.exit(1)

    project_root = os.path.abspath(sys.argv[1])
    if not os.path.isdir(project_root):
        print(f"错误: 目录不存在: {project_root}")
        sys.exit(1)

    print(f"[1/4] 扫描项目: {project_root}")
    candidates = find_activity_file(project_root)

    if not candidates:
        print("  错误: 未找到 XServerDisplayActivity.java")
        print("  请确认这是一个 Winlator 项目目录")
        sys.exit(1)

    print(f"  找到 {len(candidates)} 个候选文件:")
    for c in candidates:
        print(f"    - {os.path.relpath(c, project_root)}")

    activity_path = candidates[0]
    if len(candidates) > 1:
        print(f"  使用第一个: {os.path.relpath(activity_path, project_root)}")

    print(f"[2/4] 读取 Activity: {os.path.relpath(activity_path, project_root)}")
    with open(activity_path, 'r', encoding='utf-8', errors='replace') as f:
        lines = f.readlines()
    print(f"  共 {len(lines)} 行")

    print("[3/4] 生成补丁")
    patches = generate_patch(activity_path, lines)
    if not patches:
        print("  错误: 未能生成任何补丁")
        sys.exit(1)

    os.chdir(project_root)
    patch_file = "winlator-hud-auto.patch"
    notes_file = "PATCH_NOTES.md"
    write_patch_file(patches, patch_file)
    write_notes(patches, notes_file, activity_path)

    print(f"[4/4] 完成")
    print(f"  补丁文件: {patch_file}")
    print(f"  集成说明: {notes_file}")
    print(f"  补丁数量: {len(patches)}")
    print()
    print("下一步:")
    print("  1. cp WinlatorHUD-3.x.x.aar app/libs/")
    print("  2. git apply winlator-hud-auto.patch")
    print("  3. ./gradlew assembleDebug")

if __name__ == '__main__':
    main()
