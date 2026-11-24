"""Pure helpers for the benchmarks: statistics, log parsing, CSV, summaries.

Nothing here talks to Docker or to the server, so everything is covered by unit tests
(test_benchlib.py). Every number produced here is computed from rows read from the CSV files
that run_bench.py wrote; nothing is invented.
"""

from __future__ import annotations

import csv
import math
import random
import re
import statistics
import sys
from datetime import datetime
from pathlib import Path

# ---------------------------------------------------------------------------------------------
# CSV schemas (one row = one measurement)
# ---------------------------------------------------------------------------------------------

INDEX_THREADS_FIELDS = [
    "timestamp", "repeat", "position", "threads", "cold",
    "time_ms", "wall_ms", "documents_indexed",
    "books", "terms", "postings", "log_ms", "heap_mb", "heap_max_mb",
]
INDEX_SIZE_FIELDS = [
    "timestamp", "dataset", "files", "size_mb", "repeat", "position", "threads", "cold",
    "time_ms", "wall_ms", "documents_indexed",
    "books", "terms", "postings", "log_ms", "heap_mb", "heap_max_mb",
]
SEARCH_REQUEST_FIELDS = [
    "timestamp", "repeat", "clients", "client", "seq", "query", "latency_ms", "status", "results",
]
SEARCH_RUN_FIELDS = [
    "timestamp", "repeat", "clients", "requests_per_client", "ok", "errors", "wall_s",
    "index_threads", "documents_indexed",
]
WS_FIELDS = [
    "timestamp", "repeat", "clients", "client", "query",
    "expansion_ms", "expanded_terms", "search_ms", "total_ms", "results", "error",
]

# The line that IndexManager writes after every build (see IndexManager.logSummary).
INDEX_READY_RE = re.compile(
    r"Index ready: (?P<documents>\d+) documents \(chunks\) from (?P<books>\d+) books, "
    r"(?P<terms>\d+) terms, (?P<postings>\d+) postings, (?P<ms>\d+) ms, "
    r"(?P<threads>\d+) threads\."
    r"(?:\s*Heap used[^:]*:\s*(?P<heap_mb>\d+) MB of max (?P<heap_max_mb>\d+) MB)?"
)
# Lines that Main writes when the background model loader has finished.
WARMUP_RE = re.compile(r"Ollama model warmed up|Model warm-up failed")


class EmptyDataError(Exception):
    """A CSV file is missing or has no data rows."""


class ConsistencyError(Exception):
    """Runs that must give the same index (same data) gave different counts."""


def setup_console() -> None:
    """Print UTF-8 even when the Windows console uses another code page."""
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(encoding="utf-8", errors="replace")
        except (AttributeError, ValueError):
            pass


# ---------------------------------------------------------------------------------------------
# Log parsing
# ---------------------------------------------------------------------------------------------


def _match_to_dict(match: re.Match) -> dict:
    result = {
        "documents": int(match["documents"]),
        "books": int(match["books"]),
        "terms": int(match["terms"]),
        "postings": int(match["postings"]),
        "ms": int(match["ms"]),
        "threads": int(match["threads"]),
        "heap_mb": None,
        "heap_max_mb": None,
    }
    if match["heap_mb"] is not None:
        result["heap_mb"] = int(match["heap_mb"])
        result["heap_max_mb"] = int(match["heap_max_mb"])
    return result


def parse_index_ready(text: str) -> dict | None:
    """Parses the LAST 'Index ready:' line in the text; None if there is none."""
    matches = list(INDEX_READY_RE.finditer(text))
    return _match_to_dict(matches[-1]) if matches else None


def count_index_ready(text: str) -> int:
    return sum(1 for _ in INDEX_READY_RE.finditer(text))


def new_index_ready(count_before: int, text: str) -> dict | None:
    """The newest 'Index ready:' line, but only if there are more of them than before."""
    matches = list(INDEX_READY_RE.finditer(text))
    if len(matches) <= count_before:
        return None
    return _match_to_dict(matches[-1])


def warmup_markers(text: str) -> list[str]:
    """'warmed' or 'failed' for every model warm-up line, in log order."""
    return [
        "warmed" if "warmed up" in m.group(0) else "failed" for m in WARMUP_RE.finditer(text)
    ]


