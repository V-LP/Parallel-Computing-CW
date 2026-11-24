#!/usr/bin/env python3

from __future__ import annotations

import argparse
import asyncio
import http.client
import json
import os
import random
import sys
import threading
import time
from dataclasses import dataclass
from pathlib import Path

import benchlib
import dockerutil
from dockerutil import REPO_ROOT, BenchError, Compose

BENCH_DIR = Path(__file__).resolve().parent
DEFAULT_DATASETS = (
    "D184MB=data_extra/D184MB,D357MB=data_extra/D357MB,D670MB=data_extra/D670MB,"
    "D1GB=data_extra/D1GB,D1.7GB=data"
)


def say(message: str = "") -> None:
    print(message, flush=True)


@dataclass
class Context:
    url: str
    compose: Compose
    results_dir: Path
    repeats: int
    rng: random.Random
    seed: int
    health_timeout: float
    settle: float
    keep_old: bool



def wait_model_warmup(ctx: Context, markers_before: int, timeout: float) -> str | None:
    if timeout <= 0:
        return None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        markers = benchlib.warmup_markers(ctx.compose.logs())
        if len(markers) > markers_before:
            return markers[-1]
        time.sleep(1.0)
    return "timeout"


def start_server(ctx: Context, recreate: bool, settle: float | None = None) -> str | None:
    if recreate:
        markers_before = 0
        ctx.compose.recreate_server()
    else:
        markers_before = len(benchlib.warmup_markers(ctx.compose.logs()))
        ctx.compose.restart_server()
    dockerutil.wait_health(ctx.url, ctx.health_timeout)
    return wait_model_warmup(ctx, markers_before, ctx.settle if settle is None else settle)


def build_index(ctx: Context, threads: int) -> dict:
    logs_before = ctx.compose.logs()
    count_before = benchlib.count_index_ready(logs_before)
    started = time.perf_counter()
    try:
        answer = dockerutil.http_json("POST", ctx.url + "/api/index", {"threads": threads},
                                      timeout=3600)
    except BenchError as e:
        raise BenchError(f"{e}\nЯкщо контейнер перезапустився (код 137), не вистачило пам'яті: "
                         "збільште пам'ять Docker Desktop (докладніше в bench/README.md).") from None
    wall_ms = (time.perf_counter() - started) * 1000
    if answer.get("status") != "ok" or "documents_indexed" not in answer or "time_ms" not in answer:
        raise BenchError(f"Несподівана відповідь /api/index: {answer}")

    ready = None
    for _ in range(40):  # log can lag behind the http answer
        ready = benchlib.new_index_ready(count_before, ctx.compose.logs())
        if ready:
            break
        time.sleep(0.5)
    if ready is None:
        raise BenchError("У логу контейнера немає нового рядка 'Index ready:'. "
                         "Перевірте: docker compose logs server")
    if ready["documents"] != answer["documents_indexed"]:
        raise BenchError(f"documents_indexed у відповіді ({answer['documents_indexed']}) "
                         f"не збігається з логом ({ready['documents']})")
    if ready["threads"] != threads:
        raise BenchError(f"У логу {ready['threads']} потоків, а запитували {threads}")
    return {"api": answer, "wall_ms": wall_ms, "log": ready}


def index_row_fields(result: dict) -> dict:
    api, log = result["api"], result["log"]
    return {
        "time_ms": api["time_ms"],
        "wall_ms": round(result["wall_ms"], 1),
        "documents_indexed": api["documents_indexed"],
        "books": log["books"],
        "terms": log["terms"],
        "postings": log["postings"],
        "log_ms": log["ms"],
        "heap_mb": "" if log["heap_mb"] is None else log["heap_mb"],
        "heap_max_mb": "" if log["heap_max_mb"] is None else log["heap_max_mb"],
    }


def prepare_csv(ctx: Context, name: str) -> Path:
    path = ctx.results_dir / name
    if not ctx.keep_old:
        backup = benchlib.backup_existing(path)
        if backup:
            say(f"Старий {name} збережено як {backup.name} (git його ігнорує).")
    return path


def save_environment(ctx: Context, datasets: dict[str, Path]) -> None:
    try:
        info = dockerutil.collect_environment(ctx.compose, datasets)
        path = dockerutil.write_environment(ctx.results_dir, info)
        say(f"Середовище записано: {path}")
    except Exception as e:
        say(f"Увага: environment.json не записано: {e}")


