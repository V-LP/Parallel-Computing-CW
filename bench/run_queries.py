#!/usr/bin/env python3

import argparse
import csv
import datetime
import inspect
import json
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path

RUN_LABELS = ("холодний", "теплий")
TOP_N = 5
SNIPPET_CHARS = 80
TABLE_QUERY_CHARS = 60
EXPECTED_STAGES = ["query_expansion", "keyword_search", "done"]

REPO_ROOT = Path(__file__).resolve().parent.parent
DEFAULT_QUERIES = REPO_ROOT / "bench" / "test_queries.json"
DEFAULT_OUT_DIR = REPO_ROOT / "docs" / "report"


class BenchError(Exception):
    """readable error for main"""



def load_queries(path):
    try:
        with open(path, encoding="utf-8") as handle:
            data = json.load(handle)
    except OSError as error:
        raise BenchError(f"Не вдалося прочитати {path}: {error}") from None
    except json.JSONDecodeError as error:
        raise BenchError(f"{path} не є коректним JSON: {error}") from None
    if not isinstance(data, list) or not data:
        raise BenchError(f"{path}: очікується непорожній JSON-масив")
    seen = set()
    for position, item in enumerate(data, 1):
        if not isinstance(item, dict):
            raise BenchError(f"{path}: елемент {position} не є об'єктом")
        for field in ("id", "category", "query", "purpose"):
            value = item.get(field)
            if not isinstance(value, str) or not value.strip():
                raise BenchError(f"{path}: елемент {position}: поле '{field}' має бути непорожнім рядком")
        if item["id"] in seen:
            raise BenchError(f"{path}: id '{item['id']}' повторюється")
        seen.add(item["id"])
    return data


_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))


def http_json(method, url, payload, timeout):
    headers = {"Accept": "application/json"}
    data = None
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json; charset=utf-8"
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            body = response.read().decode("utf-8")
    except urllib.error.HTTPError as error:
        text = error.read().decode("utf-8", "replace").strip()
        raise BenchError(f"HTTP {error.code} від {url}: {text[:200]}") from None
    except (urllib.error.URLError, OSError) as error:
        raise BenchError(f"Сервер недоступний ({url}): {error}") from None
    try:
        return json.loads(body)
    except json.JSONDecodeError:
        raise BenchError(f"Відповідь {url} не є JSON: {body[:200]}") from None


def ws_url_from(base_url):
    base = base_url.rstrip("/")
    if base.startswith("https://"):
        base = "wss://" + base[len("https://"):]
    elif base.startswith("http://"):
        base = "ws://" + base[len("http://"):]
    return base + "/ws/search"


def rest_search(base_url, query, timeout):
    started = time.perf_counter()
    try:
        answer = http_json("POST", base_url.rstrip("/") + "/api/search",
                           {"query": query, "mode": "keyword"}, timeout)
    except BenchError as error:
        return {"error": str(error), "time_ms": None, "wall_ms": None, "results": []}
    wall_ms = round((time.perf_counter() - started) * 1000, 1)
    results = answer.get("results")
    if not isinstance(results, list):
        return {"error": "у відповіді немає масиву results", "time_ms": None,
                "wall_ms": wall_ms, "results": []}
    return {"error": None, "time_ms": answer.get("time_ms"), "wall_ms": wall_ms,
            "results": results}


def ws_search(base_url, query, timeout):
    events = []
    failure = None
    try:
        from websockets.sync.client import connect

        options = {"open_timeout": timeout, "max_size": None}
        if "proxy" in inspect.signature(connect).parameters:
            options["proxy"] = None  # newer websockets reads proxy env
        with connect(ws_url_from(base_url), **options) as socket:
            started = time.perf_counter()
            socket.send(json.dumps({"query": query}, ensure_ascii=False))
            while True:
                left = timeout - (time.perf_counter() - started)
                if left <= 0:
                    raise TimeoutError(f"немає done за {timeout} с")
                message = json.loads(socket.recv(timeout=left))
                events.append(((time.perf_counter() - started) * 1000, message))
                if message.get("stage") == "done":
                    break
    except Exception as error:  # noqa: BLE001
        failure = f"{type(error).__name__}: {error}"
    return parse_ws_events(events, failure)



