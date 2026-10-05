#!/usr/bin/env python3
"""Convert an unencrypted Pathline backup directory into the CSVs TimelineDryRunTest reads.

A backup dir holds generation manifests (or legacy manifest.json) + gzipped JSONL under samples/ visits/
trips/ plus snapshot/ tables. This flattens them to places/samples/visits/trips.csv (the exact
columns the test's CSV loader expects). The CSV loader splits on "," with NO quoting, so commas in
place names are stripped. Used by scripts/timeline-dryrun.sh.

Usage: dump_to_csv.py <backup-dir> <out-dir>
"""
import gzip
import base64
import hashlib
import json
import glob
import os
import re
import sys


def digest(data):
    return base64.b64encode(hashlib.sha256(data).digest()).decode("ascii")


def read_verified(directory, name, expected):
    if not name or name in (".", "..") or "/" in name or "\\" in name:
        raise ValueError("invalid backup filename")
    with open(os.path.join(directory, name), "rb") as source:
        data = source.read()
    if digest(data) != expected:
        raise ValueError("backup file failed integrity verification")
    return data


def open_backup(directory):
    candidates = []
    for path in glob.glob(os.path.join(directory, "manifest*.json")):
        name = os.path.basename(path)
        generated = re.fullmatch(r"manifest\.([1-9]\d*)\.json", name)
        if name != "manifest.json" and generated is None:
            continue
        try:
            with open(path) as source:
                manifest = json.load(source)
            checksum = manifest["checksum"]
            manifest["checksum"] = ""
            canonical = json.dumps(manifest, separators=(",", ":"), ensure_ascii=False).encode()
            if digest(canonical) != checksum:
                continue
            manifest["checksum"] = checksum
            version = manifest["formatVersion"]
            if version not in (1, 2):
                raise SystemExit("backup format is newer than this converter")
            generation = manifest.get("generation") or {}
            sequence = generation.get("sequence", 0)
            if generated and (version != 2 or sequence != int(generated[1])):
                continue
            if version == 1 and (name != "manifest.json" or generation):
                continue
            if version == 2 and (generated is None or sequence <= 0):
                continue
            candidates.append((sequence, manifest["createdAtMs"], manifest))
        except (ValueError, KeyError):
            continue  # Interrupted/unpublished manifest.
    for _, _, manifest in sorted(candidates, key=lambda item: item[:2], reverse=True):
        mode = (manifest.get("crypto") or {}).get("mode")
        if mode not in (None, "NONE"):
            raise SystemExit("backup is encrypted (crypto.mode=%s); export an unencrypted backup first" % mode)
        try:
            ref = manifest["inventory"]
            inventory = json.loads(gzip.decompress(read_verified(directory, ref["fileName"], ref["sha256"])))
            content = {}
            entries = [("snapshot", entry) for entry in inventory.get("snapshots", [])]
            entries += [(entry["stream"], entry) for entry in inventory.get("partitions", [])]
            for folder, entry in entries:
                if folder not in ("snapshot", "samples", "visits", "trips"):
                    raise ValueError("unknown backup stream")
                raw = read_verified(os.path.join(directory, folder), entry["fileName"], entry["encSha256"])
                plain = gzip.decompress(raw)
                if digest(plain) != entry["sha256"]:
                    raise ValueError("backup contents failed integrity verification")
                content[(folder, entry["fileName"])] = plain
            return inventory, content
        except (OSError, ValueError, KeyError, EOFError) as error:
            print("Skipping incomplete backup: %s" % error, file=sys.stderr)
    raise SystemExit("no complete unencrypted backup found")


def load(entries, folder, content):
    rows = []
    for entry in entries:
        for line in content[(folder, entry["fileName"])].decode().splitlines():
            if line.strip():
                rows.append(json.loads(line))
    return rows


def b(x):
    return "" if x is None else ("1" if x else "0")


def s(x):
    return "" if x is None else str(x)


def clean(name):  # CSV is split on "," with no quoting -> strip commas/newlines
    return (name or "").replace(",", " ").replace("\n", " ").strip()


def main(dump, out):
    os.makedirs(out, exist_ok=True)
    inventory, content = open_backup(dump)

    def partitions(stream):
        return load([entry for entry in inventory.get("partitions", []) if entry["stream"] == stream], stream, content)

    places = load([entry for entry in inventory.get("snapshots", []) if entry["name"] == "places"], "snapshot", content)
    with open(os.path.join(out, "places.csv"), "w") as f:
        f.write("id,name,lat,lon,radius,source,confirmed\n")
        for p in places:
            f.write("%s,%s,%s,%s,%s,%s,%s\n" % (
                p["id"], clean(p["name"]), p["latitude"], p["longitude"],
                p["radiusMeters"], p.get("source", "MAPS"), b(p.get("confirmed"))))

    sm = partitions("samples")
    sm.sort(key=lambda r: r["timestampMs"])
    with open(os.path.join(out, "samples.csv"), "w") as f:
        f.write("ts,lat,lon,acc,speed,state,ar,hasCell,incl\n")
        for r in sm:
            f.write(",".join([
                s(r["timestampMs"]), s(r["latitude"]), s(r["longitude"]),
                s(r.get("accuracy")), s(r.get("speed")),
                r.get("devicePhysicalState", "UNKNOWN"), (r.get("arActivity") or ""),
                b(r.get("hasCellService")), "1" if r.get("includedInComputation") else "0",
            ]) + "\n")

    vs = partitions("visits")
    vs.sort(key=lambda r: r["startMs"])
    with open(os.path.join(out, "visits.csv"), "w") as f:
        f.write("id,placeId,start,end,centLat,centLon,radius,sampleCount,reliability,confirmed,confidence,ongoing\n")
        for v in vs:
            f.write(",".join([
                s(v["id"]), s(v.get("placeId")), s(v["startMs"]), s(v["endMs"]),
                s(v["centroidLatitude"]), s(v["centroidLongitude"]), s(v["radiusMeters"]),
                s(v.get("sampleCount", 0)), s(v.get("reliability", 0)),
                b(v.get("confirmed")), s(v.get("confidence", 0.5)), b(v.get("isOngoing")),
            ]) + "\n")

    tr = partitions("trips")
    tr.sort(key=lambda r: r["startMs"])
    with open(os.path.join(out, "trips.csv"), "w") as f:
        f.write("id,fromVisit,toVisit,start,end,mode,dist,confirmed\n")
        for t in tr:
            f.write(",".join([
                s(t["id"]), s(t.get("fromVisitId")), s(t.get("toVisitId")),
                s(t["startMs"]), s(t["endMs"]), t.get("mode", "UNKNOWN"),
                s(t.get("distanceMeters", 0)), b(t.get("confirmed")),
            ]) + "\n")

    print("wrote CSVs to %s: places=%d samples=%d visits=%d trips=%d"
          % (out, len(places), len(sm), len(vs), len(tr)))


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit(__doc__)
    main(sys.argv[1], sys.argv[2])