def resolve_dir(text: str) -> Path:
    path = Path(text)
    return path if path.is_absolute() else (REPO_ROOT / path)


def require_dataset(name: str, path: Path) -> None:
    if not path.is_dir():
        raise BenchError(f"Каталог датасету {name} не знайдено: {path}")
    if not any(f.lower().endswith(".txt") for _, _, files in os.walk(path) for f in files):
        raise BenchError(f"У {path} немає жодного .txt файлу ({name}).")



def plan_threads(ctx: Context, args) -> list[list[int]]:
    return benchlib.make_order(args.threads, ctx.repeats, ctx.rng)


def cmd_threads(ctx: Context, args) -> int:
    data_dir = resolve_dir(args.data_dir)
    plan = plan_threads(ctx, args)
    if args.dry_run:
        say("ПЛАН: індексація від кількості потоків")
        say(f"  датасет: {data_dir} (існує: {'так' if data_dir.is_dir() else 'НІ'})")
        say(f"  threads = {args.threads}, повторів R = {ctx.repeats}, seed = {ctx.seed}")
        say(f"  усього прогонів: {sum(len(p) for p in plan)}; перший прогін = холодний кеш (cold=1)")
        for i, order in enumerate(plan, 1):
            say(f"  повтор {i}: threads у порядку {order}")
        say("  кожен прогін: docker compose restart server -> GET /api/health -> "
            "очікування прогріву моделі -> POST /api/index {\"threads\": p} -> "
            "рядок 'Index ready:' з docker compose logs")
        say("  перший прогін створює контейнер заново (щоб змонтувати потрібний датасет)")
        say("  перевірка: документи/терміни/постинги однакові для всіх threads, інакше помилка")
        say(f"  результат: {ctx.results_dir / 'index_threads.csv'}")
        return 0

    require_dataset("data", data_dir)
    ctx.compose.set_data_dir(data_dir)
    csv_path = prepare_csv(ctx, "index_threads.csv")
    checker = benchlib.ConsistencyChecker()
    total = sum(len(p) for p in plan)
    done = 0
    say(f"Індексація від потоків: {total} прогонів, датасет {data_dir}")
    try:
        for repeat, order in enumerate(plan, 1):
            for position, threads in enumerate(order, 1):
                done += 1
                cold = 1 if done == 1 else 0
                say(f"[{done}/{total}] повтор {repeat}, threads={threads}"
                    f"{' (холодний кеш)' if cold else ''}: перезапуск сервера...")
                start_server(ctx, recreate=(done == 1))
                result = build_index(ctx, threads)
                row = {
                    "timestamp": benchlib.now_iso(), "repeat": repeat, "position": position,
                    "threads": threads, "cold": cold, **index_row_fields(result),
                }
                benchlib.append_rows(csv_path, benchlib.INDEX_THREADS_FIELDS, [row])
                say(f"    time_ms={row['time_ms']} wall_ms={row['wall_ms']} "
                    f"documents={row['documents_indexed']} terms={row['terms']} "
                    f"postings={row['postings']} heap_mb={row['heap_mb']}")
                checker.check("all", result["log"], f"threads={threads}, повтор {repeat}")
    finally:
        save_environment(ctx, {"data": data_dir})
    say(f"Готово: {csv_path}")
    return 0



