#!/usr/bin/env python3
"""Builds bench/results/summary.md (tables of medians, speedup, efficiency) from the CSV files.

    python bench/summarize.py

A section whose CSV is missing or empty is replaced by a note; if there is no data at all, the
script stops with a message. All numbers are computed from the CSV files.
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import benchlib
from benchlib import fmt, markdown_table

BENCH_DIR = Path(__file__).resolve().parent


def environment_section(results_dir: Path) -> str:
    path = results_dir / "environment.json"
    if not path.exists():
        return "_environment.json не знайдено (запустіть `python bench/run_bench.py env`)._"
    info = json.loads(path.read_text(encoding="utf-8"))
    docker = info.get("docker", {})
    rows = [
        ["Дата", info.get("date")],
        ["ОС", info.get("os")],
        ["Ядра хоста", info.get("host_cpu_cores")],
        ["ОЗУ хоста, МБ", info.get("host_ram_mb")],
        ["Docker: ядра (NCPU)", docker.get("NCPU")],
        ["Docker: пам'ять, МБ",
         round(docker["MemTotal"] / (1024 * 1024)) if docker.get("MemTotal") else None],
        ["Docker: версія", docker.get("ServerVersion")],
        ["CHUNK_SIZE_KB", info.get("chunk_size_kb")],
        ["Git", (info.get("git_commit") or "-")[:12] + (" (є незакомічені зміни)"
                                                      if info.get("git_dirty") else "")],
    ]
    table = markdown_table(["Параметр", "Значення"], [[a, "-" if b is None else b] for a, b in rows])
    datasets = info.get("datasets", {})
    if datasets:
        table += "\n\n" + markdown_table(
            ["Датасет", "Файлів .txt", "Розмір, МБ"],
            [[name, fmt(d["files"]), fmt(d["size_mb"])] for name, d in datasets.items()])
    return table


def threads_section(rows: list[dict]) -> str:
    entries = benchlib.index_thread_summary(rows)
    table = markdown_table(
        ["Потоки p", "Прогонів", "Медіана, мс", "Мін, мс", "Макс, мс", "Wall (клієнт), мс",
         "S(p)", "E(p)", "Купа, МБ*"],
        [[e["threads"], e["runs"], fmt(e["median_ms"], 0), fmt(e["min_ms"], 0),
          fmt(e["max_ms"], 0), fmt(e["wall_median_ms"], 0), fmt(e["speedup"], 2),
          fmt(e["efficiency"], 2), fmt(e["heap_mb_median"], 0)] for e in entries])
    first = entries[0]
    lines = [table, "",
             f"Документів (чанків): {fmt(first['documents'])}, термінів: {fmt(first['terms'])}, "
             f"постингів: {fmt(first['postings'])} (однакові для всіх p: це перевіряється під час "
             "заміру).", "",
             "S(p) = T(1) / T(p), E(p) = S(p) / p, T = медіана `time_ms` з відповіді сервера. "
             "*Купа: рядок `Index ready` у логу, наближено (може містити сміття)."]
    cold = [(e["threads"], e["cold_times"]) for e in entries if e["cold_times"]]
    for p, times in cold:
        lines += ["", f"Холодний прогін (перший, окремо від статистики): p={p}, "
                      f"time_ms={', '.join(fmt(t, 0) for t in times)}."]
    only_cold = [e["threads"] for e in entries if e["only_cold"]]
    if only_cold:
        lines += ["", f"Увага: для p={only_cold} є лише холодний прогін, його використано у таблиці."]
    if entries[0]["threads"] != 1:
        lines += ["", "Увага: немає прогонів з p=1, S(p) і E(p) не обчислено."]
    return "\n".join(lines)


def size_section(rows: list[dict]) -> str:
    entries = benchlib.index_size_summary(rows)
    table = markdown_table(
        ["Набір", "Файлів", "Розмір, МБ", "Документів", "Постингів", "Прогонів",
         "Медіана, мс", "Мін, мс", "Макс, мс"],
        [[e["dataset"], fmt(e["files"]), fmt(e["size_mb"]), fmt(e["documents"]),
          fmt(e["postings"]), e["runs"], fmt(e["median_ms"], 0), fmt(e["min_ms"], 0),
          fmt(e["max_ms"], 0)] for e in entries])
    threads = sorted({e["threads"] for e in entries})
    return table + f"\n\nПотоків індексації: {', '.join(map(str, threads))} (фіксовано)."


def search_section(request_rows: list[dict], run_rows: list[dict]) -> str:
    entries = benchlib.search_summary(request_rows, run_rows)
    table = markdown_table(
        ["Клієнтів M", "Повторів", "Запитів ok", "Помилок", "Запитів/с (медіана)",
         "мін–макс, запитів/с", "p50, мс", "p95, мс", "p99, мс", "Порожніх відповідей, %"],
        [[e["clients"], e["repeats"], fmt(e["ok"]), e["errors"], fmt(e["rps_median"], 1),
          f"{fmt(e['rps_min'], 1)}–{fmt(e['rps_max'], 1)}", fmt(e["latency"]["p50"], 1),
          fmt(e["latency"]["p95"], 1), fmt(e["latency"]["p99"], 1), fmt(e["empty_pct"], 1)]
         for e in entries])
    return (table + "\n\nПропускна здатність = успішні запити / час раунду; перцентили "
            "(найближчий ранг) по всіх запитах усіх повторів.")


def ws_section(rows: list[dict]) -> str:
    entries = benchlib.ws_summary(rows)
    return markdown_table(
        ["Клієнтів", "Запитів", "Помилок", "Без розширення (fallback)",
         "query_expansion, медіана, мс", "query_expansion, макс, мс",
         "keyword_search (сервер), медіана, мс", "Весь запит, медіана, мс"],
        [[e["clients"], e["queries"], e["errors"], e["fallbacks"],
          fmt(e["expansion_median_ms"], 0), fmt(e["expansion_max_ms"], 0),
          fmt(e["search_median_ms"], 0), fmt(e["total_median_ms"], 0)] for e in entries])


def build_summary(results_dir: Path) -> str | None:
    """The whole markdown text, or None if there is no measurement data at all."""
    sections = []
    found = 0

    def add(title: str, files: list[str], build) -> None:
        nonlocal found
        try:
            data = [benchlib.read_csv(results_dir / f) for f in files]
        except benchlib.EmptyDataError as e:
            sections.append(f"## {title}\n\n_Немає даних: {e}_")
            return
        found += 1
        sections.append(f"## {title}\n\n{build(*data)}")

    add("Індексація від кількості потоків", ["index_threads.csv"], threads_section)
    add("Індексація від розміру даних", ["index_size.csv"], size_section)
    add("Пошук від кількості клієнтів (REST)", ["search_requests.csv", "search_runs.csv"],
        search_section)
    add("WebSocket-пайплайн (необов'язково)", ["ws_stages.csv"], ws_section)
    if found == 0:
        return None
    head = ("# Результати бенчмарків\n\nУсі числа обчислено з CSV-файлів у цій папці "
            "(`run_bench.py` -> `summarize.py`).\n\n## Середовище\n\n"
            + environment_section(results_dir))
    return head + "\n\n" + "\n\n".join(sections) + "\n"


def main(argv: list[str] | None = None) -> int:
    benchlib.setup_console()
    parser = argparse.ArgumentParser(description="Створює summary.md")
    parser.add_argument("--results-dir", default=str(BENCH_DIR / "results"))
    args = parser.parse_args(argv)
    results_dir = Path(args.results_dir)
    try:
        text = build_summary(results_dir)
    except ValueError as e:
        print(f"Зупинено: {e}", file=sys.stderr)
        return 1
    if text is None:
        print(f"Зупинено: у {results_dir} немає жодного непорожнього CSV.", file=sys.stderr)
        return 1
    out = results_dir / "summary.md"
    out.write_text(text, encoding="utf-8")
    print(f"Записано {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
