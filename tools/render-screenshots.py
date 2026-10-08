#!/usr/bin/env python3
"""
WinlatorHUD 效果图渲染脚本

按 WinlatorHUD.java 的真实绘制参数（字号/间距/颜色/布局）复刻 HUD 视觉，
生成 README 展示效果图。输出到 docs/screenshots/。

用法: python3 tools/render-screenshots.py
依赖: Pillow (pip install Pillow)
"""

import math
import random
import os
from PIL import Image, ImageDraw, ImageFont

# ==================== 布局常量（dp=3px @3x 渲染） ====================
DP = 3
TEXT_SIZE = 11 * DP        # sp(11)
SMALL_SIZE = 9 * DP        # sp(9)
FPS_SIZE = 16 * DP         # sp(16)
ROW_H = 16 * DP            # 行高
PAD = 4 * DP               # 内边距
GRAPH_H = 24 * DP          # 波形图高
HIST_H = 28 * DP           # 直方图高
GAUGE_X = 42 * DP          # 仪表盘 x 缩进
GAUGE_W = 100 * DP         # 仪表盘宽
GAUGE_H = 3 * DP           # 仪表盘高

# ==================== 主题色表（与 WinlatorHUD.java 一致） ====================
# [主题][GPU, CPU, RAM, BAT, LABEL, TEXT, DIM]
THEMES = {
    "default": dict(gpu="#4CAF50", cpu="#2196F3", ram="#9C27B0", bat="#FF9800",
                    label="#888888", text="#FFFFFF", dim="#AAAAAA"),
    "green":   dict(gpu="#35D0BA", cpu="#1A9FFF", ram="#7C4DFF", bat="#FFB020",
                    label="#7A8FA8", text="#F0F4FF", dim="#7A8FA8"),
    "amber":   dict(gpu="#FFA726", cpu="#EF6C00", ram="#8D6E63", bat="#FFCC80",
                    label="#BDBDBD", text="#FFF8E1", dim="#BDBDBD"),
}

LOAD_GREEN, LOAD_YELLOW, LOAD_RED = "#4CAF50", "#FFC107", "#F44336"
FPS_RED, FPS_YELLOW = "#F44336", "#FFC107"

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
CJK_FONT_PATH = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "screenshots")


def load_font(size):
    return ImageFont.truetype(FONT_PATH, size)


def load_cjk_font(size):
    return ImageFont.truetype(CJK_FONT_PATH, size)


def draw_game_bg(w, h):
    """模拟游戏画面背景：暗色渐变 + 抽象光晕"""
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        # 深蓝紫渐变
        r = int(18 + 12 * t)
        g = int(20 + 10 * t)
        b = int(42 + 30 * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    draw = ImageDraw.Draw(img, "RGBA")
    # 光晕
    cx, cy = w * 0.35, h * 0.30
    for r in range(180, 0, -12):
        alpha = 8
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(80, 120, 255, alpha))
    # 底部地平线光
    for y in range(h - 40, h):
        alpha = int(20 * (1 - (y - (h - 40)) / 40))
        draw.line([(0, y), (w, y)], fill=(255, 120, 60, alpha))
    return img


def draw_hud_item(draw, font, x, y, label, value, color, colors, size, canvas):
    """复刻 drawHudItem: label灰 + value彩色 + 间距"""
    if label:
        draw.text((x, y + ROW_H - 4 - size), label, font=font, fill=colors["label"])
        x += draw.textlength(label, font=font) + 3 * DP
    draw.text((x, y + ROW_H - 4 - size), value, font=font, fill=color)
    x += draw.textlength(value, font=font) + 10 * DP
    return x


def draw_vrow(draw, font, y, label, value, color, colors, size, canvas):
    """复刻 drawVRow: label缩进42dp + 行高"""
    x = 0
    if label:
        draw.text((x, y + ROW_H - 4 - size), label, font=font, fill=colors["label"])
        x += 42 * DP
    draw.text((x, y + ROW_H - 4 - size), value, font=font, fill=color)
    return y + ROW_H


def draw_gauge(draw, x, y, fraction, color):
    """复刻 drawGaugeBar: 3dp 高圆角进度条"""
    bar_h = GAUGE_H
    draw.rounded_rectangle([x, y, x + GAUGE_W, y + bar_h], radius=bar_h / 2, fill="#33FFFFFF")
    if fraction > 0:
        fill_w = min(GAUGE_W, GAUGE_W * max(0, min(1, fraction)))
        draw.rounded_rectangle([x, y, x + fill_w, y + bar_h], radius=bar_h / 2, fill=color)


