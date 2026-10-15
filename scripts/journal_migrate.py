#!/usr/bin/env python3
"""Migrate an old Journal app repo (~/.journal) into Today's journal format.

Old: entries/YYYY/MM/YYYY-MM-DD.md with `:::murmur` blocks (id, time, location,
latitude, longitude, …, then the text, with `::image … ::` blocks inside).
New: docs/design/today/SPEC.md §4 — one `## HH:MM` + `<!-- minis:entry … -->`
block per murmur, photos as relative paths in the phone's library.

Writes, under --out:
  journal/YYYY/MM/YYYY-MM-DD.md   only the new entry blocks for that day (no front matter)
  photos/<name>                   the images, renamed to what `photos=` points at
  photos.tsv                      name<TAB>taken-at (local, for touch)

Ids are ULIDs whose time part is the murmur's time and whose random part
comes from the old id, so running it again gives the same ids (the merge
step skips ids already in a day file).
"""
import argparse
import hashlib
import pathlib
import re
import shutil
from datetime import datetime, timezone, timedelta

ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
BLOCK = re.compile(r"^:::murmur\n(.*?)\n---\n(.*?)\n:::\s*$", re.S | re.M)
IMAGE = re.compile(r"^::image\n(.*?)\n::\s*$", re.S | re.M)
PHOTO_DIR = "DCIM/Minis/"


def ulid(ms: int, seed: str) -> str:
    out = []
    t = ms
    for _ in range(10):
        out.append(ALPHABET[t & 31]); t >>= 5
    out.reverse()
    rnd = int.from_bytes(hashlib.sha1(seed.encode()).digest()[:10], "big")
    out += [ALPHABET[(rnd >> (5 * i)) & 31] for i in range(15, -1, -1)]
    return "".join(out)


def kv(text: str) -> dict:
    d = {}
    for line in text.splitlines():
        k, sep, v = line.partition(":")
        if sep and k.strip() and not k.startswith(" "):
            d[k.strip()] = v.strip().strip('"')
    return d


def render_value(v: str) -> str:
    """JournalCodec.renderValue."""
    needs = v == "" or "--" in v or any(c.isspace() or c in ',"&' or ord(c) > 0x7E for c in v)
    if not needs:
        return v
    return '"' + v.replace("&", "&amp;").replace('"', "&quot;").replace("--", "&#45;&#45;") + '"'


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--src", default=str(pathlib.Path.home() / ".journal"))
    ap.add_argument("--out", required=True)
    ap.add_argument("--tz", type=int, default=8, help="UTC offset of the phone, hours")
    a = ap.parse_args()
    src, out = pathlib.Path(a.src), pathlib.Path(a.out)
    tz = timezone(timedelta(hours=a.tz))
    (out / "journal").mkdir(parents=True, exist_ok=True)
    (out / "photos").mkdir(exist_ok=True)
    days: dict[str, list[tuple[datetime, str]]] = {}
    photo_rows, missing, n_entries, n_photo_only = [], [], 0, 0

    for f in sorted((src / "entries").rglob("*.md")):
        for m in BLOCK.finditer(f.read_text()):
            meta, body = kv(m.group(1)), m.group(2)
            when = datetime.fromisoformat(meta["time"].replace("Z", "+00:00")).astimezone(tz).replace(microsecond=0)
            photos = []
            for im in IMAGE.finditer(body):
                srcpath = kv(im.group(1)).get("src", "")
                p = src / srcpath
                if not srcpath or not p.is_file():
                    missing.append(srcpath); continue
                name = "JOURNAL_" + p.name
                shutil.copy2(p, out / "photos" / name)
                photo_rows.append(f"{name}\t{when.strftime('%Y%m%d%H%M.%S')}")
                photos.append(PHOTO_DIR + name)
            text = IMAGE.sub("", body)
            text = re.sub(r"\n{3,}", "\n\n", text).strip("\n").strip()
            if not text and not photos:
                continue
            if not text:
                n_photo_only += 1
            attrs = [("id", ulid(int(when.timestamp() * 1000), meta.get("id", str(when)))),
                     ("at", when.isoformat())]
            lat, lng = meta.get("latitude"), meta.get("longitude")
            if lat and lng and float(lat) != 0 and float(lng) != 0:
                attrs += [("lat", lat), ("lng", lng)]
                if meta.get("accuracyMeters"):
                    attrs.append(("acc", str(round(float(meta["accuracyMeters"])))))
            if meta.get("location"):
                # Later entries say "成都市 · 成华区 · 路 · 地点": keep only the place itself.
                attrs.append(("place", meta["location"].split(" · ")[-1].strip()))
            if photos:
                attrs.append(("photos", ",".join(photos)))
            block = f"## {when.strftime('%H:%M')}\n<!-- minis:entry " + " ".join(f"{k}={render_value(v)}" for k, v in attrs) + " -->\n"
            if text:
                block += text.replace("<!-- minis:entry", "&lt;!-- minis:entry") + "\n"
            days.setdefault(when.date().isoformat(), []).append((when, block))
            n_entries += 1

    for day, blocks in days.items():
        p = out / "journal" / day[:4] / day[5:7] / f"{day}.md"
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text("\n".join(b for _, b in sorted(blocks)))
    (out / "photos.tsv").write_text("\n".join(photo_rows) + "\n")
    print(f"{n_entries} entries ({n_photo_only} photo-only) over {len(days)} days; {len(photo_rows)} photos; missing {len(missing)}")
    for m in missing[:10]:
        print("  missing:", m)


if __name__ == "__main__":
    main()
