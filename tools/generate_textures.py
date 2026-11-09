#!/usr/bin/env python3
"""Генерирует ОДИН файл-лист со всеми эмоциями глаза (нужен Pillow: pip install pillow).
Запускается автоматически при сборке на GitHub (см. .github/workflows/build.yml),
результат в репозиторий НЕ попадает (.gitignore).
Сетка 4x2, кадры по 256x256, порядок = порядок Emotion в Emotion.java:
NORMAL, BLINK, HAPPY, ANGRY, SAD, SURPRISED, SLEEPY, затем 8-й кадр - свечение лазера.
Свои текстуры: положи свой лист 1024x512 с тем же порядком кадров в
resourcepack/assets/eyesky/textures/item/s.png и убери шаг генерации из build.yml."""
import math, os
from PIL import Image, ImageDraw, ImageFilter, ImageChops

S = 3                      # суперсэмплинг для гладких краёв
W = 512                    # рабочий размер
C = W * S
OUT_SIZE = 256
OUT_DIR = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "resourcepack",
                       "assets", "eyesky", "textures", "item")
FRAMES = []
OUTLINE = (22, 8, 38, 255)
LID = (74, 34, 114, 255)
AURA = (150, 80, 255)


def almond(a, b):
    """Миндалевидная форма: пересечение двух кругов."""
    a, b = a * S, b * S
    R = (a * a + b * b) / (2 * b)
    off = R - b
    cx = cy = C // 2
    m1 = Image.new("L", (C, C), 0)
    ImageDraw.Draw(m1).ellipse([cx - R, cy + off - R, cx + R, cy + off + R], fill=255)
    m2 = Image.new("L", (C, C), 0)
    ImageDraw.Draw(m2).ellipse([cx - R, cy - off - R, cx + R, cy - off + R], fill=255)
    return ImageChops.darker(m1, m2)


def lerp(c1, c2, t):
    return tuple(int(c1[i] + (c2[i] - c1[i]) * t) for i in range(3))


def P(points):
    return [(x * S, y * S) for x, y in points]


def line(d, pts, width, fill=OUTLINE):
    pts = P(pts)
    d.line(pts, fill=fill, width=int(width * S), joint="curve")
    r = width * S / 2
    for x, y in (pts[0], pts[-1]):
        d.ellipse([x - r, y - r, x + r, y + r], fill=fill)


def curve(f, x0=0, x1=512, n=60):
    return [(x0 + (x1 - x0) * i / n, f(x0 + (x1 - x0) * i / n)) for i in range(n + 1)]


def draw_eye(name, b=130, iris_r=105, pupil=(40, 62), gaze=(0, 0),
             iris_cols=((8, 60, 105), (90, 235, 255)), lid=None, brow=None, closed=False):
    img = Image.new("RGBA", (C, C), (0, 0, 0, 0))
    outer = almond(224, b + 7)
    inner = almond(208, b - 8)

    # свечение
    if not closed:
        aura_a = outer.filter(ImageFilter.GaussianBlur(26 * S)).point(lambda v: int(v * 0.55))
        img.paste(Image.new("RGBA", (C, C), AURA + (255,)), (0, 0), aura_a)

    if closed:
        d = ImageDraw.Draw(img)
        arc = curve(lambda x: 250 + 55 * (1 - ((x - 256) / 215) ** 2), 41, 471)
        line(d, arc, 24)
        for k in range(-3, 4):
            x = 256 + k * 62
            y = 250 + 55 * (1 - ((x - 256) / 215) ** 2)
            ang = math.radians(90 + k * 14)
            line(d, [(x, y + 8), (x + math.cos(ang) * 55, y + math.sin(ang) * 55)], 11)
    else:
        content = Image.new("RGBA", (C, C), (0, 0, 0, 0))
        shade = Image.composite(Image.new("RGBA", (C, C), (252, 246, 248, 255)),
                                Image.new("RGBA", (C, C), (196, 150, 176, 255)),
                                inner.filter(ImageFilter.GaussianBlur(50 * S)))
        content.paste(shade, (0, 0))
        d = ImageDraw.Draw(content)
        cx, cy = 256 + gaze[0], 256 + gaze[1]
        steps = 60
        for i in range(steps):
            t = i / (steps - 1)
            r = iris_r * (1 - t)
            col = lerp(iris_cols[0], iris_cols[1], min(1, t * 1.6)) + (255,)
            d.ellipse([(cx - r) * S, (cy - r) * S, (cx + r) * S, (cy + r) * S], fill=col)
        d.ellipse([(cx - iris_r) * S, (cy - iris_r) * S, (cx + iris_r) * S, (cy + iris_r) * S],
                  outline=(10, 20, 40, 255), width=7 * S)
        px, py = pupil
        d.ellipse([(cx - px) * S, (cy - py) * S, (cx + px) * S, (cy + py) * S], fill=(4, 2, 10, 255))
        hx, hy = cx - iris_r * 0.35, cy - iris_r * 0.38
        d.ellipse([(hx - 22) * S, (hy - 22) * S, (hx + 22) * S, (hy + 22) * S], fill=(255, 255, 255, 255))
        hx2, hy2 = cx + iris_r * 0.38, cy + iris_r * 0.34
        d.ellipse([(hx2 - 9) * S, (hy2 - 9) * S, (hx2 + 9) * S, (hy2 + 9) * S], fill=(255, 255, 255, 215))
        if lid:
            d.polygon(P(lid["poly"]), fill=LID)
            line(d, lid["edge"], 15)
        img.paste(content, (0, 0), inner)
        rim = ImageChops.subtract(outer, inner)
        img.paste(Image.new("RGBA", (C, C), OUTLINE), (0, 0), rim)

    if brow:
        d = ImageDraw.Draw(img)
        for pts in brow:
            line(d, pts, 22)

    img = img.resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
    FRAMES.append(img)


