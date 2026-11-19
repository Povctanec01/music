#!/usr/bin/env python3
"""Lightweight music library HTTP API for Orange Pi Zero 3."""

from __future__ import annotations

import io
import hashlib
import json
import mimetypes
import os
import re
import time
import urllib.parse
import urllib.request
import shutil
import threading
import wave
import tempfile
import sqlite3
import secrets
import uuid
from email import policy
from email.parser import BytesHeaderParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from contextlib import closing

MUSIC_DIR = Path(os.environ.get("MUSIC_DIR", "/srv/music")).resolve()
WEB_DIR = Path(os.environ.get("WEB_DIR", "/opt/music-server/web")).resolve()
ART_CACHE_DIR = Path(os.environ.get("ART_CACHE_DIR", "/var/cache/music-art")).resolve()
HOST = os.environ.get("MUSIC_HOST", "0.0.0.0")
PORT = int(os.environ.get("MUSIC_PORT", "8080"))
AUDIO_EXT = {".mp3", ".flac", ".ogg", ".opus", ".m4a", ".aac", ".wav"}
CACHE_TTL_SEC = 30
API_TOKEN = os.environ.get("MUSIC_API_TOKEN", "")
TOKEN_FILE = os.environ.get("MUSIC_API_TOKEN_FILE", "")
ALLOWED_ORIGINS = {value.strip() for value in os.environ.get("MUSIC_ALLOWED_ORIGINS", "").split(",") if value.strip()}
_file_lock = threading.RLock()

_cache: tuple[float, list[dict]] | None = None
_metadata_cache: dict[str, tuple[int, int, int, dict]] = {}
_prefetch_lock = threading.Lock()
_prefetch_scheduled = False


def sidecar_path_for_audio(file_path: Path) -> Path:
    return file_path.with_suffix(".jpg")


def sidecar_has_art(audio_path: Path) -> bool:
    sidecar = sidecar_path_for_audio(audio_path)
    try:
        return sidecar.is_file() and sidecar.stat().st_size > 128
    except OSError:
        return False


def audio_metadata(path: Path) -> dict:
    """Optional tags/duration; unreadable metadata never hides a library file."""
    try:
        from mutagen import File
        audio = File(str(path), easy=True)
        if audio is not None:
            tags = audio.tags or {}
            return {
                "durationMs": round(audio.info.length * 1000),
                **{key: str((tags.get(key) or [""])[0]) for key in ("title", "artist", "album")},
            }
    except Exception:
        pass
    if path.suffix.lower() == ".wav":
        try:
            with wave.open(str(path), "rb") as audio:
                return {"durationMs": round(audio.getnframes() / audio.getframerate() * 1000)}
        except (OSError, EOFError, wave.Error):
            pass
    return {}


def indexed_metadata(path: Path, relative: str, stat) -> dict:
    """Persist expensive metadata and hashes; only read changed audio."""
    ART_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    with closing(sqlite3.connect(ART_CACHE_DIR / "library.sqlite3", timeout=30)) as db:
        db.execute("CREATE TABLE IF NOT EXISTS tracks (path TEXT PRIMARY KEY, size INTEGER, mtime INTEGER, ctime INTEGER, metadata TEXT)")
        row = db.execute("SELECT size, mtime, ctime, metadata FROM tracks WHERE path=?", (relative,)).fetchone()
        if row and row[:3] == (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns):
            return json.loads(row[3])
        digest = hash_file(path)
        after = path.stat()
        if (after.st_size, after.st_mtime_ns, after.st_ctime_ns) != (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns):
            raise OSError("file changed during indexing")
        metadata = {**audio_metadata(path), "sha256": digest, "version": f"{stat.st_mtime_ns}:{digest}"}
        db.execute("INSERT OR REPLACE INTO tracks VALUES (?,?,?,?,?)", (relative, stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, json.dumps(metadata)))
        db.commit()
        return metadata


