#!/usr/bin/env python3
"""Генерация текстур сетей: python3 tools/gen_textures.py  (нужны pillow и numpy). Результат — в src/main/resources/assets/tentnets/textures/entity."""
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources",
                   "assets", "tentnets", "textures", "entity")
N = 64  # размер текстуры


def tile_noise(seed, sigma, size=N):
    """Бесшовный шум (гауссово размытие через FFT), значения 0..1."""
    rng = np.random.default_rng(seed)
    f = np.fft.fft2(rng.standard_normal((size, size)))
    ky = np.fft.fftfreq(size)[:, None]
    kx = np.fft.fftfreq(size)[None, :]
    f *= np.exp(-2.0 * (math.pi ** 2) * (kx ** 2 + ky ** 2) * sigma ** 2)
    a = np.real(np.fft.ifft2(f))
    a -= a.min()
    return a / max(a.max(), 1e-9)


def quantile_index(field, levels):
    """Разбивает поле на levels равных по площади диапазонов -> индексы 0..levels-1."""
    qs = np.quantile(field, [i / levels for i in range(1, levels)])
    return np.digitize(field, qs)


# ---------------------------------------------------------------- антидроновая сеть (стальной трос, ячейка 8 px)

ANTI = {
    "anti_drone_net": ((150, 158, 166), (216, 224, 230), (78, 84, 92)),
    "anti_drone_net_black": ((44, 47, 52), (98, 103, 110), (16, 18, 20)),
    "anti_drone_net_khaki": ((104, 116, 66), (154, 168, 106), (56, 66, 34)),
}


