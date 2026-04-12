# Security Rules

- All secrets (passwords, keys, tokens, UUIDs) come from .env — never hardcoded
- Each user gets unique UUID (VLESS) and unique password (Hysteria2)
- SSH: key-based auth only after initial setup, disable password auth
- SSH: non-standard port to reduce noise
- Firewall: default deny, explicit allow only needed ports
- TLS certificates: automated renewal via acme.sh + cron
- 3X-UI panel: strong password, HTTPS only, non-standard port
- Never log sensitive data (passwords, keys) to stdout or files
- File permissions: private keys 600, scripts 755, configs 644
