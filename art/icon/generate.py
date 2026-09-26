#!/usr/bin/env python3
"""Generates ServerBox's launcher, notification and Play Store icons.

The icon is Android's head (a green dome with antennae) sitting on two server rack units:
a server on Android. Everything is drawn on the 108x108 adaptive-icon canvas (the safe zone is the
central circle of radius 33) and written out as:

  - Android vector drawables (adaptive foreground, background, monochrome; notification icon)
  - legacy launcher PNGs for every density and the 512px Play Store image, rendered with
    rsvg-convert from the same shapes

Run from the repository root: python3 art/icon/generate.py
"""
import os
import subprocess
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
APP_RES = os.path.join(ROOT, "app/src/ServerBox/res")
CUSTOM_RES = os.path.join(ROOT, "CustomLibrary/src/ServerBox/res")
LIB_RES = os.path.join(ROOT, "library/src/main/res")

BG_TOP = "#17453A"
BG_BOTTOM = "#0A1F1A"
UNIT = "#EEF3F1"
LED = "#3DDC84"
SLOT = "#8FA39D"
ANTENNA = LED
HEAD = LED
EYE = BG_BOTTOM

# Android's head: a half dome centred on (54, HEAD_BASE) with two eyes and two antennae.
HEAD_BASE = 50
HEAD_R = 19
EYES = [(46.5, 43.5), (61.5, 43.5)]
EYE_R = 2.2
ANTENNAE = [((45, 34.5), (40.5, 27.5)), ((63, 34.5), (67.5, 27.5))]
ANTENNA_W = 2.6

# Rack units under the head, top to bottom: x, y, width, height.
UNITS = [(34, 53.5, 40, 11), (34, 68, 40, 11)]
RADIUS = 3
LED_R = 2

DENSITIES = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def rounded_rect(x, y, w, h, r):
    return (f"M{x + r},{y}h{w - 2 * r}a{r},{r} 0 0 1 {r},{r}v{h - 2 * r}"
            f"a{r},{r} 0 0 1 -{r},{r}h-{w - 2 * r}a{r},{r} 0 0 1 -{r},-{r}"
            f"v-{h - 2 * r}a{r},{r} 0 0 1 {r},-{r}z")


def circle(cx, cy, r):
    return f"M{cx - r},{cy}a{r},{r} 0 1 0 {2 * r},0a{r},{r} 0 1 0 -{2 * r},0z"


def head():
    """The dome, flat side down, with slightly rounded bottom corners."""
    r, cx, base = HEAD_R, 54, HEAD_BASE
    return (f"M{cx - r},{base - 1.5}a{r},{r} 0 0 1 {2 * r},0v0.5a1,1 0 0 1 -1,1"
            f"h-{2 * r - 2}a1,1 0 0 1 -1,-1z")


def eyes():
    return [circle(x, y, EYE_R) for x, y in EYES]


def details(x, y, w, h):
    """Two LEDs on the left, three drive slots on the right of one unit."""
    cy = y + h / 2
    leds = [circle(x + 6, cy, LED_R), circle(x + 12, cy, LED_R)]
    slots = [rounded_rect(x + w - 19 + i * 5.5, y + 3, 3, h - 6, 1.2) for i in range(3)]
    return leds, slots


def antenna_paths(width):
    """Antennae as filled capsules, so they can join the monochrome silhouette."""
    import math
    paths = []
    for (x1, y1), (x2, y2) in ANTENNAE:
        dx, dy = x2 - x1, y2 - y1
        length = math.hypot(dx, dy)
        nx, ny = -dy / length * width / 2, dx / length * width / 2
        r = width / 2
        paths.append(
            f"M{x1 + nx:.3f},{y1 + ny:.3f}L{x2 + nx:.3f},{y2 + ny:.3f}"
            f"A{r},{r} 0 0 0 {x2 - nx:.3f},{y2 - ny:.3f}"
            f"L{x1 - nx:.3f},{y1 - ny:.3f}A{r},{r} 0 0 0 {x1 + nx:.3f},{y1 + ny:.3f}z")
    return paths


def silhouette():
    """One even-odd path: units and antennae, with LEDs and slots cut out."""
    parts = antenna_paths(ANTENNA_W) + [head()] + eyes()
    for unit in UNITS:
        parts.append(rounded_rect(*unit, RADIUS))
        leds, slots = details(*unit)
        parts += leds + slots
    return "".join(parts)


# ---------------------------------------------------------------------------------------
# SVG (rendered to PNG)

def svg_foreground():
    shapes = [f'<path fill="{ANTENNA}" d="{p}"/>' for p in antenna_paths(ANTENNA_W)]
    shapes.append(f'<path fill="{HEAD}" d="{head()}"/>')
    shapes += [f'<path fill="{EYE}" d="{p}"/>' for p in eyes()]
    for unit in UNITS:
        shapes.append(f'<path fill="{UNIT}" d="{rounded_rect(*unit, RADIUS)}"/>')
        leds, slots = details(*unit)
        shapes += [f'<path fill="{LED}" d="{p}"/>' for p in leds]
        shapes += [f'<path fill="{SLOT}" d="{p}"/>' for p in slots]
    return "\n  ".join(shapes)


def svg_background():
    return (f'<defs><linearGradient id="bg" x1="0" y1="0" x2="0" y2="1">'
            f'<stop offset="0" stop-color="{BG_TOP}"/><stop offset="1" stop-color="{BG_BOTTOM}"/>'
            f'</linearGradient></defs><rect width="108" height="108" fill="url(#bg)"/>')


