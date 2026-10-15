#!/usr/bin/env python3
"""Разбирает блок TSDB по байтам и печатает его содержимое.

Формат описан в docs/structure-and-usecases.md, а пишется кодом в
src/main/kotlin/org/pirsbd/tsdb/storage/codec/. Этот скрипт читает те же
байты независимо от Kotlin: если он разобрал файл, значит формат на диске
действительно такой, как заявлено.

Примеры:
    tools/dump-block.py demo-data
    tools/dump-block.py demo-data/01M50.../
    tools/dump-block.py demo-data --query __name__=cpu host=a
"""

import argparse
import json
import pathlib
import struct
import sys
import zlib

INDEX_MAGIC = 0xBAAAD700
CHUNK_MAGIC = 0x43484E4B
TOMBSTONE_MAGIC = 0x0130BA30
TOC_SIZE = 36


class Reader:
    """Big-endian чтение с проверкой границ, как ByteReader в Kotlin."""

    def __init__(self, data):
        self.d = data

    def u32(self, o):
        return struct.unpack_from(">I", self.d, o)[0]

    def i32(self, o):
        return struct.unpack_from(">i", self.d, o)[0]

    def i64(self, o):
        return struct.unpack_from(">q", self.d, o)[0]

    def f64(self, o):
        return struct.unpack_from(">d", self.d, o)[0]

    def utf8(self, o):
        n = self.i32(o)
        return self.d[o + 4:o + 4 + n].decode("utf-8"), o + 4 + n

    def section(self, o):
        """len:int32, payload, crc32:int32. Возвращает (начало, длина, crc ок)."""
        n = self.i32(o)
        body = o + 4
        stored = self.u32(body + n)
        actual = zlib.crc32(self.d[body:body + n]) & 0xFFFFFFFF
        return body, n, stored == actual


def parse_index(path):
    r = Reader(path.read_bytes())
    size = len(r.d)
    if size < TOC_SIZE:
        sys.exit(f"{path}: короче TOC ({size} < {TOC_SIZE})")
    if r.u32(0) != INDEX_MAGIC:
        sys.exit(f"{path}: не тот magic, 0x{r.u32(0):08X}")

    toc = size - TOC_SIZE
    refs = [r.i64(toc + 8 * i) for i in range(4)]
    sym_at, ser_at, post_at, off_at = refs
    toc_ok = (zlib.crc32(r.d[toc:toc + 32]) & 0xFFFFFFFF) == r.u32(toc + 32)

    body, _, sym_ok = r.section(sym_at)
    count = r.i32(body)
    o = body + 4
    symbols = []
    for _ in range(count):
        s, o = r.utf8(o)
        symbols.append(s)

    series = {}
    o = ser_at
    while o < post_at:
        body, n, ok = r.section(o)
        p = body
        label_count = r.i32(p)
        p += 4
        labels = []
        for _ in range(label_count):
            labels.append((symbols[r.i32(p)], symbols[r.i32(p + 4)]))
            p += 8
        chunk_count = r.i32(p)
        p += 4
        chunks = []
        for _ in range(chunk_count):
            ref = r.i64(p + 16)
            chunks.append({
                "mint": r.i64(p), "maxt": r.i64(p + 8),
                "file": ref >> 32, "offset": ref & 0xFFFFFFFF,
            })
            p += 24
        series[o] = {"labels": labels, "chunks": chunks, "crc_ok": ok}
        o = body + n + 4

    postings = {}
    o = post_at
    while o < off_at:
        body, n, ok = r.section(o)
        cnt = r.i32(body)
        postings[o] = {
            "refs": [r.i64(body + 4 + 8 * i) for i in range(cnt)],
            "crc_ok": ok,
        }
        o = body + n + 4

    body, _, off_ok = r.section(off_at)
    count = r.i32(body)
    o = body + 4
    table = {}
    for _ in range(count):
        name, o = r.utf8(o)
        value, o = r.utf8(o)
        table[(name, value)] = r.i64(o)
        o += 8

    return {
        "size": size, "toc": toc, "toc_ok": toc_ok, "refs": refs,
        "symbols": symbols, "symbols_ok": sym_ok,
        "series": series, "postings": postings,
        "table": table, "table_ok": off_ok,
    }


