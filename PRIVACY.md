# ServerBox Privacy Policy

_Last updated: 2026-09-27_

ServerBox turns an Android phone into a Linux server. This policy explains what the app does with
your data. In short: ServerBox does not collect, store, sell or share any personal data. It has no
accounts, ads, analytics or crash reporting.

## Data on your device

Everything ServerBox creates stays on your phone, in the app's private storage: the Linux
distributions you install, your files inside them, your sessions and settings, the usernames,
passwords and SSH keys you set, and the setup log. Uninstalling ServerBox deletes all of it.

## Network connections

ServerBox connects to the internet only to do what you ask:

- **Downloading distributions.** When you install a distribution, ServerBox downloads its image
  from the GitHub Container Registry (`ghcr.io`) or, for devices set to a time zone in mainland
  China, from public mirrors of it. Like any web request, these servers see your IP address and
  the image being downloaded; ServerBox sends nothing else. See
  [GitHub's privacy statement](https://docs.github.com/site-policy/privacy-policies/github-general-privacy-statement).
- **Programs you run.** Software inside a distribution (for example `apt`, `curl` or your own
  services) makes its own network connections. ServerBox does not monitor or collect that traffic.
- **The SSH server.** Each distribution runs an SSH server on the phone. By default it only
  accepts connections from the phone itself; if you turn on **Allow SSH from the network**, other
  devices on your network can connect with the username and password or keys you set.

## Permissions

- **Internet, network and Wi-Fi state:** to download distributions, run the SSH server and keep
  Wi-Fi awake while a server is running.
- **Notifications and foreground service:** to keep servers running in the background and show
  that they are.
- **Ignore battery optimization, wake lock, run at startup:** so servers keep running with the
  screen off and can restart after a reboot, if you enable it.
- **Storage (folders you choose):** programs inside a distribution can reach the phone's shared
  storage at `/sdcard` only after you grant access to a folder. ServerBox does not read those
  files for itself.

## Children

ServerBox is a technical tool not directed at children, and it does not knowingly collect data
from anyone.

## Changes and contact

Changes to this policy are published in this file, and its history is in the
[repository](https://github.com/madeye/ServerBox/commits/master/PRIVACY.md). Questions:
[open an issue](https://github.com/madeye/ServerBox/issues).