def cmd_size(ctx: Context, args) -> int:
    datasets = [(name, resolve_dir(path)) for name, path in benchlib.parse_datasets(args.datasets)]
    plan = benchlib.make_order([name for name, _ in datasets], ctx.repeats, ctx.rng)
    paths = dict(datasets)
    if args.dry_run:
        say("ПЛАН: індексація від розміру даних")
        for name, path in datasets:
            say(f"  {name}: {path} (існує: {'так' if path.is_dir() else 'НІ'})")
        say(f"  потоків (фіксовано) = {args.size_threads}, повторів R = {ctx.repeats}, "
            f"seed = {ctx.seed}")
        say(f"  усього прогонів: {sum(len(p) for p in plan)}; перший = холодний кеш (cold=1)")
        for i, order in enumerate(plan, 1):
            say(f"  повтор {i}: {order}")
        say("  при зміні датасету: docker compose up -d --no-deps --force-recreate server "
            "(з BENCH_DATA_DIR і bench/docker-compose.bench.yml), інакше restart server")
        say("  далі: GET /api/health -> POST /api/index -> рядок 'Index ready:' з логу")
        say(f"  результат: {ctx.results_dir / 'index_size.csv'}")
        return 0

    for name, path in datasets:
        require_dataset(name, path)
    scans = {name: dockerutil.scan_dataset(path) for name, path in datasets}
    for name, scan in scans.items():
        say(f"  {name}: {scan['files']} файлів .txt, {scan['size_mb']} МБ")
    csv_path = prepare_csv(ctx, "index_size.csv")
    checker = benchlib.ConsistencyChecker()
    total = sum(len(p) for p in plan)
    done = 0
    current = None
    say(f"Індексація від розміру: {total} прогонів, потоків {args.size_threads}")
    try:
        for repeat, order in enumerate(plan, 1):
            for position, name in enumerate(order, 1):
                done += 1
                cold = 1 if done == 1 else 0
                recreate = name != current
                current = name
                ctx.compose.set_data_dir(paths[name])
                say(f"[{done}/{total}] повтор {repeat}, {name}"
                    f"{' (холодний кеш)' if cold else ''}: "
                    f"{'пересоздання контейнера' if recreate else 'перезапуск сервера'}...")
                start_server(ctx, recreate=recreate)
                result = build_index(ctx, args.size_threads)
                row = {
                    "timestamp": benchlib.now_iso(), "dataset": name,
                    "files": scans[name]["files"], "size_mb": scans[name]["size_mb"],
                    "repeat": repeat, "position": position, "threads": args.size_threads,
                    "cold": cold, **index_row_fields(result),
                }
                benchlib.append_rows(csv_path, benchlib.INDEX_SIZE_FIELDS, [row])
                say(f"    time_ms={row['time_ms']} wall_ms={row['wall_ms']} "
                    f"documents={row['documents_indexed']} postings={row['postings']}")
                checker.check(name, result["log"], f"{name}, повтор {repeat}")
    finally:
        save_environment(ctx, paths)
    say(f"Готово: {csv_path}")
    return 0



def search_request(conn, queries: list[str], index: int) -> tuple[float, str, int]:
    body = json.dumps({"query": queries[index], "mode": "keyword"}).encode("utf-8")
    started = time.perf_counter()
    conn.request("POST", "/api/search", body=body,
                 headers={"Content-Type": "application/json"})
    response = conn.getresponse()
    data = response.read()
    latency_ms = (time.perf_counter() - started) * 1000
    results = -1
    if response.status == 200:
        try:
            results = len(json.loads(data)["results"])
        except (ValueError, KeyError, TypeError):
            return latency_ms, "BADJSON", -1
    return latency_ms, str(response.status), results


def search_round(url: str, queries: list[str], clients: int, per_client: int,
                 repeat: int) -> tuple[list[dict], dict]:
    per_thread: list[list[dict]] = [[] for _ in range(clients)]
    barrier = threading.Barrier(clients + 1)

    def worker(client: int) -> None:
        conn = dockerutil.connect_keepalive(url)
        try:
            conn.connect()
        except OSError:
            pass
        barrier.wait()
        for seq in range(per_client):
            index = benchlib.query_for(client, seq, len(queries))
            try:
                latency, status, results = search_request(conn, queries, index)
            except (OSError, ValueError, http.client.HTTPException):
                latency, status, results = 0.0, "ERR", -1
                conn.close()
                conn = dockerutil.connect_keepalive(url)
            per_thread[client].append({
                "timestamp": benchlib.now_iso(), "repeat": repeat, "clients": clients,
                "client": client, "seq": seq, "query": queries[index],
                "latency_ms": round(latency, 3), "status": status, "results": results,
            })
        conn.close()

    threads = [threading.Thread(target=worker, args=(i,), daemon=True) for i in range(clients)]
    for t in threads:
        t.start()
    barrier.wait()
    started = time.perf_counter()
    for t in threads:
        t.join()
    wall = time.perf_counter() - started

    rows = [row for chunk in per_thread for row in chunk]
    ok = sum(1 for r in rows if r["status"] == "200")
    run = {
        "timestamp": benchlib.now_iso(), "repeat": repeat, "clients": clients,
        "requests_per_client": per_client, "ok": ok, "errors": len(rows) - ok,
        "wall_s": round(wall, 4),
    }
    return rows, run