def parse_chunks(path):
    r = Reader(path.read_bytes())
    if r.u32(0) != CHUNK_MAGIC:
        sys.exit(f"{path}: не тот magic, 0x{r.u32(0):08X}")
    out = []
    o = 8  # magic 4 + version 1 + padding 3
    while o < len(r.d):
        n = r.i32(o)
        enc = r.d[o + 4]
        data_at = o + 5
        cnt = r.i32(data_at)
        samples = [
            (r.i64(data_at + 4 + 16 * i), r.f64(data_at + 4 + 16 * i + 8))
            for i in range(cnt)
        ]
        stored = r.u32(data_at + n)
        actual = zlib.crc32(r.d[o + 4:o + 5 + n]) & 0xFFFFFFFF
        out.append({
            "offset": o, "len": n, "encoding": enc,
            "samples": samples, "crc_ok": stored == actual,
            "total": 4 + 1 + n + 4,
        })
        o += 4 + 1 + n + 4
    return out, len(r.d)


def parse_tombstones(path):
    r = Reader(path.read_bytes())
    if r.u32(0) != TOMBSTONE_MAGIC:
        sys.exit(f"{path}: не тот magic, 0x{r.u32(0):08X}")
    count = r.i32(5)
    out = []
    for i in range(count):
        o = 9 + 24 * i
        out.append({"ref": r.i64(o), "mint": r.i64(o + 8), "maxt": r.i64(o + 16)})
    stored = r.u32(9 + 24 * count)
    actual = zlib.crc32(r.d[5:9 + 24 * count]) & 0xFFFFFFFF
    return out, stored == actual, len(r.d)


def labels_str(labels):
    return "{" + ", ".join(f"{n}={v}" for n, v in labels) + "}"


def ok(flag):
    return "ок" if flag else "РАСХОЖДЕНИЕ CRC"


def dump(block, query):
    print(f"блок {block.name}\n")

    meta = json.loads((block / "meta.json").read_text())
    print("meta.json")
    print(f"  ulid        {meta['ulid']}")
    print(f"  диапазон    [{meta['minTime']}..{meta['maxTime']}]")
    st = meta["stats"]
    print(f"  статистика  серий {st['numSeries']}, чанков {st['numChunks']}, сэмплов {st['numSamples']}")

    idx = parse_index(block / "index")
    print(f"\nindex, {idx['size']} байт")
    print(f"  magic 0xBAAAD700, TOC в конце: seek({idx['size']} - {TOC_SIZE}) = 0x{idx['toc']:X}, crc {ok(idx['toc_ok'])}")
    names = ["symbols", "series", "postings", "offset table"]
    for name, ref in zip(names, idx["refs"]):
        print(f"    ref({name:12}) = 0x{ref:04X}")

    print(f"\n  SYMBOL TABLE, crc {ok(idx['symbols_ok'])}: {len(idx['symbols'])} уникальных строк")
    print("    " + ", ".join(f"[{i}]={s!r}" for i, s in enumerate(idx["symbols"])))
    print("    строка лейбла хранится один раз, в сериях вместо неё int32")

    print("\n  SERIES, в порядке Labels; оффсет записи и есть её SeriesRef")
    for off, s in idx["series"].items():
        print(f"    ref=0x{off:04X}  {labels_str(s['labels'])}  crc {ok(s['crc_ok'])}")
        for c in s["chunks"]:
            print(f"               чанк [{c['mint']}..{c['maxt']}] -> файл {c['file']}, оффсет {c['offset']}")

    print("\n  POSTINGS, обратный индекс; ref'ы отсортированы")
    inverse = {v: k for k, v in idx["table"].items()}
    for off, p in idx["postings"].items():
        key = inverse.get(off, ("?", "?"))
        label = "ВСЕ СЕРИИ (allPostings)" if key[0] == "" else f"{key[0]}={key[1]}"
        refs = ", ".join(f"0x{r:04X}" for r in p["refs"])
        print(f"    @0x{off:04X} {label:26} {len(p['refs'])} серий: {refs}  crc {ok(p['crc_ok'])}")

    chunks, chunks_size = parse_chunks(block / "chunks" / "000001")
    print(f"\nchunks/000001, {chunks_size} байт, magic 0x43484E4B")
    for c in chunks:
        shown = ", ".join(f"({t}, {v:g})" for t, v in c["samples"][:6])
        more = "" if len(c["samples"]) <= 6 else f" ... ещё {len(c['samples']) - 6}"
        print(f"  @{c['offset']:<5} len={c['len']} (только data), encoding={c['encoding']} (RAW), crc {ok(c['crc_ok'])}")
        print(f"         {len(c['samples'])} сэмплов: {shown}{more}")
        print(f"         4 len + 1 enc + {c['len']} data + 4 crc = {c['total']} байт, дальше @{c['offset'] + c['total']}")

    tombs, tomb_ok, tomb_size = parse_tombstones(block / "tombstones")
    print(f"\ntombstones, {tomb_size} байт, magic 0x0130BA30, crc {ok(tomb_ok)}")
    if not tombs:
        print("  маркеров нет")
    for t in tombs:
        series = idx["series"].get(t["ref"])
        who = labels_str(series["labels"]) if series else "неизвестная серия"
        print(f"  ref=0x{t['ref']:04X} {who}: удалён [{t['mint']}..{t['maxt']}]")
    if tombs:
        print("  ref'ы те же, что у серий в индексе: писатель переводит Labels в оффсеты")
        print("  уже после записи индекса, потому что раньше оффсетов не существует")

    if query:
        trace_query(idx, chunks, query)


