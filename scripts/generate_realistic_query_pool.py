#!/usr/bin/env python3
"""从 train_segment 生成加权真实查询池。"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
from pathlib import Path


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=3306)
    parser.add_argument("--user", default="root")
    parser.add_argument("--password", default="")
    parser.add_argument("--database", default="train_ticket")
    parser.add_argument("--limit", type=int, default=538)
    parser.add_argument(
        "--output",
        default="docs/query-pool-realistic.json",
    )
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    sql = f"""
        SELECT from_station_id, to_station_id,
               COUNT(DISTINCT train_id) AS train_count
        FROM `{args.database}`.train_segment
        WHERE from_station_id <> to_station_id
        GROUP BY from_station_id, to_station_id
        ORDER BY train_count DESC, from_station_id, to_station_id
        LIMIT {args.limit}
    """
    env = os.environ.copy()
    if args.password:
        env["MYSQL_PWD"] = args.password
    completed = subprocess.run(
        [
            args.mysql,
            "--batch",
            "--skip-column-names",
            "-h",
            args.host,
            "-P",
            str(args.port),
            "-u",
            args.user,
            "-e",
            sql,
        ],
        check=True,
        capture_output=True,
        text=True,
        encoding="utf-8",
        env=env,
    )
    pairs = []
    for line in completed.stdout.splitlines():
        if not line.strip():
            continue
        from_id, to_id, train_count = line.split("\t")
        pairs.append({
            "fromStationId": int(from_id),
            "toStationId": int(to_id),
            "trainCount": int(train_count),
        })

    if len(pairs) < 120:
        raise RuntimeError(f"有效站对不足 120，实际只有 {len(pairs)}")

    invalid = [
        {"fromStationId": 999901, "toStationId": 999902},
        {"fromStationId": 999903, "toStationId": 999904},
        {"fromStationId": 999905, "toStationId": 999906},
        {"fromStationId": 999907, "toStationId": 999908},
    ]
    payload = {
        "description": "70% hot / 20% medium / 8% cold / 2% invalid",
        "dates": ["2026-11-26", "2026-11-27", "2026-11-28"],
        "seatTypes": [3, 3, 3, 3, 3, 3, 2, 2, 2, 1],
        "groups": [
            {"name": "hot", "weight": 0.70, "pairs": pairs[:20]},
            {
                "name": "medium",
                "weight": 0.20,
                "pairs": pairs[20:120],
            },
            {
                "name": "cold",
                "weight": 0.08,
                "pairs": pairs[120:],
            },
            {
                "name": "invalid",
                "weight": 0.02,
                "pairs": invalid,
            },
        ],
    }
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    print(
        f"query pool written: {output}, pairs={len(pairs)}, "
        f"hot=20, medium=100, cold={max(0, len(pairs) - 120)}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