def warmup_pass(url: str, queries: list[str]) -> int:
    conn = dockerutil.connect_keepalive(url)
    failures = 0
    try:
        for index in range(len(queries)):
            _latency, status, _results = search_request(conn, queries, index)
            if status != "200":
                failures += 1
    finally:
        conn.close()
    return failures


def cmd_search(ctx: Context, args) -> int:
    data_dir = resolve_dir(args.data_dir)
    queries_path = resolve_dir(args.queries)
    plan = benchlib.make_order(args.clients, ctx.repeats, ctx.rng)
    if args.dry_run:
        say("ПЛАН: пошук від кількості клієнтів (REST POST /api/search, mode=keyword)")
        say(f"  датасет: {data_dir} (існує: {'так' if data_dir.is_dir() else 'НІ'})")
        say(f"  запити: {queries_path} (існує: {'так' if queries_path.is_file() else 'НІ'})")
        say(f"  M = {args.clients}, по K = {args.requests} запитів на клієнта (замкнений цикл), "
            f"повторів R = {ctx.repeats}, seed = {ctx.seed}")
        for i, order in enumerate(plan, 1):
            say(f"  повтор {i}: M у порядку {order}")
        say(f"  спочатку: перезапуск сервера, свіжа індексація (threads={args.index_threads}), "
            "один прогрівальний прохід по всіх запитах (не записується)")
        say(f"  результат: {ctx.results_dir / 'search_requests.csv'} (кожен запит) і "
            f"{ctx.results_dir / 'search_runs.csv'} (час кожного раунду)")
        return 0

    require_dataset("data", data_dir)
    queries = benchlib.load_queries(queries_path)
    ctx.compose.set_data_dir(data_dir)
    requests_csv = prepare_csv(ctx, "search_requests.csv")
    runs_csv = prepare_csv(ctx, "search_runs.csv")
    say(f"Пошук: {len(queries)} запитів у наборі, M = {args.clients}, K = {args.requests}")
    try:
        say("Перезапуск сервера і свіжа індексація...")
        start_server(ctx, recreate=True)
        result = build_index(ctx, args.index_threads)
        documents = result["api"]["documents_indexed"]
        say(f"Проіндексовано документів: {documents}, time_ms={result['api']['time_ms']}")
        failures = warmup_pass(ctx.url, queries)
        if failures:
            raise BenchError(f"Прогрівальний прохід: {failures} запитів завершились помилкою.")
        say("Прогрівальний прохід виконано.")
        for repeat, order in enumerate(plan, 1):
            for clients in order:
                rows, run = search_round(ctx.url, queries, clients, args.requests, repeat)
                run["index_threads"] = args.index_threads
                run["documents_indexed"] = documents
                benchlib.append_rows(requests_csv, benchlib.SEARCH_REQUEST_FIELDS, rows)
                benchlib.append_rows(runs_csv, benchlib.SEARCH_RUN_FIELDS, [run])
                line = f"  повтор {repeat}, M={clients}: ok={run['ok']} помилок={run['errors']} "
                line += f"wall={run['wall_s']} с"
                ok_latency = [r["latency_ms"] for r in rows if r["status"] == "200"]
                if ok_latency:
                    line += (f", {benchlib.throughput(run['ok'], run['wall_s']):.1f} запитів/с, "
                             f"p50={benchlib.percentile(ok_latency, 50):.1f} мс, "
                             f"p95={benchlib.percentile(ok_latency, 95):.1f} мс")
                say(line)
                if run["errors"]:
                    say(f"    УВАГА: {run['errors']} запитів не отримали 200 (див. CSV, status).")
    finally:
        save_environment(ctx, {"data": data_dir})
    say(f"Готово: {requests_csv}, {runs_csv}")
    return 0