def hash_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def write_token() -> str:
    if TOKEN_FILE:
        try:
            return Path(TOKEN_FILE).read_text(encoding="utf-8").strip()
        except OSError:
            return ""
    return API_TOKEN


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
                    path.resolve().relative_to(MUSIC_DIR)
                except (OSError, ValueError):
                    continue
                cached_metadata = _metadata_cache.get(rel)
                if cached_metadata and cached_metadata[:3] == (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns):
                    metadata = cached_metadata[3]
                else:
                    try:
                        metadata = indexed_metadata(path, rel, stat)
                    except OSError:
                        continue
                    _metadata_cache[rel] = (stat.st_size, stat.st_mtime_ns, stat.st_ctime_ns, metadata)
                files.append(
                    {
                        "path": rel,
                        "size": stat.st_size,
                        "mtime": int(stat.st_mtime),
                        "hasArt": sidecar_has_art(path),
                        **metadata,
                    }
                )
        files.sort(key=lambda item: item["path"].lower())
    _cache = (now, files)
    schedule_art_prefetch(files)
    return files


def safe_file(relative: str) -> Path | None:
    relative = relative.lstrip("/")
    if not relative or any(part.startswith(".") for part in Path(relative).parts):
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
    # Query parameters have already been decoded by parse_qs.
    relative = relative.strip("/")
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
    """Pull embedded cover art, then sidecar / sibling / YouTube thumbnail."""
    embedded = extract_embedded_cover(file_path)
    if embedded:
        return embedded
    sidecar = extract_sidecar_cover(file_path)
    if sidecar:
        return sidecar
    sibling = extract_sibling_audio_cover(file_path)
    if sibling:
        return sibling
    return extract_youtube_thumbnail(file_path)


def extract_youtube_thumbnail(file_path: Path) -> tuple[bytes, str] | None:
    stem = file_path.stem
    matches = list(re.finditer(r"\[(?:-)?([A-Za-z0-9_-]{11})\]", stem))
    if not matches:
        loose = list(re.finditer(r"\[(-?[A-Za-z0-9_-]{6,})\]", stem))
        if not loose:
            return None
        video_id = loose[-1].group(1).lstrip("-")
    else:
        video_id = matches[-1].group(1)
    if not video_id:
        return None
    for url in (
        f"https://i.ytimg.com/vi/{video_id}/hqdefault.jpg",
        f"https://img.youtube.com/vi/{video_id}/hqdefault.jpg",
        f"https://i.ytimg.com/vi/{video_id}/mqdefault.jpg",
    ):
        try:
            request = urllib.request.Request(url, headers={"User-Agent": "MusicServer/1.0"})
            with urllib.request.urlopen(request, timeout=10) as response:
                payload = response.read()
                if len(payload) > 128:
                    return payload, "image/jpeg"
        except Exception:
            continue
    return None


def extract_embedded_cover(file_path: Path) -> tuple[bytes, str] | None:
    try:
        from mutagen import File
        from mutagen.flac import FLAC
        from mutagen.id3 import APIC, ID3
        from mutagen.mp4 import MP4, MP4Cover
    except ImportError:
        return None

    # Direct ID3 read works best for many YouTube / MP3 downloads.
    try:
        id3 = ID3(str(file_path))
        for frame in id3.getall("APIC"):
            if getattr(frame, "data", None):
                return frame.data, frame.mime or "image/jpeg"
    except Exception:
        pass

    try:
        audio = File(str(file_path))
    except Exception:
        # Broken/unsupported audio must not abort uploads or the cover worker.
        return None
    if audio is None:
        return None

    pictures = getattr(audio, "pictures", None)
    if pictures:
        picture = pictures[0]
        data = getattr(picture, "data", None)
        if data:
            return data, getattr(picture, "mime", None) or "image/jpeg"

    if isinstance(audio, FLAC) and audio.pictures:
        picture = audio.pictures[0]
        return picture.data, picture.mime or "image/jpeg"

    if isinstance(audio, MP4) and audio.tags is not None:
        covers = audio.tags.get("covr")
        if covers:
            cover = covers[0]
            mime = "image/png" if getattr(cover, "imageformat", None) == MP4Cover.FORMAT_PNG else "image/jpeg"
            return bytes(cover), mime

    tags = getattr(audio, "tags", None)
    if not tags:
        return None

    values = []
    try:
        values = list(tags.values())
    except Exception:
        values = []

    for tag in values:
        frames = tag if isinstance(tag, list) else [tag]
        for frame in frames:
            if isinstance(frame, APIC) and getattr(frame, "data", None):
                return frame.data, frame.mime or "image/jpeg"
            if isinstance(frame, MP4Cover):
                mime = "image/png" if frame.imageformat == MP4Cover.FORMAT_PNG else "image/jpeg"
                return bytes(frame), mime

    if hasattr(tags, "getall"):
        try:
            for cover in tags.getall("covr"):
                if isinstance(cover, MP4Cover):
                    mime = "image/png" if cover.imageformat == MP4Cover.FORMAT_PNG else "image/jpeg"
                    return bytes(cover), mime
        except Exception:
            pass

    return None


