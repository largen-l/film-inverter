"""Downloads (stdlib only) and archive extraction."""
from __future__ import annotations

import json
import os
import re
import shutil
import tarfile
import urllib.request
import zipfile
from pathlib import Path
from typing import Callable, Optional

Log = Callable[[str], None]
UA = "film-inverter-tool"


def fetch(url: str, dest: Path, log: Log, user_agent: Optional[str] = None) -> Path:
    """Download url -> dest unless it already exists (via dest.part, so an interrupted download is retried)."""
    if dest.exists():
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    log(f"  downloading {dest.name}")
    req = urllib.request.Request(url, headers={"User-Agent": user_agent or UA})
    part = dest.with_name(dest.name + ".part")
    with urllib.request.urlopen(req, timeout=60) as r, open(part, "wb") as f:
        total = int(r.headers.get("Content-Length") or 0)
        done, step = 0, max(1, total // 10) if total else 0
        mark = step
        while True:
            chunk = r.read(1 << 20)
            if not chunk:
                break
            f.write(chunk)
            done += len(chunk)
            if step and done >= mark:
                log(f"    {done * 100 // total}%")
                mark += step
    part.replace(dest)
    return dest


def extract(archive: Path, dest: Path, log: Log) -> Path:
    """Unpack zip / tar.gz into dest; a single top-level folder becomes dest itself."""
    if dest.exists():
        return dest
    log(f"  extracting -> {dest}")
    tmp = dest.with_name(dest.name + ".tmp")
    if tmp.exists():
        shutil.rmtree(tmp)
    tmp.mkdir(parents=True)
    if archive.suffix.lower() == ".zip":
        with zipfile.ZipFile(archive) as z:
            for info in z.infolist():
                z.extract(info, tmp)
                mode = info.external_attr >> 16           # keep executable bits (zip from Unix hosts)
                if mode & 0o111:
                    (tmp / info.filename).chmod(mode & 0o777)
    else:
        with tarfile.open(archive) as t:
            t.extractall(tmp)
    items = list(tmp.iterdir())
    if len(items) == 1 and items[0].is_dir():
        items[0].replace(dest)
        tmp.rmdir()
    else:
        tmp.replace(dest)
    return dest


def github_json(path: str) -> dict:
    headers = {"User-Agent": UA, "Accept": "application/vnd.github+json"}
    token = os.environ.get("GITHUB_TOKEN") or os.environ.get("GH_TOKEN")
    if token:                         # CI: anonymous API calls share a small per-IP limit (v0.28.0 setup hit it)
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"https://api.github.com/{path}", headers=headers)
    with urllib.request.urlopen(req, timeout=30) as r:
        return json.load(r)


def github_asset(repo: str, pattern: str) -> tuple[str, str]:
    """(url, name) of the first asset in the latest release whose name matches the regex."""
    rel = github_json(f"repos/{repo}/releases/latest")
    rx = re.compile(pattern)
    for a in rel.get("assets", []):
        if rx.search(a["name"]):
            return a["browser_download_url"], a["name"]
    raise RuntimeError(f"no asset matching {pattern!r} in the latest {repo} release")