async def ws_round(ws_url: str, queries: list[str], timeout: float) -> list[dict]:
    import websockets

    connections = []
    try:
        for _ in queries:
            connections.append(await websockets.connect(
                ws_url, open_timeout=15, ping_interval=None, max_size=None))

        async def one(ws, query: str) -> dict:
            record = {"query": query, "expansion_ms": "", "expanded_terms": "", "search_ms": "",
                      "total_ms": "", "results": "", "error": ""}
            started = time.perf_counter()
            await ws.send(json.dumps({"query": query}))
            try:
                while True:
                    message = json.loads(await asyncio.wait_for(ws.recv(), timeout))
                    elapsed = round((time.perf_counter() - started) * 1000, 1)
                    stage = message.get("stage")
                    data = message.get("data") or {}
                    if stage == "query_expansion":
                        record["expansion_ms"] = elapsed
                        record["expanded_terms"] = len(data.get("expanded_terms", []))
                    elif stage == "keyword_search":
                        record["search_ms"] = data.get("time_ms", "")
                        record["results"] = len(data.get("results", []))
                    elif stage == "error":
                        record["error"] = str(data.get("message", data))
                    elif stage == "done":
                        record["total_ms"] = elapsed
                        break
            except Exception as e:
                record["error"] = record["error"] or repr(e)
            return record

        return list(await asyncio.gather(*(one(ws, q) for ws, q in zip(connections, queries))))
    finally:
        for ws in connections:
            try:
                await ws.close()
            except Exception:
                pass


def cmd_ws(ctx: Context, args) -> int:
    data_dir = resolve_dir(args.data_dir)
    queries_path = resolve_dir(args.queries)
    needed = sum(args.ws_clients) * ctx.repeats
    if args.dry_run:
        say("ПЛАН (необов'язковий): WebSocket-пайплайн /ws/search")
        say(f"  клієнтів одночасно: {args.ws_clients}, повторів R = {ctx.repeats}")
        say(f"  потрібно унікальних запитів: {needed} (кеш QueryExpander не повинен спрацьовувати)")
        say("  потрібна встановлена бібліотека websockets і працююча Ollama з моделлю")
        say("  кроки: docker compose up -d ollama, перезапуск сервера, індексація, "
            "очікування прогріву моделі, раунди M одночасних клієнтів")
        say(f"  результат: {ctx.results_dir / 'ws_stages.csv'}")
        return 0
    try:
        import websockets  # noqa: F401
    except ImportError:
        say("Пакет websockets не встановлено: необов'язковий експеримент пропущено "
            "(pip install websockets).")
        return 0

    queries = benchlib.load_queries(queries_path)
    if needed > len(queries):
        raise BenchError(f"Для {ctx.repeats} повторів потрібно {needed} унікальних запитів, "
                         f"у {queries_path.name} лише {len(queries)}. Зменшіть --repeats "
                         "або --ws-clients.")
    ctx.rng.shuffle(queries)
    pool = iter(queries)
    ctx.compose.set_data_dir(data_dir)
    csv_path = prepare_csv(ctx, "ws_stages.csv")
    ws_url = args.url.replace("http://", "ws://", 1).replace("https://", "wss://", 1)
    ws_url = ws_url.rstrip("/") + "/ws/search"
    try:
        say("Запуск Ollama і сервера...")
        ctx.compose.start_ollama()
        status = start_server(ctx, recreate=True, settle=max(ctx.settle, 180))
        if status != "warmed":
            raise BenchError(f"Модель Ollama не прогріта (стан: {status}). Перевірте "
                             "docker compose logs server ollama; модель має бути завантажена.")
        if not dockerutil.http_json("GET", ctx.url + "/api/health").get("ollama"):
            raise BenchError("GET /api/health повертає ollama=false: розширення не працюватиме.")
        result = build_index(ctx, args.index_threads)
        say(f"Проіндексовано документів: {result['api']['documents_indexed']}")
        for repeat in range(1, ctx.repeats + 1):
            for clients in args.ws_clients:
                batch = [next(pool) for _ in range(clients)]
                records = asyncio.run(ws_round(ws_url, batch, args.ws_timeout))
                rows = []
                for client, record in enumerate(records):
                    rows.append({"timestamp": benchlib.now_iso(), "repeat": repeat,
                                 "clients": clients, "client": client, **record})
                benchlib.append_rows(csv_path, benchlib.WS_FIELDS, rows)
                shown = ", ".join(f"{r['expansion_ms']} мс (термінів {r['expanded_terms']})"
                                  for r in records)
                say(f"  повтор {repeat}, M={clients}: query_expansion: {shown}")
                errors = [r["error"] for r in records if r["error"]]
                if errors:
                    say(f"    УВАГА: помилки: {errors}")
    finally:
        save_environment(ctx, {"data": data_dir})
    say(f"Готово: {csv_path}")
    return 0



