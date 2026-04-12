# Server Operations Rules

- Every bash script MUST start with `set -euo pipefail`
- Scripts MUST be idempotent — safe to re-run without breaking state
- Use `command -v` to check if a tool is installed before installing
- Use `systemctl is-active` before restarting services
- Paths via variables, not hardcoded strings
- Always `apt-get` not `apt` in scripts (non-interactive)
- Use `-y` flag for non-interactive installs
- Check OS/distro before package operations: `source /etc/os-release`
- Log actions to stdout with timestamps for debugging
- Always backup configs before overwriting: `cp file file.bak.$(date +%s)`