def short_snippet(text, limit=SNIPPET_CHARS):
    one_line = " ".join(str(text or "").split())
    if len(one_line) <= limit:
        return one_line
    return one_line[: limit - 3].rstrip() + "..."


def normalize_hits(results, top=TOP_N):
    hits = []
    for item in results[:top]:
        hits.append({
            "doc_id": item.get("doc_id", ""),
            "score": item.get("score"),
            "snippet": short_snippet(item.get("snippet", "")),
        })
    return hits


def parse_ws_events(events, failure=None):
    summary = {"stages": [], "expanded_terms": None, "expansion_ms": None, "search_ms": None,
               "total_ms": None, "results": [], "results_count": None, "error": failure}
    for elapsed, message in events:
        stage = message.get("stage")
        data = message.get("data") or {}
        summary["stages"].append(stage)
        if stage == "query_expansion":
            summary["expanded_terms"] = list(data.get("expanded_terms") or [])
            summary["expansion_ms"] = round(elapsed, 1)
        elif stage == "keyword_search":
            results = data.get("results") or []
            summary["results"] = results
            summary["results_count"] = len(results)
            summary["search_ms"] = data.get("time_ms")
        elif stage == "error" and summary["error"] is None:
            summary["error"] = str(data.get("message", "error"))
        elif stage == "done":
            summary["total_ms"] = round(elapsed, 1)
    return summary


def check_health(health, no_ollama):
    if not isinstance(health, dict) or health.get("status") != "ok":
        return f"GET /api/health відповів несподівано: {health!r}"
    ollama = health.get("ollama")
    if no_ollama and ollama is not False:
        return ("Режим --no-ollama очікує health.ollama == false, а сервер каже true. Скрипт нічого "
                "не зупиняє: виконайте `docker compose stop ollama` і повторіть.")
    if not no_ollama and ollama is not True:
        return ("health.ollama == false: розширення буде порожнім, і звіт не покаже роботу LLM. "
                "Запустіть Ollama або виконайте скрипт з --no-ollama.")
    return None


def fallback_issues(run):
    ws, rest = run["ws"], run["rest"]
    issues = []
    if ws["error"] is not None and "keyword_search" not in ws["stages"]:
        if "error" in ws["stages"]:
            return issues
        issues.append(f"WebSocket не дав результатів: {ws['error']}")
        return issues
    if ws["expanded_terms"]:
        issues.append("expanded_terms не порожній: " + ", ".join(ws["expanded_terms"]))
    if ws["stages"] != EXPECTED_STAGES:
        issues.append("етапи WebSocket: " + (", ".join(ws["stages"]) or "жодного"))
    if rest["error"] is None and ws["results_count"] is not None:
        if _ids_scores(rest["results"]) != _ids_scores(ws["results"]):
            issues.append("результати WebSocket відрізняються від REST")
    return issues


def _ids_scores(results):
    return [(item.get("doc_id"), item.get("score")) for item in results]


def run_differences(first, second):
    notes = []
    for side, label in (("rest", "REST"), ("ws", "WS")):
        if first[side]["error"] != second[side]["error"]:
            notes.append(f"{label}: помилка тільки в одному прогоні")
        if _ids_scores(first[side]["results"]) != _ids_scores(second[side]["results"]):
            notes.append(f"{label}: інший список результатів")
    if (first["ws"]["expanded_terms"] or []) != (second["ws"]["expanded_terms"] or []):
        notes.append("інші розширені терміни")
    return notes


def collect(queries, rest_fn, ws_fn, progress=None):
    records = []
    for number, item in enumerate(queries, 1):
        runs = []
        for index, label in enumerate(RUN_LABELS, 1):
            rest = rest_fn(item["query"])
            ws = ws_fn(item["query"])
            runs.append({"run": index, "label": label, "rest": rest, "ws": ws})
            if progress:
                progress(number, len(queries), item, runs[-1])
        records.append({"query": item, "runs": runs})
    return records



def csv_header():
    columns = ["id", "category", "query", "purpose", "run", "run_label", "expanded_terms",
               "expanded_count", "rest_results", "ws_results", "rest_time_ms", "rest_wall_ms",
               "ws_expansion_ms", "ws_search_ms", "ws_total_ms", "rest_error", "ws_error"]
    for side in ("rest", "ws"):
        for rank in range(1, TOP_N + 1):
            columns += [f"{side}_top{rank}_doc_id", f"{side}_top{rank}_score",
                        f"{side}_top{rank}_snippet"]
    return columns + ["relevance_student", "comment"]


