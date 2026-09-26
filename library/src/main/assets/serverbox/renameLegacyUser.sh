#!/bin/sh
# Renames the pre-ServerBox default user "userland" (and its group and home directory) to the
# session's user, run as root inside the guest before a server starts. The app's database
# migration already moved the session to the new name; this makes the filesystem match, so
# files, SSH keys and the password carry over.
#
# Usage: renameLegacyUser.sh <new-name>. Does nothing unless "userland" exists and <new-name>
# doesn't, so it is safe to run on every start.

old=userland
new="$1"

[ -n "$new" ] && [ "$new" != "$old" ] || exit 0
grep -q "^$old:" /etc/passwd 2>/dev/null || exit 0
grep -q "^$new:" /etc/passwd 2>/dev/null && exit 0

# Field 1 is the name in every file. passwd's field 6 is the home directory; group's field 4
# and gshadow's fields 3 and 4 are comma-separated member lists.
rename_in() {
    file="$1"
    [ -f "$file" ] || return 0
    awk -F: -v OFS=: -v old="$old" -v new="$new" -v file="$file" '
        function members(list,    n, i, parts, out) {
            n = split(list, parts, ",")
            out = ""
            for (i = 1; i <= n; i++) {
                if (parts[i] == old) parts[i] = new
                out = out (i > 1 ? "," : "") parts[i]
            }
            return out
        }
        {
            if ($1 == old) $1 = new
            if (file ~ /passwd$/ && $1 == new && $6 == "/home/" old) $6 = "/home/" new
            if (file ~ /\/group$/ && NF >= 4) $4 = members($4)
            if (file ~ /gshadow$/ && NF >= 4) { $3 = members($3); $4 = members($4) }
            print
        }' "$file" > "$file.serverbox-tmp" && cat "$file.serverbox-tmp" > "$file"
    rm -f "$file.serverbox-tmp"
}

for file in /etc/passwd /etc/shadow /etc/group /etc/gshadow; do
    rename_in "$file"
done

if [ -d "/home/$old" ] && [ ! -e "/home/$new" ]; then
    mv "/home/$old" "/home/$new"
fi

echo "Renamed user $old to $new"
