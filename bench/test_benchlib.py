"""Unit tests for the helpers (no server, no Docker).

Run from the repository root:
    python -m unittest discover -s bench -p "test_*.py" -v

All numbers below are made-up test inputs, not measurements.
"""

import random
import tempfile
import unittest
from pathlib import Path

import benchlib
import summarize

LINE = (
    "[main] INFO ua.coursework.engine.index.IndexManager - Index ready: 120 documents (chunks) "
    "from 30 books, 4000 terms, 90000 postings, 1500 ms, 8 threads. "
    "Heap used (approx., may include garbage): 300 MB of max 6000 MB"
)


class PercentileTest(unittest.TestCase):
    def test_nearest_rank_on_1_to_100(self):
        data = list(range(1, 101))
        self.assertEqual(50, benchlib.percentile(data, 50))
        self.assertEqual(95, benchlib.percentile(data, 95))
        self.assertEqual(99, benchlib.percentile(data, 99))
        self.assertEqual(100, benchlib.percentile(data, 100))
        self.assertEqual(1, benchlib.percentile(data, 0))

    def test_rounds_up_on_small_samples(self):
        self.assertEqual(10, benchlib.percentile(range(1, 11), 95))  # ceil(9.5) = 10
        self.assertEqual(5, benchlib.percentile(range(1, 11), 50))

    def test_float_noise_does_not_change_the_rank(self):
        # 16.1 * 1000 / 100 is 161.00000000000003 in floating point; the rank must still be 161
        self.assertEqual(161, benchlib.percentile(range(1, 1001), 16.1))

    def test_unsorted_input_and_single_value(self):
        self.assertEqual(3, benchlib.percentile([5, 1, 3], 50))
        self.assertEqual(42, benchlib.percentile([42], 99))

    def test_errors(self):
        with self.assertRaises(ValueError):
            benchlib.percentile([], 50)
        with self.assertRaises(ValueError):
            benchlib.percentile([1], 101)
        with self.assertRaises(ValueError):
            benchlib.percentile([1], -1)


class StatsTest(unittest.TestCase):
    def test_summarize_latencies(self):
        s = benchlib.summarize_latencies(range(1, 101))
        self.assertEqual(100, s["n"])
        self.assertEqual((1, 100), (s["min"], s["max"]))
        self.assertAlmostEqual(50.5, s["mean"])
        self.assertEqual((50, 95, 99), (s["p50"], s["p95"], s["p99"]))
        with self.assertRaises(ValueError):
            benchlib.summarize_latencies([])

    def test_spread(self):
        self.assertEqual((3, 1, 9), benchlib.spread([9, 1, 3]))
        with self.assertRaises(ValueError):
            benchlib.spread([])

    def test_throughput(self):
        self.assertEqual(50.0, benchlib.throughput(100, 2.0))
        with self.assertRaises(ValueError):
            benchlib.throughput(10, 0)

    def test_speedup_and_efficiency(self):
        table = benchlib.speedup_table({1: 800.0, 2: 400.0, 4: 250.0})
        self.assertEqual([1, 2, 4], [r["threads"] for r in table])
        self.assertAlmostEqual(1.0, table[0]["speedup"])
        self.assertAlmostEqual(2.0, table[1]["speedup"])
        self.assertAlmostEqual(1.0, table[1]["efficiency"])
        self.assertAlmostEqual(3.2, table[2]["speedup"])
        self.assertAlmostEqual(0.8, table[2]["efficiency"])

    def test_speedup_needs_baseline(self):
        with self.assertRaises(ValueError):
            benchlib.speedup_table({2: 400.0, 4: 250.0})


