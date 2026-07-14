#!/usr/bin/env python3
"""Generate Pathline's compact 31-unit mainland compatibility mask from pinned geoBoundaries."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

SOURCE_SHA256 = "3a00467a0db9b4136facb5f2f3d0edbfd96adb15651cfdf63991da9281030e85"
EXCLUDED = {
    "Hong Kong Special Administrative Region",
    "Macau Special Administrative Region",
    "Taiwan Province",
}
EXPECTED_ALL = {
    "Hainan Province",
    "Taiwan Province",
    "Guangxi Zhuang Autonomous Region",
    "Fujian Province",
    "Yunnan Province",
    "Guizhou Province",
    "Jiangxi Province",
    "Hunan Province",
    "Zhejiang Province",
    "Shanghai Municipality",
    "Chongqing Municipality",
    "Hubei Province",
    "Sichuan Province",
    "Anhui Province",
    "Jiangsu Province",
    "Henan Province",
    "Tibet Autonomous Region",
    "Shandong Province",
    "Qinghai Province",
    "Ningxia Ningxia Hui Autonomous Region",
    "Shaanxi Province",
    "Tianjin Municipality",
    "Shanxi Province",
    "Beijing Municipality",
    "Gansu Province",
    "Hebei Province",
    "Liaoning Province",
    "Jilin Province",
    "Xinjiang Uyghur Autonomous Region",
    "Inner Mongolia Autonomous Region",
    "Heilongjiang Province",
    "Macau Special Administrative Region",
    "Hong Kong Special Administrative Region",
    "Guangzhou Province",
}


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument(
        "output",
        type=Path,
        nargs="?",
        default=Path("app/src/main/res/raw/mainland_china_adm1.json"),
    )
    args = parser.parse_args()

    raw = args.source.read_bytes()
    digest = hashlib.sha256(raw).hexdigest()
    if digest != SOURCE_SHA256:
        raise SystemExit(f"unexpected source SHA-256: {digest}")
    source = json.loads(raw)
    names = {feature["properties"]["shapeName"] for feature in source["features"]}
    if names != EXPECTED_ALL:
        raise SystemExit(f"unexpected source feature names: {sorted(names)}")

    features = []
    for feature in source["features"]:
        name = feature["properties"]["shapeName"]
        if name in EXCLUDED:
            continue
        features.append(
            {
                "type": feature["type"],
                "properties": {"shapeName": name},
                "geometry": feature["geometry"],
            }
        )
    if len(features) != 31 or not any(
        feature["properties"]["shapeName"] == "Hainan Province" for feature in features
    ):
        raise SystemExit("expected exactly 31 mainland units including Hainan")

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps({"type": "FeatureCollection", "features": features}, separators=(",", ":")),
        encoding="utf-8",
    )


if __name__ == "__main__":
    main()