def trace_query(idx, chunks, query):
    pairs = []
    for item in query:
        if "=" not in item:
            sys.exit(f"матчер должен быть вида name=value, получено {item!r}")
        name, value = item.split("=", 1)
        pairs.append((name, value))

    shown = " ".join(f"{n}={v}" for n, v in pairs)

    # Блок, в котором нет ни одной из пар, не разбираем подробно: при
    # нескольких блоках подряд это был бы один шум.
    missing = [f"{n}={v}" for n, v in pairs if (n, v) not in idx["table"]]
    if missing:
        print(f"\nзапрос {shown}: в этом блоке нет пар " + ", ".join(missing) + ", он пропускается")
        return

    print("\n" + "=" * 68)
    print("КАК РЕШАЕТСЯ ЗАПРОС  " + shown)
    print("=" * 68)

    lists = []
    for name, value in pairs:
        off = idx["table"][(name, value)]
        refs = idx["postings"][off]["refs"]
        lists.append(refs)
        print(f"  offset table: {name}={value} -> postings @0x{off:04X} -> "
              + ", ".join(f"0x{r:04X}" for r in refs))

    shortest = min(lists, key=len)
    result = [r for r in shortest if all(r in l for l in lists)]
    print(f"  пересекаем, начиная с самого короткого списка ({len(shortest)} элем.)")
    print("  остаётся: " + (", ".join(f"0x{r:04X}" for r in result) or "ничего"))

    by_offset = {c["offset"]: c for c in chunks}
    for ref in result:
        s = idx["series"][ref]
        print(f"  Series @0x{ref:04X} -> {labels_str(s['labels'])}")
        for c in s["chunks"]:
            chunk = by_offset.get(c["offset"])
            samples = ", ".join(f"({t}, {v:g})" for t, v in chunk["samples"]) if chunk else "?"
            print(f"    чанк @{c['offset']} -> {samples}")
    print("  (tombstones применяются поверх этого при чтении)")


def main():
    ap = argparse.ArgumentParser(description="Разобрать блок TSDB по байтам.")
    ap.add_argument("path", help="каталог данных или конкретная папка блока")
    ap.add_argument("--query", nargs="+", metavar="name=value",
                    help="проследить, как решается запрос по этим матчерам")
    args = ap.parse_args()

    root = pathlib.Path(args.path)
    if not root.is_dir():
        sys.exit(f"{root}: не каталог")

    blocks = [root] if (root / "meta.json").exists() else sorted(
        p for p in root.iterdir() if p.is_dir() and (p / "meta.json").exists()
    )
    if not blocks:
        sys.exit(f"{root}: блоков не найдено. Сначала ./gradlew run")

    for i, block in enumerate(blocks):
        if i:
            print("\n" + "-" * 68 + "\n")
        dump(block, args.query)


if __name__ == "__main__":
    main()
