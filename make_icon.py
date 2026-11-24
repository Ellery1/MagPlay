"""把选中的图标生成 Android 自适应图标资源（前景/背景 + 各密度）。"""
from PIL import Image, ImageDraw
import os

SRC = r"E:\python\MagPlay\app_icons\A_modern_Android_app_launcher__2026-11-23T15-25-19.png"
RES = r"E:\python\MagPlay\app\src\main\res"

# 源图主体颜色取样（深紫蓝底）
BG_COLOR = (30, 26, 60, 255)  # #1E1A3C 深紫

src = Image.open(SRC).convert("RGBA")
W, H = src.size

# ---- 1. 前景层：保留整图，缩放到 adaptive foreground 的 108dp 画布 ----
# 自适应图标前景可视安全区为中央 66dp（直径），108dp 画布。
# 让主体图形落在安全区内：源图本身有圆角底和留白，缩到 108dp 的 ~72% 居中。
FG_CANVAS = 432  # xxxhdpi: 108dp * 4
fg_canvas = Image.new("RGBA", (FG_CANVAS, FG_CANVAS), (0, 0, 0, 0))
# 主体缩放：占画布 62%，居中（主体距中心约 66/108=61% 的安全直径）
target = int(FG_CANVAS * 0.62)
img_fg = src.resize((target, target), Image.LANCZOS)
off = (FG_CANVAS - target) // 2
fg_canvas.alpha_composite(img_fg, (off, off))

# ---- 2. 兼容模式圆形图标（API<26）：整图裁成圆形 ----
def make_round(size):
    im = src.resize((size, size), Image.LANCZOS)
    mask = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(mask)
    d.ellipse((0, 0, size - 1, size - 1), fill=255)
    out = Image.new("RGBA", (size, size), (0, 0, 0, 0))
    out.paste(im, (0, 0), mask)
    return out

# 密度 -> 尺寸
DENSITIES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
FG_DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}

for den, size in DENSITIES.items():
    d = os.path.join(RES, f"mipmap-{den}")
    os.makedirs(d, exist_ok=True)
    # 背景层：纯色 PNG（自适应图标 background 用颜色或 drawable）
    bg = Image.new("RGBA", (size, size), BG_COLOR)
    bg.save(os.path.join(d, "ic_launcher_background.png"))
    # 圆形兼容图标
    make_round(size).save(os.path.join(d, "ic_launcher.png"))
    make_round(size).save(os.path.join(d, "ic_launcher_round.png"))
    # 前景层
    fgsize = FG_DENSITIES[den]
    fgc = Image.new("RGBA", (fgsize, fgsize), (0, 0, 0, 0))
    tgt = int(fgsize * 0.62)
    imf = src.resize((tgt, tgt), Image.LANCZOS)
    o = (fgsize - tgt) // 2
    fgc.alpha_composite(imf, (o, o))
    fgc.save(os.path.join(d, "ic_launcher_foreground.png"))
    print(f"{den}: launcher={size}px, foreground={fgsize}px")

# anydpi-v26 自适应图标 XML
v26 = os.path.join(RES, "mipmap-anydpi-v26")
os.makedirs(v26, exist_ok=True)
with open(os.path.join(v26, "ic_launcher.xml"), "w", encoding="utf-8") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
''')
with open(os.path.join(v26, "ic_launcher_round.xml"), "w", encoding="utf-8") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@color/ic_launcher_background"/>
    <foreground android:drawable="@mipmap/ic_launcher_foreground"/>
</adaptive-icon>
''')

# 背景色定义
values = os.path.join(RES, "values")
os.makedirs(values, exist_ok=True)
colors_path = os.path.join(values, "ic_launcher_colors.xml")
with open(colors_path, "w", encoding="utf-8") as f:
    f.write('''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <color name="ic_launcher_background">#1E1A3C</color>
</resources>
''')

print("DONE: 自适应图标资源已生成")