def csv_rows(records):
    for record in records:
        item = record["query"]
        for run in record["runs"]:
            rest, ws = run["rest"], run["ws"]
            terms = ws["expanded_terms"] or []
            rest_count = len(rest["results"]) if rest["error"] is None else None
            row = [item["id"], item["category"], item["query"], item["purpose"], run["run"],
                   run["label"], "; ".join(terms), len(terms), _blank(rest_count),
                   _blank(ws["results_count"]), _blank(rest["time_ms"]), _blank(rest["wall_ms"]),
                   _blank(ws["expansion_ms"]), _blank(ws["search_ms"]), _blank(ws["total_ms"]),
                   rest["error"] or "", ws["error"] or ""]
            for hits in (normalize_hits(rest["results"]), normalize_hits(ws["results"])):
                for rank in range(TOP_N):
                    if rank < len(hits):
                        row += [hits[rank]["doc_id"], hits[rank]["score"], hits[rank]["snippet"]]
                    else:
                        row += ["", "", ""]
            yield row + ["", ""]


def _blank(value):
    return "" if value is None else value


def write_csv(path, records):
    # excel needs the bom
    with open(path, "w", encoding="utf-8-sig", newline="") as handle:
        writer = csv.writer(handle)
        writer.writerow(csv_header())
        writer.writerows(csv_rows(records))



def md_escape(text):
    return " ".join(str(text).split()).replace("|", "\\|")


def display_query(query):
    one_line = " ".join(query.split())
    if len(one_line) <= TABLE_QUERY_CHARS:
        return md_escape(one_line)
    return md_escape(one_line[: TABLE_QUERY_CHARS - 3].rstrip()) + f"... ({len(query)} символів)"


def fmt_pair(first, second):
    return " / ".join("-" if value is None else f"{value:g}" for value in (first, second))


def fmt_terms(ws):
    if ws["expanded_terms"] is None:
        return "помилка WS" if ws["error"] else "-"
    return md_escape(", ".join(ws["expanded_terms"])) if ws["expanded_terms"] else "немає"


def fmt_count(run):
    rest_count = len(run["rest"]["results"]) if run["rest"]["error"] is None else None
    return fmt_pair(rest_count, run["ws"]["results_count"])


def fmt_score(score):
    return f"{score:.4f}" if isinstance(score, (int, float)) else "-"


def fmt_top1(results):
    if not results:
        return "-"
    top = results[0]
    return f"`{md_escape(top.get('doc_id', ''))}` ({fmt_score(top.get('score'))})"


