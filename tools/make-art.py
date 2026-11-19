"""Turns the 13 reference banners (16:9 jpg files, the same set as BookahCalendar's month pictures; not part of the repository) into the
small WebP files the app bundles, one scene per month. Run from anywhere:

    python tools/make-art.py "<folder with the 13 source jpg files>"

The reference files are read in name order (the order the owner saved them); index -> month below. Index 1 is a second copy of the harvest-moon
scene with a calligraphy caption and is left out (index 9 is the clean one). Each picture is cropped a little to drop the printed frame,
scaled to 1200 px wide and saved as lossy WebP into app/src/main/res/drawable-nodpi/art_<mm>_<name>.webp (about 100 KB each).

To add your own picture: put it in res/drawable-nodpi (any lowercase name), and add its id to the list in app/.../Art.kt.
"""
import glob
import os
import sys

from PIL import Image

MONTHS = {  # reference index -> (month number, file name part)
    3: (1, "fuji_snow_village"),
    2: (2, "plum_in_snow"),
    0: (3, "hina_dolls_blossom"),
    4: (4, "cherry_river"),
    6: (5, "koi_and_iris"),
    5: (6, "hydrangea_rain"),
    8: (7, "tanabata_night"),
    7: (8, "fireworks_night"),
    9: (9, "harvest_moon"),
    10: (10, "chrysanthemum_mist"),
    11: (11, "maple_temple"),
    12: (12, "snow_lantern_night"),
}
EXTRA_RIGHT_CROP = {11: 0.88}   # index 11 (maple temple) has a wide printed border on its right edge
OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "android", "app", "src", "main", "res", "drawable-nodpi")


def main(folder: str) -> None:
    files = sorted(glob.glob(os.path.join(folder, "*.jpg")))
    if len(files) != 13:
        sys.exit(f"expected 13 reference pictures, found {len(files)}")
    os.makedirs(OUT, exist_ok=True)
    total = 0
    for index, (month, name) in MONTHS.items():
        im = Image.open(files[index]).convert("RGB")
        w, h = im.size
        im = im.crop((int(w * 0.035), int(h * 0.05), int(w * EXTRA_RIGHT_CROP.get(index, 0.965)), int(h * 0.95)))      # drops the printed frame
        im = im.resize((1200, round(1200 * im.height / im.width)), Image.LANCZOS)
        path = os.path.join(OUT, f"art_{month:02d}_{name}.webp")
        im.save(path, "WEBP", quality=74, method=6)
        total += os.path.getsize(path)
        print(os.path.basename(path), im.size, os.path.getsize(path) // 1024, "KB")
    print("total", total // 1024, "KB")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
