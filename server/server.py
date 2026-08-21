#!/usr/bin/env python3
"""Lightweight music library HTTP API for Orange Pi Zero 3."""

from __future__ import annotations

import cgi
import hashlib
import json
import mimetypes
import os
import time
import urllib.parse
import urllib.request
import shutil
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

MUSIC_DIR = Path(os.environ.get("MUSIC_DIR", "/srv/music")).resolve()
WEB_DIR = Path(os.environ.get("WEB_DIR", "/opt/music-server/web")).resolve()
ART_CACHE_DIR = Path(os.environ.get("ART_CACHE_DIR", "/var/cache/music-art")).resolve()
HOST = os.environ.get("MUSIC_HOST", "0.0.0.0")
PORT = int(os.environ.get("MUSIC_PORT", "8080"))
AUDIO_EXT = {".mp3", ".flac", ".ogg", ".opus", ".m4a", ".aac", ".wav"}
CACHE_TTL_SEC = 30

_cache: tuple[float, list[dict]] | None = None


def list_library(force: bool = False) -> list[dict]:
    global _cache
    now = time.time()
    if not force and _cache and now - _cache[0] < CACHE_TTL_SEC:
        return _cache[1]
    files: list[dict] = []
    if MUSIC_DIR.exists():
        for root, dirs, filenames in os.walk(MUSIC_DIR):
            dirs[:] = [d for d in dirs if not d.startswith(".")]
            for name in filenames:
                if name.startswith("."):
                    continue
                path = Path(root) / name
                if path.suffix.lower() not in AUDIO_EXT:
                    continue
                try:
                    rel = path.relative_to(MUSIC_DIR).as_posix()
                    stat = path.stat()
                except OSError:
                    continue
                files.append({"path": rel, "size": stat.st_size, "mtime": int(stat.st_mtime)})
        files.sort(key=lambda item: item["path"].lower())
    _cache = (now, files)
    return files


def safe_file(relative: str) -> Path | None:
    relative = urllib.parse.unquote(relative).lstrip("/")
    if not relative or ".." in Path(relative).parts:
        return None
    candidate = (MUSIC_DIR / relative).resolve()
    try:
        candidate.relative_to(MUSIC_DIR)
    except ValueError:
        return None
    if not candidate.is_file():
        return None
    return candidate


def safe_directory(relative: str) -> Path | None:
    relative = urllib.parse.unquote(relative).strip("/")
    if not relative:
        return MUSIC_DIR
    if ".." in Path(relative).parts:
        return None
    candidate = (MUSIC_DIR / relative).resolve()
    try:
        candidate.relative_to(MUSIC_DIR)
    except ValueError:
        return None
    return candidate


def invalidate_cache() -> None:
    global _cache
    _cache = None


def art_cache_paths(relative: str) -> tuple[Path, Path]:
    key = hashlib.sha256(relative.encode("utf-8")).hexdigest()
    return ART_CACHE_DIR / f"{key}.img", ART_CACHE_DIR / f"{key}.meta"


def extract_cover(file_path: Path) -> tuple[bytes, str] | None:
    try:
        from mutagen import File
        from mutagen.id3 import APIC
        from mutagen.mp4 import MP4Cover
    except ImportError:
        return None

    audio = File(file_path)
    if audio is None:
        return None

    if hasattr(audio, "pictures") and audio.pictures:
        picture = audio.pictures[0]
        return picture.data, picture.mime or "image/jpeg"

    tags = getattr(audio, "tags", None)
    if not tags:
        return None

    for tag in tags.values():
        if isinstance(tag, APIC):
            return tag.data, tag.mime or "image/jpeg"

    if hasattr(tags, "getall"):
        for cover in tags.getall("covr"):
            if isinstance(cover, MP4Cover):
                return bytes(cover), "image/jpeg"

    return None


def load_cached_art(relative: str, source_mtime: int) -> tuple[bytes, str] | None:
    image_path, meta_path = art_cache_paths(relative)
    if not image_path.is_file() or not meta_path.is_file():
        return None
    try:
        meta = json.loads(meta_path.read_text(encoding="utf-8"))
        if meta.get("mtime") != source_mtime:
            return None
        mime = meta.get("mime") or "image/jpeg"
        return image_path.read_bytes(), mime
    except (OSError, json.JSONDecodeError):
        return None


def store_cached_art(relative: str, source_mtime: int, payload: bytes, mime: str) -> None:
    ART_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    image_path, meta_path = art_cache_paths(relative)
    image_path.write_bytes(payload)
    meta_path.write_text(json.dumps({"mtime": source_mtime, "mime": mime}), encoding="utf-8")


def resolve_art(relative: str) -> tuple[bytes, str] | None:
    target = safe_file(relative)
    if target is None:
        return None
    try:
        source_mtime = int(target.stat().st_mtime)
    except OSError:
        return None

    cached = load_cached_art(relative, source_mtime)
    if cached:
        return cached

    extracted = extract_cover(target)
    if not extracted:
        return None

    payload, mime = extracted
    store_cached_art(relative, source_mtime, payload, mime)
    return payload, mime