def extract_sidecar_cover(file_path: Path) -> tuple[bytes, str] | None:
    parent = file_path.parent
    stem = file_path.stem
    candidates = [parent / f"{stem}{ext}" for ext in (".jpg", ".jpeg", ".png", ".webp")]
    candidates.extend(parent / name for name in ("cover.jpg", "cover.png", "folder.jpg", "Folder.jpg", "AlbumArt.jpg"))
    for candidate in candidates:
        if not candidate.is_file():
            continue
        try:
            payload = candidate.read_bytes()
        except OSError:
            continue
        if not payload:
            continue
        mime = mimetypes.guess_type(candidate.name)[0] or "image/jpeg"
        return payload, mime
    return None


def normalize_track_stem(stem: str) -> str:
    # Drop trailing YouTube-style ids: "Title [TOoC7aXkpMs]"
    cleaned = re.sub(r"\s*\[[A-Za-z0-9_-]{6,}\]\s*$", "", stem).strip()
    return cleaned.casefold()


def extract_sibling_audio_cover(file_path: Path) -> tuple[bytes, str] | None:
    """YouTube .m4a often has no cover; reuse art from a nearby .mp3 of the same title."""
    base = normalize_track_stem(file_path.stem)
    if not base:
        return None
    try:
        siblings = list(file_path.parent.iterdir())
    except OSError:
        return None
    for candidate in siblings:
        if candidate == file_path or not candidate.is_file():
            continue
        if candidate.suffix.lower() not in AUDIO_EXT:
            continue
        other = normalize_track_stem(candidate.stem)
        if not other:
            continue
        if other == base or other.startswith(base) or base.startswith(other):
            art = extract_embedded_cover(candidate)
            if art:
                return art
            art = extract_sidecar_cover(candidate)
            if art:
                return art
    return None


def clear_cached_art(relative: str) -> None:
    image_path, meta_path = art_cache_paths(relative)
    for path in (image_path, meta_path):
        try:
            path.unlink(missing_ok=True)
        except OSError:
            pass


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


def store_sidecar_art(audio_path: Path, payload: bytes) -> None:
    """Persist cover next to the audio file so sync can ship it to phones."""
    if len(payload) <= 128:
        return
    sidecar = sidecar_path_for_audio(audio_path)
    sidecar.write_bytes(payload)


def load_stored_sidecar_art(target: Path) -> tuple[bytes, str] | None:
    """Return on-disk `{stem}.jpg` saved by the server prefetch step."""
    sidecar = sidecar_path_for_audio(target)
    if not sidecar.is_file():
        return None
    try:
        if sidecar.stat().st_size <= 128:
            return None
        payload = sidecar.read_bytes()
        if len(payload) <= 128:
            return None
        mime = mimetypes.guess_type(sidecar.name)[0] or "image/jpeg"
        return payload, mime
    except OSError:
        return None