os.makedirs(OUT_DIR, exist_ok=True)

draw_eye("normal")
draw_eye("blink", closed=True)

draw_eye("happy", gaze=(0, -22),
         iris_cols=((10, 90, 90), (120, 255, 200)),
         lid=dict(poly=[(0, 512)] + curve(lambda x: 300 - 62 * (1 - ((x - 256) / 256) ** 2)) + [(512, 512)],
                  edge=curve(lambda x: 300 - 62 * (1 - ((x - 256) / 256) ** 2))))

draw_eye("angry", pupil=(26, 66), gaze=(0, 8),
         iris_cols=((90, 8, 8), (255, 150, 40)),
         lid=dict(poly=[(0, 0), (512, 0), (512, 170), (256, 300), (0, 170)],
                  edge=[(0, 170), (256, 300), (512, 170)]),
         brow=[[(40, 55), (256, 120), (472, 55)]])

draw_eye("sad", gaze=(0, 22), pupil=(44, 58),
         iris_cols=((10, 30, 100), (110, 160, 255)),
         lid=dict(poly=[(0, 0), (512, 0), (512, 275), (256, 165), (0, 275)],
                  edge=[(0, 275), (256, 165), (512, 275)]),
         brow=[[(40, 115), (256, 50), (472, 115)]])

draw_eye("surprised", b=175, iris_r=82, pupil=(20, 26),
         brow=[curve(lambda x: 52 - 38 * (1 - ((x - 256) / 215) ** 2), 41, 471)])

draw_eye("sleepy", gaze=(0, 20), pupil=(36, 52),
         iris_cols=((30, 20, 90), (110, 110, 220)),
         lid=dict(poly=[(0, 0), (512, 0), (512, 262)] +
                       curve(lambda x: 262 + 22 * ((x - 256) / 256) ** 2, 512, 0) + [(0, 262)],
                  edge=curve(lambda x: 262 + 22 * ((x - 256) / 256) ** 2)))

# кадр 8 (индекс 7): мягкое свечение для лазера
glow = Image.new("RGBA", (C, C), (255, 90, 20, 0))
gd = ImageDraw.Draw(glow)
for r, col, a in [(215, (255, 60, 20), 45), (170, (255, 110, 30), 80), (125, (255, 170, 60), 130),
                  (80, (255, 225, 150), 190), (40, (255, 255, 240), 255)]:
    gd.ellipse([C // 2 - r * S, C // 2 - r * S, C // 2 + r * S, C // 2 + r * S], fill=col + (a,))
glow = glow.filter(ImageFilter.GaussianBlur(26 * S)).resize((OUT_SIZE, OUT_SIZE), Image.LANCZOS)
FRAMES.append(glow)

sheet = Image.new("RGBA", (OUT_SIZE * 4, OUT_SIZE * 2), (0, 0, 0, 0))
for i, fr in enumerate(FRAMES):
    sheet.paste(fr, ((i % 4) * OUT_SIZE, (i // 4) * OUT_SIZE))
sheet.save(os.path.join(OUT_DIR, "s.png"))
print("saved sheet with", len(FRAMES), "frames")
