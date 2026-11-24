import csv
import io
import json
import sys
import tempfile
import unittest
from pathlib import Path
from unittest import mock

sys.path.insert(0, str(Path(__file__).resolve().parent))
import run_queries as rq  # noqa: E402

QUERIES_FILE = Path(__file__).resolve().parent / "test_queries.json"


def hit(doc_id, score, snippet="some text"):
    return {"doc_id": doc_id, "score": score, "snippet": snippet}


def rest_ok(results, time_ms=5):
    return {"error": None, "time_ms": time_ms, "wall_ms": time_ms + 1.5, "results": results}


def ws_ok(results, terms=(), search_ms=3, expansion_ms=400.0, total_ms=410.0):
    events = [
        (expansion_ms, {"stage": "query_expansion",
                        "data": {"original": "q", "expanded_terms": list(terms)}}),
        (expansion_ms + 5, {"stage": "keyword_search",
                            "data": {"results": results, "time_ms": search_ms}}),
        (total_ms, {"stage": "done"}),
    ]
    return rq.parse_ws_events(events)


def run_of(rest, ws, index=1):
    return {"run": index, "label": rq.RUN_LABELS[index - 1], "rest": rest, "ws": ws}


def record(query="white whale", terms=("ship",), results=None, item_id="q01"):
    results = results if results is not None else [hit("a.txt#1", 1.0), hit("b.txt#2", 0.5)]
    item = {"id": item_id, "category": "2-3 слова", "query": query, "purpose": "test | purpose"}
    runs = [run_of(rest_ok(results), ws_ok(results, terms), 1),
            run_of(rest_ok(results, 2), ws_ok(results, terms, total_ms=20.0), 2)]
    return {"query": item, "runs": runs}


META = {"started": "2026-01-01T10:00:00+02:00", "url": "http://localhost:8080",
        "health": {"status": "ok", "ollama": True}, "no_ollama": False,
        "documents": 100, "threads": 4}


class LoadQueriesTest(unittest.TestCase):
    def write(self, content):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "q.json"
        path.write_text(content if isinstance(content, str) else json.dumps(content),
                        encoding="utf-8")
        return path

    def item(self, **changes):
        base = {"id": "q1", "category": "c", "query": "x", "purpose": "p"}
        base.update(changes)
        return base

    def test_the_real_file_is_valid_and_covers_the_required_categories(self):
        queries = rq.load_queries(QUERIES_FILE)
        self.assertGreaterEqual(len(queries), 20)
        self.assertLessEqual(len(queries), 30)
        self.assertEqual(len({q["id"] for q in queries}), len(queries))
        for item in queries:
            self.assertEqual(set(item), {"id", "category", "query", "purpose"})
        texts = {q["query"] for q in queries}
        for required in ("harpoon", "sherlock holmes", "pirates treasure island", "the and of",
                         "біла вага"):
            self.assertIn(required, texts)
        categories = {q["category"] for q in queries}
        self.assertGreaterEqual(len(categories), 12)

    def test_the_real_file_has_a_very_long_query_above_the_websocket_limit(self):
        longest = max(len(q["query"]) for q in rq.load_queries(QUERIES_FILE))
        self.assertGreater(longest, 1000)

    def test_rejects_bad_files(self):
        with self.assertRaises(rq.BenchError):
            rq.load_queries(Path("/no/such/file.json"))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write("{not json"))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write([]))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write({"id": "q1"}))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write(["text"]))

    def test_rejects_missing_blank_or_duplicate_fields(self):
        broken = self.item()
        del broken["purpose"]
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write([broken]))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write([self.item(query="   ")]))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write([self.item(category=5)]))
        with self.assertRaises(rq.BenchError):
            rq.load_queries(self.write([self.item(), self.item(query="y")]))

    def test_reads_utf8(self):
        path = self.write(json.dumps([self.item(query="біла вага")], ensure_ascii=False))
        self.assertEqual(rq.load_queries(path)[0]["query"], "біла вага")


