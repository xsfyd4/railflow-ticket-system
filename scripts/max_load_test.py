#!/usr/bin/env python3
"""同步启动的多进程 HTTP 最大吞吐压测器。

用途：
- 多个 Python 进程绕开单进程 GIL；
- 每个进程内的线程复用 HTTP/1.1 长连接；
- 所有连接建立后统一发令，避免分片启动时间不同；
- 只检查 HTTP 状态和响应中的业务 code，不逐请求解析完整 JSON。

示例：
    python scripts/max_load_test.py --processes 4 --workers-per-process 16
"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import multiprocessing as mp
import os
import statistics
import threading
import time
import urllib.parse
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from typing import Any


@dataclass
class WorkerStats:
    count: int
    success: int
    errors: list[str]
    latencies_ms: list[float]


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
            raise RuntimeError(
                f"登录失败：HTTP {response.status}, body={payload}"
            )
        return payload["data"]["token"]
    finally:
        connection.close()


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1)
    return ordered[max(index, 0)]


def worker(
    host: str,
    port: int,
    timeout: float,
    path: str,
    token: str | None,
    start_event: Any,
    stop_event: Any,
    barrier: Any,
    output_queue: Any,
) -> None:
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    latencies: list[float] = []
    errors: list[str] = []
    success = 0
    try:
        # 先把 TCP 连接建好，再与其他线程和进程一起等待发令。
        connection.connect()
        barrier.wait()
        start_event.wait()

        while not stop_event.is_set():
            started = time.perf_counter()
            try:
                headers = {"Accept": "application/json"}
                if token:
                    headers["Authorization"] = f"Bearer {token}"
                connection.request("GET", path, headers=headers)
                response = connection.getresponse()
                body = response.read()
                elapsed_ms = (time.perf_counter() - started) * 1000
                latencies.append(elapsed_ms)
                if response.status == 200 and b'"code":200' in body:
                    success += 1
                else:
                    errors.append(
                        f"HTTP {response.status}: {body[:160]!r}"
                    )
            except (OSError, http.client.HTTPException) as exc:
                errors.append(f"{type(exc).__name__}: {exc}")
                connection.close()
                connection = http.client.HTTPConnection(
                    host, port, timeout=timeout
                )
                try:
                    connection.connect()
                except OSError as reconnect_error:
                    errors.append(
                        f"reconnect {type(reconnect_error).__name__}: "
                        f"{reconnect_error}"
                    )
    finally:
        connection.close()
        output_queue.put(WorkerStats(
            count=len(latencies),
            success=success,
            errors=errors,
            latencies_ms=latencies,
        ))


def run_shard(
    host: str,
    port: int,
    timeout: float,
    path: str,
    token: str | None,
    workers: int,
    start_event: Any,
    stop_event: Any,
    ready_queue: Any,
    result_queue: Any,
) -> None:
    barrier = threading.Barrier(workers + 1)
    worker_queues = [mp.Queue() for _ in range(workers)]
    threads = [
        threading.Thread(
            target=worker,
            args=(
                host,
                port,
                timeout,
                path,
                token,
                start_event,
                stop_event,
                barrier,
                worker_queues[index],
            ),
            daemon=True,
        )
        for index in range(workers)
    ]
    for thread in threads:
        thread.start()

    try:
        barrier.wait(timeout=30)
    except threading.BrokenBarrierError as exc:
        result_queue.put({
            "count": 0,
            "success": 0,
            "errors": [f"worker barrier failed: {exc}"],
            "latenciesMs": [],
        })
        return
    ready_queue.put(os.getpid())
    start_event.wait()
    for thread in threads:
        thread.join()

    merged = WorkerStats(0, 0, [], [])
    for queue in worker_queues:
        stats = queue.get(timeout=10)
        merged.count += stats.count
        merged.success += stats.success
        merged.errors.extend(stats.errors)
        merged.latencies_ms.extend(stats.latencies_ms)
    result_queue.put({
        "count": merged.count,
        "success": merged.success,
        "errors": merged.errors[:20],
        "errorCount": len(merged.errors),
        "latenciesMs": merged.latencies_ms,
    })


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="多进程最大吞吐压测")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--username", default="zhangsan")
    parser.add_argument("--password", default="123456")
    parser.add_argument(
        "--scenario",
        choices=("search", "ping"),
        default="search",
    )
    parser.add_argument("--from-station", type=int, default=1)
    parser.add_argument("--to-station", type=int, default=27)
    parser.add_argument("--travel-date", default=date.today().isoformat())
    parser.add_argument("--seat-type", type=int, default=3)
    parser.add_argument("--page-size", type=int, default=10)
    parser.add_argument("--processes", type=int, default=4)
    parser.add_argument("--workers-per-process", type=int, default=16)
    parser.add_argument("--duration", type=float, default=10.0)
    parser.add_argument("--warmup", type=int, default=50)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--output")
    args = parser.parse_args()
    if not 1 <= args.processes <= 32:
        parser.error("--processes 必须在 1 到 32 之间")
    if not 1 <= args.workers_per_process <= 128:
        parser.error("--workers-per-process 必须在 1 到 128 之间")
    if args.duration <= 0:
        parser.error("--duration 必须大于 0")
    return args


def main() -> int:
    args = parse_args()
    token: str | None = None
    if args.scenario == "search":
        token = login(
            args.host,
            args.port,
            args.timeout,
            args.username,
            args.password,
        )
        query = urllib.parse.urlencode({
            "fromStationId": args.from_station,
            "toStationId": args.to_station,
            "travelDate": args.travel_date,
            "page": 1,
            "size": args.page_size,
            "seatType": args.seat_type,
        })
        path = "/api/user/tickets/search?" + query
    else:
        path = "/api/public/ping"

    warmup = http.client.HTTPConnection(
        args.host, args.port, timeout=args.timeout
    )
    try:
        for _ in range(args.warmup):
            headers = {"Accept": "application/json"}
            if token:
                headers["Authorization"] = f"Bearer {token}"
            warmup.request("GET", path, headers=headers)
            response = warmup.getresponse()
            body = response.read()
            if response.status != 200 or b'"code":200' not in body:
                raise RuntimeError(
                    f"预热失败：HTTP {response.status}, body={body[:300]!r}"
                )
    finally:
        warmup.close()

    context = mp.get_context("spawn")
    start_event = context.Event()
    stop_event = context.Event()
    ready_queue = context.Queue()
    result_queue = context.Queue()
    processes = [
        context.Process(
            target=run_shard,
            args=(
                args.host,
                args.port,
                args.timeout,
                path,
                token,
                args.workers_per_process,
                start_event,
                stop_event,
                ready_queue,
                result_queue,
            ),
        )
        for _ in range(args.processes)
    ]
    for process in processes:
        process.start()

    for _ in processes:
        ready_queue.get(timeout=60)

    started = time.perf_counter()
    start_event.set()
    time.sleep(args.duration)
    stop_event.set()

    total = 0
    success = 0
    error_count = 0
    all_errors: list[str] = []
    latencies: list[float] = []
    for _ in processes:
        shard = result_queue.get(timeout=30)
        total += shard["count"]
        success += shard["success"]
        error_count += shard["errorCount"]
        all_errors.extend(shard["errors"])
        latencies.extend(shard["latenciesMs"])
    elapsed = time.perf_counter() - started
    for process in processes:
        process.join(timeout=10)
        if process.is_alive():
            process.terminate()
            process.join(timeout=2)

    if error_count == 0:
        error_count = sum(
            1 for process in processes if process.exitcode != 0
        )
    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "target": f"http://{args.host}:{args.port}{path}",
        "scenario": args.scenario,
        "processes": args.processes,
        "workersPerProcess": args.workers_per_process,
        "totalConcurrency": args.processes * args.workers_per_process,
        "configuredDurationSeconds": args.duration,
        "actualDurationSeconds": round(elapsed, 3),
        "totalRequests": success + error_count,
        "successfulRequests": success,
        "failedRequests": error_count,
        "errorCount": error_count,
        "qps": round(success / elapsed, 2) if elapsed > 0 else 0,
        "latencyMs": {
            "avg": round(statistics.fmean(latencies), 2)
            if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "sampleErrors": all_errors[:20],
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