class IndexReadyTest(unittest.TestCase):
    def test_parses_all_fields(self):
        info = benchlib.parse_index_ready(LINE)
        self.assertEqual(
            {"documents": 120, "books": 30, "terms": 4000, "postings": 90000, "ms": 1500,
             "threads": 8, "heap_mb": 300, "heap_max_mb": 6000},
            info)

    def test_line_without_heap_part(self):
        info = benchlib.parse_index_ready(
            "Index ready: 5 documents (chunks) from 2 books, 10 terms, 20 postings, 7 ms, 1 threads.")
        self.assertEqual(5, info["documents"])
        self.assertIsNone(info["heap_mb"])

    def test_takes_the_last_line_and_ignores_noise(self):
        older = LINE.replace("120 documents", "99 documents")
        text = f"start\n{older}\nWARN something\n{LINE}\ntail\n"
        self.assertEqual(120, benchlib.parse_index_ready(text)["documents"])
        self.assertEqual(2, benchlib.count_index_ready(text))

    def test_no_match(self):
        self.assertIsNone(benchlib.parse_index_ready("nothing here"))
        self.assertIsNone(benchlib.parse_index_ready(""))
        self.assertEqual(0, benchlib.count_index_ready("Index ready: broken"))

    def test_new_line_only_if_count_grew(self):
        text = f"{LINE}\n"
        self.assertIsNone(benchlib.new_index_ready(1, text))
        self.assertEqual(8, benchlib.new_index_ready(0, text)["threads"])
        self.assertEqual(8, benchlib.new_index_ready(1, text + LINE)["threads"])

    def test_warmup_markers(self):
        text = ("a\nOllama model warmed up in 900 ms\nb\n"
                "Model warm-up failed (ignored): x\n")
        self.assertEqual(["warmed", "failed"], benchlib.warmup_markers(text))
        self.assertEqual([], benchlib.warmup_markers("nothing"))


class ConsistencyTest(unittest.TestCase):
    COUNTS = {"documents": 10, "terms": 20, "postings": 30, "ms": 5}

    def test_same_counts_pass(self):
        checker = benchlib.ConsistencyChecker()
        checker.check("all", self.COUNTS)
        checker.check("all", dict(self.COUNTS, ms=999))  # time may differ

    def test_different_postings_raise(self):
        checker = benchlib.ConsistencyChecker()
        checker.check("all", self.COUNTS)
        with self.assertRaises(benchlib.ConsistencyError) as ctx:
            checker.check("all", dict(self.COUNTS, postings=29), "threads=4")
        self.assertIn("postings", str(ctx.exception))
        self.assertIn("гонки", str(ctx.exception))

    def test_groups_are_independent(self):
        checker = benchlib.ConsistencyChecker()
        checker.check("small", self.COUNTS)
        checker.check("big", dict(self.COUNTS, documents=11))  # another dataset: fine


