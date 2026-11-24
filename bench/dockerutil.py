from __future__ import annotations

import http.client
import json
import os
import platform
import subprocess
import sys
import time
import urllib.error
import urllib.request
from pathlib import Path
from urllib.parse import urlparse

import benchlib

REPO_ROOT = Path(__file__).resolve().parent.parent


class BenchError(Exception):
    """benchmark failure"""



class Compose:

    def __init__(self, root: Path = REPO_ROOT) -> None:
        self.root = Path(root)
        self.data_dir: Path | None = None

    def base_command(self) -> list[str]:
        return [
            "docker", "compose",
            "--project-directory", str(self.root),
            "-f", str(self.root / "docker-compose.yml"),
            "-f", str(self.root / "bench" / "docker-compose.bench.yml"),
        ]

    def set_data_dir(self, path: Path | str) -> None:
        self.data_dir = Path(path).resolve()

    def _env(self) -> dict:
        env = dict(os.environ)
        if self.data_dir is not None:
            env["BENCH_DATA_DIR"] = self.data_dir.as_posix()
        return env

    def run(self, *args: str, timeout: float = 900, check: bool = True) -> str:
        command = self.base_command() + list(args)
        try:
            proc = subprocess.run(
                command,
                cwd=self.root,
                env=self._env(),
                stdout=subprocess.PIPE,
                stderr=subprocess.STDOUT,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout,
            )
        except FileNotFoundError:
            raise BenchError("Команду docker не знайдено. Запустіть Docker Desktop.") from None
        except subprocess.TimeoutExpired:
            raise BenchError(f"Команда не завершилась за {timeout} с: {' '.join(args)}") from None
        if check and proc.returncode != 0:
            tail = proc.stdout.strip()[-800:]
            raise BenchError(f"docker compose {' '.join(args)} завершилась з кодом "
                             f"{proc.returncode}:\n{tail}")
        return proc.stdout

    def restart_server(self) -> None:
        self.run("restart", "server")

    def recreate_server(self) -> None:
        self.run("up", "-d", "--no-deps", "--force-recreate", "server")

    def start_ollama(self) -> None:
        self.run("up", "-d", "ollama")

    def logs(self) -> str:
        return self.run("logs", "--no-color", "server", check=False)


_OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # never use a proxy


def http_json(method: str, url: str, payload: dict | None = None, timeout: float = 30) -> dict:
    data = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"} if data is not None else {}
    request = urllib.request.Request(url, data=data, method=method, headers=headers)
    try:
        with _OPENER.open(request, timeout=timeout) as response:
            body = response.read()
    except urllib.error.HTTPError as e:
        text = e.read().decode("utf-8", errors="replace")[:300]
        raise BenchError(f"{method} {url}: HTTP {e.code}: {text}") from None
    except (urllib.error.URLError, OSError) as e:
        raise BenchError(f"{method} {url}: {e}") from None
    try:
        return json.loads(body)
    except ValueError:
        raise BenchError(f"{method} {url}: відповідь не є JSON: {body[:200]!r}") from None


def wait_health(url: str, timeout: float, poll: float = 0.5) -> dict:
    deadline = time.monotonic() + timeout
    last_error = ""
    while time.monotonic() < deadline:
        try:
            answer = http_json("GET", url + "/api/health", timeout=5)
            if answer.get("status") == "ok":
                return answer
            last_error = f"status={answer.get('status')!r}"
        except BenchError as e:
            last_error = str(e)
        time.sleep(poll)
    raise BenchError(f"Сервер не став готовим за {timeout:.0f} с ({last_error}). "
                     "Перегляньте: docker compose logs server")


def connect_keepalive(url: str, timeout: float = 120) -> http.client.HTTPConnection:
    parsed = urlparse(url)
    return http.client.HTTPConnection(parsed.hostname, parsed.port or 80, timeout=timeout)



def scan_dataset(path: Path) -> dict:
    files = 0
    total = 0
    for dirpath, _dirs, names in os.walk(path):
        for name in names:
            if name.lower().endswith(".txt"):
                files += 1
                total += os.path.getsize(os.path.join(dirpath, name))
    return {
        "path": str(Path(path)),
        "files": files,
        "size_bytes": total,
        "size_mb": round(total / (1024 * 1024), 1),
    }