class SmallHelpersTest(unittest.TestCase):
    def test_short_snippet(self):
        self.assertEqual(rq.short_snippet("a  b\n c"), "a b c")
        self.assertEqual(rq.short_snippet(None), "")
        cut = rq.short_snippet("word " * 100)
        self.assertLessEqual(len(cut), rq.SNIPPET_CHARS)
        self.assertTrue(cut.endswith("..."))
        self.assertEqual(rq.short_snippet("x" * rq.SNIPPET_CHARS), "x" * rq.SNIPPET_CHARS)

    def test_normalize_hits_keeps_top_five_only(self):
        hits = rq.normalize_hits([hit(f"d{i}", 1 - i / 10) for i in range(8)])
        self.assertEqual([h["doc_id"] for h in hits], ["d0", "d1", "d2", "d3", "d4"])
        self.assertEqual(rq.normalize_hits([]), [])

    def test_ws_url_from(self):
        self.assertEqual(rq.ws_url_from("http://localhost:8080"), "ws://localhost:8080/ws/search")
        self.assertEqual(rq.ws_url_from("http://h:1/"), "ws://h:1/ws/search")
        self.assertEqual(rq.ws_url_from("https://h"), "wss://h/ws/search")

    def test_md_escape_and_display_query(self):
        self.assertEqual(rq.md_escape("a|b\nc"), "a\\|b c")
        self.assertEqual(rq.display_query("white whale"), "white whale")
        shown = rq.display_query("word " * 300)
        self.assertIn("1500 символів", shown)
        self.assertLess(len(shown), 100)

    def test_fmt_pair(self):
        self.assertEqual(rq.fmt_pair(12, 3), "12 / 3")
        self.assertEqual(rq.fmt_pair(None, 3.5), "- / 3.5")

    def test_output_paths_differ_for_the_no_ollama_mode(self):
        normal = rq.output_paths("out", False)
        fallback = rq.output_paths("out", True)
        self.assertEqual(normal[0].name, "test-queries.csv")
        self.assertEqual(normal[1].name, "test-queries.md")
        self.assertNotEqual(normal, fallback)


class ParseWsEventsTest(unittest.TestCase):
    def test_full_answer(self):
        summary = ws_ok([hit("a#1", 1.0)], terms=["ship", "sea"], search_ms=7,
                        expansion_ms=1500.04, total_ms=1520.0)
        self.assertEqual(summary["stages"], rq.EXPECTED_STAGES)
        self.assertEqual(summary["expanded_terms"], ["ship", "sea"])
        self.assertEqual(summary["expansion_ms"], 1500.0)
        self.assertEqual(summary["search_ms"], 7)
        self.assertEqual(summary["total_ms"], 1520.0)
        self.assertEqual(summary["results_count"], 1)
        self.assertIsNone(summary["error"])

    def test_error_stage(self):
        summary = rq.parse_ws_events([
            (3.0, {"stage": "error", "data": {"message": "query is too long"}}),
            (3.5, {"stage": "done"})])
        self.assertEqual(summary["error"], "query is too long")
        self.assertIsNone(summary["expanded_terms"])
        self.assertIsNone(summary["results_count"])
        self.assertEqual(summary["total_ms"], 3.5)

    def test_connection_failure_without_messages(self):
        summary = rq.parse_ws_events([], "TimeoutError: no done")
        self.assertEqual(summary["error"], "TimeoutError: no done")
        self.assertIsNone(summary["total_ms"])
        self.assertEqual(summary["stages"], [])

    def test_empty_expansion_is_an_empty_list_not_none(self):
        self.assertEqual(ws_ok([], terms=[])["expanded_terms"], [])


class CheckHealthTest(unittest.TestCase):
    def test_normal_mode_needs_ollama(self):
        self.assertIsNone(rq.check_health({"status": "ok", "ollama": True}, False))
        self.assertIn("ollama == false", rq.check_health({"status": "ok", "ollama": False}, False))

    def test_no_ollama_mode_needs_ollama_to_be_down(self):
        self.assertIsNone(rq.check_health({"status": "ok", "ollama": False}, True))
        message = rq.check_health({"status": "ok", "ollama": True}, True)
        self.assertIn("--no-ollama", message)
        self.assertIn("docker compose stop ollama", message)

    def test_unexpected_health_answers(self):
        self.assertIsNotNone(rq.check_health({"status": "down", "ollama": False}, True))
        self.assertIsNotNone(rq.check_health({"ollama": False}, True))
        self.assertIsNotNone(rq.check_health("ok", False))
        self.assertIsNotNone(rq.check_health({"status": "ok"}, True))


