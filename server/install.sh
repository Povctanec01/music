#!/bin/sh
set -eu
# Run from this repository's server directory on the Orange Pi, as root.
[ "$(id -u)" -eq 0 ] || { echo 'Run with sudo' >&2; exit 1; }
script_dir=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
id music >/dev/null 2>&1 || useradd --system --user-group --home-dir /nonexistent --shell /usr/sbin/nologin music
install -d -o root -g music -m 0750 /etc/music-api
install -d -o music -g music -m 0750 /srv/music /var/cache/music-art
install -d -o root -g root -m 0755 /opt/music-server
if [ ! -f /etc/music-api/token ]; then
    (umask 077; python3 -c 'import secrets; print(secrets.token_urlsafe(48))' > /etc/music-api/token)
fi
chown root:music /etc/music-api/token
chmod 0640 /etc/music-api/token
install -m 0644 "$script_dir/server.py" /opt/music-server/server.py
install -m 0644 "$script_dir/music-api.service" /etc/systemd/system/music-api.service
# Existing audio remains in place. Grant the service group access without following symlinks.
find /srv/music -type d -exec chgrp music {} + -exec chmod g+rwx {} +
find /srv/music -type f -exec chgrp music {} + -exec chmod g+rw {} +
if [ -d "$script_dir/../web/dist" ]; then
    install -d -m 0755 /opt/music-server/web
    cp -R "$script_dir/../web/dist/." /opt/music-server/web/
fi
systemctl daemon-reload
systemctl enable --now music-api
systemctl restart music-api
echo 'Installed. Enter the server token in Android/web settings; it is stored in /etc/music-api/token.'