def draw_graph(draw, x, y, w, h, random):
    """帧时间波形图：绿色折线"""
    pts = []
    for i in range(int(w)):
        t = i / w * math.pi * 4
        v = 40 + 18 * math.sin(t) + random.uniform(-6, 6)
        pts.append((x + i, y + h - v))
    draw.line(pts, fill="#4CAF50", width=2)


def draw_histogram(draw, x, y, w, h):
    """帧时间直方图：10 bins，绿为主少量黄红"""
    bins = [34, 22, 12, 6, 4, 3, 2, 1, 1, 1]
    max_b = max(bins)
    bin_w = w / len(bins)
    for i, v in enumerate(bins):
        bh = (v / max_b) * h
        color = LOAD_GREEN if i < 4 else (LOAD_YELLOW if i < 7 else LOAD_RED)
        draw.rectangle([x + i * bin_w + 1, y + h - bh, x + (i + 1) * bin_w - 1, y + h], fill=color)


def fps_color(fps):
    if fps >= 60: return "#4CAF50"
    if fps >= 30: return FPS_YELLOW
    return FPS_RED


def load_color(load):
    if load >= 85: return LOAD_RED
    if load >= 60: return LOAD_YELLOW
    return LOAD_GREEN


def make_hud(width, height, draw_fn):
    """绘制 HUD 叠加层：背景 + 描边 + 内容"""
    bg = draw_game_bg(width, height)
    overlay = Image.new("RGBA", (width, height), (0, 0, 0, 0))
    o = ImageDraw.Draw(overlay, "RGBA")
    # 半透明黑底 + 白描边（bgAlpha=0xCC, outline=0.4）
    o.rounded_rectangle([0, 0, width - 1, height - 1], radius=4 * DP, fill=(0, 0, 0, 0xCC), outline=(255, 255, 255, 40), width=2)
    draw_fn(o)
    bg = bg.convert("RGBA")
    return Image.alpha_composite(bg, overlay)


# ==================== 场景数据 ====================
class Metrics:
    def __init__(self):
        self.fps = 60
        self.ft = 16.7
        self.avg = 58
        self.low1 = 45
        self.low01 = 38
        self.gpu_load = 78
        self.gpu_temp = 62
        self.gpu_clock = 850
        self.vram = 5.1
        self.cpu_load = 45
        self.cpu_temp = 55
        self.cpu_clock = 2800
        self.ram = 3.2
        self.ram_pct = 62
        self.bat = 81
        self.bat_temp = 34
        self.bat_power = 4.2
        self.net_d = 120
        self.net_u = 45
        self.disk_r = 12
        self.disk_w = 3
        self.engine = "DXVK 2.3"
        self.exe = "game.exe"
        self.dur = "01:23:45"
        self.refresh = 120
        self.cores = [2800, 2400, 2000, 1600, 1400, 1200, 1000, 800]


def f1(v):
    return f"{v:.1f}" if isinstance(v, float) else str(v)


# ==================== 各视图 ====================