def ensure_art_for_relative(relative: str) -> bool:
    return resolve_art(relative) is not None


def prefetch_art_for_paths(paths: list[str], force: bool = False, sync_limit: int = 1) -> dict:
    """Quick art check: sync only a few tracks, schedule the rest in background."""
    ready = 0
    missing = 0
    total = 0
    pending_paths: list[str] = []
    for relative in paths:
        relative = (relative or "").strip()
        if not relative:
            continue
        total += 1
        target = safe_file(relative)
        if not force and target is not None and sidecar_has_art(target):
            ready += 1
            continue
        pending_paths.append(relative)

    synced = 0
    for relative in pending_paths[:sync_limit]:
        if ensure_art_for_relative(relative):
            ready += 1
        else:
            missing += 1
        synced += 1

    tail = pending_paths[synced:]
    if tail:
        schedule_art_prefetch([{"path": path} for path in tail], force=force)
        missing += len(tail)

    return {
        "total": total,
        "ready": ready,
        "missing": missing,
        "pending": len(tail),
        "status": "done" if not tail else "prefetch_scheduled",
    }


def schedule_art_prefetch(files: list[dict], force: bool = False) -> None:
    """Background: download missing covers on the server (YouTube / embedded)."""
    global _prefetch_scheduled

    def worker() -> None:
        global _prefetch_scheduled
        try:
            for entry in files:
                relative = entry.get("path") or ""
                if not relative:
                    continue
                if not force and entry.get("hasArt"):
                    continue
                try:
                    ensure_art_for_relative(relative)
                except Exception as exc:
                    print(f"Cover prefetch failed for {relative}: {exc}")
        finally:
            with _prefetch_lock:
                _prefetch_scheduled = False

    with _prefetch_lock:
        if _prefetch_scheduled:
            return
        _prefetch_scheduled = True
    threading.Thread(target=worker, name="art-prefetch", daemon=True).start()


def clear_sidecar_art(relative: str) -> None:
    target = safe_file(relative)
    if target is None:
        return
    sidecar = sidecar_path_for_audio(target)
    try:
        sidecar.unlink(missing_ok=True)
    except OSError:
        pass


def refresh_library_art(force_sidecar: bool = True) -> dict:
    files = list_library(force=True)
    refreshed = 0
    missing = 0
    for entry in files:
        relative = entry["path"]
        if force_sidecar:
            clear_cached_art(relative)
            # A hand-provided sidecar can be the only original cover. Keep it
            # available as fallback while trying embedded art again.
            target = safe_file(relative)
            extracted = extract_cover(target) if target else None
            if extracted and target:
                payload, mime = extracted
                store_sidecar_art(target, payload)
                store_cached_art(relative, int(target.stat().st_mtime), payload, mime)
        if ensure_art_for_relative(relative):
            refreshed += 1
            entry["hasArt"] = True
        else:
            missing += 1
            entry["hasArt"] = False
    return {"total": len(files), "refreshed": refreshed, "missing": missing}


def resolve_art(relative: str) -> tuple[bytes, str] | None:
    target = safe_file(relative)
    if target is None:
        return None
    try:
        source_mtime = int(target.stat().st_mtime)
    except OSError:
        return None

    sidecar = load_stored_sidecar_art(target)
    if sidecar:
        payload, mime = sidecar
        store_cached_art(relative, source_mtime, payload, mime)
        return payload, mime

    cached = load_cached_art(relative, source_mtime)
    if cached:
        payload, mime = cached
        store_sidecar_art(target, payload)
        return cached

    extracted = extract_cover(target)
    if not extracted:
        return None

    payload, mime = extracted
    store_cached_art(relative, source_mtime, payload, mime)
    store_sidecar_art(target, payload)
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
    match = re.fullmatch(r"v?([0-9][0-9A-Za-z._-]*)\+([0-9]+)", tag_name.strip())
    if not match:
        return None, None
    version_code = int(match[2])
    if not 0 < version_code <= 2_100_000_000:
        return None, None
    return match[1], version_code


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
    ascii_fallback = re.sub(r'[\x00-\x1f\x7f\\"]', '_', ascii_fallback) or "download"
    utf8_name = urllib.parse.quote(filename, safe="")
    return f"attachment; filename=\"{ascii_fallback}\"; filename*=UTF-8''{utf8_name}"


