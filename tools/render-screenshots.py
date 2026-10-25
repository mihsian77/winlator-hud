#!/usr/bin/env python3
"""
WinlatorHUD 效果图渲染脚本（v3.9）

按 WinlatorHUD.java v3.9 的真实绘制参数复刻：
- 中文标签（帧率/内存/电池/功耗/处理器/1%低/平均）
- 横向精简 7 项 + 分隔线
- 竖列两列对齐（label 左对齐 / 值右对齐）
- 详细/MEGA 竖向：直方图 + 实时帧时间频谱条
- 右上角数据源状态微标（绿/黄/红）
- FG 帧生成徽标（帧率 120 (60) FG×2）
- 背景贴合内容（半透明黑 + 白描边）

用法: python3 tools/render-screenshots.py
依赖: Pillow
"""

import math
import random
import os
from PIL import Image, ImageDraw, ImageFont

DP = 3
TEXT_SIZE = 11 * DP
SMALL_SIZE = 9 * DP
FPS_SIZE = 16 * DP
ROW_H = 16 * DP
PAD = 4 * DP
GRAPH_H = 24 * DP
HIST_H = 28 * DP
SPEC_H = 18 * DP
GAUGE_X = 42 * DP
GAUGE_W = 100 * DP
GAUGE_H = 3 * DP

THEMES = {
    "default": dict(gpu="#4CAF50", cpu="#2196F3", ram="#9C27B0", bat="#FF9800",
                    label="#888888", text="#FFFFFF", dim="#AAAAAA"),
    "green":   dict(gpu="#35D0BA", cpu="#1A9FFF", ram="#7C4DFF", bat="#FFB020",
                    label="#7A8FA8", text="#F0F4FF", dim="#7A8FA8"),
    "amber":   dict(gpu="#FFA726", cpu="#EF6C00", ram="#8D6E63", bat="#FFCC80",
                    label="#BDBDBD", text="#FFF8E1", dim="#BDBDBD"),
}

LOAD_GREEN, LOAD_YELLOW, LOAD_RED = "#4CAF50", "#FFC107", "#F44336"
SEP_LINE = "#33FFFFFF"

FONT_PATH = "/usr/share/fonts/truetype/dejavu/DejaVuSansMono.ttf"
CJK_FONT_PATH = "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc"
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "docs", "screenshots")


def load_font(size):
    return ImageFont.truetype(FONT_PATH, size)


def load_cjk(size):
    return ImageFont.truetype(CJK_FONT_PATH, size)


def draw_game_bg(w, h):
    img = Image.new("RGB", (w, h))
    px = img.load()
    for y in range(h):
        t = y / max(1, h - 1)
        r = int(18 + 12 * t); g = int(20 + 10 * t); b = int(42 + 30 * t)
        for x in range(w):
            px[x, y] = (r, g, b)
    draw = ImageDraw.Draw(img, "RGBA")
    cx, cy = w * 0.35, h * 0.30
    for r in range(180, 0, -12):
        draw.ellipse([cx - r, cy - r, cx + r, cy + r], fill=(80, 120, 255, 8))
    for y in range(h - 40, h):
        alpha = int(20 * (1 - (y - (h - 40)) / 40))
        draw.line([(0, y), (w, y)], fill=(255, 120, 60, alpha))
    return img


def item_w(font, label, value):
    """复刻 itemW：label + 3dp + value + 10dp"""
    w = 0
    if label:
        w += draw_font_len(label, font) + 3 * DP
    w += draw_font_len(value, font) + 10 * DP
    return w


def draw_font_len(text, font):
    return font.getlength(text)


def fps_color(fps):
    if fps >= 60: return "#4CAF50"
    if fps >= 30: return FPS_YELLOW
    return FPS_RED


def load_color(load):
    if load >= 85: return LOAD_RED
    if load >= 60: return LOAD_YELLOW
    return LOAD_GREEN


def f1(v):
    return f"{v:.1f}"


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