def make_anti(base, hi, sh):
    img = np.zeros((N, N, 4), dtype=np.uint8)
    cell = 8
    for y in range(N):
        for x in range(N):
            cx, cy = x % cell, y % cell
            on_h = cy < 2  # горизонтальная жила
            on_v = cx < 2  # вертикальная жила
            if not (on_h or on_v):
                continue
            col = None
            if on_v:  # цилиндрическая светотень поперёк жилы: слева тень, справа блик
                col = sh if cx == 0 else hi
            if on_h:
                c2 = sh if cy == 1 else hi
                col = c2 if col is None else (c2 if ((x // cell + y // cell) % 2 == 0) else col)
            # узел: плетение «над-под» и тёмная кромка
            if on_h and on_v:
                over_h = ((x // cell + y // cell) % 2 == 0)
                col = (hi if over_h else base)
            # лёгкая «зернистость» металла
            j = ((x * 73856093) ^ (y * 19349663)) & 7
            col = tuple(max(0, min(255, c + (j - 3) * 2)) for c in col)
            img[y, x] = (*col, 255)
    # зажим на пересечении: небольшой блик и тень, без перекрытия жил
    for gy in range(0, N, cell):
        for gx in range(0, N, cell):
            img[gy % N, gx % N] = (*hi, 255)
            img[gy % N, (gx + 1) % N] = (*base, 255)
            img[(gy + 1) % N, gx % N] = (*base, 255)
            img[(gy + 1) % N, (gx + 1) % N] = (*sh, 255)
            # тень от узла на жилах
            img[(gy + 2) % N, (gx + 1) % N] = (*sh, 255)
            img[(gy + 1) % N, (gx + 2) % N] = (*sh, 255)
    return Image.fromarray(img, "RGBA")


# ---------------------------------------------------------------- маскировочная сеть

MASK = {
    # 4 цвета камуфляжа (тёмный -> светлый), цвет шнура
    "mask_net": ([(34, 54, 26), (54, 82, 38), (86, 124, 54), (122, 158, 70)], (84, 64, 42)),
    "mask_net_desert": ([(104, 82, 48), (140, 112, 66), (184, 152, 92), (218, 192, 138)], (92, 72, 44)),
    "mask_net_winter": ([(120, 134, 144), (158, 170, 178), (200, 212, 220), (232, 238, 242)], (104, 114, 122)),
    "mask_net_urban": ([(48, 52, 56), (72, 78, 82), (100, 106, 112), (138, 144, 150)], (36, 38, 40)),
    "mask_net_marsh": ([(52, 46, 32), (72, 72, 40), (104, 104, 58), (138, 136, 80)], (56, 44, 30)),
    "mask_net_night": ([(12, 16, 11), (22, 30, 20), (38, 52, 36), (58, 76, 50)], (10, 12, 9)),
    "mask_net_autumn": ([(70, 86, 34), (118, 68, 26), (168, 98, 32), (206, 142, 54)], (80, 54, 24)),
    "mask_net_digital": ([(78, 68, 50), (72, 90, 56), (112, 122, 76), (142, 144, 138)], (58, 54, 44)),
}


def make_mask(name, pal, cord, seed):
    rng = np.random.default_rng(seed)
    big = tile_noise(seed, 4.2)
    mid = tile_noise(seed + 1, 2.0)
    field = big * 0.78 + mid * 0.22
    if name == "mask_net_digital":  # «цифра»: пиксельные блоки 4x4
        small = 4
        field = field[::small, ::small]
        field = np.kron(field, np.ones((small, small)))
    idx = quantile_index(field, 4)
    rgb = np.zeros((N, N, 3), dtype=np.float32)
    for i, c in enumerate(pal):
        rgb[idx == i] = c

    # полоски ткани (гирлянды из лоскутов): короткие диагональные штрихи светлее/темнее
    for _ in range(170):
        x, y = int(rng.integers(0, N)), int(rng.integers(0, N))
        ln = int(rng.integers(4, 10))
        dx, dy = (1, 1) if rng.random() < 0.5 else (1, -1)
        k = 0.82 if rng.random() < 0.5 else 1.16
        for t in range(ln):
            xx, yy = (x + dx * t) % N, (y + dy * t) % N
            rgb[yy, xx] *= k
    # мелкое зерно ткани
    rgb *= (0.94 + 0.12 * rng.random((N, N, 1))).astype(np.float32)
    rgb = np.clip(rgb, 0, 255)

    alpha = np.full((N, N), 255, dtype=np.uint8)
    # прорези/дыры (~14%), через них видно основу
    holes = tile_noise(seed + 2, 1.7) > np.quantile(tile_noise(seed + 2, 1.7), 0.86)
    alpha[holes] = 0
    # лёгкие рваные края вокруг дыр: затемнение
    edge = np.zeros_like(holes)
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
        edge |= np.roll(np.roll(holes, dy, 0), dx, 1)
    edge &= ~holes
    rgb[edge] *= 0.72

    # шнуровая основа: сетка каждые 8 px (1/4 блока), 2 px толщиной, узлы 3x3
    cell = 8
    cord_hi = tuple(min(255, int(c * 1.45) + 8) for c in cord)
    for y in range(N):
        for x in range(N):
            cx, cy = x % cell, y % cell
            if cx < 2 or cy < 2:
                on_both = cx < 2 and cy < 2
                col = cord_hi if (cx == 1 or cy == 1) and not on_both else cord
                if on_both:
                    col = cord_hi
                # шнур лежит поверх ткани только в дырах и вдоль краёв; в остальных местах — приглушённый «шов»
                if alpha[y, x] == 0:
                    alpha[y, x] = 255
                    rgb[y, x] = col
                else:
                    rgb[y, x] = rgb[y, x] * 0.55 + np.array(cord, dtype=np.float32) * 0.45
    out = np.dstack([rgb.astype(np.uint8), alpha])
    return Image.fromarray(out, "RGBA")


# ---------------------------------------------------------------- листва (атлас 2x2, клетка 64 px)

def leaf_cell(kind, seed, S=256):
    """Один лист, основание внизу по центру, кончик вверху. Рисуется в 4x разрешении и уменьшается."""
    rng = np.random.default_rng(seed)
    mask = Image.new("L", (S, S), 0)
    d = ImageDraw.Draw(mask)
    cx = S / 2
    y0, y1 = S * 0.94, S * 0.04  # основание, кончик

    def width(t):
        if kind == 0:   # дубовый, лопастной
            return 0.40 * math.sin(math.pi * t ** 0.8) * (1 + 0.20 * math.sin(6.5 * math.pi * t))
        if kind == 1:   # яйцевидный
            return 0.44 * math.sin(math.pi * t ** 0.75)
        if kind == 2:   # ланцетный
            return 0.25 * math.sin(math.pi * t ** 0.9)
        return 0.48 * (math.sin(math.pi * t) ** 0.65)  # широкий округлый

    pts_l, pts_r = [], []
    for i in range(61):
        t = i / 60.0
        y = y0 + (y1 - y0) * t
        w = width(t) * S
        pts_l.append((cx - w, y))
        pts_r.append((cx + w, y))
    d.polygon(pts_l + pts_r[::-1], fill=255)
    d.line([(cx, y0 + S * 0.03), (cx, y0)], fill=255, width=max(2, S // 40))  # черенок

    # цвет: градиент от тёмного основания к светлому кончику + случайные пятна
    yy = np.linspace(1.0, 0.0, S)[:, None]  # 1 у основания (низ), 0 у кончика
    dark = np.array([34, 76, 26], dtype=np.float32)
    light = np.array([104, 156, 56], dtype=np.float32)
    shade = rng.uniform(0.9, 1.1)
    grad = (dark[None, None, :] * yy[:, :, None] + light[None, None, :] * (1 - yy[:, :, None])) * shade
    grad = np.repeat(grad, S, axis=1)
    img = Image.fromarray(np.clip(grad, 0, 255).astype(np.uint8), "RGB").convert("RGBA")
    dr = ImageDraw.Draw(img)
    # центральная жилка и боковые жилки
    vein = (128, 176, 78, 255)
    vein_d = (46, 98, 34, 255)
    dr.line([(cx, y0), (cx, y1 + S * 0.06)], fill=vein, width=max(2, S // 56))
    for k in range(1, 7):
        t = k / 7.5
        y = y0 + (y1 - y0) * t
        w = width(t + 0.1) * S * 0.85
        for sgn in (-1, 1):
            dr.line([(cx, y), (cx + sgn * w, y + (y1 - y0) * 0.12)], fill=vein_d, width=max(1, S // 90))
    # тёмные крапинки
    for _ in range(22):
        px, py = rng.uniform(cx - S * 0.3, cx + S * 0.3), rng.uniform(S * 0.1, S * 0.9)
        r = rng.uniform(2, 6)
        dr.ellipse([px - r, py - r, px + r, py + r], fill=(30, 70, 24, 255))
    img.putalpha(mask)
    return img.resize((64, 64), Image.LANCZOS)


def make_leaves():
    atlas = Image.new("RGBA", (128, 128), (0, 0, 0, 0))
    for i in range(4):
        atlas.paste(leaf_cell(i, 100 + i), ((i % 2) * 64, (i // 2) * 64))
    # жёсткая альфа (cutout): убираем полупрозрачные края, чтобы не было светлых ореолов
    a = np.array(atlas)
    a[:, :, 3] = np.where(a[:, :, 3] > 100, 255, 0)
    return Image.fromarray(a, "RGBA")


def main():
    os.makedirs(OUT, exist_ok=True)
    for name, (base, hi, sh) in ANTI.items():
        make_anti(base, hi, sh).save(os.path.join(OUT, name + ".png"))
    for i, (name, (pal, cord)) in enumerate(MASK.items()):
        make_mask(name, pal, cord, 11 + i * 7).save(os.path.join(OUT, name + ".png"))
    make_leaves().save(os.path.join(OUT, "net_leaves.png"))
    print("textures written to", os.path.normpath(OUT))


if __name__ == "__main__":
    sys.exit(main())