class FallbackIssuesTest(unittest.TestCase):
    results = [hit("a#1", 1.0), hit("b#1", 0.4)]

    def test_correct_fallback_has_no_issues(self):
        run = run_of(rest_ok(self.results), ws_ok(self.results, terms=[]))
        self.assertEqual(rq.fallback_issues(run), [])

    def test_expansion_while_ollama_is_down_is_reported(self):
        run = run_of(rest_ok(self.results), ws_ok(self.results, terms=["ship"]))
        self.assertTrue(any("expanded_terms" in t for t in rq.fallback_issues(run)))

    def test_different_results_are_reported(self):
        run = run_of(rest_ok(self.results), ws_ok([hit("a#1", 1.0)], terms=[]))
        self.assertTrue(any("відрізняються" in t for t in rq.fallback_issues(run)))
        other_score = [hit("a#1", 1.0), hit("b#1", 0.3)]
        run = run_of(rest_ok(self.results), ws_ok(other_score, terms=[]))
        self.assertTrue(rq.fallback_issues(run))

    def test_websocket_without_search_stage_is_reported(self):
        broken = rq.parse_ws_events([], "ConnectionClosed")
        self.assertTrue(rq.fallback_issues(run_of(rest_ok(self.results), broken)))

    def test_rejected_query_is_not_judged(self):
        rejected = rq.parse_ws_events([(1.0, {"stage": "error", "data": {"message": "long"}}),
                                       (2.0, {"stage": "done"})])
        self.assertEqual(rq.fallback_issues(run_of(rest_ok(self.results), rejected)), [])

    def test_empty_results_on_both_sides_are_fine(self):
        run = run_of(rest_ok([]), ws_ok([], terms=[]))
        self.assertEqual(rq.fallback_issues(run), [])


class RunDifferencesTest(unittest.TestCase):
    def test_same_content_gives_no_notes(self):
        first, second = record()["runs"]
        self.assertEqual(rq.run_differences(first, second), [])

    def test_different_expansion_and_results_are_listed(self):
        first, _ = record(terms=["ship"])["runs"]
        second = record(terms=["boat"], results=[hit("z#1", 1.0)])["runs"][1]
        notes = rq.run_differences(first, second)
        self.assertTrue(any("розширені" in n for n in notes))
        self.assertTrue(any("REST" in n for n in notes))
        self.assertTrue(any("WS" in n for n in notes))

    def test_error_in_one_run_only(self):
        first, second = record()["runs"]
        second["rest"] = {"error": "HTTP 500", "time_ms": None, "wall_ms": None, "results": []}
        self.assertTrue(any("помилка" in n for n in rq.run_differences(first, second)))


class CollectTest(unittest.TestCase):
    def test_every_query_runs_twice_on_both_sides(self):
        calls = []

        def rest(query):
            calls.append(("rest", query))
            return rest_ok([])

        def ws(query):
            calls.append(("ws", query))
            return ws_ok([])

        queries = [{"id": "a", "category": "c", "query": "one", "purpose": "p"},
                   {"id": "b", "category": "c", "query": "two", "purpose": "p"}]
        seen = []
        records = rq.collect(queries, rest, ws, lambda n, total, item, run: seen.append((n, run["run"])))
        self.assertEqual(calls, [("rest", "one"), ("ws", "one"), ("rest", "one"), ("ws", "one"),
                                 ("rest", "two"), ("ws", "two"), ("rest", "two"), ("ws", "two")])
        self.assertEqual([[r["label"] for r in rec["runs"]] for rec in records],
                         [["холодний", "теплий"]] * 2)
        self.assertEqual(seen, [(1, 1), (1, 2), (2, 1), (2, 2)])


