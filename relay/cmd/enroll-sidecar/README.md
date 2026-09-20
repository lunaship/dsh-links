# enroll-sidecar

Public invite dispenser in front of loopback Control. Only stdlib python3.

```bash
# server:
sudo mkdir -p /opt/enroll-sidecar
sudo cp enroll-sidecar.py /opt/enroll-sidecar/
cat > /opt/enroll-sidecar/enroll-sidecar.env <<EOF
CONTROL_URL=https://127.0.0.1:8080
ADMIN_TOKEN_FILE=/var/lib/docker/volumes/openship-dsh-links-relay-relay-data/_data/admin.token
BIND=127.0.0.1
PORT=8787
CORS_ORIGINS=*
INVITE_TTL=8h
EOF
chmod 600 /opt/enroll-sidecar/enroll-sidecar.env
sudo cp enroll-sidecar.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now enroll-sidecar
curl http://127.0.0.1:8787/health
```

Cloudflare tunnel (`/etc/cloudflared/config.yml`):

```yaml
ingress:
  - hostname: enroll.dshlinks.com
    service: http://127.0.0.1:8787
  - hostname: admin.dshlinks.com
    service: https://127.0.0.1:8080
    originRequest:
      noTLSVerify: true
  - service: http_status:404
```
