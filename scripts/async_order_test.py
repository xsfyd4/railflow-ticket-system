#!/usr/bin/env python3
"""RabbitMQ 异步下单链路测试。

每个任务执行：
POST 异步下单 -> 轮询请求状态 -> 成功后取消订单恢复库存。
用于验证 Redis 预扣、RabbitMQ 投递、消费者落库和状态查询并没有被
同步下单压力测试覆盖的完整链路。
"""

from __future__ import annotations

import argparse
import http.client
import json
import statistics
import time
import uuid
from concurrent.futures import ThreadPoolExecutor, as_completed
from datetime import date
from pathlib import Path
from typing import Any

from max_load_test import login, percentile


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
    encoded = None
    if body is not None:
        encoded = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    if idempotency_key:
        headers["Idempotency-Key"] = idempotency_key

    connection.request(method, path, body=encoded, headers=headers)
    response = connection.getresponse()
    raw = response.read()
    result = json.loads(raw)
    if response.status != 200 or result.get("code") != 200:
        raise RuntimeError(
            f"HTTP {response.status}, body={raw[:240]!r}"
        )
    return result


def run_task(
    index: int,
    host: str,
    port: int,
    timeout: float,
    token: str,
    run_id: str,
    travel_date: str,
    from_station: int,
    to_station: int,
    seat_type: int,
    passenger_id: int,
    train_min: int,
    train_max: int,
) -> dict[str, Any]:
    connection = http.client.HTTPConnection(host, port, timeout=timeout)
    request_id = f"async-{run_id}-{index}"
    train_id = train_min + index % (train_max - train_min + 1)
    result: dict[str, Any] = {
        "index": index,
        "requestId": request_id,
        "success": False,
    }
    try:
        started = time.perf_counter()
        submitted = request_json(
            connection,
            "POST",
            "/api/user/orders/async",
            token,
            {
                "trainId": train_id,
                "travelDate": travel_date,
                "fromStationId": from_station,
                "toStationId": to_station,
                "seatType": seat_type,
                "passengerIds": [passenger_id],
            },
            request_id,
        )
        result["submitMs"] = (time.perf_counter() - started) * 1000

        status = None
        order_no = None
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            current = request_json(
                connection,
                "GET",
                f"/api/user/orders/async/{request_id}",
                token,
            )
            status = current["data"]["status"]
            order_no = current["data"].get("orderNo")
            if status in (3, 4):
                break
            time.sleep(0.05)

        result["finalStatus"] = status
        result["orderNo"] = order_no
        if status != 3 or not order_no:
            raise RuntimeError(
                f"异步下单未成功，status={status}, "
                f"reason={submitted.get('data', {}).get('failReason')}"
            )

        request_json(
            connection,
            "POST",
            f"/api/user/orders/{order_no}/cancel",
            token,
            {},
        )
        result["endToEndMs"] = (time.perf_counter() - started) * 1000
        result["success"] = True
        return result
    except Exception as exc:
        result["error"] = f"{type(exc).__name__}: {exc}"
        return result
    finally:
        connection.close()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="RabbitMQ 异步下单链路测试")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--username", default="zhangsan")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--travel-date", default=date.today().isoformat())
    parser.add_argument("--from-station", type=int, default=1)
    parser.add_argument("--to-station", type=int, default=27)
    parser.add_argument("--seat-type", type=int, default=3)
    parser.add_argument("--passenger-id", type=int, default=1)
    parser.add_argument("--train-min", type=int, default=1)
    parser.add_argument("--train-max", type=int, default=10)
    parser.add_argument("--count", type=int, default=100)
    parser.add_argument("--concurrency", type=int, default=16)
    parser.add_argument("--timeout", type=float, default=30.0)
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.count <= 0:
        parser.error("--count 必须大于 0")
    if args.concurrency <= 0:
        parser.error("--concurrency 必须大于 0")
    if args.timeout <= 0:
        parser.error("--timeout 必须大于 0")
    if args.train_min > args.train_max:
        parser.error("--train-min 不能大于 --train-max")
    return args


def main() -> int:
    args = parse_args()
    token = login(
        args.host, args.port, args.timeout, args.username, args.password
    )
    run_id = uuid.uuid4().hex[:10]
    started = time.perf_counter()
    results = []
    with ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(
                run_task,
                index,
                args.host,
                args.port,
                args.timeout,
                token,
                run_id,
                args.travel_date,
                args.from_station,
                args.to_station,
                args.seat_type,
                args.passenger_id,
                args.train_min,
                args.train_max,
            )
            for index in range(args.count)
        ]
        for future in as_completed(futures):
            results.append(future.result())

    elapsed = time.perf_counter() - started
    successes = [item for item in results if item["success"]]
    failures = [item for item in results if not item["success"]]
    submit_latencies = [
        item["submitMs"] for item in results if "submitMs" in item
    ]
    end_to_end_latencies = [
        item["endToEndMs"] for item in successes
    ]
    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "target": f"http://{args.host}:{args.port}/api/user/orders/async",
        "scenario": "async-order",
        "count": args.count,
        "concurrency": args.concurrency,
        "actualDurationSeconds": round(elapsed, 3),
        "ordersPerSecond": round(len(successes) / elapsed, 2)
        if elapsed > 0 else 0,
        "successfulOrders": len(successes),
        "failedOrders": len(failures),
        "submitLatencyMs": {
            "avg": round(statistics.fmean(submit_latencies), 2)
            if submit_latencies else 0,
            "p50": round(percentile(submit_latencies, 0.50), 2),
            "p95": round(percentile(submit_latencies, 0.95), 2),
            "p99": round(percentile(submit_latencies, 0.99), 2),
            "max": round(max(submit_latencies), 2)
            if submit_latencies else 0,
        },
        "endToEndLatencyMs": {
            "avg": round(statistics.fmean(end_to_end_latencies), 2)
            if end_to_end_latencies else 0,
            "p50": round(percentile(end_to_end_latencies, 0.50), 2),
            "p95": round(percentile(end_to_end_latencies, 0.95), 2),
            "p99": round(percentile(end_to_end_latencies, 0.99), 2),
            "max": round(max(end_to_end_latencies), 2)
            if end_to_end_latencies else 0,
        },
        "sampleFailures": [
            item.get("error", "unknown") for item in failures[:20]
        ],
    }
    rendered = json.dumps(summary, ensure_ascii=False, indent=2)
    print(rendered)
    if args.output:
        output = Path(args.output)
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(rendered + "\n", encoding="utf-8")
    return 0 if not failures else 1


if __name__ == "__main__":
    raise SystemExit(main())