# ---------------------------------------------------------------------------------------------
# Statistics
# ---------------------------------------------------------------------------------------------


def percentile(values, p: float) -> float:
    """Nearest-rank percentile: the smallest value with at least p% of the data at or below it.

    p50 of 1..100 is 50, p95 is 95. Simple to explain; no interpolation.
    """
    data = sorted(values)
    if not data:
        raise ValueError("percentile of an empty list")
    if not 0 <= p <= 100:
        raise ValueError(f"p must be in 0..100, got {p}")
    rank = max(1, math.ceil(round(p * len(data) / 100, 9)))
    return data[rank - 1]


def spread(values) -> tuple[float, float, float]:
    """(median, min, max)."""
    data = list(values)
    if not data:
        raise ValueError("spread of an empty list")
    return statistics.median(data), min(data), max(data)


def summarize_latencies(values) -> dict:
    data = [float(v) for v in values]
    if not data:
        raise ValueError("no latencies")
    return {
        "n": len(data),
        "mean": statistics.fmean(data),
        "min": min(data),
        "max": max(data),
        "p50": percentile(data, 50),
        "p95": percentile(data, 95),
        "p99": percentile(data, 99),
    }


def throughput(ok_requests: int, wall_seconds: float) -> float:
    if wall_seconds <= 0:
        raise ValueError("wall time must be positive")
    return ok_requests / wall_seconds


def speedup_table(medians: dict[int, float]) -> list[dict]:
    """S(p) = T(1)/T(p) and E(p) = S(p)/p from median times. Needs the p=1 baseline."""
    if 1 not in medians:
        raise ValueError("the baseline (threads=1) is missing, speedup is undefined")
    base = medians[1]
    rows = []
    for p in sorted(medians):
        s = base / medians[p]
        rows.append({"threads": p, "speedup": s, "efficiency": s / p})
    return rows


# ---------------------------------------------------------------------------------------------
# Plans and parameters
# ---------------------------------------------------------------------------------------------


def make_order(values, repeats: int, rng: random.Random) -> list[list]:
    """For every repeat a shuffled copy of values; two neighbouring repeats never get the same
    order (when there are at least two values)."""
    values = list(values)
    plan: list[list] = []
    for _ in range(repeats):
        order = values[:]
        rng.shuffle(order)
        tries = 0
        while plan and len(values) > 1 and order == plan[-1] and tries < 100:
            rng.shuffle(order)
            tries += 1
        if plan and len(values) > 1 and order == plan[-1]:
            order = order[1:] + order[:1]  # practically unreachable; keeps the guarantee
        plan.append(order)
    return plan


def parse_int_list(text: str) -> list[int]:
    """'1,2, 4' -> [1, 2, 4]; positive integers only, duplicates dropped, order kept."""
    result: list[int] = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        try:
            value = int(part)
        except ValueError:
            raise ValueError(f"not an integer: {part!r}") from None
        if value < 1:
            raise ValueError(f"must be >= 1: {value}")
        if value not in result:
            result.append(value)
    if not result:
        raise ValueError("the list is empty")
    return result


def parse_datasets(text: str) -> list[tuple[str, str]]:
    """'D184MB=data_extra/D184MB,D1.7GB=data' -> [('D184MB', 'data_extra/D184MB'), ...]."""
    result: list[tuple[str, str]] = []
    for part in text.split(","):
        part = part.strip()
        if not part:
            continue
        name, sep, path = part.partition("=")
        if not sep or not name.strip() or not path.strip():
            raise ValueError(f"expected NAME=PATH, got {part!r}")
        if any(name.strip() == n for n, _ in result):
            raise ValueError(f"duplicate dataset name {name.strip()!r}")
        result.append((name.strip(), path.strip()))
    if not result:
        raise ValueError("no datasets")
    return result


def load_queries(path: Path) -> list[str]:
    queries = []
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#"):
            queries.append(line)
    if not queries:
        raise ValueError(f"no queries in {path}")
    return queries


def query_for(client: int, seq: int, total: int) -> int:
    """Index of the query for request number seq of a client. Clients start at different places
    of the list, so that they do not all send the same query at the same moment."""
    return (client * 7 + seq) % total


