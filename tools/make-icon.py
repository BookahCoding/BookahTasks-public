"""BookahTasks icon drafts: ukiyo-e style, Hikyaku for Windows default palette (Washi & Ink), a brush-stroke tick motif.
Run: python -I -X utf8 tools/make-icon.py docs/icon-drafts
Writes draft-a/b/c.svg and sheet.html (each draft in 4 launcher masks + 48 px + a flat one-colour silhouette).
Every shape is drawn here from scratch (no traced print, no downloaded art)."""
import pathlib
import sys

VERMILION = "#A63F2D"
VERM_D = "#7E2C20"
WASHI = "#F4EDD9"
WASHI_D = "#E6DCC2"
INK = "#2B2822"
INDIGO = "#245A86"
INDIGO_D = "#173B58"
CORAL = "#E5927E"

# a brush-stroke tick in a 100x100 box: thin start, heavy belly, tapering flick
TICK = ("M17,55 C20,50 27,50 31,54 L43,67 C52,50 64,33 77,19 C80,16 85,16 87,19 "
        "C89,22 88,25 86,28 C74,43 62,62 51,82 C48,87 42,88 38,84 L17,61 C15,59 15,57 17,55 Z")
TICK_HI = "M24,56 L42,74"      # a lighter dry-brush line along the first stroke

WAVE_BODY = ("M0,100 L0,62 C4,40 22,22 44,22 C62,22 76,32 80,46 C82,52 80,58 76,60 "
             "C78,52 70,44 60,44 C48,44 44,56 50,66 C56,76 70,80 100,80 L100,100 Z")
WAVE_CREST = "M0,62 C4,40 22,22 44,22 C62,22 76,32 80,46 C82,52 80,58 76,60"


def svg(inner, bg):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="432" height="432">'
            f'<rect width="108" height="108" fill="{bg}"/>{inner}</svg>')


def place(inner, scale=0.62, ox=54, oy=54):
    """Puts 100x100 artwork in the middle of the 108 box, inside the 66/108 safe zone."""
    s = scale
    return f'<g transform="translate({ox - 50 * s:.2f},{oy - 50 * s:.2f}) scale({s})">{inner}</g>'


def draft_a():
    """Washi paper, a vermilion sun disc, a sumi brush tick across it."""
    art = (f'<circle cx="50" cy="50" r="46" fill="{VERMILION}"/>'
           f'<circle cx="50" cy="50" r="46" fill="none" stroke="{VERM_D}" stroke-width="2" opacity=".5"/>'
           f'<path d="{TICK}" fill="{INK}"/>'
           f'<path d="{TICK_HI}" stroke="{WASHI}" stroke-width="1.4" stroke-linecap="round" opacity=".35" fill="none"/>')
    return svg(place(art, 0.74), WASHI)


def draft_b():
    """Indigo night sea: a washi wave crest curling around a vermilion tick, small sun above."""
    wave = (f'<path d="{WAVE_BODY}" fill="{WASHI}"/>'
            f'<path d="{WAVE_CREST}" fill="none" stroke="{WASHI_D}" stroke-width="3" stroke-linecap="round" stroke-dasharray="0.1 6"/>')
    art = (f'<circle cx="74" cy="24" r="12" fill="{VERMILION}"/>'
           f'<g transform="translate(0,6)">{wave}</g>'
           f'<g transform="translate(14,2) scale(.7)"><path d="{TICK}" fill="{VERMILION}" stroke="{WASHI}" stroke-width="3" stroke-linejoin="round"/></g>')
    return svg(place(art, 0.8), INDIGO)


def draft_c():
    """Deep indigo field: a bamboo stem bent into a tick, nodes in sumi ink, one vermilion leaf-mark."""
    def seg(x1, y1, x2, y2, w=11):
        return f'<line x1="{x1}" y1="{y1}" x2="{x2}" y2="{y2}" stroke="{WASHI}" stroke-width="{w}" stroke-linecap="round"/>'

    def node(x, y, w=15):
        return f'<line x1="{x - w / 2}" y1="{y}" x2="{x + w / 2}" y2="{y}" stroke="{INK}" stroke-width="2.4" stroke-linecap="round" transform="rotate(-20 {x} {y})"/>'
    art = (seg(22, 56, 42, 76) + seg(42, 76, 80, 24) +
           node(32, 66) + node(55, 56, 14) + node(66, 40, 14) +
           f'<path d="M80,24 C90,16 96,18 96,18 C94,28 88,30 80,24 Z" fill="{VERMILION}"/>')
    return svg(place(art, 0.78), INDIGO_D)


def silhouette(draft_svg):
    return draft_svg.replace(WASHI, "#000").replace(VERMILION, "#000").replace(INDIGO_D, "#fff").replace(INDIGO, "#fff").replace(INK, "#000")


def sheet(drafts):
    masks = [("circle", "50%"), ("squircle", "30%"), ("rounded", "18%"), ("square", "0")]
    rows = []
    for name, s in drafts:
        cells = "".join(
            f'<div class="cell"><div class="m" style="border-radius:{r}">{s.replace("width=\"432\" height=\"432\"", "width=\"100%\" height=\"100%\"")}</div><span>{n}</span></div>'
            for n, r in masks)
        small = s.replace('width="432" height="432"', 'width="48" height="48"')
        rows.append(f'<section><h2>Draft {name}</h2><div class="row">{cells}'
                    f'<div class="cell"><div class="m" style="width:48px;height:48px;border-radius:30%">{small}</div><span>48 px</span></div></div></section>')
    css = ("body{font:16px system-ui;background:#f7f7f5;color:#2b2822;margin:24px}h1{font-weight:500}"
           ".row{display:flex;gap:20px;align-items:flex-end;margin-bottom:28px}.cell{text-align:center}"
           ".m{width:120px;height:120px;overflow:hidden;box-shadow:0 1px 4px #0003}.cell span{display:block;font-size:12px;margin-top:4px}")
    return f"<!doctype html><meta charset=utf-8><title>BookahTasks icon drafts</title><style>{css}</style><h1>BookahTasks icon drafts</h1>{''.join(rows)}"


def main(out):
    out = pathlib.Path(out)
    out.mkdir(parents=True, exist_ok=True)
    drafts = [("A", draft_a()), ("B", draft_b()), ("C", draft_c())]
    for name, s in drafts:
        (out / f"draft-{name.lower()}.svg").write_text(s, encoding="utf-8")
    (out / "sheet.html").write_text(sheet(drafts), encoding="utf-8")
    print("wrote", out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "docs/icon-drafts")