def svg(content, view="0 0 108 108", clip=None):
    clip_def = clip_use = ""
    if clip:
        clip_def = f'<clipPath id="c">{clip}</clipPath>'
        clip_use = ' clip-path="url(#c)"'
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="{view}">'
            f'<defs>{clip_def}</defs><g{clip_use}>\n  {content}\n</g></svg>\n')


def render(svg_text, size, out):
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with tempfile.NamedTemporaryFile("w", suffix=".svg", delete=False) as f:
        f.write(svg_text)
        name = f.name
    subprocess.run(["rsvg-convert", "-w", str(size), "-h", str(size), "-o", out, name], check=True)
    os.unlink(name)


# ---------------------------------------------------------------------------------------
# Android vector drawables

def vector(paths, size="108dp", viewport=108, extra_ns="", translate=0):
    body = "\n".join(paths)
    if translate:
        body = (f'    <group android:translateX="{translate}" android:translateY="{translate}">\n'
                f'{body}\n    </group>')
    return (f'<?xml version="1.0" encoding="utf-8"?>\n'
            f'<!-- Generated by art/icon/generate.py; edit that instead. -->\n'
            f'<vector xmlns:android="http://schemas.android.com/apk/res/android"{extra_ns}\n'
            f'    android:width="{size}"\n    android:height="{size}"\n'
            f'    android:viewportWidth="{viewport}"\n    android:viewportHeight="{viewport}">\n'
            f'{body}\n</vector>\n')


def vpath(d, fill, even_odd=False):
    fill_type = '\n        android:fillType="evenOdd"' if even_odd else ""
    return f'    <path\n        android:fillColor="{fill}"{fill_type}\n        android:pathData="{d}" />'


def vector_foreground():
    paths = [vpath(p, ANTENNA) for p in antenna_paths(ANTENNA_W)]
    paths.append(vpath(head(), HEAD))
    paths += [vpath(p, EYE) for p in eyes()]
    for unit in UNITS:
        paths.append(vpath(rounded_rect(*unit, RADIUS), UNIT))
        leds, slots = details(*unit)
        paths += [vpath(p, LED) for p in leds] + [vpath(p, SLOT) for p in slots]
    return vector(paths)


def vector_background():
    gradient = (
        '    <path android:pathData="M0,0h108v108h-108z">\n'
        '        <aapt:attr name="android:fillColor">\n'
        '            <gradient\n'
        '                android:type="linear"\n'
        '                android:startX="54" android:startY="0"\n'
        '                android:endX="54" android:endY="108"\n'
        f'                android:startColor="{BG_TOP}"\n'
        f'                android:endColor="{BG_BOTTOM}" />\n'
        '        </aapt:attr>\n'
        '    </path>')
    return vector([gradient], extra_ns='\n    xmlns:aapt="http://schemas.android.com/aapt"')


def vector_monochrome():
    return vector([vpath(silhouette(), "#FFFFFFFF", even_odd=True)])


def vector_notification():
    # The icon spans about 40x53 (y 26..79) near the canvas centre; crop to a 56x56 viewport
    # around it so it fills the 24dp status-bar icon.
    return vector([vpath(silhouette(), "#FFFFFFFF", even_odd=True)],
                  size="24dp", viewport=56, translate=-26)


def write(path, text):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w") as f:
        f.write(text)


def main():
    fg, bg = svg_foreground(), svg_background()
    full = svg(bg + "\n  " + fg)
    # Legacy icons show the whole 108 canvas cropped to the central 72 (the adaptive icon's
    # visible area), as a rounded square or a circle.
    square = svg(bg + "\n  " + fg, view="18 18 72 72",
                 clip='<rect x="18" y="18" width="72" height="72" rx="14"/>')
    round_ = svg(bg + "\n  " + fg, view="18 18 72 72", clip='<circle cx="54" cy="54" r="36"/>')
    foreground_only = svg(fg)

    write(os.path.join(ROOT, "art/icon/icon.svg"), full)

    # The app's adaptive icon: vector layers.
    drawable = os.path.join(APP_RES, "drawable")
    write(os.path.join(drawable, "ic_main_launcher_foreground.xml"), vector_foreground())
    write(os.path.join(drawable, "ic_main_launcher_background.xml"), vector_background())
    write(os.path.join(drawable, "ic_main_launcher_monochrome.xml"), vector_monochrome())

    # Notification icon, for the app and :CustomLibrary's flavor.
    for res in (APP_RES, CUSTOM_RES):
        write(os.path.join(res, "drawable/ic_stat_icon.xml"), vector_notification())

    for density, scale in DENSITIES.items():
        mipmap = os.path.join(APP_RES, f"mipmap-{density}")
        render(square, round(48 * scale), os.path.join(mipmap, "ic_main_launcher.png"))
        render(round_, round(48 * scale), os.path.join(mipmap, "ic_main_launcher_round.png"))
        # The library's own icon (the apps list's placeholder and the documents provider).
        lib_mipmap = os.path.join(LIB_RES, f"mipmap-{density}")
        render(square, round(48 * scale), os.path.join(lib_mipmap, "ic_launcher.png"))
        render(round_, round(48 * scale), os.path.join(lib_mipmap, "ic_launcher_round.png"))
        render(foreground_only, round(108 * scale), os.path.join(lib_mipmap, "ic_launcher_foreground.png"))

    render(full, 512, os.path.join(ROOT, "app/src/ServerBox/ic_main_launcher-playstore.png"))


if __name__ == "__main__":
    main()