class OutputTest(unittest.TestCase):
    def read_csv(self, records):
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        path = Path(directory.name) / "x.csv"
        rq.write_csv(path, records)
        raw = path.read_bytes()
        self.assertTrue(raw.startswith(b"\xef\xbb\xbf"), "CSV must start with a UTF-8 BOM")
        return list(csv.reader(io.StringIO(raw.decode("utf-8-sig"), newline="")))

    def test_csv_has_one_row_per_query_and_run_with_empty_manual_columns(self):
        rows = self.read_csv([record(item_id="q01"), record(item_id="q02", query="біла вага")])
        header, body = rows[0], rows[1:]
        self.assertEqual(len(body), 4)
        self.assertEqual(header[-2:], ["relevance_student", "comment"])
        for row in body:
            self.assertEqual(len(row), len(header))
            self.assertEqual(row[-2:], ["", ""])
        self.assertEqual([r[5] for r in body[:2]], ["холодний", "теплий"])
        self.assertEqual(body[2][2], "біла вага")

    def test_csv_contains_top5_time_and_expansion_fields(self):
        many = [hit(f"d{i}#1", 1 - i / 10, f"snippet {i}") for i in range(7)]
        rows = self.read_csv([record(results=many, terms=["ship", "sea"])])
        header, first = rows[0], dict(zip(rows[0], rows[1]))
        self.assertEqual(first["expanded_terms"], "ship; sea")
        self.assertEqual(first["expanded_count"], "2")
        self.assertEqual(first["rest_results"], "7")
        self.assertEqual(first["rest_top5_doc_id"], "d4#1")
        self.assertEqual(first["ws_top1_snippet"], "snippet 0")
        self.assertNotIn("rest_top6_doc_id", header)
        self.assertEqual(first["rest_time_ms"], "5")
        self.assertEqual(first["ws_search_ms"], "3")
        self.assertEqual(first["ws_total_ms"], "410.0")

    def test_csv_missing_ranks_and_failed_calls_are_blank(self):
        failed = run_of({"error": "HTTP 500", "time_ms": None, "wall_ms": None, "results": []},
                        rq.parse_ws_events([], "TimeoutError: x"))
        item = {"id": "q", "category": "c", "query": "a, \"b\"\nc", "purpose": "p"}
        rows = self.read_csv([{"query": item, "runs": [failed, failed]}])
        row = dict(zip(rows[0], rows[1]))
        self.assertEqual(row["query"], "a, \"b\"\nc")
        self.assertEqual(row["rest_results"], "")
        self.assertEqual(row["rest_time_ms"], "")
        self.assertEqual(row["rest_error"], "HTTP 500")
        self.assertEqual(row["ws_error"], "TimeoutError: x")
        self.assertEqual(row["ws_top1_doc_id"], "")

    def table_rows(self, markdown):
        lines = markdown.splitlines()
        start = next(i for i, line in enumerate(lines) if line.startswith("| № | Категорія"))
        rows = []
        for line in lines[start + 2:]:
            if not line.startswith("|"):
                break
            rows.append([cell.strip() for cell in line.strip().strip("|").split(" | ")])
        return lines[start], rows

    def test_markdown_table_has_required_columns_and_empty_manual_cells(self):
        markdown = rq.render_markdown([record(), record(query="a|b", item_id="q02")], META)
        header, rows = self.table_rows(markdown)
        for column in ("№", "Категорія", "Запит", "Розширення", "Результатів", "Топ-1",
                       "REST", "WS", "Релевантність (оцінює студент)", "Коментар"):
            self.assertIn(column, header)
        self.assertEqual(len(rows), 2)
        for row in rows:
            self.assertEqual(row[-2:], ["", ""])
        self.assertEqual(rows[0][0], "1")
        self.assertEqual(rows[0][3], "ship")
        self.assertEqual(rows[0][4], "2 / 2")
        self.assertEqual(rows[0][5], "`a.txt#1` (1.0000)")
        self.assertEqual(rows[0][7], "5 / 2")
        self.assertEqual(rows[0][8], "410 / 20")
        self.assertIn("a\\|b", markdown)

    def test_markdown_never_fills_relevance_or_comment(self):
        markdown = rq.render_markdown([record()], META)
        _, rows = self.table_rows(markdown)
        self.assertEqual(rows[0][9], "")
        self.assertEqual(rows[0][10], "")

    def test_markdown_reports_empty_expansion_errors_and_differences(self):
        good = record(terms=[])
        broken = record(item_id="q02")
        broken["runs"][1]["rest"] = {"error": "HTTP 500: boom", "time_ms": None, "wall_ms": None,
                                     "results": []}
        markdown = rq.render_markdown([good, broken], META)
        _, rows = self.table_rows(markdown)
        self.assertEqual(rows[0][3], "немає")
        self.assertIn("HTTP 500: boom", markdown)
        self.assertIn("№2", markdown)

    def test_markdown_without_differences(self):
        self.assertIn("Відмінностей і помилок немає.", rq.render_markdown([record()], META))

    def test_markdown_no_ollama_section(self):
        meta = dict(META, no_ollama=True, health={"status": "ok", "ollama": False})
        ok = rq.render_markdown([record(terms=[])], meta)
        self.assertIn("## Перевірка fallback", ok)
        self.assertIn("порожній", ok)
        bad = rq.render_markdown([record(terms=["ship"])], meta)
        self.assertIn("Fallback порушено", bad)
        self.assertNotIn("## Перевірка fallback", rq.render_markdown([record()], META))

    def test_markdown_shortens_a_long_query_but_the_csv_keeps_it(self):
        long_query = "whale " * 400
        item = record(query=long_query)
        markdown = rq.render_markdown([item], META)
        self.assertNotIn(long_query.strip(), markdown)
        self.assertIn("символів", markdown)
        rows = self.read_csv([item])
        self.assertEqual(rows[1][2], long_query)

    def test_markdown_top5_section_lists_snippet_starts(self):
        markdown = rq.render_markdown([record(results=[hit("a#1", 1.0, "the harpoon flew")])], META)
        self.assertIn("1. `a#1` (1.0000) the harpoon flew", markdown)

    def test_markdown_handles_missing_results(self):
        markdown = rq.render_markdown([record(results=[])], META)
        self.assertIn("немає результатів", markdown)