class ConsistencyChecker:
    """The index must not depend on the number of threads. The first run of a group is the
    reference; any later run with other counts is a sign of a race (or of other data)."""

    FIELDS = ("documents", "terms", "postings")

    def __init__(self) -> None:
        self._reference: dict[str, dict] = {}

    def check(self, group: str, counts: dict, label: str = "") -> None:
        current = {name: counts[name] for name in self.FIELDS}
        reference = self._reference.setdefault(group, current)
        if reference != current:
            diffs = ", ".join(
                f"{name}: було {reference[name]}, стало {current[name]}"
                for name in self.FIELDS
                if reference[name] != current[name]
            )
            raise ConsistencyError(
                f"Різні результати індексації для групи '{group}' ({label}): {diffs}. "
                "Індекс не повинен залежати від кількості потоків: це ознака гонки."
            )


# ---------------------------------------------------------------------------------------------
# CSV
# ---------------------------------------------------------------------------------------------


def append_rows(path: Path, fields: list[str], rows: list[dict]) -> None:
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    new_file = not path.exists() or path.stat().st_size == 0
    with path.open("a", newline="", encoding="utf-8") as handle:
        writer = csv.DictWriter(handle, fieldnames=fields, extrasaction="raise", lineterminator="\n")
        if new_file:
            writer.writeheader()
        writer.writerows(rows)


def read_csv(path: Path) -> list[dict]:
    """All data rows; EmptyDataError if the file is missing or has only a header."""
    path = Path(path)
    if not path.exists():
        raise EmptyDataError(f"Файл {path} не знайдено. Спочатку запустіть відповідний замір.")
    with path.open(newline="", encoding="utf-8") as handle:
        rows = list(csv.DictReader(handle))
    if not rows:
        raise EmptyDataError(f"Файл {path} порожній (немає жодного виміру).")
    return rows


def backup_existing(path: Path) -> Path | None:
    """Moves an old CSV aside (name ends with .tmp, so git ignores it); returns the new path."""
    path = Path(path)
    if not path.exists() or path.stat().st_size == 0:
        return None
    stamp = datetime.now().strftime("%Y%m%d-%H%M%S")
    target = path.with_name(f"{path.stem}.{stamp}{path.suffix}.tmp")
    path.rename(target)
    return target


def now_iso() -> str:
    return datetime.now().isoformat(timespec="seconds")


# ---------------------------------------------------------------------------------------------
# Summaries computed from CSV rows (used by plot.py and summarize.py)
# ---------------------------------------------------------------------------------------------


def _group(rows: list[dict], key) -> dict:
    groups: dict = {}
    for row in rows:
        groups.setdefault(key(row), []).append(row)
    return groups


def _index_group_stats(group: list[dict]) -> dict:
    """Statistics of runs of one configuration. Cold runs (first run on a cold file cache) are
    kept apart; the statistics use warm runs, and all runs only if there are no warm ones."""
    warm = [r for r in group if r["cold"] != "1"]
    cold = [r for r in group if r["cold"] == "1"]
    used = warm or group
    times = [float(r["time_ms"]) for r in used]
    median, low, high = spread(times)
    heaps = [float(r["heap_mb"]) for r in used if r.get("heap_mb") not in (None, "")]
    first = used[0]
    return {
        "runs": len(used),
        "cold_runs": len(cold),
        "only_cold": not warm,
        "cold_times": [float(r["time_ms"]) for r in cold],
        "median_ms": median,
        "min_ms": low,
        "max_ms": high,
        "wall_median_ms": statistics.median(float(r["wall_ms"]) for r in used),
        "documents": int(first["documents_indexed"]),
        "terms": int(first["terms"]),
        "postings": int(first["postings"]),
        "heap_mb_median": statistics.median(heaps) if heaps else None,
    }