class MultipartReader:
    """Bounded multipart reader; audio parts are spooled to disk, not RAM."""
    def __init__(self, stream, length: int):
        self.stream = stream
        self.remaining = length
        self.buffer = b""

    def copy_until(self, markers: tuple[bytes, ...], output, limit: int | None = None) -> int:
        written = 0
        keep = max(map(len, markers)) - 1
        while True:
            hits = [(self.buffer.find(marker), index, marker) for index, marker in enumerate(markers)]
            hits = [hit for hit in hits if hit[0] >= 0]
            if hits:
                offset, index, marker = min(hits)
                if limit is not None and written + offset > limit:
                    raise ValueError("multipart headers too large")
                output.write(self.buffer[:offset])
                self.buffer = self.buffer[offset + len(marker):]
                return index
            count = max(0, len(self.buffer) - keep)
            if limit is not None and written + count > limit:
                raise ValueError("multipart headers too large")
            output.write(self.buffer[:count])
            written += count
            self.buffer = self.buffer[count:]
            if self.remaining <= 0:
                raise ValueError("incomplete multipart body")
            chunk = self.stream.read(min(64 * 1024, self.remaining))
            if not chunk:
                raise ValueError("incomplete multipart body")
            self.remaining -= len(chunk)
            self.buffer += chunk

    def parts(self, content_type: str):
        header = BytesHeaderParser(policy=policy.default).parsebytes(
            f"Content-Type: {content_type}\r\n\r\n".encode("utf-8")
        )
        boundary = header.get_boundary()
        if not boundary or len(boundary) > 200:
            raise ValueError("invalid multipart boundary")
        delimiter = b"--" + boundary.encode("ascii")
        self.copy_until((delimiter + b"\r\n",), io.BytesIO(), limit=1024)
        while True:
            headers = io.BytesIO()
            self.copy_until((b"\r\n\r\n",), headers, limit=64 * 1024)
            part = BytesHeaderParser(policy=policy.default).parsebytes(headers.getvalue() + b"\r\n\r\n")
            with tempfile.TemporaryFile() as file:
                final = self.copy_until((b"\r\n" + delimiter + b"\r\n", b"\r\n" + delimiter + b"--"), file)
                file.seek(0)
                yield part.get_param("name", header="content-disposition"), part.get_filename(), file
            if final:
                # Consume any epilogue so a persistent connection remains valid.
                while self.remaining:
                    chunk = self.stream.read(min(64 * 1024, self.remaining))
                    if not chunk:
                        raise ValueError("incomplete multipart body")
                    self.remaining -= len(chunk)
                return


def trash_root() -> Path:
    root = (MUSIC_DIR / ".trash").resolve()
    root.relative_to(MUSIC_DIR)
    root.mkdir(exist_ok=True)
    return root


def move_to_trash(target: Path) -> str:
    relative = target.relative_to(MUSIC_DIR).as_posix()
    identifier = uuid.uuid4().hex
    entry = trash_root() / identifier
    entry.mkdir()
    sources = [target] + [target.with_suffix(ext) for ext in (".jpg", ".jpeg", ".png", ".webp") if target.with_suffix(ext).is_file()]
    for source in sources:
        source.resolve().relative_to(MUSIC_DIR)
    metadata = {"id": identifier, "path": relative, "deletedAt": int(time.time()), "files": [p.name for p in sources]}
    (entry / "entry.json").write_text(json.dumps(metadata), encoding="utf-8")
    moved = []
    try:
        for source in sources:
            os.replace(source, entry / source.name)
            moved.append(source)
    except OSError:
        for source in reversed(moved):
            os.replace(entry / source.name, source)
        raise
    return identifier


