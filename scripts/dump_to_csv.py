#!/usr/bin/env python3
"""Convert an unencrypted Pathline backup directory into the CSVs TimelineDryRunTest reads.

A backup dir holds manifest.json + gzipped JSONL partitioned by ISO week under samples/ visits/
trips/ plus snapshot/ tables. This flattens them to places/samples/visits/trips.csv (the exact
columns the test's CSV loader expects). The CSV loader splits on "," with NO quoting, so commas in
place names are stripped. Used by scripts/timeline-dryrun.sh.

Usage: dump_to_csv.py <backup-dir> <out-dir>
"""
import gzip
import json
import glob
import os
import sys


def load(globpat):
    rows = []
    for f in sorted(glob.glob(globpat)):
        with gzip.open(f, "rt") as fh:
            for line in fh:
                line = line.strip()
                if line:
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
    with open(os.path.join(dump, "manifest.json")) as f:
        manifest = json.load(f)
    mode = (manifest.get("crypto") or {}).get("mode")
    if mode not in (None, "NONE"):
        sys.exit("backup is encrypted (crypto.mode=%s); export an unencrypted backup first" % mode)

    places = load(os.path.join(dump, "snapshot", "places.*.gz"))
    with open(os.path.join(out, "places.csv"), "w") as f:
        f.write("id,name,lat,lon,radius,source,confirmed\n")
        for p in places:
            f.write("%s,%s,%s,%s,%s,%s,%s\n" % (
                p["id"], clean(p["name"]), p["latitude"], p["longitude"],
                p["radiusMeters"], p.get("source", "MAPS"), b(p.get("confirmed"))))

    sm = load(os.path.join(dump, "samples", "*.jsonl.gz"))
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

    vs = load(os.path.join(dump, "visits", "*.jsonl.gz"))
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

    tr = load(os.path.join(dump, "trips", "*.jsonl.gz"))
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
