#!/bin/sh
# ServerBox's SSH server launcher, run as root inside the guest by LocalServerManager.
#
# Environment (all optional):
#   INITIAL_USERNAME           user whose ~/.ssh/authorized_keys receives SERVERBOX_AUTHORIZED_KEYS
#   SERVERBOX_SSH_ADDRESS      address to listen on; 127.0.0.1 (default) keeps SSH on this device
#   SERVERBOX_SSH_PORT         port to listen on (default 2022)
#   SERVERBOX_AUTHORIZED_KEYS  newline-separated public keys to add
#   SERVERBOX_SSH_KEYS_ONLY    1 to refuse password logins
#   RESOLV, HOSTS, HOSTNAME    contents of /etc/resolv.conf, /etc/hosts and /etc/hostname
#   INITIAL_PASSWORD           password for INITIAL_USERNAME if the user has to be created
#
# Before starting dropbear it runs every executable in /etc/serverbox/autostart.d, in name order,
# in the background with output in /var/log/serverbox-autostart.log. That is the place for
# services a server needs (databases, web servers, ...) in guests without a working init.

unset LD_LIBRARY_PATH
unset LD_PRELOAD

# Android has no resolv.conf for PRoot to bind; the app passes the network's DNS servers.
[ -n "$RESOLV" ] && printf '%s\n' "$RESOLV" > /etc/resolv.conf
[ -n "$HOSTS" ] && printf '%s\n' "$HOSTS" > /etc/hosts
[ -n "$HOSTNAME" ] && printf '%s\n' "$HOSTNAME" > /etc/hostname

address="${SERVERBOX_SSH_ADDRESS:-127.0.0.1}"
port="${SERVERBOX_SSH_PORT:-2022}"

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
if [ ! -f /support/serverbox_client_key ]; then
    dropbearkey -t ed25519 -f /support/serverbox_client_key >/dev/null 2>&1
fi
client_pubkey="$(dropbearkey -y -f /support/serverbox_client_key 2>/dev/null | grep '^ssh-')"

# Filesystems installed before the extractor kept setuid bits lost them, and PRoot's fake root
# only lets sudo/su become root through that bit. Put it back (this script runs as fake root).
for suid in /usr/bin/sudo /bin/bbsuid /usr/bin/su /bin/su; do
    [ -f "$suid" ] && [ ! -L "$suid" ] && [ ! -u "$suid" ] && chmod u+s "$suid" 2>/dev/null
done

user="${INITIAL_USERNAME:-user}"
# Filesystems set up before PRoot faked root never got their user (useradd/chpasswd failed);
# create it now. addNonRootUser.sh reads INITIAL_USERNAME/INITIAL_PASSWORD.
if ! getent passwd "$user" >/dev/null 2>&1 && [ -x /support/addNonRootUser.sh ]; then
    rm -rf "/home/$user" 2>/dev/null # it skips users whose home already exists
    /support/addNonRootUser.sh
fi
home="$(getent passwd "$user" | cut -d: -f6)"
if [ -n "$home" ] && [ -d "$home" ]; then
    mkdir -p "$home/.ssh"
    touch "$home/.ssh/authorized_keys"
    printf '%s\n%s\n' "$client_pubkey" "$SERVERBOX_AUTHORIZED_KEYS" | while IFS= read -r key; do
        [ -z "$key" ] && continue
        grep -qxF "$key" "$home/.ssh/authorized_keys" || printf '%s\n' "$key" >> "$home/.ssh/authorized_keys"
    done
    chmod 700 "$home/.ssh"
    chmod 600 "$home/.ssh/authorized_keys"
    chown -R "$user:" "$home/.ssh" 2>/dev/null
fi

if [ -d /etc/serverbox/autostart.d ]; then
    mkdir -p /var/log
    for job in /etc/serverbox/autostart.d/*; do
        [ -f "$job" ] && [ -x "$job" ] || continue
        echo "== $(date) starting $job" >> /var/log/serverbox-autostart.log
        nohup "$job" >> /var/log/serverbox-autostart.log 2>&1 &
    done
fi

set -- -E -p "$address:$port"
[ "$SERVERBOX_SSH_KEYS_ONLY" = "1" ] && set -- "$@" -s -g
mkdir -p /run /var/log
# -F: stay in the foreground, a descendant of the session's process, which is how the app
# finds (isServerInProcTree.sh) and stops (killProcTree.sh) the server. Nothing reads our
# stdout while the server runs, so its log goes to a file rather than a pipe that fills up.
exec dropbear -F "$@" -P /run/dropbear.pid >> /var/log/dropbear.log 2>&1