def index_thread_summary(rows: list[dict]) -> list[dict]:
    """One entry per threads value, with speedup and efficiency if threads=1 is present."""
    entries = []
    for p, group in sorted(_group(rows, lambda r: int(r["threads"])).items()):
        entry = _index_group_stats(group)
        entry["threads"] = p
        entries.append(entry)
    base = next((e for e in entries if e["threads"] == 1), None)
    for e in entries:
        if base is None:
            e.update(speedup=None, speedup_lo=None, speedup_hi=None, efficiency=None)
            continue
        p = e["threads"]
        e["speedup"] = base["median_ms"] / e["median_ms"]
        e["speedup_lo"] = base["median_ms"] / e["max_ms"]  # slowest run -> smallest speedup
        e["speedup_hi"] = base["median_ms"] / e["min_ms"]
        e["efficiency"] = e["speedup"] / p
    return entries


def index_size_summary(rows: list[dict]) -> list[dict]:
    """One entry per dataset, ordered by size."""
    entries = []
    for name, group in _group(rows, lambda r: r["dataset"]).items():
        entry = _index_group_stats(group)
        entry["dataset"] = name
        entry["files"] = int(group[0]["files"])
        entry["size_mb"] = float(group[0]["size_mb"])
        entry["threads"] = int(group[0]["threads"])
        entries.append(entry)
    entries.sort(key=lambda e: e["size_mb"])
    return entries


def search_summary(request_rows: list[dict], run_rows: list[dict]) -> list[dict]:
    """One entry per number of clients: throughput over repeats, latency over all requests."""
    entries = []
    requests_by_clients = _group(request_rows, lambda r: int(r["clients"]))
    runs_by_clients = _group(run_rows, lambda r: int(r["clients"]))
    for clients in sorted(requests_by_clients):
        requests = requests_by_clients[clients]
        ok = [r for r in requests if r["status"] == "200"]
        errors = len(requests) - len(ok)
        if not ok:
            raise ValueError(f"no successful requests for {clients} clients")
        latency = summarize_latencies(float(r["latency_ms"]) for r in ok)
        runs = runs_by_clients.get(clients, [])
        if not runs:
            raise ValueError(f"no run rows (wall time) for {clients} clients")
        rps = [throughput(int(r["ok"]), float(r["wall_s"])) for r in runs]
        rps_median, rps_min, rps_max = spread(rps)
        empty = sum(1 for r in ok if r["results"] == "0")
        entries.append({
            "clients": clients,
            "repeats": len(runs),
            "ok": len(ok),
            "errors": errors,
            "rps_median": rps_median,
            "rps_min": rps_min,
            "rps_max": rps_max,
            "latency": latency,
            "empty_pct": 100.0 * empty / len(ok),
        })
    return entries


def ws_summary(rows: list[dict]) -> list[dict]:
    """One entry per number of clients. A 'fallback' is a finished query without expansion."""
    entries = []
    for clients, group in sorted(_group(rows, lambda r: int(r["clients"])).items()):
        good = [r for r in group if not r["error"]]
        entry = {
            "clients": clients,
            "queries": len(group),
            "errors": len(group) - len(good),
            "fallbacks": sum(1 for r in good if r["expanded_terms"] == "0"),
            "expansion_median_ms": None,
            "expansion_max_ms": None,
            "search_median_ms": None,
            "total_median_ms": None,
        }
        if good:
            entry["expansion_median_ms"] = statistics.median(float(r["expansion_ms"]) for r in good)
            entry["expansion_max_ms"] = max(float(r["expansion_ms"]) for r in good)
            entry["search_median_ms"] = statistics.median(float(r["search_ms"]) for r in good)
            entry["total_median_ms"] = statistics.median(float(r["total_ms"]) for r in good)
        entries.append(entry)
    return entries


# ---------------------------------------------------------------------------------------------
# Markdown
# ---------------------------------------------------------------------------------------------


def fmt(value, digits: int = 1) -> str:
    """Number to text; '-' for a missing value."""
    if value is None:
        return "-"
    if isinstance(value, int):
        return f"{value:,}".replace(",", " ")
    return f"{value:,.{digits}f}".replace(",", " ")


def markdown_table(headers: list[str], rows: list[list]) -> str:
    cells = [[str(c) for c in row] for row in rows]
    lines = ["| " + " | ".join(headers) + " |", "|" + "|".join("---" for _ in headers) + "|"]
    lines += ["| " + " | ".join(row) + " |" for row in cells]
    return "\n".join(lines)