def draw_hud_item(o, font, x, y, label, value, color, colors, size):
    """v3.9 drawHudItem：label灰(中文用CJK字体) + value彩色 + 横向分隔线"""
    lf = load_cjk(size) if label and any(ord(c) > 127 for c in label) else font
    if label:
        o.text((x, y + ROW_H - 4 - size), label, font=lf, fill=colors["label"])
        x += draw_font_len(label, lf) + 3 * DP
    vf = load_cjk(size) if any(ord(c) > 127 for c in value) else font
    o.text((x, y + ROW_H - 4 - size), value, font=vf, fill=color)
    x += draw_font_len(value, vf) + 10 * DP
    if label:
        o.line([(x - 5 * DP, y + 3 * DP), (x - 5 * DP, y + ROW_H - 3 * DP)], fill=SEP_LINE, width=1)
    return x


def draw_vrow(o, font, y, label, value, color, colors, size, content_w):
    """v3.9 drawVRow：label 左对齐(中文CJK) + value 右对齐到内容宽度"""
    lf = load_cjk(size) if label and any(ord(c) > 127 for c in label) else font
    if label:
        o.text((0, y + ROW_H - 4 - size), label, font=lf, fill=colors["label"])
    vf = load_cjk(size) if any(ord(c) > 127 for c in value) else font
    val_w = draw_font_len(value, vf)
    o.text((content_w - val_w, y + ROW_H - 4 - size), value, font=vf, fill=color)
    return y + ROW_H


def draw_gauge(o, x, y, fraction, color):
    bar_h = GAUGE_H
    o.rounded_rectangle([x, y, x + GAUGE_W, y + bar_h], radius=bar_h / 2, fill="#33FFFFFF")
    if fraction > 0:
        fill_w = min(GAUGE_W, GAUGE_W * max(0, min(1, fraction)))
        o.rounded_rectangle([x, y, x + fill_w, y + bar_h], radius=bar_h / 2, fill=color)


def draw_graph(o, x, y, w, h, rnd):
    pts = []
    for i in range(int(w)):
        t = i / w * math.pi * 4
        v = 40 + 18 * math.sin(t) + rnd.uniform(-6, 6)
        pts.append((x + i, y + h - v))
    o.line(pts, fill="#4CAF50", width=2)


def draw_histogram(o, x, y, w, h):
    bins = [34, 22, 12, 6, 4, 3, 2, 1, 1, 1]
    max_b = max(bins)
    bin_w = w / len(bins)
    for i, v in enumerate(bins):
        bh = (v / max_b) * h
        color = LOAD_GREEN if i < 4 else (LOAD_YELLOW if i < 7 else LOAD_RED)
        o.rectangle([x + i * bin_w + 1, y + h - bh, x + (i + 1) * bin_w - 1, y + h], fill=color)


def draw_spectrum(o, x, y, w, h, rnd):
    """v3.9 实时帧时间频谱条：10-bin 滑动窗口，绿→黄→红"""
    bins = []
    base = [4, 6, 9, 12, 8, 5, 3, 2, 1, 1]
    for i, b in enumerate(base):
        bins.append(max(1, b + rnd.randint(-2, 2)))
    max_b = max(bins)
    bin_w = w / len(bins)
    for i, v in enumerate(bins):
        bh = (v / max_b) * h
        ratio = i / (len(bins) - 1)
        color = LOAD_GREEN if ratio < 0.5 else (LOAD_YELLOW if ratio < 0.8 else LOAD_RED)
        o.rounded_rectangle([x + i * bin_w + 1, y + h - bh, x + (i + 1) * bin_w - 1, y + h],
                            radius=1, fill=color)


def draw_status_dot(o, w, dot_color):
    """v3.9 状态微标：右上角 8px 圆点（绿/黄/红）"""
    dot_x = (w - PAD * 2) - 6 * DP
    dot_y = 6 * DP
    o.ellipse([dot_x - 4 * DP, dot_y - 4 * DP, dot_x + 4 * DP, dot_y + 4 * DP], fill=dot_color)