def render_h_compact(path):
    m = Metrics()
    colors = THEMES["default"]
    m.random = random.Random(1)
    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        x = 0
        x = draw_hud_item(o, fp, x, y, "FPS", str(m.fps), fps_color(m.fps), colors, FPS_SIZE, None)
        x = draw_hud_item(o, f, x, y, "GPU", f"{m.gpu_load}%", colors["gpu"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "CPU", f"{m.cpu_load}%", colors["cpu"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "RAM", f"{m.ram_pct}%", colors["ram"], colors, TEXT_SIZE, None)
        draw_hud_item(o, f, x, y, "BAT", f"{m.bat}%", colors["bat"], colors, TEXT_SIZE, None)
    img = make_hud(1100, ROW_H + 2 * PAD, draw)
    img.save(path)


def render_h_normal(path, theme="default"):
    m = Metrics()
    colors = THEMES[theme]
    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        x = 0
        x = draw_hud_item(o, fp, x, y, "FPS", f"{m.fps} {f1(m.ft)}ms", fps_color(m.fps), colors, FPS_SIZE, None)
        x = draw_hud_item(o, f, x, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz", colors["gpu"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", colors["cpu"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "RAM", f"{f1(m.ram)}G", colors["ram"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "BAT", f"{m.bat}% {m.bat_temp}°C {f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE, None)
        draw_hud_item(o, f, x, y, "1%", str(m.low1), colors["dim"], colors, TEXT_SIZE, None)
    img = make_hud(1600, ROW_H + 2 * PAD, draw)
    img.save(path)


def render_h_detailed(path):
    m = Metrics()
    colors = THEMES["default"]
    m.random = random.Random(2)
    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        # 第一行：FPS 统计 + 波形图
        x = 0
        x = draw_hud_item(o, fp, x, y, "FPS", f"{m.fps} {f1(m.ft)}ms", fps_color(m.fps), colors, FPS_SIZE, None)
        x = draw_hud_item(o, f, x, y, "AVG", str(m.avg), colors["dim"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "1%", str(m.low1), colors["dim"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "0.1%", str(m.low01), colors["dim"], colors, TEXT_SIZE, None)
        gx = x + PAD
        gw = 1600 - 2 * PAD - gx
        draw_graph(o, gx, y + ROW_H - GRAPH_H - 2, gw, GRAPH_H, m.random)
        y += ROW_H
        # 第二行：GPU/CPU/RAM/BAT/NET/引擎/进程
        x = 0
        x = draw_hud_item(o, f, x, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz {f1(m.vram)}G", colors["gpu"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", colors["cpu"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "RAM", f"{f1(m.ram)}G", colors["ram"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "BAT", f"{m.bat}% {m.bat_temp}°C {f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "NET", f"{m.net_d}↓ {m.net_u}↑", colors["dim"], colors, TEXT_SIZE, None)
        x = draw_hud_item(o, f, x, y, "", m.engine, colors["dim"], colors, TEXT_SIZE, None)
        draw_hud_item(o, f, x, y, "", m.exe, colors["dim"], colors, TEXT_SIZE, None)
    img = make_hud(1600, ROW_H * 2 + 2 * PAD, draw)
    img.save(path)


def render_v_compact(path):
    m = Metrics()
    colors = THEMES["default"]
    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        y = draw_vrow(o, fp, y, "FPS", str(m.fps), fps_color(m.fps), colors, FPS_SIZE, None)
        y = draw_vrow(o, f, y, "GPU", f"{m.gpu_load}%", colors["gpu"], colors, TEXT_SIZE, None)
        y = draw_vrow(o, f, y, "CPU", f"{m.cpu_load}%", colors["cpu"], colors, TEXT_SIZE, None)
        y = draw_vrow(o, f, y, "RAM", f"{m.ram_pct}%", colors["ram"], colors, TEXT_SIZE, None)
        draw_vrow(o, f, y, "BAT", f"{m.bat}%", colors["bat"], colors, TEXT_SIZE, None)
    img = make_hud(420, 5 * ROW_H + 2 * PAD, draw)
    img.save(path)


def render_v_detailed(path):
    m = Metrics()
    colors = THEMES["default"]
    height_holder = {}
    def draw(o):
        f = load_font(TEXT_SIZE)
        sf = load_font(SMALL_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        # FPS + 直方图
        y = draw_vrow(o, fp, y, "FPS", f"{m.fps}  {f1(m.ft)}ms", fps_color(m.fps), colors, FPS_SIZE, None)
        draw_histogram(o, 0, y, 160 * DP, HIST_H)
        y += HIST_H + SMALL_SIZE + 4 * DP
        y = draw_vrow(o, sf, y, "", f"AVG {m.avg}  1% {m.low1}  0.1% {m.low01}", colors["dim"], colors, SMALL_SIZE, None)
        # GPU + 仪表盘
        gc = load_color(m.gpu_load)
        y = draw_vrow(o, f, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz", gc, colors, TEXT_SIZE, None)
        draw_gauge(o, GAUGE_X, y - 4 * DP, m.gpu_load / 100, gc)
        y = draw_vrow(o, sf, y, "VRAM", f"{f1(m.vram)} GiB", gc, colors, SMALL_SIZE, None)
        # CPU + 仪表盘
        cc = load_color(m.cpu_load)
        y = draw_vrow(o, f, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", cc, colors, TEXT_SIZE, None)
        draw_gauge(o, GAUGE_X, y - 4 * DP, m.cpu_load / 100, cc)
        for i in range(4):
            y = draw_vrow(o, sf, y, f"C{i}", f"{m.cores[i]}MHz", cc, colors, SMALL_SIZE, None)
        # RAM + 仪表盘
        rc = load_color(m.ram_pct)
        y = draw_vrow(o, f, y, "RAM", f"{f1(m.ram)}G {m.ram_pct}%", rc, colors, TEXT_SIZE, None)
        draw_gauge(o, GAUGE_X, y - 4 * DP, m.ram_pct / 100, rc)
        # BAT / NET / THROTTLE / DISK / DISP
        y = draw_vrow(o, f, y, "BAT", f"{m.bat}% {m.bat_temp}°C {f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE, None)
        y = draw_vrow(o, sf, y, "NET", f"{m.net_d}↓ {m.net_u}↑", colors["dim"], colors, SMALL_SIZE, None)
        y = draw_vrow(o, sf, y, "DISK", f"{m.disk_r}↓ {m.disk_w}↑", colors["dim"], colors, SMALL_SIZE, None)
        y = draw_vrow(o, sf, y, "DISP", f"@{m.refresh}Hz", colors["dim"], colors, SMALL_SIZE, None)
        y = draw_vrow(o, sf, y, "", m.engine, colors["dim"], colors, SMALL_SIZE, None)
        y = draw_vrow(o, sf, y, "", m.exe, colors["dim"], colors, SMALL_SIZE, None)
        y = draw_vrow(o, sf, y, "", m.dur, colors["dim"], colors, SMALL_SIZE, None)
        height_holder["h"] = y
    # 先画再定高（避免参数求值顺序问题）
    overlay = Image.new("RGBA", (480, 1400), (0, 0, 0, 0))
    o = ImageDraw.Draw(overlay, "RGBA")
    o.rounded_rectangle([0, 0, 479, 1399], radius=4 * DP, fill=(0, 0, 0, 0xCC), outline=(255, 255, 255, 40), width=2)
    draw(o)
    h = height_holder["h"] + ROW_H + 2 * PAD
    overlay = overlay.crop((0, 0, 480, h))
    bg = draw_game_bg(480, h)
    img = Image.alpha_composite(bg.convert("RGBA"), overlay)
    img.save(path)


def render_themes(path):
    """三主题横向标准并排对比"""
    from PIL import Image as _I
    thumbs = []
    for theme in ("default", "green", "amber"):
        tmp = f"/tmp/winlatorhud-theme-{theme}.png"
        render_h_normal(tmp, theme)
        thumbs.append(_I.open(tmp))
    w = sum(t.width for t in thumbs) + 2 * 30
    h = max(t.height for t in thumbs) + 60
    canvas = _I.new("RGB", (w, h), (12, 12, 18))
    x = 30
    for t in thumbs:
        canvas.paste(t, (x, 30))
        x += t.width + 30
    canvas.save(path)


def render_selfcheck(path):
    colors = THEMES["default"]
    names = ["GPU负载", "GPU温度", "GPU频率", "VRAM",
             "CPU负载", "CPU温度", "CPU频率", "内存",
             "电池", "功耗", "热节流", "磁盘IO", "网络"]
    values = ["78%", "62°C", "850MHz", "5.1G",
              "45%", "55°C", "2.8GHz", "62%",
              "81%", "4.2W", "", "12↓ 3↑", "120↓ 45↑"]
    status = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]
    def draw(o):
        f = load_cjk_font(TEXT_SIZE)
        sf = load_cjk_font(SMALL_SIZE)
        y = 0
        o.text((0, y + TEXT_SIZE - TEXT_SIZE), "数据源自检 (SELF-CHECK)", font=f, fill="#64B5F6")
        y += ROW_H + 2 * DP
        o.rectangle([0, y, 160 * DP, y + 1], fill=(255, 255, 255, 51))
        y += 4 * DP
        dot_r = int(TEXT_SIZE * 0.28)
        for i, name in enumerate(names):
            dot = LOAD_GREEN if status[i] == 0 else (LOAD_YELLOW if status[i] == 1 else LOAD_RED)
            o.ellipse([0, y + SMALL_SIZE / 2 - dot_r, 2 * dot_r, y + SMALL_SIZE / 2 + dot_r], fill=dot)
            o.text((2 * dot_r + 6 * DP, y + SMALL_SIZE - SMALL_SIZE), name, font=sf, fill="#E0E0E0")
            val = values[i]
            vcol = "#FFFFFF" if status[i] != 2 else "#888888"
            o.text((160 * DP - o.textlength(val, font=sf), y + SMALL_SIZE - SMALL_SIZE), val, font=sf, fill=vcol)
            y += ROW_H
        y += 4 * DP
        o.text((0, y + SMALL_SIZE - SMALL_SIZE), "WinlatorHUD.toggleSelfCheck() 退出", font=sf, fill="#888888")
    # 高度固定可算：标题1行 + 13数据行 + 提示1行 + 间距
    fixed_h = 15 * ROW_H + 2 * DP + 4 * DP + 4 * DP + 2 * PAD
    img = make_hud(480, fixed_h, draw)
    img.save(path)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    jobs = [
        ("horizontal-compact.png", lambda p: render_h_compact(p)),
        ("horizontal-normal.png", lambda p: render_h_normal(p)),
        ("horizontal-detailed.png", lambda p: render_h_detailed(p)),
        ("vertical-compact.png", lambda p: render_v_compact(p)),
        ("vertical-detailed.png", lambda p: render_v_detailed(p)),
        ("themes.png", lambda p: render_themes(p)),
        ("selfcheck.png", lambda p: render_selfcheck(p)),
    ]
    for name, fn in jobs:
        path = os.path.join(OUT_DIR, name)
        fn(path)
        print(f"✓ {name} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    main()