class PlanTest(unittest.TestCase):
    def test_every_repeat_has_all_values_and_orders_differ(self):
        plan = benchlib.make_order([1, 2, 4, 8, 16], 5, random.Random(1))
        self.assertEqual(5, len(plan))
        for order in plan:
            self.assertEqual([1, 2, 4, 8, 16], sorted(order))
        for a, b in zip(plan, plan[1:]):
            self.assertNotEqual(a, b)

    def test_same_seed_same_plan(self):
        a = benchlib.make_order(range(1, 6), 3, random.Random(7))
        b = benchlib.make_order(range(1, 6), 3, random.Random(7))
        self.assertEqual(a, b)

    def test_two_values_alternate_and_one_value_is_allowed(self):
        plan = benchlib.make_order([1, 2], 4, random.Random(3))
        for a, b in zip(plan, plan[1:]):
            self.assertNotEqual(a, b)
        self.assertEqual([[5], [5]], benchlib.make_order([5], 2, random.Random(3)))

    def test_parse_int_list(self):
        self.assertEqual([1, 2, 4], benchlib.parse_int_list("1, 2,4,2"))
        for bad in ("", "a", "0", "1,-2"):
            with self.assertRaises(ValueError):
                benchlib.parse_int_list(bad)

    def test_parse_datasets(self):
        self.assertEqual([("A", "x/a"), ("B", "data")], benchlib.parse_datasets("A=x/a, B=data"))
        for bad in ("", "A", "A=", "=x", "A=x,A=y"):
            with self.assertRaises(ValueError):
                benchlib.parse_datasets(bad)

    def test_query_for_spreads_clients_and_stays_in_range(self):
        self.assertEqual(0, benchlib.query_for(0, 0, 30))
        self.assertEqual(7, benchlib.query_for(1, 0, 30))
        self.assertEqual(1, benchlib.query_for(0, 31, 30))
        starts = {benchlib.query_for(c, 0, 30) for c in range(8)}
        self.assertEqual(8, len(starts))

    def test_load_queries_skips_comments_and_blank_lines(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "q.txt"
            path.write_text("# comment\n\nwhale\n  Sherlock Holmes  \n", encoding="utf-8")
            self.assertEqual(["whale", "Sherlock Holmes"], benchlib.load_queries(path))
            path.write_text("# only comments\n", encoding="utf-8")
            with self.assertRaises(ValueError):
                benchlib.load_queries(path)

    def test_the_real_queries_file_is_usable(self):
        queries = benchlib.load_queries(Path(__file__).with_name("queries.txt"))
        self.assertGreaterEqual(len(queries), 28)
        self.assertEqual(len(queries), len(set(queries)))


class CsvTest(unittest.TestCase):
    def test_roundtrip_and_header_written_once(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "sub" / "x.csv"
            fields = ["a", "b"]
            benchlib.append_rows(path, fields, [{"a": 1, "b": "x"}])
            benchlib.append_rows(path, fields, [{"a": 2, "b": "y"}])
            self.assertEqual([{"a": "1", "b": "x"}, {"a": "2", "b": "y"}], benchlib.read_csv(path))

    def test_missing_and_header_only_files_are_empty_data(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "x.csv"
            with self.assertRaises(benchlib.EmptyDataError):
                benchlib.read_csv(path)
            path.write_text("a,b\n", encoding="utf-8")
            with self.assertRaises(benchlib.EmptyDataError):
                benchlib.read_csv(path)

    def test_unknown_column_is_an_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            with self.assertRaises(ValueError):
                benchlib.append_rows(Path(tmp) / "x.csv", ["a"], [{"a": 1, "zzz": 2}])

    def test_backup_moves_the_old_file_aside(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "x.csv"
            self.assertIsNone(benchlib.backup_existing(path))
            path.write_text("a\n1\n", encoding="utf-8")
            moved = benchlib.backup_existing(path)
            self.assertFalse(path.exists())
            self.assertTrue(moved.name.endswith(".csv.tmp"))


def index_row(threads, time_ms, cold=0, wall=None, heap=100, docs=10, terms=20, postings=30):
    return {"threads": str(threads), "cold": str(cold), "time_ms": str(time_ms),
            "wall_ms": str(wall if wall is not None else time_ms + 5),
            "documents_indexed": str(docs), "terms": str(terms), "postings": str(postings),
            "heap_mb": str(heap)}


class IndexSummaryTest(unittest.TestCase):
    def test_medians_speedup_efficiency(self):
        rows = [index_row(1, 900), index_row(1, 800), index_row(1, 1000),
                index_row(2, 450), index_row(2, 500), index_row(2, 400)]
        s = benchlib.index_thread_summary(rows)
        self.assertEqual([1, 2], [e["threads"] for e in s])
        self.assertEqual(900, s[0]["median_ms"])
        self.assertEqual(450, s[1]["median_ms"])
        self.assertAlmostEqual(2.0, s[1]["speedup"])
        self.assertAlmostEqual(1.0, s[1]["efficiency"])
        self.assertAlmostEqual(900 / 500, s[1]["speedup_lo"])  # slowest run
        self.assertAlmostEqual(900 / 400, s[1]["speedup_hi"])  # fastest run

    def test_cold_run_is_kept_apart(self):
        rows = [index_row(4, 5000, cold=1), index_row(4, 300), index_row(4, 340),
                index_row(1, 900)]
        s = benchlib.index_thread_summary(rows)
        four = next(e for e in s if e["threads"] == 4)
        self.assertEqual(2, four["runs"])
        self.assertEqual(1, four["cold_runs"])
        self.assertEqual([5000.0], four["cold_times"])
        self.assertEqual(320, four["median_ms"])  # the cold 5000 is not in the median
        self.assertFalse(four["only_cold"])

    def test_only_cold_run_is_used_and_marked(self):
        s = benchlib.index_thread_summary([index_row(1, 900, cold=1)])
        self.assertTrue(s[0]["only_cold"])
        self.assertEqual(900, s[0]["median_ms"])

    def test_no_baseline_gives_no_speedup(self):
        s = benchlib.index_thread_summary([index_row(2, 400), index_row(4, 250)])
        self.assertTrue(all(e["speedup"] is None and e["efficiency"] is None for e in s))

    def test_size_summary_is_ordered_by_size(self):
        def row(name, mb, t):
            r = index_row(8, t)
            r.update(dataset=name, files="3", size_mb=str(mb))
            return r
        s = benchlib.index_size_summary([row("big", 900, 90), row("small", 100, 10),
                                         row("small", 100, 12)])
        self.assertEqual(["small", "big"], [e["dataset"] for e in s])
        self.assertEqual(11, s[0]["median_ms"])


class SearchSummaryTest(unittest.TestCase):
    @staticmethod
    def req(clients, latency, status="200", results="3"):
        return {"clients": str(clients), "latency_ms": str(latency), "status": status,
                "results": results}

    def test_throughput_latency_errors_and_empty_share(self):
        requests = [self.req(2, v) for v in range(1, 101)]
        requests.append(self.req(2, 0, status="ERR", results="-1"))
        requests[0]["results"] = "0"
        runs = [{"clients": "2", "ok": "100", "wall_s": "2.0"},
                {"clients": "2", "ok": "100", "wall_s": "4.0"}]
        entry = benchlib.search_summary(requests, runs)[0]
        self.assertEqual(100, entry["ok"])
        self.assertEqual(1, entry["errors"])
        self.assertEqual((25.0, 50.0), (entry["rps_min"], entry["rps_max"]))
        self.assertEqual(37.5, entry["rps_median"])
        self.assertEqual(95, entry["latency"]["p95"])
        self.assertAlmostEqual(1.0, entry["empty_pct"])

    def test_missing_run_rows_is_an_error(self):
        with self.assertRaises(ValueError):
            benchlib.search_summary([self.req(1, 5)], [])

    def test_ws_summary_counts_fallbacks_and_errors(self):
        def row(clients, exp, terms, error=""):
            return {"clients": str(clients), "expansion_ms": str(exp), "expanded_terms": str(terms),
                    "search_ms": "3", "total_ms": str(exp + 5), "error": error}
        rows = [row(2, 100, 4), row(2, 300, 0), row(2, 0, 0, error="timeout")]
        entry = benchlib.ws_summary(rows)[0]
        self.assertEqual((3, 1, 1), (entry["queries"], entry["errors"], entry["fallbacks"]))
        self.assertEqual(200, entry["expansion_median_ms"])


class MarkdownTest(unittest.TestCase):
    def test_table(self):
        text = benchlib.markdown_table(["a", "b"], [[1, "x"], [2, "y"]])
        self.assertEqual("| a | b |\n|---|---|\n| 1 | x |\n| 2 | y |", text)

    def test_fmt(self):
        self.assertEqual("-", benchlib.fmt(None))
        self.assertEqual("1 234", benchlib.fmt(1234))
        self.assertEqual("1 234.57", benchlib.fmt(1234.567, 2))


class SummarizeTest(unittest.TestCase):
    def test_no_data_returns_none(self):
        with tempfile.TemporaryDirectory() as tmp:
            self.assertIsNone(summarize.build_summary(Path(tmp)))
            self.assertEqual(1, summarize.main(["--results-dir", tmp]))

    def test_summary_from_a_csv(self):
        with tempfile.TemporaryDirectory() as tmp:
            rows = [{"timestamp": "t", "repeat": 1, "position": 1, "threads": p, "cold": 0,
                     "time_ms": t, "wall_ms": t + 3, "documents_indexed": 10, "books": 2,
                     "terms": 20, "postings": 30, "log_ms": t, "heap_mb": 100, "heap_max_mb": 1000}
                    for p, t in ((1, 800), (2, 400))]
            benchlib.append_rows(Path(tmp) / "index_threads.csv", benchlib.INDEX_THREADS_FIELDS,
                                 rows)
            text = summarize.build_summary(Path(tmp))
            self.assertIn("| 2 | 1 | 400 | 400 | 400 | 403 | 2.00 | 1.00 | 100 |", text)
            self.assertIn("Немає даних", text)  # the other experiments have no CSV
            self.assertEqual(0, summarize.main(["--results-dir", tmp]))
            self.assertTrue((Path(tmp) / "summary.md").exists())


if __name__ == "__main__":
    unittest.main()
