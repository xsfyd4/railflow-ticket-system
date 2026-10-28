#!/usr/bin/env python3
"""综合业务 HTTP 压测器。

默认流量比例：
- 90% 查票；
- 5% 创建订单后立即取消；
- 3% 查询订单列表；
- 2% 对已支付订单重复执行幂等支付。

创建订单使用唯一幂等键，取消成功后库存会恢复。脚本只压真实业务接口，
不直接修改数据库或 Redis。
"""

from __future__ import annotations

import argparse
import http.client
import json
import multiprocessing as mp
import os
import statistics
import threading
import time
import urllib.parse
import uuid
from dataclasses import dataclass, field
from datetime import date
from pathlib import Path
from typing import Any

from max_load_test import login, percentile


@dataclass
class WorkerStats:
    http_requests: int = 0
    operations: int = 0
    success: int = 0
    latencies_ms: list[float] = field(default_factory=list)
    operation_counts: dict[str, int] = field(default_factory=dict)
    success_counts: dict[str, int] = field(default_factory=dict)
    errors: list[str] = field(default_factory=list)
    orphan_order_nos: list[str] = field(default_factory=list)


def request_json(
    connection: http.client.HTTPConnection,
    method: str,
    path: str,
    token: str,
    body: dict[str, Any] | None = None,
    idempotency_key: str | None = None,
) -> dict[str, Any]:
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
    }
    payload = None
    if body is not None:
        payload = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key

    connection.request(method, path, body=payload, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    try:
        result = json.loads(raw)
    except json.JSONDecodeError:
        result = {}
    if response.status != 200 or result.get("code") != 200:
        raise RuntimeError(
            f"HTTP {response.status}, body={raw[:240]!r}"
        )
    return result


def choose_operation(
    sequence: int,
    worker_id: int,
    search_percent: int,
    order_roundtrip_percent: int,
    order_list_percent: int,
) -> str:
    slot = (sequence * 37 + worker_id * 13) % 100
    if slot < search_percent:
        return "search"
    slot -= search_percent
    if slot < order_roundtrip_percent:
        return "order_roundtrip"
    slot -= order_roundtrip_percent
    if slot < order_list_percent:
        return "order_list"
    return "pay_idempotent"


def worker(
    host: str,
    port: int,
    timeout: float,
    token: str,
    worker_id: int,
    run_id: str,
    search_path: str,
    order_list_path: str,
    paid_order_no: str,
    train_min: int,
    train_max: int,
    passenger_id: int,
    travel_date: str,
    from_station: int,
    to_station: int,
    seat_type: int,
    search_percent: int,
    order_roundtrip_percent: int,
    order_list_percent: int,
    start_event: Any,
    stop_event: Any,
    barrier: Any,
    output_queue: Any,
) -> None:
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    stats = WorkerStats()
    connection.connect()

    def call(
        method: str,
        path: str,
        body: dict[str, Any] | None = None,
        idempotency_key: str | None = None,
    ) -> dict[str, Any]:
        stats.http_requests += 1
        return request_json(
            connection, method, path, token, body, idempotency_key
        )

    def connect() -> http.client.HTTPConnection:
        connection.close()
        replacement = http.client.HTTPConnection(
            host, port, timeout=timeout
        )
        replacement.connect()
        return replacement

    try:
        barrier.wait()
        start_event.wait()
        sequence = 0
        while not stop_event.is_set():
            sequence += 1
            operation = choose_operation(
                sequence,
                worker_id,
                search_percent,
                order_roundtrip_percent,
                order_list_percent,
            )
            stats.operations += 1
            stats.operation_counts[operation] = (
                stats.operation_counts.get(operation, 0) + 1
            )
            started = time.perf_counter()
            try:
                if operation == "search":
                    call("GET", search_path)
                elif operation == "order_list":
                    call("GET", order_list_path)
                elif operation == "pay_idempotent":
                    call(
                        "POST",
                        f"/api/user/orders/{paid_order_no}/pay",
                        {},
                        f"mixed-pay-{paid_order_no}",
                    )
                else:
                    key = f"mixed-{run_id}-{worker_id}-{sequence}"
                    train_id = train_min + (
                        (sequence + worker_id) % (train_max - train_min + 1)
                    )
                    created = call(
                        "POST",
                        "/api/user/orders",
                        {
                            "trainId": train_id,
                            "travelDate": travel_date,
                            "fromStationId": from_station,
                            "toStationId": to_station,
                            "seatType": seat_type,
                            "passengerIds": [passenger_id],
                        },
                        key,
                    )
                    order_no = created["data"]["orderNo"]
                    try:
                        call(
                            "POST",
                            f"/api/user/orders/{order_no}/cancel",
                            {},
                        )
                    except Exception:
                        stats.orphan_order_nos.append(order_no)
                        raise

                stats.success += 1
                stats.success_counts[operation] = (
                    stats.success_counts.get(operation, 0) + 1
                )
            except (OSError, http.client.HTTPException) as exc:
                stats.errors.append(f"{operation}: {type(exc).__name__}: {exc}")
                try:
                    connection = connect()
                except OSError as reconnect_error:
                    stats.errors.append(
                        "reconnect: "
                        f"{type(reconnect_error).__name__}: {reconnect_error}"
                    )
            except Exception as exc:
                stats.errors.append(f"{operation}: {type(exc).__name__}: {exc}")
            finally:
                stats.latencies_ms.append(
                    (time.perf_counter() - started) * 1000
                )
    finally:
        connection.close()
        output_queue.put(stats)


def run_shard(
    workers: int,
    start_event: Any,
    stop_event: Any,
    ready_queue: Any,
    result_queue: Any,
    worker_args: tuple[Any, ...],
) -> None:
    barrier = threading.Barrier(workers + 1)
    worker_queues = [mp.Queue() for _ in range(workers)]
    threads = [
        threading.Thread(
            target=worker,
            args=(
                *worker_args[:4],
                worker_args[4] + index,
                *worker_args[5:],
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
        barrier.wait(timeout=60)
    except threading.BrokenBarrierError as exc:
        result_queue.put({
            "error": f"worker barrier failed: {exc}",
        })
        return

    ready_queue.put(os.getpid())
    start_event.wait()
    for thread in threads:
        thread.join()
    result_queue.put({
        "workers": [queue.get(timeout=20) for queue in worker_queues],
    })


def find_paid_order(
    host: str,
    port: int,
    timeout: float,
    token: str,
) -> str:
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    try:
        result = request_json(
            connection,
            "GET",
            "/api/user/orders?status=1&page=1&size=1",
            token,
        )
        records = result["data"]["records"]
        if not records:
            raise RuntimeError(
                "没有可复用的已支付订单，请先手工创建并支付一笔订单"
            )
        return records[0]["orderNo"]
    finally:
        connection.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="综合读写业务压测")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--username", default="zhangsan")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--from-station", type=int, default=1)
    parser.add_argument("--to-station", type=int, default=27)
    parser.add_argument("--travel-date", default=date.today().isoformat())
    parser.add_argument("--seat-type", type=int, default=3)
    parser.add_argument("--page-size", type=int, default=10)
    parser.add_argument("--passenger-id", type=int, default=1)
    parser.add_argument("--train-min", type=int, default=1)
    parser.add_argument("--train-max", type=int, default=10)
    parser.add_argument("--search-percent", type=int, default=90)
    parser.add_argument("--order-roundtrip-percent", type=int, default=5)
    parser.add_argument("--order-list-percent", type=int, default=3)
    parser.add_argument("--processes", type=int, default=4)
    parser.add_argument("--workers-per-process", type=int, default=16)
    parser.add_argument("--duration", type=float, default=30.0)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--timeout", type=float, default=15.0)
    parser.add_argument("--output")
    args = parser.parse_args()
    if not 1 <= args.processes <= 32:
        parser.error("--processes 必须在 1 到 32 之间")
    if not 1 <= args.workers_per_process <= 128:
        parser.error("--workers-per-process 必须在 1 到 128 之间")
    if args.duration <= 0:
        parser.error("--duration 必须大于 0")
    if not 0 <= args.search_percent <= 100:
        parser.error("--search-percent 必须在 0 到 100 之间")
    if not 0 <= args.order_roundtrip_percent <= 100:
        parser.error("--order-roundtrip-percent 必须在 0 到 100 之间")
    if not 0 <= args.order_list_percent <= 100:
        parser.error("--order-list-percent 必须在 0 到 100 之间")
    if (
        args.search_percent
        + args.order_roundtrip_percent
        + args.order_list_percent
        > 100
    ):
        parser.error("前三种操作比例之和不能超过 100")
    if args.train_min > args.train_max:
        parser.error("--train-min 不能大于 --train-max")
    return args


def main() -> int:
    args = parse_args()
    token = login(
        args.host, args.port, args.timeout, args.username, args.password
    )
    paid_order_no = find_paid_order(
        args.host, args.port, args.timeout, token
    )
    query = urllib.parse.urlencode({
        "fromStationId": args.from_station,
        "toStationId": args.to_station,
        "travelDate": args.travel_date,
        "page": 1,
        "size": args.page_size,
        "seatType": args.seat_type,
    })
    search_path = "/api/user/tickets/search?" + query
    order_list_path = "/api/user/orders?page=1&size=10"

    warmup = http.client.HTTPConnection(
        args.host, args.port, timeout=args.timeout
    )
    try:
        for _ in range(args.warmup):
            request_json(warmup, "GET", search_path, token)
        request_json(warmup, "GET", order_list_path, token)
    finally:
        warmup.close()

    total_workers = args.processes * args.workers_per_process
    context = mp.get_context("spawn")
    start_event = context.Event()
    stop_event = context.Event()
    ready_queue = context.Queue()
    result_queue = context.Queue()
    run_id = uuid.uuid4().hex[:12]
    base_worker_args = (
        args.host,
        args.port,
        args.timeout,
        token,
        0,
        run_id,
        search_path,
        order_list_path,
        paid_order_no,
        args.train_min,
        args.train_max,
        args.passenger_id,
        args.travel_date,
        args.from_station,
        args.to_station,
        args.seat_type,
        args.search_percent,
        args.order_roundtrip_percent,
        args.order_list_percent,
    )
    processes = []
    for shard_index in range(args.processes):
        shard_args = list(base_worker_args)
        shard_args[4] = shard_index * args.workers_per_process
        process = context.Process(
            target=run_shard,
            args=(
                args.workers_per_process,
                start_event,
                stop_event,
                ready_queue,
                result_queue,
                tuple(shard_args),
            ),
        )
        process.start()
        processes.append(process)

    for _ in processes:
        ready_queue.get(timeout=90)

    started = time.perf_counter()
    start_event.set()
    time.sleep(args.duration)
    stop_event.set()

    merged = WorkerStats()
    all_errors: list[str] = []
    orphan_order_nos: list[str] = []
    shard_errors = 0
    for _ in processes:
        shard = result_queue.get(timeout=60)
        if "error" in shard:
            shard_errors += 1
            all_errors.append(shard["error"])
            continue
        for worker_stats in shard["workers"]:
            merged.http_requests += worker_stats.http_requests
            merged.operations += worker_stats.operations
            merged.success += worker_stats.success
            merged.latencies_ms.extend(worker_stats.latencies_ms)
            for key, value in worker_stats.operation_counts.items():
                merged.operation_counts[key] = (
                    merged.operation_counts.get(key, 0) + value
                )
            for key, value in worker_stats.success_counts.items():
                merged.success_counts[key] = (
                    merged.success_counts.get(key, 0) + value
                )
            all_errors.extend(worker_stats.errors)
            orphan_order_nos.extend(worker_stats.orphan_order_nos)

    elapsed = time.perf_counter() - started
    for process in processes:
        process.join(timeout=15)
        if process.is_alive():
            process.terminate()
            process.join(timeout=3)
            shard_errors += 1

    failed = merged.operations - merged.success
    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "target": f"http://{args.host}:{args.port}",
        "scenario": "mixed",
        "configuredMixPercent": {
            "search": args.search_percent,
            "orderRoundtrip": args.order_roundtrip_percent,
            "orderList": args.order_list_percent,
            "payIdempotent": (
                100
                - args.search_percent
                - args.order_roundtrip_percent
                - args.order_list_percent
            ),
        },
        "processes": args.processes,
        "workersPerProcess": args.workers_per_process,
        "totalConcurrency": total_workers,
        "configuredDurationSeconds": args.duration,
        "actualDurationSeconds": round(elapsed, 3),
        "httpRequests": merged.http_requests,
        "httpRequestsPerSecond": (
            round(merged.http_requests / elapsed, 2) if elapsed > 0 else 0
        ),
        "logicalOperations": merged.operations,
        "successfulOperations": merged.success,
        "failedOperations": failed,
        "operationsPerSecond": (
            round(merged.success / elapsed, 2) if elapsed > 0 else 0
        ),
        "operationsByType": merged.operation_counts,
        "successByType": merged.success_counts,
        "latencyMs": {
            "avg": round(statistics.fmean(merged.latencies_ms), 2)
            if merged.latencies_ms else 0,
            "p50": round(percentile(merged.latencies_ms, 0.50), 2),
            "p95": round(percentile(merged.latencies_ms, 0.95), 2),
            "p99": round(percentile(merged.latencies_ms, 0.99), 2),
            "max": round(max(merged.latencies_ms), 2)
            if merged.latencies_ms else 0,
        },
        "paidOrderNo": paid_order_no,
        "orphanOrderNos": orphan_order_nos[:20],
        "errorCount": failed + shard_errors,
        "sampleErrors": all_errors[:20],
    }
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if summary["errorCount"] == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
