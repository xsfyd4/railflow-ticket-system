#!/usr/bin/env python3
"""火车票后端 HTTP 基准测试。

只使用 Python 标准库，不依赖 pip 包。

示例：
    python scripts/load_test.py --workers 8 --duration 10
    python scripts/load_test.py --workers 16 --duration 20 --output docs/load-test-16.json
"""

from __future__ import annotations

import argparse
import http.client
import json
import math
import statistics
import threading
import time
from dataclasses import dataclass
from datetime import date
from pathlib import Path
from urllib.parse import urlencode


@dataclass
class RequestResult:
    elapsed_ms: float
    http_status: int
    business_code: int | None


def extract_business_code(body: bytes) -> int | None:
    try:
        return int(json.loads(body)["code"])
    except (ValueError, KeyError, TypeError, json.JSONDecodeError):
        return None


class ApiConnection:
    def __init__(self, host: str, port: int, timeout: float):
        self.host = host
        self.port = port
        self.timeout = timeout
        self.connection: http.client.HTTPConnection | None = None

    def request(
        self,
        method: str,
        path: str,
        body: dict | None = None,
        token: str | None = None,
    ) -> tuple[int, bytes, float]:
        headers = {"Accept": "application/json"}
        payload = None
        if body is not None:
            payload = json.dumps(body, ensure_ascii=False).encode("utf-8")
            headers["Content-Type"] = "application/json"
        if token:
            headers["Authorization"] = f"Bearer {token}"

        for attempt in range(2):
            started = time.perf_counter()
            try:
                if self.connection is None:
                    self.connection = http.client.HTTPConnection(
                        self.host, self.port, timeout=self.timeout
                    )
                self.connection.request(method, path, body=payload, headers=headers)
                response = self.connection.getresponse()
                response_body = response.read()
                elapsed_ms = (time.perf_counter() - started) * 1000
                return response.status, response_body, elapsed_ms
            except (OSError, http.client.HTTPException):
                self.close()
                if attempt == 1:
                    raise
        raise RuntimeError("unreachable")

    def close(self) -> None:
        if self.connection is not None:
            self.connection.close()
            self.connection = None


def login(client: ApiConnection, username: str, password: str) -> str:
    status, body, _ = client.request(
        "POST",
        "/api/user/auth/login",
        {"username": username, "password": password},
    )
    if status != 200:
        raise RuntimeError(f"登录失败：HTTP {status}")
    data = json.loads(body)
    if data.get("code") != 200:
        raise RuntimeError(f"登录失败：{data.get('code')} {data.get('msg')}")
    return data["data"]["token"]


def percentile(values: list[float], ratio: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    index = min(len(ordered) - 1, math.ceil(len(ordered) * ratio) - 1)
    return ordered[max(index, 0)]


def run_worker(
    args: argparse.Namespace,
    token: str,
    path: str,
    start_event: threading.Event,
    deadline: float,
    output: list[RequestResult],
    errors: list[str],
    lock: threading.Lock,
) -> None:
    client = ApiConnection(args.host, args.port, args.timeout)
    local_results: list[RequestResult] = []
    local_errors: list[str] = []
    start_event.wait()
    try:
        while time.perf_counter() < deadline:
            try:
                http_status, body, elapsed_ms = client.request(
                    "GET", path, token=token
                )
                local_results.append(
                    RequestResult(
                        elapsed_ms=elapsed_ms,
                        http_status=http_status,
                        business_code=extract_business_code(body),
                    )
                )
            except Exception as exc:  # noqa: BLE001 - 压测需要收集所有异常
                local_errors.append(f"{type(exc).__name__}: {exc}")
    finally:
        client.close()
        with lock:
            output.extend(local_results)
            errors.extend(local_errors)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="火车票查票 HTTP 基准测试")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8080)
    parser.add_argument("--username", default="zhangsan")
    parser.add_argument("--password", default="123456")
    parser.add_argument("--from-station", type=int, default=1)
    parser.add_argument("--to-station", type=int, default=27)
    parser.add_argument("--travel-date", default=date.today().isoformat())
    parser.add_argument("--seat-type", type=int)
    parser.add_argument("--workers", type=int, default=8)
    parser.add_argument("--duration", type=float, default=10.0)
    parser.add_argument("--warmup", type=int, default=20)
    parser.add_argument("--timeout", type=float, default=10.0)
    parser.add_argument("--page-size", type=int, default=10)
    parser.add_argument("--output")
    args = parser.parse_args()
    if args.workers < 1 or args.workers > 128:
        parser.error("--workers 必须在 1 到 128 之间")
    if args.duration <= 0:
        parser.error("--duration 必须大于 0")
    return args


def main() -> int:
    args = parse_args()
    client = ApiConnection(args.host, args.port, args.timeout)
    token = login(client, args.username, args.password)

    query: dict[str, int | str] = {
        "fromStationId": args.from_station,
        "toStationId": args.to_station,
        "travelDate": args.travel_date,
        "page": 1,
        "size": args.page_size,
    }
    if args.seat_type is not None:
        query["seatType"] = args.seat_type
    path = "/api/user/tickets/search?" + urlencode(query)

    # 预热连接、JIT 和数据库连接池
    for _ in range(args.warmup):
        status, body, _ = client.request("GET", path, token=token)
        if status != 200 or extract_business_code(body) != 200:
            raise RuntimeError(f"预热请求失败：HTTP {status}, body={body[:300]!r}")
    client.close()

    results: list[RequestResult] = []
    errors: list[str] = []
    lock = threading.Lock()
    start_event = threading.Event()
    threads = [
        threading.Thread(
            target=run_worker,
            args=(
                args,
                token,
                path,
                start_event,
                time.perf_counter() + args.duration,
                results,
                errors,
                lock,
            ),
            daemon=True,
        )
        for _ in range(args.workers)
    ]

    for thread in threads:
        thread.start()
    started = time.perf_counter()
    start_event.set()
    for thread in threads:
        thread.join()
    elapsed = time.perf_counter() - started

    latencies = [result.elapsed_ms for result in results]
    successful = [
        result
        for result in results
        if result.http_status == 200 and result.business_code == 200
    ]
    code_counts: dict[str, int] = {}
    for result in results:
        key = f"http={result.http_status},biz={result.business_code}"
        code_counts[key] = code_counts.get(key, 0) + 1

    summary = {
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
        "target": f"http://{args.host}:{args.port}{path}",
        "workers": args.workers,
        "configuredDurationSeconds": args.duration,
        "actualDurationSeconds": round(elapsed, 3),
        "warmupRequests": args.warmup,
        "totalRequests": len(results) + len(errors),
        "successfulRequests": len(successful),
        "failedRequests": len(results) - len(successful) + len(errors),
        "errorCount": len(errors),
        "qps": round(len(results) / elapsed, 2) if elapsed > 0 else 0,
        "latencyMs": {
            "avg": round(statistics.fmean(latencies), 2) if latencies else 0,
            "p50": round(percentile(latencies, 0.50), 2),
            "p95": round(percentile(latencies, 0.95), 2),
            "p99": round(percentile(latencies, 0.99), 2),
            "max": round(max(latencies), 2) if latencies else 0,
        },
        "businessCodes": code_counts,
        "sampleErrors": errors[:10],
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