def render_markdown(records, meta):
    no_ollama = meta["no_ollama"]
    lines = ["# Таблиця тестових запитів" + (" (без Ollama, перевірка fallback)" if no_ollama else ""),
             "",
             f"- Дата запуску: {meta['started']}",
             f"- Сервер: {meta['url']}",
             f"- Документів в індексі: {meta['documents']} (потоків індексації: {meta['threads']})",
             f"- `GET /api/health` перед запуском: `{json.dumps(meta['health'])}`",
             f"- Запитів: {len(records)}, прогонів на запит: {len(RUN_LABELS)} "
             f"(1 = {RUN_LABELS[0]}, 2 = {RUN_LABELS[1]})",
             "",
             "Як читати таблицю:",
             "",
             "- **REST** це `POST /api/search` з `mode: keyword`: без розширення запиту. "
             "**WS** це `/ws/search`: розширення запиту, потім пошук.",
             "- Усі значення взяті з відповідей сервера. Вміст колонок «Розширення», «Результатів» і "
             "«Топ-1» взято з першого (холодного) прогону; відмінності другого прогону описані нижче.",
             "- «Результатів» це `REST / WS`; сервер повертає не більше 10 результатів, тож 10 означає "
             "«10 або більше». Час у мілісекундах, `холодний / теплий`. Час REST це "
             "`time_ms` із відповіді сервера. Час WS це від відправки запиту до повідомлення `done` "
             "(вимірює клієнт, тобто разом з очікуванням моделі); розбивка на етапи нижче.",
             "- Другий прогін може бути швидшим через кеш розширень сервера (LRU) і файловий кеш ОС. "
             "У межах прогону REST виконується перед WS, тому WS частково читає вже закешовані файли.",
             "- Колонки «Релевантність» і «Коментар» заповнює студент вручну.",
             ""]

    lines += ["| № | Категорія | Запит | Розширення | Результатів (REST / WS) | Топ-1 REST (doc_id, score) "
              "| Топ-1 WS (doc_id, score) | REST, мс (хол. / тепл.) | WS, мс (хол. / тепл.) "
              "| Релевантність (оцінює студент) | Коментар |",
              "|---|---|---|---|---|---|---|---|---|---|---|"]
    for number, record in enumerate(records, 1):
        first, second = record["runs"]
        item = record["query"]
        lines.append("| " + " | ".join([
            str(number), md_escape(item["category"]), display_query(item["query"]),
            fmt_terms(first["ws"]), fmt_count(first), fmt_top1(first["rest"]["results"]),
            fmt_top1(first["ws"]["results"]),
            fmt_pair(first["rest"]["time_ms"], second["rest"]["time_ms"]),
            fmt_pair(first["ws"]["total_ms"], second["ws"]["total_ms"]), "", ""]) + " |")

    lines += ["", "## Час етапів WebSocket, мс (холодний / теплий)", "",
              "Розширення: від відправки запиту до `query_expansion` (вимірює клієнт). Пошук: "
              "`time_ms` етапу `keyword_search` (повідомляє сервер). Усього: до `done`.", "",
              "| № | Запит | Розширення | Пошук | Усього |", "|---|---|---|---|---|"]
    for number, record in enumerate(records, 1):
        first, second = record["runs"]
        lines.append("| " + " | ".join([
            str(number), display_query(record["query"]["query"]),
            fmt_pair(first["ws"]["expansion_ms"], second["ws"]["expansion_ms"]),
            fmt_pair(first["ws"]["search_ms"], second["ws"]["search_ms"]),
            fmt_pair(first["ws"]["total_ms"], second["ws"]["total_ms"])]) + " |")

    lines += ["", "## Відмінності між прогонами та помилки", ""]
    notes = []
    for number, record in enumerate(records, 1):
        first, second = record["runs"]
        for run in (first, second):
            for side, label in (("rest", "REST"), ("ws", "WS")):
                if run[side]["error"]:
                    notes.append(f"- №{number} ({run['label']} прогін), {label}: "
                                 f"{md_escape(run[side]['error'])}")
        for text in run_differences(first, second):
            notes.append(f"- №{number}: {text}")
    lines += notes if notes else ["Відмінностей і помилок немає."]

    if no_ollama:
        lines += ["", "## Перевірка fallback", ""]
        problems = []
        for number, record in enumerate(records, 1):
            for run in record["runs"]:
                for text in fallback_issues(run):
                    problems.append(f"- №{number} ({run['label']} прогін): {md_escape(text)}")
        if problems:
            lines += ["Fallback порушено:", ""] + problems
        else:
            lines += ["Для всіх запитів: `expanded_terms` порожній, послідовність етапів "
                      "`query_expansion`, `keyword_search`, `done`, результати WS збігаються з REST "
                      "(запити, які сервер відхилив помилкою WS, не оцінювалися)."]

    lines += ["", f"## Топ-{TOP_N}: перший (холодний) прогін", "",
              f"Початок сніпета: перші {SNIPPET_CHARS} символів.", ""]
    for number, record in enumerate(records, 1):
        item, first = record["query"], record["runs"][0]
        lines += [f"### {number}. {display_query(item['query'])}", "",
                  f"Призначення: {md_escape(item['purpose'])}", ""]
        for title, results in (("REST", first["rest"]["results"]), ("WS", first["ws"]["results"])):
            hits = normalize_hits(results)
            lines.append(f"**{title}:** немає результатів" if not hits else f"**{title}:**")
            for rank, hit in enumerate(hits, 1):
                lines.append(f"{rank}. `{md_escape(hit['doc_id'])}` ({fmt_score(hit['score'])}) "
                             f"{md_escape(hit['snippet'])}")
            lines.append("")
    return "\n".join(lines).rstrip() + "\n"



