# Music API на Orange Pi Zero 3

Лёгкий HTTP-сервер на Python 3 (только стандартная библиотека).

## Что слушает

- `GET /api/health` — проверка доступности
- `GET /api/library` — JSON список треков
- `GET /files/{relative_path}` — скачивание файла, поддерживает `Range`

Папка с музыкой по умолчанию: `/srv/music`.

## Установка

```bash
sudo mkdir -p /srv/music /opt/music-server
sudo cp server.py /opt/music-server/server.py
sudo cp music-api.service /etc/systemd/system/music-api.service
sudo systemctl daemon-reload
sudo systemctl enable --now music-api
```

Положите аудиофайлы в `/srv/music` (можно с подпапками).

## Адреса для приложения

- LAN: `http://192.168.1.80:8080`
- Tailscale: `http://<tailscale-ip>:8080`

Узнать Tailscale IP: `tailscale ip -4`.

На этом устройстве:

- LAN: `http://192.168.1.80:8080`
- Tailscale: `http://100.106.26.26:8080`
