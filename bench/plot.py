#!/usr/bin/env python3
"""Plots from the CSV files in bench/results/ (nothing is drawn from numbers typed by hand).

    python bench/plot.py            # every plot whose CSV exists; stops if there is no data at all
    python bench/plot.py threads    # index_time.png, index_speedup.png, index_efficiency.png
    python bench/plot.py size       # index_size.png
    python bench/plot.py search     # search_throughput.png, search_latency.png
    python bench/plot.py ws         # ws_stages.png

If the CSV of the chosen experiment is missing or empty, the script stops with a message.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import benchlib

BENCH_DIR = Path(__file__).resolve().parent
DPI = 150


def load_plt():
    try:
        import matplotlib

        matplotlib.use("Agg")  # no window: we only write PNG files
        import matplotlib.pyplot as plt
        from matplotlib.ticker import NullLocator, ScalarFormatter
    except ImportError:
        sys.exit("Не встановлено matplotlib. Виконайте: pip install matplotlib")
    return plt, NullLocator, ScalarFormatter


def docker_cores(results_dir: Path) -> int | None:
    """The number of CPUs that Docker gives to containers (from environment.json), if known."""
    path = results_dir / "environment.json"
    if not path.exists():
        return None
    try:
        info = json.loads(path.read_text(encoding="utf-8"))
        return info.get("docker", {}).get("NCPU")
    except ValueError:
        return None


def _log2_axis(ax, values, tools) -> None:
    _plt, null_locator, scalar_formatter = tools
    ax.set_xscale("log", base=2)
    ax.set_xticks(values)
    ax.xaxis.set_major_formatter(scalar_formatter())
    ax.xaxis.set_minor_locator(null_locator())


def _save(fig, results_dir: Path, name: str) -> None:
    path = results_dir / name
    fig.tight_layout()
    fig.savefig(path, dpi=DPI)
    print(f"Записано {path}")


def plot_threads(rows: list[dict], results_dir: Path, tools) -> None:
    plt = tools[0]
    entries = benchlib.index_thread_summary(rows)
    ps = [e["threads"] for e in entries]
    cores = docker_cores(results_dir)
    base = next((e for e in entries if e["threads"] == 1), None)

    # 1. time
    fig, ax = plt.subplots(figsize=(7, 4.6))
    ax.plot(ps, [e["median_ms"] for e in entries], "o-", label="медіана")
    ax.fill_between(ps, [e["min_ms"] for e in entries], [e["max_ms"] for e in entries],
                    alpha=0.25, label="мін–макс")
    if base:
        ax.plot(ps, [base["median_ms"] / p for p in ps], "--", color="gray",
                label="ідеально: T(1)/p")
    cold = [(e["threads"], t) for e in entries for t in e["cold_times"]]
    if cold:
        ax.scatter([c[0] for c in cold], [c[1] for c in cold], marker="x", color="red",
                   zorder=5, label="холодний прогін (окремо)")
    if cores and min(ps) <= cores <= max(ps):
        ax.axvline(cores, linestyle=":", color="black", label=f"ядра Docker: {cores}")
    _log2_axis(ax, ps, tools)
    ax.set_xlabel("Кількість потоків p")
    ax.set_ylabel("Час індексації, мс")
    ax.set_title("Індексація: час від кількості потоків")
    ax.set_ylim(bottom=0)
    ax.grid(True, alpha=0.3)
    ax.legend()
    _save(fig, results_dir, "index_time.png")
    plt.close(fig)

    if base is None:
        print("Немає прогонів із threads=1: прискорення й ефективність не обчислити "
              "(index_speedup.png, index_efficiency.png пропущено).")
        return

    # 2. speedup
    fig, ax = plt.subplots(figsize=(7, 4.6))
    ax.plot(ps, [e["speedup"] for e in entries], "o-", label="S(p) за медіаною")
    ax.fill_between(ps, [e["speedup_lo"] for e in entries], [e["speedup_hi"] for e in entries],
                    alpha=0.25, label="мін–макс")
    ax.plot(ps, ps, "--", color="gray", label="ідеально: S = p")
    if cores and min(ps) <= cores <= max(ps):
        ax.axvline(cores, linestyle=":", color="black", label=f"ядра Docker: {cores}")
    _log2_axis(ax, ps, tools)
    ax.set_xlabel("Кількість потоків p")
    ax.set_ylabel("Прискорення S(p) = T(1) / T(p)")
    ax.set_title("Індексація: прискорення")
    ax.grid(True, alpha=0.3)
    ax.legend()
    _save(fig, results_dir, "index_speedup.png")
    plt.close(fig)

    # 3. efficiency
    fig, ax = plt.subplots(figsize=(7, 4.6))
    ax.plot(ps, [e["efficiency"] for e in entries], "o-", label="E(p) = S(p) / p")
    ax.axhline(1.0, linestyle="--", color="gray", label="ідеально: E = 1")
    if cores and min(ps) <= cores <= max(ps):
        ax.axvline(cores, linestyle=":", color="black", label=f"ядра Docker: {cores}")
    _log2_axis(ax, ps, tools)
    ax.set_xlabel("Кількість потоків p")
    ax.set_ylabel("Ефективність E(p)")
    ax.set_title("Індексація: ефективність")
    ax.set_ylim(bottom=0)
    ax.grid(True, alpha=0.3)
    ax.legend()
    _save(fig, results_dir, "index_efficiency.png")
    plt.close(fig)


def plot_size(rows: list[dict], results_dir: Path, tools) -> None:
    plt = tools[0]
    entries = benchlib.index_size_summary(rows)
    fig, (left, right) = plt.subplots(1, 2, figsize=(11, 4.6), sharey=True)
    for ax, key, xlabel in (
        (left, "size_mb", "Розмір даних, МБ"),
        (right, "documents", "Кількість документів (чанків)"),
    ):
        xs = [e[key] for e in entries]
        med = [e["median_ms"] for e in entries]
        err = [[m - e["min_ms"] for m, e in zip(med, entries)],
               [e["max_ms"] - m for m, e in zip(med, entries)]]
        ax.errorbar(xs, med, yerr=err, fmt="o-", capsize=4, label="медіана, мін–макс")
        for x, y, e in zip(xs, med, entries):
            ax.annotate(e["dataset"], (x, y), textcoords="offset points", xytext=(5, -12),
                        fontsize=8)
        ax.set_xlabel(xlabel)
        ax.grid(True, alpha=0.3)
        ax.set_ylim(bottom=0)
    threads = {e["threads"] for e in entries}
    left.set_ylabel("Час індексації, мс")
    left.legend()
    fig.suptitle("Індексація: час від розміру даних"
                 + (f" ({threads.pop()} потоків)" if len(threads) == 1 else ""))
    _save(fig, results_dir, "index_size.png")
    plt.close(fig)


def plot_search(request_rows: list[dict], run_rows: list[dict], results_dir: Path, tools) -> None:
    plt = tools[0]
    entries = benchlib.search_summary(request_rows, run_rows)
    ms = [e["clients"] for e in entries]

    fig, ax = plt.subplots(figsize=(7, 4.6))
    ax.plot(ms, [e["rps_median"] for e in entries], "o-", label="медіана")
    ax.fill_between(ms, [e["rps_min"] for e in entries], [e["rps_max"] for e in entries],
                    alpha=0.25, label="мін–макс за повторами")
    _log2_axis(ax, ms, tools)
    ax.set_xlabel("Кількість клієнтів M")
    ax.set_ylabel("Пропускна здатність, запитів/с")
    ax.set_title("Пошук: пропускна здатність")
    ax.set_ylim(bottom=0)
    ax.grid(True, alpha=0.3)
    ax.legend()
    _save(fig, results_dir, "search_throughput.png")
    plt.close(fig)

    fig, ax = plt.subplots(figsize=(7, 4.6))
    for key, style in (("p50", "o-"), ("p95", "s-"), ("p99", "^-")):
        ax.plot(ms, [e["latency"][key] for e in entries], style, label=key)
    _log2_axis(ax, ms, tools)
    ax.set_xlabel("Кількість клієнтів M")
    ax.set_ylabel("Затримка запиту, мс")
    ax.set_title("Пошук: перцентилі затримки")
    ax.set_ylim(bottom=0)
    ax.grid(True, alpha=0.3)
    ax.legend()
    _save(fig, results_dir, "search_latency.png")
    plt.close(fig)


def plot_ws(rows: list[dict], results_dir: Path, tools) -> None:
    plt = tools[0]
    entries = [e for e in benchlib.ws_summary(rows) if e["expansion_median_ms"] is not None]
    if not entries:
        sys.exit("У ws_stages.csv усі запити завершились помилкою: графік неможливий.")
    xs = list(range(len(entries)))
    width = 0.38
    fig, ax = plt.subplots(figsize=(7, 4.6))
    ax.bar([x - width / 2 for x in xs], [e["expansion_median_ms"] for e in entries], width,
           label="query_expansion (до отримання етапу), медіана")
    ax.bar([x + width / 2 for x in xs], [e["search_median_ms"] for e in entries], width,
           label="keyword_search (time_ms сервера), медіана")
    for x, e in zip(xs, entries):
        ax.annotate(f"без розширення: {e['fallbacks']}/{e['queries']}", (x, 0),
                    textcoords="offset points", xytext=(0, -28), ha="center", fontsize=8)
    ax.set_xticks(xs)
    ax.set_xticklabels([str(e["clients"]) for e in entries])
    ax.set_xlabel("Кількість одночасних WebSocket-клієнтів")
    ax.set_ylabel("Час, мс")
    ax.set_title("WebSocket: етапи пайплайна (семафор на Ollama)")
    ax.set_yscale("log")
    ax.grid(True, axis="y", alpha=0.3)
    ax.legend()
    _save(fig, results_dir, "ws_stages.png")
    plt.close(fig)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="Графіки з CSV")
    parser.add_argument("what", nargs="?", default="all",
                        choices=["all", "threads", "size", "search", "ws"])
    parser.add_argument("--results-dir", default=str(BENCH_DIR / "results"))
    return parser


def main(argv: list[str] | None = None) -> int:
    benchlib.setup_console()
    args = build_parser().parse_args(argv)
    results_dir = Path(args.results_dir)
    explicit = args.what != "all"
    wanted = ["threads", "size", "search", "ws"] if not explicit else [args.what]
    tools = None
    drawn = 0
    skipped = []
    for name in wanted:
        files = {"threads": ["index_threads.csv"], "size": ["index_size.csv"],
                 "search": ["search_requests.csv", "search_runs.csv"], "ws": ["ws_stages.csv"]}[name]
        try:
            data = [benchlib.read_csv(results_dir / f) for f in files]
        except benchlib.EmptyDataError as e:
            if explicit:
                print(f"Зупинено: {e}", file=sys.stderr)
                return 1
            skipped.append(str(e))
            continue
        if tools is None:
            tools = load_plt()
        try:
            if name == "threads":
                plot_threads(data[0], results_dir, tools)
            elif name == "size":
                plot_size(data[0], results_dir, tools)
            elif name == "search":
                plot_search(data[0], data[1], results_dir, tools)
            else:
                plot_ws(data[0], results_dir, tools)
        except ValueError as e:
            print(f"Зупинено ({name}): {e}", file=sys.stderr)
            return 1
        drawn += 1
    for message in skipped:
        print(f"Пропущено: {message}")
    if drawn == 0:
        print("Зупинено: немає жодного непорожнього CSV у " + str(results_dir), file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