def safe_web_path(relative: str) -> Path | None:
    if not WEB_DIR.exists():
        return None
    relative = urllib.parse.unquote(relative).lstrip("/")
    candidate = (WEB_DIR / relative).resolve()
    try:
        candidate.relative_to(WEB_DIR)
    except ValueError:
        return None
    return candidate if candidate.is_file() else None


def parse_version_tag(tag_name: str) -> tuple[str, int] | tuple[None, None]:
    tag = tag_name.strip().lstrip("v")
    if "+" not in tag:
        return None, None
    version_name, _, version_code = tag.rpartition("+")
    try:
        return version_name, int(version_code)
    except ValueError:
        return None, None


def github_latest_release(repo: str) -> dict:
    request = urllib.request.Request(
        f"https://api.github.com/repos/{repo}/releases/latest",
        headers={
            "Accept": "application/vnd.github+json",
            "User-Agent": "music-api",
        },
    )
    with urllib.request.urlopen(request, timeout=15) as response:
        payload = json.loads(response.read().decode("utf-8"))
    apk_asset = next(
        (asset for asset in payload.get("assets", []) if str(asset.get("name", "")).lower().endswith(".apk")),
        None,
    )
    version_name, version_code = parse_version_tag(payload.get("tag_name", ""))
    return {
        "repo": repo,
        "tagName": payload.get("tag_name", ""),
        "releaseName": payload.get("name", ""),
        "versionName": version_name,
        "versionCode": version_code,
        "publishedAt": payload.get("published_at"),
        "body": payload.get("body", ""),
        "apkUrl": apk_asset.get("browser_download_url") if apk_asset else None,
        "apkFileName": apk_asset.get("name") if apk_asset else None,
        "htmlUrl": payload.get("html_url"),
    }