def trash_entries() -> list[dict]:
    entries = []
    for file in trash_root().glob("*/entry.json"):
        try:
            entry = json.loads(file.read_text(encoding="utf-8"))
            if (file.parent / Path(entry["path"]).name).is_file():
                entries.append({key: entry[key] for key in ("id", "path", "deletedAt")})
        except (OSError, ValueError, KeyError):
            continue
    return sorted(entries, key=lambda item: item["deletedAt"], reverse=True)


def restore_trash(identifier: str) -> str:
    if not re.fullmatch(r"[a-f0-9]{32}", identifier):
        raise ValueError("invalid trash id")
    entry = trash_root() / identifier
    metadata = json.loads((entry / "entry.json").read_text(encoding="utf-8"))
    relative = metadata["path"]
    target_dir = safe_directory(str(Path(relative).parent))
    if target_dir is None:
        raise ValueError("invalid restore path")
    sources = []
    for name in metadata["files"]:
        if Path(name).name != name or name in {".", ".."}:
            raise ValueError("invalid trash file")
        source = entry / name
        if source.is_file():
            if (target_dir / name).exists():
                raise FileExistsError(name)
            sources.append(source)
    if not sources:
        raise FileNotFoundError(identifier)
    target_dir.mkdir(parents=True, exist_ok=True)
    moved = []
    try:
        for source in sources:
            os.replace(source, target_dir / source.name)
            moved.append(source)
    except OSError:
        for source in reversed(moved):
            os.replace(target_dir / source.name, source)
        raise
    invalidate_cache()
    return relative


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def _origin_allowed(self) -> bool:
        origin = self.headers.get("Origin")
        return not origin or origin == self._base_url() or origin in ALLOWED_ORIGINS

    def _require_write_auth(self) -> bool:
        token = write_token()
        if not token:
            self.close_connection = True
            self._json(503, {"error": "Write access is not configured"})
            return False
        supplied = self.headers.get("Authorization", "")
        if not self._origin_allowed() or not secrets.compare_digest(supplied.encode("utf-8"), f"Bearer {token}".encode("utf-8")):
            self.close_connection = True
            self._json(401, {"error": "Server access token required"})
            return False
        return True

    def handle(self) -> None:
        try:
            super().handle()
        except ConnectionError:
            # Browsers cancel range downloads when seeking or replacing src.
            self.close_connection = True

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
        if path in {"/api/art/refresh", "/api/art/prefetch", "/api/library/refresh", "/api/trash"} and not self._require_write_auth():
            return
        if path == "/api/trash":
            self._json(200, {"files": trash_entries()})
            return
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
            self._send_file(urllib.parse.unquote(path[len("/files/") :]))
            return
        if path == "/api/art/refresh":
            try:
                self._json(200, refresh_library_art(force_sidecar=True))
            except Exception as exc:
                self._json(500, {"error": str(exc)})
            return
        if path == "/api/art/prefetch":
            try:
                files = list_library(force=True)
                schedule_art_prefetch(files, force=False)
                ready = sum(1 for item in files if item.get("hasArt"))
                self._json(
                    200,
                    {
                        "total": len(files),
                        "ready": ready,
                        "pending": len(files) - ready,
                        "status": "prefetch_scheduled",
                    },
                )
            except Exception as exc:
                self._json(500, {"error": str(exc)})
            return
        if path.startswith("/api/art/"):
            self._send_art(urllib.parse.unquote(path[len("/api/art/") :]))
            return
        if self._serve_web(path):
            return
        self._json(404, {"error": "not found"})

    def do_OPTIONS(self) -> None:
        if not self._origin_allowed():
            self._json(403, {"error": "Origin is not allowed"})
            return
        self.send_response(204)
        self._send_cors_headers()
        self.send_header("Access-Control-Allow-Methods", "GET, HEAD, POST, DELETE, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type, Range, Authorization, X-Content-SHA256, X-File-Size")
        self.send_header("Content-Length", "0")
        self.end_headers()

    def do_HEAD(self) -> None:
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/api/health":
            self._json(200, {"status": "ok"}, body=False)
            return
        self.do_GET()

    def do_POST(self) -> None:
        if not self._require_write_auth():
            return
        parsed = urllib.parse.urlparse(self.path)
        match = re.fullmatch(r"/api/trash/([a-f0-9]{32})/restore", parsed.path)
        if match:
            try:
                with _file_lock:
                    relative = restore_trash(match[1])
                self._json(200, {"restored": relative})
            except FileExistsError:
                self._json(409, {"error": "A file with this name already exists"})
            except (OSError, ValueError):
                self._json(404, {"error": "Trash entry not found"})
            return
        if parsed.path == "/api/upload":
            self._upload(parsed.query)
            return
        if parsed.path == "/api/art/prefetch":
            try:
                length = int(self.headers.get("Content-Length", "0"))
                raw = self.rfile.read(length) if length else b"{}"
                body = json.loads(raw.decode("utf-8") or "{}")
                if not isinstance(body, dict):
                    raise ValueError("JSON object expected")
                paths = body.get("paths") or []
                if not isinstance(paths, list) or any(not isinstance(path, str) for path in paths):
                    raise ValueError("paths must be an array of strings")
                force = bool(body.get("force"))
                if paths:
                    self._json(200, prefetch_art_for_paths(paths, force=force))
                else:
                    files = list_library(force=True)
                    schedule_art_prefetch(files, force=force)
                    ready = sum(1 for item in files if item.get("hasArt"))
                    self._json(
                        200,
                        {
                            "total": len(files),
                            "ready": ready,
                            "pending": len(files) - ready,
                            "status": "prefetch_scheduled",
                        },
                    )
            except (ValueError, UnicodeError) as exc:
                self._json(400, {"error": str(exc)})
            except Exception as exc:
                self._json(500, {"error": str(exc)})
            return
        self._json(404, {"error": "not found"})

    def do_DELETE(self) -> None:
        if not self._require_write_auth():
            return
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path.startswith("/api/file/"):
            self._delete_file(urllib.parse.unquote(parsed.path[len("/api/file/") :]))
            return
        self._json(404, {"error": "not found"})

    def _delete_file(self, relative: str) -> None:
        target = safe_file(relative)
        if target is None:
            self._json(404, {"error": "file not found"})
            return
        try:
            with _file_lock:
                trash_id = move_to_trash(target)
        except OSError as exc:
            self._json(500, {"error": str(exc)})
            return
        clear_cached_art(target.relative_to(MUSIC_DIR).as_posix())
        invalidate_cache()
        self._json(200, {"deleted": target.relative_to(MUSIC_DIR).as_posix(), "trashId": trash_id})

    def _json(self, code: int, payload: dict, body: bool = True) -> None:
        data = json.dumps(payload).encode("utf-8")
        self.send_response(code)
        self._send_cors_headers()
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        if body and self.command != "HEAD":
            self.wfile.write(data)

    def _send_cors_headers(self) -> None:
        if self.headers.get("Origin") and self._origin_allowed():
            self.send_header("Access-Control-Allow-Origin", self.headers["Origin"])
            self.send_header("Vary", "Origin")
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
        if self.headers.get("If-Match"):
            metadata = indexed_metadata(target, relative, target.stat())
            if self.headers["If-Match"] != f'"{metadata["sha256"]}"':
                self._json(412, {"error": "File changed"})
                return
        mime = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        range_header = self.headers.get("Range")
        start = 0
        end = size - 1
        status = 200
        if range_header and range_header.startswith("bytes="):
            spec = range_header.split("=", 1)[1].strip()
            match = re.fullmatch(r"([0-9]*)-([0-9]*)", spec)
            try:
                if not match or not any(match.groups()) or size == 0:
                    raise ValueError("Invalid Range")
                left, right = match.groups()
                if left:
                    start = int(left)
                    end = min(int(right), size - 1) if right else size - 1
                else:
                    suffix_length = int(right)
                    if suffix_length <= 0:
                        raise ValueError("Invalid suffix length")
                    start = max(0, size - suffix_length)
                if start >= size or end < start:
                    raise ValueError("Unsatisfiable Range")
            except ValueError:
                self.send_response(416)
                self._send_cors_headers()
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
            else:
                self.send_header("Connection", "close")
                self.close_connection = True
            self.send_header("Content-Disposition", content_disposition(filename))
            self.end_headers()

            if self.command != "HEAD":
                shutil.copyfileobj(response, self.wfile)

    def _serve_web(self, path: str) -> bool:
        if path.startswith(("/api/", "/files/")):
            return False
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
        try:
            self._upload_files(query)
        except (ValueError, UnicodeError) as exc:
            self.close_connection = True
            self._json(400, {"error": str(exc)})
        except OSError as exc:
            self.close_connection = True
            self._json(500, {"error": str(exc)})

    def _upload_files(self, query: str) -> None:
        params = urllib.parse.parse_qs(query)
        relative_dir = params.get("path", [""])[0]
        target_dir = safe_directory(relative_dir)
        if target_dir is None:
            self.close_connection = True
            self._json(400, {"error": "invalid upload path"})
            return
        content_type = self.headers.get("Content-Type", "")
        if "multipart/form-data" not in content_type:
            self.close_connection = True
            self._json(400, {"error": "multipart/form-data expected"})
            return

        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0:
            raise ValueError("Content-Length is required")
        reader = MultipartReader(self.rfile, length)

        uploaded: list[dict] = []
        target_dir.mkdir(parents=True, exist_ok=True)
        for field, uploaded_name, file in reader.parts(content_type):
            if field != "files":
                continue
            filename = Path((uploaded_name or "").replace("\\", "/")).name
            if not filename or filename.startswith(".") or Path(filename).suffix.lower() not in AUDIO_EXT:
                continue
            destination = (target_dir / filename).resolve()
            try:
                destination.relative_to(MUSIC_DIR)
            except ValueError:
                continue
            staged = target_dir / f".upload-{uuid.uuid4().hex}"
            try:
                with staged.open("xb") as output:
                    shutil.copyfileobj(file, output, 64 * 1024)
                    output.flush()
                    os.fsync(output.fileno())
                checksum = hash_file(staged)
                expected = self.headers.get("X-Content-SHA256")
                expected_size = self.headers.get("X-File-Size")
                if expected and not secrets.compare_digest(checksum, expected.lower()):
                    raise ValueError("Upload checksum mismatch")
                if expected_size is not None and staged.stat().st_size != int(expected_size):
                    raise ValueError("Upload size mismatch")
                with _file_lock:
                    os.replace(staged, destination)
            finally:
                staged.unlink(missing_ok=True)
            uploaded.append(
                {
                    "name": filename,
                    "path": destination.relative_to(MUSIC_DIR).as_posix(),
                    "size": destination.stat().st_size,
                    "sha256": checksum,
                }
            )
            ensure_art_for_relative(destination.relative_to(MUSIC_DIR).as_posix())

        if not uploaded:
            self._json(400, {"error": "no valid audio files uploaded"})
            return

        invalidate_cache()
        self._json(200, {"uploaded": uploaded, "libraryCount": len(list_library(force=True))})


def main() -> None:
    MUSIC_DIR.mkdir(parents=True, exist_ok=True)
    ART_CACHE_DIR.mkdir(parents=True, exist_ok=True)
    print(f"Music API on {HOST}:{PORT}, dir={MUSIC_DIR}")
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    def initial_scan():
        try:
            initial = list_library(force=True)
            print(f"Library: {len(initial)} tracks, index ready")
        except Exception as exc:
            print(f"Startup library scan failed: {exc}")
    threading.Thread(target=initial_scan, name="library-index", daemon=True).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
