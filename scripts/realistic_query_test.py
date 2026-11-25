#!/usr/bin/env python3
"""按真实冷热分布执行多进程 HTTP 查票压测。"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import multiprocessing as mp
import os
import random
import statistics
import threading
import time
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any


@dataclass
class WorkerStats:
    count: int
    success: int
    errors: list[str]
    latencies_ms: list[float]


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1)
    return ordered[max(index, 0)]


def login(host: str, port: int, timeout: float,
          username: str, password: str) -> str:
    body = json.dumps(
        {"username": username, "password": password}
    ).encode("utf-8")
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        connection.request(
            "POST",
            "/api/user/auth/login",
            body=body,
            headers={"Content-Type": "application/json"},
        )
        response = connection.getresponse()
        payload = json.loads(response.read())
        if response.status != 200 or payload.get("code") != 200:
            raise RuntimeError(f"登录失败: {payload}")
        return payload["data"]["token"]
    finally:
        connection.close()


def choose_query(pool: dict[str, Any], rng: random.Random) -> str:
    value = rng.random()
    accumulated = 0.0
    selected = pool["groups"][0]
    for group in pool["groups"]:
        accumulated += float(group["weight"])
        if value <= accumulated:
            selected = group
            break
    pair = rng.choice(selected["pairs"])
    query = urllib.parse.urlencode({
        "fromStationId": pair["fromStationId"],
        "toStationId": pair["toStationId"],
        "travelDate": rng.choice(pool["dates"]),
        "seatType": rng.choice(pool["seatTypes"]),
        "page": rng.choices([1, 2, 3], weights=[90, 8, 2])[0],
        "size": 10,
    })
    return "/api/user/tickets/search?" + query


def worker(host: str, port: int, timeout: float, token: str,
           pool: dict[str, Any], start_event: Any, stop_event: Any,
           barrier: Any, output_queue: Any, seed: int) -> None:
    rng = random.Random(seed)
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    latencies: list[float] = []
    errors: list[str] = []
    success = 0
    try:
        connection.connect()
        barrier.wait()
        start_event.wait()
        while not stop_event.is_set():
            path = choose_query(pool, rng)
            started = time.perf_counter()
            try:
                connection.request(
                    "GET", path,
                    headers={
                        "Accept": "application/json",
                        "Authorization": f"Bearer {token}",
                    },
                )
                response = connection.getresponse()
                body = response.read()
                elapsed_ms = (time.perf_counter() - started) * 1000
                latencies.append(elapsed_ms)
                if response.status == 200 and b'"code":200' in body:
                    success += 1
                else:
                    errors.append(f"HTTP {response.status}: {body[:160]!r}")
            except (OSError, http.client.HTTPException) as exc:
                errors.append(f"{type(exc).__name__}: {exc}")
                connection.close()
                connection = http.client.HTTPConnection(
                    host, port, timeout=timeout
                )
                connection.connect()
    finally:
        connection.close()
        output_queue.put(WorkerStats(
            count=len(latencies),
            success=success,
            errors=errors,
            latencies_ms=latencies,
        ))


def run_shard(host: str, port: int, timeout: float, token: str,
              pool: dict[str, Any], workers: int, start_event: Any,
              stop_event: Any, ready_queue: Any, result_queue: Any,
              process_index: int) -> None:
    barrier = threading.Barrier(workers + 1)
    queues = [mp.Queue() for _ in range(workers)]
    threads = [
        threading.Thread(
            target=worker,
            args=(
                host, port, timeout, token, pool,
                start_event, stop_event, barrier, queues[index],
                process_index * 100_000 + index,
            ),
            daemon=True,
        )
        for index in range(workers)
    ]
    for thread in threads:
        thread.start()
    barrier.wait(timeout=30)
    ready_queue.put(os.getpid())
    start_event.wait()
    for thread in threads:
        thread.join()

    merged = WorkerStats(0, 0, [], [])
    for queue in queues:
        stats = queue.get(timeout=10)
        merged.count += stats.count
        merged.success += stats.success
        merged.errors.extend(stats.errors)
        merged.latencies_ms.extend(stats.latencies_ms)
    result_queue.put({
        "count": merged.count,
        "success": merged.success,
        "errorCount": len(merged.errors),
        "errors": merged.errors[:20],
        "latenciesMs": merged.latencies_ms,
    })


def metric(host: str, port: int, name: str) -> float:
    url = f"http://{host}:{port}/actuator/metrics/{name}"
    with urllib.request.urlopen(url, timeout=5) as response:
        payload = json.loads(response.read())
    measurements = payload.get("measurements", [])
    return float(measurements[0]["value"]) if measurements else 0.0


def path_metrics(host: str, port: int) -> dict[str, float]:
    return {
        name: metric(host, port, f"ticket.search.path.{name}")
        for name in (
            "cache.hit",
            "cache.wait-hit",
            "index",
            "mysql-slow",
            "route-miss",
        )
    }


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--username", default="zhangsan")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--pool", default="docs/query-pool-realistic.json")
    parser.add_argument("--processes", type=int, default=4)
    parser.add_argument("--workers-per-process", type=int, default=16)
    parser.add_argument("--duration", type=float, default=60.0)
    parser.add_argument("--warmup", type=int, default=100)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--output")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    pool = json.loads(Path(args.pool).read_text(encoding="utf-8"))
    token = login(
        args.host, args.port, args.timeout,
        args.username, args.password,
    )

    warm_rng = random.Random(42)
    warm_connection = http.client.HTTPConnection(
        args.host, args.port, timeout=args.timeout
    )
    try:
        for _ in range(args.warmup):
            path = choose_query(pool, warm_rng)
            warm_connection.request(
                "GET", path,
                headers={
                    "Accept": "application/json",
                    "Authorization": f"Bearer {token}",
                },
            )
            response = warm_connection.getresponse()
            response.read()
            if response.status != 200:
                raise RuntimeError("混合流量预热失败")
    finally:
        warm_connection.close()

    before = path_metrics(args.host, args.port)
    context = mp.get_context("spawn")
    start_event = context.Event()
    stop_event = context.Event()
    ready_queue = context.Queue()
    result_queue = context.Queue()
    processes = [
        context.Process(
            target=run_shard,
            args=(
                args.host, args.port, args.timeout, token, pool,
                args.workers_per_process, start_event, stop_event,
                ready_queue, result_queue, index,
            ),
        )
        for index in range(args.processes)
    ]
    for process in processes:
        process.start()
    for _ in processes:
        ready_queue.get(timeout=60)
    started = time.perf_counter()
    start_event.set()
    time.sleep(args.duration)
    stop_event.set()

    total = success = error_count = 0
    errors: list[str] = []
    latencies: list[float] = []
    for _ in processes:
        shard = result_queue.get(timeout=30)
        total += shard["count"]
        success += shard["success"]
        error_count += shard["errorCount"]
        errors.extend(shard["errors"])
        latencies.extend(shard["latenciesMs"])
    elapsed = time.perf_counter() - started
    for process in processes:
        process.join(timeout=10)
        if process.is_alive():
            process.terminate()

    after = path_metrics(args.host, args.port)
    counters = {
        key: round(after[key] - before[key], 2)
        for key in before
    }
    total_requests = success + error_count
    cache_hits = counters["cache.hit"] + counters["cache.wait-hit"]
    miss_requests = max(0.0, total_requests - cache_hits)
    summary = {
        "target": f"http://{args.host}:{args.port}",
        "pool": args.pool,
        "concurrency": args.processes * args.workers_per_process,
        "durationSeconds": round(elapsed, 3),
        "totalRequests": total_requests,
        "successfulRequests": success,
        "failedRequests": total_requests - success,
        "qps": round(success / elapsed, 2) if elapsed > 0 else 0,
        "latencyMs": {
            "avg": round(statistics.fmean(latencies), 2)
            if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "pathCounters": counters,
        "cacheHitRate": round(cache_hits / total_requests, 4)
        if total_requests else 0,
        "missRequests": round(miss_requests, 2),
        "sampleErrors": errors[:20],
    }
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if summary["failedRequests"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