def parse_args(argv):
    parser = argparse.ArgumentParser(description="Запуск тестових запитів (REST + WebSocket).")
    parser.add_argument("--url", default="http://localhost:8080", help="адреса сервера")
    parser.add_argument("--queries", type=Path, default=DEFAULT_QUERIES, help="JSON зі списком запитів")
    parser.add_argument("--out-dir", type=Path, default=DEFAULT_OUT_DIR, help="куди писати звіт")
    parser.add_argument("--timeout", type=float, default=120, help="таймаут одного запиту, с")
    parser.add_argument("--no-ollama", action="store_true",
                        help="перевірка fallback: Ollama має бути вже зупинена (скрипт її не зупиняє)")
    parser.add_argument("--force", action="store_true",
                        help="перезаписати існуючі файли (втратить ручні оцінки в .md)")
    return parser.parse_args(argv)


def output_paths(out_dir, no_ollama):
    stem = "test-queries-no-ollama" if no_ollama else "test-queries"
    return Path(out_dir) / f"{stem}.csv", Path(out_dir) / f"{stem}.md"


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        if hasattr(stream, "reconfigure"):
            stream.reconfigure(encoding="utf-8", errors="replace")
    args = parse_args(argv)
    csv_path, md_path = output_paths(args.out_dir, args.no_ollama)
    try:
        queries = load_queries(args.queries)
        existing = [path for path in (csv_path, md_path) if path.exists()]
        if existing and not args.force:
            raise BenchError("Файли вже існують: " + ", ".join(map(str, existing)) +
                             ". У .md можуть бути ваші оцінки: додайте --force або інший --out-dir.")
        base = args.url.rstrip("/")
        health = http_json("GET", base + "/api/health", None, 10)
        problem = check_health(health, args.no_ollama)
        if problem:
            raise BenchError(problem)
        stats = http_json("GET", base + "/api/stats", None, 10)
        if not stats.get("documents"):
            raise BenchError("Індекс порожній. Спершу виконайте індексацію: `./search --index` "
                             "(або POST /api/index).")
    except BenchError as error:
        print(f"Помилка: {error}", file=sys.stderr)
        return 2

    def progress(number, total, item, run):
        rest, ws = run["rest"], run["ws"]
        print(f"[{number:02d}/{total}] {item['id']} {run['label']}: REST {rest['time_ms']} мс "
              f"({len(rest['results'])} рез.), WS {ws['total_ms']} мс ({ws['results_count']} рез.)"
              + (f", ПОМИЛКА: {rest['error'] or ws['error']}" if rest["error"] or ws["error"] else ""),
              file=sys.stderr)

    started = datetime.datetime.now().astimezone().isoformat(timespec="seconds")
    records = collect(queries,
                      lambda query: rest_search(base, query, args.timeout),
                      lambda query: ws_search(base, query, args.timeout),
                      progress)
    meta = {"started": started, "url": base, "health": health, "no_ollama": args.no_ollama,
            "documents": stats.get("documents"), "threads": stats.get("threads_used")}

    Path(args.out_dir).mkdir(parents=True, exist_ok=True)
    write_csv(csv_path, records)
    md_path.write_text(render_markdown(records, meta), encoding="utf-8")
    print(f"Записано: {csv_path}\n          {md_path}", file=sys.stderr)

    if args.no_ollama:
        problems = [(record["query"]["id"], text) for record in records
                    for run in record["runs"] for text in fallback_issues(run)]
        if problems:
            print(f"Fallback ПОРУШЕНО ({len(problems)}):", file=sys.stderr)
            for query_id, text in problems:
                print(f"  {query_id}: {text}", file=sys.stderr)
            if any("expanded_terms не порожній" in text for _, text in problems):
                print("Підказка: непорожнє розширення при вимкненій Ollama зазвичай означає кеш "
                      "розширень сервера від попереднього запуску. Перезапустіть сервер "
                      "(`docker compose restart server`), проіндексуйте знову і повторіть.",
                      file=sys.stderr)
            return 1
        print("Fallback працює: розширення порожні, WS дає ті самі результати, що REST.",
              file=sys.stderr)
    return 0


if __name__ == "__main__":
    sys.exit(main())