class MainGuardsTest(unittest.TestCase):
    def run_main(self, argv, http_answers=None):
        output = io.StringIO()
        with mock.patch.object(sys, "stderr", output), \
                mock.patch.object(rq, "http_json",
                                  side_effect=http_answers or AssertionError("no network expected")):
            code = rq.main(argv)
        return code, output.getvalue()

    def test_refuses_to_overwrite_existing_reports_without_force(self):
        with tempfile.TemporaryDirectory() as directory:
            (Path(directory) / "test-queries.md").write_text("my notes", encoding="utf-8")
            code, text = self.run_main(["--out-dir", directory])
            self.assertEqual(code, 2)
            self.assertIn("--force", text)
            self.assertEqual((Path(directory) / "test-queries.md").read_text(encoding="utf-8"),
                             "my notes")

    def test_no_ollama_stops_when_health_says_ollama_is_up(self):
        with tempfile.TemporaryDirectory() as directory:
            code, text = self.run_main(["--no-ollama", "--out-dir", directory],
                                       [{"status": "ok", "ollama": True}])
            self.assertEqual(code, 2)
            self.assertIn("docker compose stop ollama", text)
            self.assertEqual(list(Path(directory).iterdir()), [])

    def test_stops_when_the_index_is_empty(self):
        with tempfile.TemporaryDirectory() as directory:
            code, text = self.run_main(["--out-dir", directory],
                                       [{"status": "ok", "ollama": True},
                                        {"documents": 0, "index_build_time_ms": 0, "threads_used": 0}])
            self.assertEqual(code, 2)
            self.assertIn("Індекс порожній", text)

    def test_stops_with_a_readable_message_when_the_server_is_down(self):
        with tempfile.TemporaryDirectory() as directory:
            code, text = self.run_main(["--out-dir", directory],
                                       rq.BenchError("Сервер недоступний (x)"))
            self.assertEqual(code, 2)
            self.assertIn("Сервер недоступний", text)


if __name__ == "__main__":
    unittest.main()
