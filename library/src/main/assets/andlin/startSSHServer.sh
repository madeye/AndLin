#!/bin/sh
# AndLin's SSH server launcher, run as root inside the guest by LocalServerManager.
#
# Environment (all optional):
#   INITIAL_USERNAME        user whose ~/.ssh/authorized_keys receives ANDLIN_AUTHORIZED_KEYS
#   ANDLIN_SSH_ADDRESS      address to listen on; 127.0.0.1 (default) keeps SSH on this device
#   ANDLIN_SSH_PORT         port to listen on (default 2022)
#   ANDLIN_AUTHORIZED_KEYS  newline-separated public keys to add
#   ANDLIN_SSH_KEYS_ONLY    1 to refuse password logins
#
# Before starting dropbear it runs every executable in /etc/andlin/autostart.d, in name order,
# in the background with output in /var/log/andlin-autostart.log. That is the place for
# services a server needs (databases, web servers, ...) in guests without a working init.

unset LD_LIBRARY_PATH
unset LD_PRELOAD

address="${ANDLIN_SSH_ADDRESS:-127.0.0.1}"
port="${ANDLIN_SSH_PORT:-2022}"

if [ ! -f /support/.ssh_setup_complete ]; then
    rm -rf /etc/dropbear
    mkdir -p /etc/dropbear
    dropbearkey -t ed25519 -f /etc/dropbear/dropbear_ed25519_host_key >/dev/null 2>&1
    dropbearkey -t ecdsa -s 521 -f /etc/dropbear/dropbear_ecdsa_host_key >/dev/null 2>&1
    dropbearkey -t rsa -s 3072 -f /etc/dropbear/dropbear_rsa_host_key >/dev/null 2>&1
    touch /support/.ssh_setup_complete
fi

# The app's own terminal logs in with this key (dbclient -i), so it keeps working when
# password logins are refused.
if [ ! -f /support/andlin_client_key ]; then
    dropbearkey -t ed25519 -f /support/andlin_client_key >/dev/null 2>&1
fi
client_pubkey="$(dropbearkey -y -f /support/andlin_client_key 2>/dev/null | grep '^ssh-')"

user="${INITIAL_USERNAME:-user}"
home="$(getent passwd "$user" | cut -d: -f6)"
if [ -n "$home" ] && [ -d "$home" ]; then
    mkdir -p "$home/.ssh"
    touch "$home/.ssh/authorized_keys"
    printf '%s\n%s\n' "$client_pubkey" "$ANDLIN_AUTHORIZED_KEYS" | while IFS= read -r key; do
        [ -z "$key" ] && continue
        grep -qxF "$key" "$home/.ssh/authorized_keys" || printf '%s\n' "$key" >> "$home/.ssh/authorized_keys"
    done
    chmod 700 "$home/.ssh"
    chmod 600 "$home/.ssh/authorized_keys"
    chown -R "$user:" "$home/.ssh" 2>/dev/null
fi

if [ -d /etc/andlin/autostart.d ]; then
    mkdir -p /var/log
    for job in /etc/andlin/autostart.d/*; do
        [ -f "$job" ] && [ -x "$job" ] || continue
        echo "== $(date) starting $job" >> /var/log/andlin-autostart.log
        nohup "$job" >> /var/log/andlin-autostart.log 2>&1 &
    done
fi

set -- -E -p "$address:$port"
[ "$ANDLIN_SSH_KEYS_ONLY" = "1" ] && set -- "$@" -s -g
mkdir -p /run
exec dropbear "$@" -P /run/dropbear.pid