def content_disposition(filename: str) -> str:
    ascii_fallback = filename.encode("ascii", "ignore").decode("ascii").strip()
    ascii_fallback = ascii_fallback.replace("\\", "_").replace('"', "_") or "download"
    utf8_name = urllib.parse.quote(filename, safe="")
    return f"attachment; filename=\"{ascii_fallback}\"; filename*=UTF-8''{utf8_name}"


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _base_url(self) -> str:
        # Used to build absolute links for clients (web/Android) without
        # hardcoding whether the server is exposed via LAN or Tailscale.
        scheme = self.headers.get("X-Forwarded-Proto", "http")
        host = self.headers.get("Host")
        if not host:
            return ""
        return f"{scheme}://{host}"

    def log_message(self, fmt: str, *args) -> None:
        print("%s - %s" % (self.address_string(), fmt % args))

    def do_GET(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        path = parsed.path
        if path == "/api/health":
            self._json(200, {"status": "ok"})
            return
        if path == "/api/library":
            self._json(200, {"files": list_library()})
            return
        if path == "/api/library/refresh":
            invalidate_cache()
            self._json(200, {"files": list_library(force=True)})
            return
        if path == "/api/releases/latest":
            repo = urllib.parse.parse_qs(parsed.query).get("repo", [""])[0].strip()
            if not repo:
                self._json(400, {"error": "repo query parameter is required"})
                return
            try:
                payload = github_latest_release(repo)
                base_url = self._base_url()
                if base_url and payload.get("apkFileName"):
                    # Force clients to download through this server to avoid
                    # potential connectivity restrictions to GitHub.
                    payload["apkUrl"] = (
                        f"{base_url}/api/apk/latest?repo={urllib.parse.quote(repo, safe='')}"
                    )
                self._json(200, payload)
            except Exception as exc:
                self._json(502, {"error": str(exc)})
            return
        if path == "/api/apk/latest":
            repo = urllib.parse.parse_qs(parsed.query).get("repo", [""])[0].strip()
            if not repo:
                self._json(400, {"error": "repo query parameter is required"})
                return
            try:
                payload = github_latest_release(repo)
                apk_url = payload.get("apkUrl")
                apk_name = payload.get("apkFileName") or "music-update.apk"
                if not apk_url:
                    self._json(404, {"error": "No APK asset in latest release"})
                    return
                self._send_remote_file(apk_url, apk_name)
            except Exception as exc:
                self._json(502, {"error": str(exc)})
            return
        if path.startswith("/files/"):
            self._send_file(path[len("/files/") :])
            return
        if path.startswith("/api/art/"):
            self._send_art(path[len("/api/art/") :])
            return
        if self._serve_web(path):
            return
        self._json(404, {"error": "not found"})

    def do_OPTIONS(self) -> None:
        self.send_response(204)
        self._send_cors_headers()
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Range")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/health":
            self._json(200, {"status": "ok"}, body=False)
            return
        self.do_GET()

    def do_POST(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/upload":
            self._upload(parsed.query)
            return
        self._json(404, {"error": "not found"})

    def _json(self, code: int, payload: dict, body: bool = True) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self._send_cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body:
            self.wfile.write(data)

    def _send_cors_headers(self) -> None:
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Expose-Headers", "Content-Length, Content-Range")

    def _send_art(self, relative: str) -> None:
        resolved = resolve_art(relative)
        if resolved is None:
            self._json(404, {"error": "cover not found"})
            return
        payload, mime = resolved
        self.send_response(200)
        self._send_cors_headers()
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "public, max-age=86400")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)

    def _send_file(self, relative: str) -> None:
        target = safe_file(relative)
        if target is None:
            self._json(404, {"error": "file not found"})
            return
        size = target.stat().st_size
        mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        range_header = self.headers.get("Range")
        start = 0
        end = size - 1
        status = 200
        if range_header and range_header.startswith("bytes="):
            spec = range_header.split("=", 1)[1].split(",")[0].strip()
            left, _, right = spec.partition("-")
            try:
                if left:
                    start = int(left)
                if right:
                    end = int(right)
            except ValueError:
                self.send_error(400, "Invalid Range")
                return
            if start >= size:
                self.send_response(416)
                self.send_header("Content-Range", f"bytes */{size}")
                self.send_header("Content-Length", "0")
                self.end_headers()
                return
            end = min(end, size - 1)
            status = 206

        length = end - start + 1
        self.send_response(status)
        self._send_cors_headers()
        self.send_header("Content-Type", mime)
        self.send_header("Accept-Ranges", "bytes")
        self.send_header("Content-Length", str(length))
        if status == 206:
            self.send_header("Content-Range", f"bytes {start}-{end}/{size}")
        self.send_header("Content-Disposition", content_disposition(target.name))
        self.end_headers()
        if self.command == "HEAD":
            return
        with target.open("rb") as handle:
            handle.seek(start)
            remaining = length
            while remaining > 0:
                chunk = handle.read(min(64 * 1024, remaining))
                if not chunk:
                    break
                self.wfile.write(chunk)
                remaining -= len(chunk)

    def _send_remote_file(self, remote_url: str, filename: str) -> None:
        with urllib.request.urlopen(remote_url, timeout=60) as response:
            mime = response.headers.get("Content-Type") or mimetypes.guess_type(filename)[0] or "application/octet-stream"
            size = response.headers.get("Content-Length")

            self.send_response(200)
            self._send_cors_headers()
            self.send_header("Content-Type", mime)
            if size:
                self.send_header("Content-Length", size)
            self.send_header("Content-Disposition", content_disposition(filename))
            self.end_headers()

            shutil.copyfileobj(response, self.wfile)

    def _serve_web(self, path: str) -> bool:
        if not WEB_DIR.exists():
            return False
        relative = path.lstrip("/")
        target = safe_web_path(relative) if relative else None
        if target is None and path in {"/", ""}:
            target = safe_web_path("index.html")
        if target is None and "." not in Path(relative or "index.html").name:
            target = safe_web_path("index.html")
        if target is None:
            return False

        mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        payload = target.read_bytes()
        self.send_response(200)
        self.send_header("Content-Type", mime)
        self.send_header("Content-Length", str(len(payload)))
        self.send_header("Cache-Control", "no-store" if target.name == "index.html" else "public, max-age=3600")
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(payload)
        return True

    def _upload(self, query: str) -> None:
        params = urllib.parse.parse_qs(query)
        relative_dir = params.get("path", [""])[0]
        target_dir = safe_directory(relative_dir)
        if target_dir is None:
            self._json(400, {"error": "invalid upload path"})
            return
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self._json(400, {"error": "multipart/form-data expected"})
            return

        form = cgi.FieldStorage(
            fp=self.rfile,
            headers=self.headers,
            environ={
                "REQUEST_METHOD": "POST",
                "CONTENT_TYPE": content_type,
            },
        )
        fields = form["files"] if "files" in form else []
        if not isinstance(fields, list):
            fields = [fields]
        if not fields:
            self._json(400, {"error": "no files uploaded"})
            return

        uploaded: list[dict] = []
        target_dir.mkdir(parents=True, exist_ok=True)
        for item in fields:
            filename = Path(item.filename or "").name
            if not filename or filename.startswith(".") or Path(filename).suffix.lower() not in AUDIO_EXT:
                continue
            destination = (target_dir / filename).resolve()
            try:
                destination.relative_to(MUSIC_DIR)
            except ValueError:
                continue
            with destination.open("wb") as output:
                while True:
                    chunk = item.file.read(64 * 1024)
                    if not chunk:
                        break
                    output.write(chunk)
            uploaded.append(
                {
                    "name": filename,
                    "path": destination.relative_to(MUSIC_DIR).as_posix(),
                    "size": destination.stat().st_size,
                }
            )

        if not uploaded:
            self._json(400, {"error": "no valid audio files uploaded"})
            return

        invalidate_cache()
        self._json(200, {"uploaded": uploaded, "libraryCount": len(list_library(force=True))})


def main() -> None:
    MUSIC_DIR.mkdir(parents=True, exist_ok=True)
    ART_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    print(f"Music API on {HOST}:{PORT}, dir={MUSIC_DIR}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