def make_hud(content_w, content_h, draw_fn):
    w = content_w + PAD * 2
    h = content_h + PAD * 2
    bg = draw_game_bg(w, h)
    overlay = Image.new("RGBA", (w, h), (0, 0, 0, 0))
    o = ImageDraw.Draw(overlay, "RGBA")
    o.rounded_rectangle([0, 0, w - 1, h - 1], radius=4 * DP, fill=(0, 0, 0, 0xCC),
                        outline=(255, 255, 255, 40), width=2)
    draw_fn(o)
    bg = bg.convert("RGBA")
    return Image.alpha_composite(bg, overlay)


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
        x = draw_hud_item(o, fp, x, y, "帧率", str(m.fps), fps_color(m.fps), colors, FPS_SIZE)
        x = draw_hud_item(o, f, x, y, "GPU", f"{m.gpu_load}%", colors["gpu"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "CPU", f"{m.cpu_load}%", colors["cpu"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "内存", f"{m.ram_pct}%", colors["ram"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "功耗", f"{f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "处理器", f"{m.cpu_temp}°C", colors["cpu"], colors, TEXT_SIZE)
        draw_hud_item(o, f, x, y, "电池", f"{m.bat_temp}°C", colors["bat"], colors, TEXT_SIZE)
    img = make_hud(1150, ROW_H, draw)
    draw_status_dot(ImageDraw.Draw(img, "RGBA"), img.width, LOAD_GREEN)
    img.save(path)


def render_h_normal(path, theme="default"):
    m = Metrics()
    colors = THEMES[theme]

    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        x = 0
        x = draw_hud_item(o, fp, x, y, "帧率", f"{m.fps} {f1(m.ft)}ms", fps_color(m.fps), colors, FPS_SIZE)
        x = draw_hud_item(o, f, x, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz", colors["gpu"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", colors["cpu"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "内存", f"{f1(m.ram)}G", colors["ram"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "电池", f"{m.bat}% {m.bat_temp}°C {f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE)
        draw_hud_item(o, f, x, y, "1%低", str(m.low1), colors["dim"], colors, TEXT_SIZE)
    img = make_hud(1700, ROW_H, draw)
    draw_status_dot(ImageDraw.Draw(img, "RGBA"), img.width, LOAD_GREEN)
    img.save(path)


def render_h_detailed(path):
    m = Metrics()
    colors = THEMES["default"]
    m.random = random.Random(2)

    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        x = 0
        # v3.9 FG 徽标演示：帧率 120 (60) FG×2
        x = draw_hud_item(o, fp, x, y, "帧率", f"120 (60) FG×2", fps_color(m.fps), colors, FPS_SIZE)
        x = draw_hud_item(o, f, x, y, "平均", str(m.avg), colors["dim"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "1%低", str(m.low1), colors["dim"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "0.1%低", str(m.low01), colors["dim"], colors, TEXT_SIZE)
        gx = x + PAD
        gw = 1700 - 2 * PAD - gx
        draw_graph(o, gx, y + ROW_H - GRAPH_H - 2, gw, GRAPH_H, m.random)
        y += ROW_H
        x = 0
        x = draw_hud_item(o, f, x, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz {f1(m.vram)}G", colors["gpu"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", colors["cpu"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "内存", f"{f1(m.ram)}G", colors["ram"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "电池", f"{m.bat}% {m.bat_temp}°C {f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "网络", f"{m.net_d}↓ {m.net_u}↑", colors["dim"], colors, TEXT_SIZE)
        x = draw_hud_item(o, f, x, y, "", m.engine, colors["dim"], colors, TEXT_SIZE)
        draw_hud_item(o, f, x, y, "", m.exe, colors["dim"], colors, TEXT_SIZE)
    img = make_hud(1700, ROW_H * 2, draw)
    draw_status_dot(ImageDraw.Draw(img, "RGBA"), img.width, LOAD_GREEN)
    img.save(path)


def render_v_compact(path):
    m = Metrics()
    colors = THEMES["default"]

    def draw(o):
        f = load_font(TEXT_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        y = draw_vrow(o, fp, y, "帧率", f"{m.fps} {f1(m.ft)}ms", fps_color(m.fps), colors, FPS_SIZE, 240 * DP)
        y = draw_vrow(o, f, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz", colors["gpu"], colors, TEXT_SIZE, 240 * DP)
        y = draw_vrow(o, f, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", colors["cpu"], colors, TEXT_SIZE, 240 * DP)
        y = draw_vrow(o, f, y, "内存", f"{f1(m.ram)}G {m.ram_pct}%", colors["ram"], colors, TEXT_SIZE, 240 * DP)
        draw_vrow(o, f, y, "电池", f"{m.bat}% {m.bat_temp}°C", colors["bat"], colors, TEXT_SIZE, 240 * DP)
    img = make_hud(240 * DP, 5 * ROW_H, draw)
    draw_status_dot(ImageDraw.Draw(img, "RGBA"), img.width, LOAD_GREEN)
    img.save(path)


def render_v_detailed(path):
    m = Metrics()
    colors = THEMES["default"]
    m.random = random.Random(3)
    content_w = 240 * DP
    height_holder = {}

    def draw(o):
        f = load_font(TEXT_SIZE)
        sf = load_font(SMALL_SIZE)
        fp = load_font(FPS_SIZE)
        y = 0
        y = draw_vrow(o, fp, y, "帧率", f"{m.fps}  {f1(m.ft)}ms", fps_color(m.fps), colors, FPS_SIZE, content_w)
        draw_histogram(o, 0, y, 160 * DP, HIST_H)
        y += HIST_H + 2 * DP
        draw_spectrum(o, 0, y, 160 * DP, SPEC_H, m.random)
        y += SPEC_H + SMALL_SIZE + 4 * DP
        y = draw_vrow(o, sf, y, "", f"平均 {m.avg}  1%低 {m.low1}  0.1%低 {m.low01}", colors["dim"], colors, SMALL_SIZE, content_w)
        gc = load_color(m.gpu_load)
        y = draw_vrow(o, f, y, "GPU", f"{m.gpu_load}% {m.gpu_temp}°C {m.gpu_clock}MHz", gc, colors, TEXT_SIZE, content_w)
        draw_gauge(o, GAUGE_X, y - 4 * DP, m.gpu_load / 100, gc)
        y = draw_vrow(o, sf, y, "显存", f"{f1(m.vram)} GiB", gc, colors, SMALL_SIZE, content_w)
        cc = load_color(m.cpu_load)
        y = draw_vrow(o, f, y, "CPU", f"{m.cpu_load}% {m.cpu_temp}°C {m.cpu_clock/1000:.1f}GHz", cc, colors, TEXT_SIZE, content_w)
        draw_gauge(o, GAUGE_X, y - 4 * DP, m.cpu_load / 100, cc)
        for i in range(4):
            y = draw_vrow(o, sf, y, f"C{i}", f"{m.cores[i]}MHz", cc, colors, SMALL_SIZE, content_w)
        rc = load_color(m.ram_pct)
        y = draw_vrow(o, f, y, "内存", f"{f1(m.ram)}G {m.ram_pct}%", rc, colors, TEXT_SIZE, content_w)
        draw_gauge(o, GAUGE_X, y - 4 * DP, m.ram_pct / 100, rc)
        y = draw_vrow(o, f, y, "电池", f"{m.bat}% {m.bat_temp}°C {f1(m.bat_power)}W", colors["bat"], colors, TEXT_SIZE, content_w)
        y = draw_vrow(o, sf, y, "网络", f"{m.net_d}↓ {m.net_u}↑", colors["dim"], colors, SMALL_SIZE, content_w)
        y = draw_vrow(o, sf, y, "磁盘", f"{m.disk_r}↓ {m.disk_w}↑", colors["dim"], colors, SMALL_SIZE, content_w)
        y = draw_vrow(o, sf, y, "显示", f"@{m.refresh}Hz", colors["dim"], colors, SMALL_SIZE, content_w)
        y = draw_vrow(o, sf, y, "", m.engine, colors["dim"], colors, SMALL_SIZE, content_w)
        y = draw_vrow(o, sf, y, "", m.exe, colors["dim"], colors, SMALL_SIZE, content_w)
        y = draw_vrow(o, sf, y, "", m.dur, colors["dim"], colors, SMALL_SIZE, content_w)
        height_holder["h"] = y
    overlay = Image.new("RGBA", (content_w + PAD * 2, 1500), (0, 0, 0, 0))
    o = ImageDraw.Draw(overlay, "RGBA")
    o.rounded_rectangle([0, 0, content_w + PAD * 2 - 1, 1499], radius=4 * DP,
                        fill=(0, 0, 0, 0xCC), outline=(255, 255, 255, 40), width=2)
    draw(o)
    h = height_holder["h"] + ROW_H + 2 * PAD
    overlay = overlay.crop((0, 0, content_w + PAD * 2, h))
    draw_status_dot(ImageDraw.Draw(overlay, "RGBA"), overlay.width, LOAD_GREEN)
    bg = draw_game_bg(overlay.width, h)
    img = Image.alpha_composite(bg.convert("RGBA"), overlay)
    img.save(path)


def render_themes(path):
    thumbs = []
    for theme in ("default", "green", "amber"):
        tmp = f"/tmp/winlatorhud-theme-{theme}.png"
        render_h_normal(tmp, theme)
        thumbs.append(Image.open(tmp))
    w = sum(t.width for t in thumbs) + 2 * 30
    h = max(t.height for t in thumbs) + 60
    canvas = Image.new("RGB", (w, h), (12, 12, 18))
    x = 30
    for t in thumbs:
        canvas.paste(t, (x, 30))
        x += t.width + 30
    canvas.save(path)


def render_selfcheck(path):
    colors = THEMES["default"]
    names = ["GPU负载", "GPU温度", "GPU频率", "显存",
             "CPU负载", "CPU温度", "CPU频率", "内存",
             "电池", "功耗", "热节流", "磁盘IO", "网络"]
    values = ["78%", "62°C", "850MHz", "5.1G",
              "45%", "55°C", "2.8GHz", "62%",
              "81%", "4.2W", "", "12↓ 3↑", "120↓ 45↑"]
    status = [0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0]

    def draw(o):
        f = load_cjk(TEXT_SIZE)
        sf = load_cjk(SMALL_SIZE)
        y = 0
        o.text((0, y), "数据源自检 (SELF-CHECK)", font=f, fill="#64B5F6")
        y += ROW_H + 2 * DP
        o.rectangle([0, y, 160 * DP, y + 1], fill=(255, 255, 255, 51))
        y += 4 * DP
        dot_r = int(TEXT_SIZE * 0.28)
        for i, name in enumerate(names):
            dot = LOAD_GREEN if status[i] == 0 else (LOAD_YELLOW if status[i] == 1 else LOAD_RED)
            o.ellipse([0, y + SMALL_SIZE / 2 - dot_r, 2 * dot_r, y + SMALL_SIZE / 2 + dot_r], fill=dot)
            o.text((2 * dot_r + 6 * DP, y), name, font=sf, fill="#E0E0E0")
            val = values[i]
            vcol = "#FFFFFF" if status[i] != 2 else "#888888"
            o.text((160 * DP - o.textlength(val, font=sf), y), val, font=sf, fill=vcol)
            y += ROW_H
        y += 4 * DP
        o.text((0, y), "WinlatorHUD.toggleSelfCheck() 退出", font=sf, fill="#888888")
    fixed_h = 15 * ROW_H + 2 * DP + 4 * DP + 4 * DP + 2 * PAD
    img = make_hud(480, fixed_h, draw)
    img.save(path)


def main():
    os.makedirs(OUT_DIR, exist_ok=True)
    jobs = [
        ("horizontal-compact.png", render_h_compact),
        ("horizontal-normal.png", render_h_normal),
        ("horizontal-detailed.png", render_h_detailed),
        ("vertical-compact.png", render_v_compact),
        ("vertical-detailed.png", render_v_detailed),
        ("themes.png", render_themes),
        ("selfcheck.png", render_selfcheck),
    ]
    for name, fn in jobs:
        path = os.path.join(OUT_DIR, name)
        fn(path)
        print(f"✓ {name} ({os.path.getsize(path)} bytes)")


if __name__ == "__main__":
    main()