def cmd_env(ctx: Context, args) -> int:
    if args.dry_run:
        say("ПЛАН: записати bench/results/environment.json (ядра, ОЗУ, ОС, docker info, "
            "CHUNK_SIZE_KB, файли й розмір датасету, git-хеш, дата)")
        return 0
    datasets = {}
    for name, path in benchlib.parse_datasets(args.datasets):
        resolved = resolve_dir(path)
        if resolved.is_dir():
            datasets[name] = resolved
        else:
            say(f"Пропущено {name}: каталогу {resolved} немає")
    save_environment(ctx, datasets)
    return 0


def cmd_all(ctx: Context, args) -> int:
    code = 0
    for command in (cmd_env, cmd_threads, cmd_size, cmd_search, cmd_ws):
        code = command(ctx, args) or code
    return code


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Бенчмарки пошукового сервера")
    parser.add_argument("command", choices=["threads", "size", "search", "ws", "env", "all"])
    parser.add_argument("--dry-run", action="store_true", help="надрукувати план і вийти")
    parser.add_argument("--url", default="http://localhost:8080", help="адреса API")
    parser.add_argument("-R", "--repeats", type=int, default=3, help="кількість повторів (3)")
    parser.add_argument("--seed", type=int, default=42, help="seed для порядку прогонів (42)")
    parser.add_argument("--results-dir", default=str(BENCH_DIR / "results"))
    parser.add_argument("--keep-old", action="store_true",
                        help="дописувати у наявні CSV (за замовчуванням їх відкладають убік)")
    parser.add_argument("--health-timeout", type=float, default=180,
                        help="скільки секунд чекати /api/health після перезапуску (180)")
    parser.add_argument("--settle", type=float, default=30,
                        help="макс. секунд очікування прогріву моделі після старту (30; 0 = ні)")
    parser.add_argument("--data-dir", default="data", help="датасет для threads/search/ws")
    parser.add_argument("--threads", type=benchlib.parse_int_list,
                        default=[1, 2, 4, 8, 16], help="потоки індексації (1,2,4,8,16)")
    parser.add_argument("--datasets", default=DEFAULT_DATASETS,
                        help="НАЗВА=КАТАЛОГ через кому (для size і env)")
    parser.add_argument("--size-threads", type=int, default=8,
                        help="фіксована кількість потоків для експерименту size (8)")
    parser.add_argument("--clients", type=benchlib.parse_int_list, default=[1, 2, 4, 8, 16, 32],
                        help="кількість клієнтів M для пошуку (1,2,4,8,16,32)")
    parser.add_argument("--requests", type=int, default=50, help="запитів на клієнта K (50)")
    parser.add_argument("--index-threads", type=int, default=8,
                        help="потоки індексації перед search/ws (8)")
    parser.add_argument("--queries", default=str(BENCH_DIR / "queries.txt"))
    parser.add_argument("--ws-clients", type=benchlib.parse_int_list, default=[1, 2, 4],
                        help="одночасні WebSocket-клієнти (1,2,4)")
    parser.add_argument("--ws-timeout", type=float, default=180)
    return parser


def main(argv: list[str] | None = None) -> int:
    benchlib.setup_console()
    parser = build_parser()
    args = parser.parse_args(argv)
    if args.repeats < 1 or args.requests < 1 or args.size_threads < 1 or args.index_threads < 1:
        parser.error("--repeats, --requests, --size-threads, --index-threads мають бути >= 1")
    ctx = Context(
        url=args.url.rstrip("/"),
        compose=Compose(),
        results_dir=Path(args.results_dir),
        repeats=args.repeats,
        rng=random.Random(args.seed),
        seed=args.seed,
        health_timeout=args.health_timeout,
        settle=args.settle,
        keep_old=args.keep_old,
    )
    commands = {"threads": cmd_threads, "size": cmd_size, "search": cmd_search,
                "ws": cmd_ws, "env": cmd_env, "all": cmd_all}
    try:
        return commands[args.command](ctx, args)
    except benchlib.ConsistencyError as e:
        print(f"\nПОМИЛКА КОРЕКТНОСТІ: {e}", file=sys.stderr)
        return 3
    except (BenchError, ValueError) as e:
        print(f"\nПомилка: {e}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        print("\nПерервано. Уже зроблені виміри лишилися в CSV.", file=sys.stderr)
        return 130


if __name__ == "__main__":
    sys.exit(main())