def _total_ram_bytes() -> int | None:
    try:
        if sys.platform.startswith("win"):
            import ctypes

            class MemoryStatus(ctypes.Structure):
                _fields_ = [
                    ("dwLength", ctypes.c_ulong), ("dwMemoryLoad", ctypes.c_ulong),
                    ("ullTotalPhys", ctypes.c_ulonglong), ("ullAvailPhys", ctypes.c_ulonglong),
                    ("ullTotalPageFile", ctypes.c_ulonglong),
                    ("ullAvailPageFile", ctypes.c_ulonglong),
                    ("ullTotalVirtual", ctypes.c_ulonglong),
                    ("ullAvailVirtual", ctypes.c_ulonglong),
                    ("ullAvailExtendedVirtual", ctypes.c_ulonglong),
                ]

            status = MemoryStatus()
            status.dwLength = ctypes.sizeof(status)
            ctypes.windll.kernel32.GlobalMemoryStatusEx(ctypes.byref(status))
            return int(status.ullTotalPhys)
        if sys.platform == "darwin":
            out = subprocess.run(["sysctl", "-n", "hw.memsize"], capture_output=True, text=True)
            return int(out.stdout.strip())
        with open("/proc/meminfo", encoding="utf-8") as handle:
            for line in handle:
                if line.startswith("MemTotal:"):
                    return int(line.split()[1]) * 1024
    except Exception:
        return None
    return None


def _run_text(command: list[str], cwd: Path | None = None) -> str | None:
    try:
        proc = subprocess.run(command, cwd=cwd, capture_output=True, text=True,
                              encoding="utf-8", errors="replace", timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        return None
    return proc.stdout.strip() if proc.returncode == 0 else None


def _docker_info() -> dict:
    text = _run_text(["docker", "info", "--format", "{{json .}}"])
    if not text:
        return {"available": False}
    try:
        info = json.loads(text)
    except ValueError:
        return {"available": False}
    keys = ["NCPU", "MemTotal", "ServerVersion", "OperatingSystem", "OSType", "KernelVersion",
            "Driver", "Name"]
    result = {key: info.get(key) for key in keys}
    result["available"] = True
    result["compose_version"] = _run_text(["docker", "compose", "version", "--short"])
    return result


def _chunk_size_kb(compose: Compose) -> str | None:
    try:
        out = compose.run("exec", "-T", "server", "printenv", "CHUNK_SIZE_KB", check=True,
                          timeout=60)
    except BenchError:
        return os.environ.get("CHUNK_SIZE_KB")
    return out.strip() or None


def _module_version(name: str) -> str | None:
    try:
        module = __import__(name)
        return getattr(module, "__version__", "installed")
    except ImportError:
        return None


def collect_environment(compose: Compose, datasets: dict[str, Path]) -> dict:
    ram = _total_ram_bytes()
    git_hash = _run_text(["git", "rev-parse", "HEAD"], cwd=compose.root)
    dirty = None
    if git_hash is not None:
        status = _run_text(["git", "status", "--porcelain"], cwd=compose.root)
        dirty = bool(status)
    return {
        "date": benchlib.now_iso(),
        "host_cpu_cores": os.cpu_count(),
        "host_ram_mb": round(ram / (1024 * 1024)) if ram else None,
        "os": platform.platform(),
        "docker": _docker_info(),
        "chunk_size_kb": _chunk_size_kb(compose),
        "git_commit": git_hash,
        "git_dirty": dirty,
        "python": platform.python_version(),
        "matplotlib": _module_version("matplotlib"),
        "websockets": _module_version("websockets"),
        "datasets": {name: scan_dataset(path) for name, path in datasets.items()},
    }


def write_environment(results_dir: Path, info: dict) -> Path:
    path = Path(results_dir) / "environment.json"
    path.parent.mkdir(parents=True, exist_ok=True)
    merged = dict(info)
    if path.exists():
        try:
            old = json.loads(path.read_text(encoding="utf-8"))
            datasets = dict(old.get("datasets", {}))
            datasets.update(info.get("datasets", {}))
            merged["datasets"] = datasets
        except ValueError:
            pass
    path.write_text(json.dumps(merged, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    return path
