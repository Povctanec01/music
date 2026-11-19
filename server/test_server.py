"""Local integration checks; never use the real music directory or internet."""
import http.client
import io
import json
from pathlib import Path
import tempfile
import threading
import unittest
import wave
import hashlib
import os
from unittest.mock import patch

import server


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        root = Path(self.temp.name)
        self.patches = [
            patch.object(server, 'MUSIC_DIR', root / 'music'),
            patch.object(server, 'ART_CACHE_DIR', root / 'art'),
            patch.object(server, 'WEB_DIR', root / 'web'),
            patch.object(server, 'schedule_art_prefetch'),
            patch.object(server, 'API_TOKEN', 'test-access-token'),
            patch.object(server, 'TOKEN_FILE', ''),
            patch.object(server, 'extract_youtube_thumbnail', return_value=None),
        ]
        for item in self.patches:
            item.start()
        server.MUSIC_DIR.mkdir()
        server.WEB_DIR.mkdir()
        (server.WEB_DIR / 'index.html').write_text('<html>music</html>')
        (server.MUSIC_DIR / 'трек %.mp3').write_bytes(b'0123456789')
        (server.MUSIC_DIR / 'empty.wav').touch()
        server.invalidate_cache()
        server._metadata_cache.clear()
        self.http = server.ThreadingHTTPServer(('127.0.0.1', 0), server.Handler)
        self.thread = threading.Thread(target=self.http.serve_forever, daemon=True)
        self.thread.start()

    def tearDown(self):
        self.http.shutdown()
        self.http.server_close()
        self.thread.join()
        for item in reversed(self.patches):
            item.stop()
        self.temp.cleanup()

    def request(self, method, path, body=None, headers=None):
        conn = http.client.HTTPConnection(*self.http.server_address, timeout=3)
        try:
            auth_headers = {'Authorization': 'Bearer test-access-token', **(headers or {})}
            conn.request(method, path, body, auth_headers)
            response = conn.getresponse()
            return response.status, dict(response.getheaders()), response.read()
        finally:
            conn.close()

    def test_health_library_web_and_head(self):
        self.assertEqual(json.loads(self.request('GET', '/api/health')[2]), {'status': 'ok'})
        files = json.loads(self.request('GET', '/api/library')[2])['files']
        self.assertEqual(len(files), 2)
        self.assertEqual(self.request('GET', '/settings')[2], b'<html>music</html>')
        self.assertEqual(self.request('GET', '/api/unknown')[0], 404)
        self.assertEqual(self.request('OPTIONS', '/api/upload')[0], 204)
        # A HEAD response must leave the persistent connection ready for the next request.
        conn = http.client.HTTPConnection(*self.http.server_address, timeout=3)
        try:
            conn.request('HEAD', '/api/library')
            self.assertEqual(conn.getresponse().read(), b'')
            conn.request('GET', '/api/health')
            self.assertEqual(json.loads(conn.getresponse().read()), {'status': 'ok'})
        finally:
            conn.close()

    def test_file_ranges(self):
        path = '/files/' + server.urllib.parse.quote('трек %.mp3')
        for spec, expected in [('bytes=2-5', b'2345'), ('bytes=7-', b'789'),
                               ('bytes=-3', b'789'), ('bytes=0-99', b'0123456789')]:
            with self.subTest(spec=spec):
                code, headers, data = self.request('GET', path, headers={'Range': spec})
                self.assertEqual(code, 206)
                self.assertEqual(data, expected)
                self.assertEqual(int(headers['Content-Length']), len(data))
        for spec in ['bytes=10-', 'bytes=5-2', 'bytes=-0', 'bytes=-', 'bytes=abc']:
            with self.subTest(spec=spec):
                self.assertEqual(self.request('GET', path, headers={'Range': spec})[0], 416)
        self.assertEqual(self.request('GET', '/files/empty.wav')[2], b'')

    def test_paths_and_literal_percent(self):
        self.assertIsNone(server.safe_file('../outside.mp3'))
        self.assertIsNone(server.safe_directory('../outside'))
        (server.MUSIC_DIR / 'literal%20name.mp3').write_bytes(b'literal')
        path = '/files/literal%2520name.mp3'
        self.assertEqual(self.request('GET', path)[2], b'literal')
        self.assertEqual(self.request('GET', '/files/%2e%2e/outside.mp3')[0], 404)

    def test_upload_delete_and_invalid_upload(self):
        boundary = 'music-test-boundary'
        body = (f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
                'filename="upload.wav"\r\nContent-Type: audio/wav\r\n\r\n').encode() + b'RIFFtest' + f'\r\n--{boundary}--\r\n'.encode()
        headers = {'Content-Type': f'multipart/form-data; boundary={boundary}'}
        code, _, data = self.request('POST', '/api/upload?path=edits%2520literal', body, headers)
        self.assertEqual(code, 200)
        self.assertEqual(json.loads(data)['uploaded'][0]['path'], 'edits%20literal/upload.wav')
        self.assertTrue((server.MUSIC_DIR / 'edits%20literal/upload.wav').is_file())
        self.assertEqual(self.request('DELETE', '/api/file/edits%2520literal/upload.wav')[0], 200)
        self.assertEqual(self.request('POST', '/api/upload?path=../bad', body, headers)[0], 400)

    def test_version_tags(self):
        self.assertEqual(server.parse_version_tag('v1.0.30+42'), ('1.0.30', 42))
        self.assertEqual(server.parse_version_tag('v1.1.0-beta+43'), ('1.1.0-beta', 43))
        for value in ['v1.0', 'v+42', 'v1+-1', 'v1+0', 'vv1+2', 'v1+abc']:
            with self.subTest(value=value):
                self.assertEqual(server.parse_version_tag(value), (None, None))

    def test_art_cache_and_sidecar(self):
        audio = server.MUSIC_DIR / 'трек %.mp3'
        payload = b'cover' * 100
        server.store_sidecar_art(audio, payload)
        self.assertTrue(server.sidecar_has_art(audio))
        self.assertEqual(server.resolve_art(audio.name)[0], payload)
        mtime = int(audio.stat().st_mtime)
        self.assertEqual(server.load_cached_art(audio.name, mtime)[0], payload)
        self.assertIsNone(server.load_cached_art(audio.name, mtime + 1))
        server.clear_cached_art(audio.name)
        self.assertIsNone(server.load_cached_art(audio.name, mtime))

    def test_refresh_preserves_original_sidecar_and_percent_name(self):
        audio = server.MUSIC_DIR / 'literal%20.wav'
        audio.write_bytes(b'broken audio')
        cover = b'original-cover' * 100
        server.store_sidecar_art(audio, cover)
        server.refresh_library_art()
        self.assertEqual(server.resolve_art(audio.name)[0], cover)
        self.assertEqual(audio.with_suffix('.jpg').read_bytes(), cover)

    def test_library_includes_duration_for_sorting(self):
        audio = server.MUSIC_DIR / 'stereo.wav'
        with wave.open(str(audio), 'wb') as output:
            output.setparams((2, 2, 8000, 0, 'NONE', 'not compressed'))
            output.writeframes(b'\x00' * 32000)
        entry = next(item for item in server.list_library(force=True) if item['path'] == audio.name)
        self.assertEqual(entry['durationMs'], 1000)

    def test_streaming_multipart_binary_boundaries_unicode_and_multiple_files(self):
        boundary = 'test-streaming-boundary'
        data = b'\x00\xff' * 40000 + b'\r\n--test-streaming-boundaryX' + b'end'
        parts = []
        for name, payload in [('песня.wav', data), ('second.mp3', b'second')]:
            parts.append((f'--{boundary}\r\nContent-Disposition: form-data; name="files"; '
                          f'filename="{name}"\r\n\r\n').encode('utf-8') + payload + b'\r\n')
        body = b''.join(parts) + f'--{boundary}--\r\n'.encode()
        class SmallChunks(io.BytesIO):
            def read(self, size=-1):
                return super().read(min(size, 7))
        reader = server.MultipartReader(SmallChunks(body), len(body))
        actual = [(name, filename, stream.read()) for name, filename, stream in reader.parts(f'multipart/form-data; boundary={boundary}')]
        self.assertEqual(actual, [('files', 'песня.wav', data), ('files', 'second.mp3', b'second')])
        truncated = server.MultipartReader(io.BytesIO(body[:-10]), len(body) - 10)
        with self.assertRaises(ValueError):
            list(truncated.parts(f'multipart/form-data; boundary={boundary}'))

    def test_release_metadata_and_apk_proxy_with_unknown_length(self):
        release = {'repo': 'owner/repo', 'apkUrl': 'https://example.invalid/app.apk', 'apkFileName': 'music.apk'}
        class Download(io.BytesIO):
            headers = {'Content-Type': 'application/vnd.android.package-archive'}
        with patch.object(server, 'github_latest_release', return_value=release):
            code, _, data = self.request('GET', '/api/releases/latest?repo=owner/repo')
            self.assertEqual(code, 200)
            self.assertIn('/api/apk/latest?repo=owner%2Frepo', json.loads(data)['apkUrl'])
            with patch.object(server.urllib.request, 'urlopen', side_effect=lambda *a, **kw: Download(b'APK-test')):
                code, headers, data = self.request('GET', '/api/apk/latest?repo=owner/repo')
                self.assertEqual((code, data), (200, b'APK-test'))
                self.assertEqual(headers['Connection'], 'close')
                self.assertEqual(self.request('HEAD', '/api/apk/latest?repo=owner/repo')[2], b'')
        self.assertEqual(self.request('GET', '/api/releases/latest')[0], 400)

    def test_invalid_prefetch_input_returns_client_error(self):
        for body in [b'{', b'[]', b'{"paths":"wrong"}']:
            with self.subTest(body=body):
                self.assertEqual(self.request('POST', '/api/art/prefetch', body, {'Content-Type': 'application/json'})[0], 400)

    def test_auth_and_cors_protect_mutations(self):
        self.assertEqual(self.request('DELETE', '/api/file/empty.wav', headers={'Authorization': ''})[0], 401)
        self.assertTrue((server.MUSIC_DIR / 'empty.wav').exists())
        self.assertEqual(self.request('DELETE', '/api/file/empty.wav', headers={'Origin': 'https://evil.invalid'})[0], 401)
        self.assertEqual(self.request('OPTIONS', '/api/upload', headers={'Origin': 'https://evil.invalid'})[0], 403)
        with patch.object(server, 'API_TOKEN', ''):
            self.assertEqual(self.request('DELETE', '/api/file/empty.wav')[0], 503)

    def test_delete_restore_and_conflict_preserve_files(self):
        path = '/api/file/' + server.urllib.parse.quote('трек %.mp3')
        data = json.loads(self.request('DELETE', path)[2])
        identifier = data['trashId']
        self.assertIsNone(server.safe_file('трек %.mp3'))
        self.assertEqual(self.request('GET', '/files/.trash/' + identifier + '/' + server.urllib.parse.quote('трек %.mp3'))[0], 404)
        self.assertEqual(json.loads(self.request('GET', '/api/trash')[2])['files'][0]['id'], identifier)
        restore = f'/api/trash/{identifier}/restore'
        self.assertEqual(self.request('POST', restore)[0], 200)
        self.assertEqual((server.MUSIC_DIR / 'трек %.mp3').read_bytes(), b'0123456789')
        identifier = json.loads(self.request('DELETE', path)[2])['trashId']
        (server.MUSIC_DIR / 'трек %.mp3').write_bytes(b'new')
        self.assertEqual(self.request('POST', f'/api/trash/{identifier}/restore')[0], 409)
        self.assertEqual((server.MUSIC_DIR / 'трек %.mp3').read_bytes(), b'new')

    def test_same_size_update_and_persistent_index(self):
        audio = server.MUSIC_DIR / 'трек %.mp3'
        original = next(item for item in server.list_library(force=True) if item['path'] == audio.name)
        stamp = audio.stat().st_mtime_ns
        audio.write_bytes(b'abcdefghij')
        # POSIX ctime detects edits even if mtime is restored. Windows ctime is birth time.
        os.utime(audio, ns=(stamp, stamp if os.name != 'nt' else stamp + 1_000_000))
        changed = next(item for item in server.list_library(force=True) if item['path'] == audio.name)
        self.assertNotEqual(original['sha256'], changed['sha256'])
        server._metadata_cache.clear()
        with patch.object(server, 'audio_metadata', side_effect=AssertionError('must reuse SQLite')):
            again = server.list_library(force=True)
        self.assertEqual(next(item for item in again if item['path'] == audio.name)['sha256'], hashlib.sha256(b'abcdefghij').hexdigest())
        self.assertEqual(self.request('GET', '/files/' + server.urllib.parse.quote(audio.name), headers={'If-Match': '"old"'})[0], 412)

    def test_failed_upload_integrity_preserves_existing_audio(self):
        audio = server.MUSIC_DIR / 'existing.wav'
        audio.write_bytes(b'original')
        body = b'--boundary\r\nContent-Disposition: form-data; name="files"; filename="existing.wav"\r\n\r\nreplacement\r\n--boundary--\r\n'
        code = self.request('POST', '/api/upload', body, {'Content-Type': 'multipart/form-data; boundary=boundary', 'X-Content-SHA256': 'bad'})[0]
        self.assertEqual(code, 400)
        self.assertEqual(audio.read_bytes(), b'original')
        self.assertEqual(list(server.MUSIC_DIR.glob('.upload-*')), [])

    def test_failed_atomic_commit_keeps_original_and_cleans_staging(self):
        audio = server.MUSIC_DIR / 'existing.wav'
        audio.write_bytes(b'original')
        body = b'--boundary\r\nContent-Disposition: form-data; name="files"; filename="existing.wav"\r\n\r\nreplacement\r\n--boundary--\r\n'
        with patch.object(server.os, 'replace', side_effect=OSError('simulated storage failure')):
            self.assertEqual(self.request('POST', '/api/upload', body, {'Content-Type': 'multipart/form-data; boundary=boundary'})[0], 500)
        self.assertEqual(audio.read_bytes(), b'original')
        self.assertEqual(list(server.MUSIC_DIR.glob('.upload-*')), [])


if __name__ == '__main__':
    unittest.main()
