package tech.anl.library.proot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire protocol spoken by PRoot's `--droid_files` extension
 * (`src/extension/droid_files/droid_files.c`, branch `droid_files` of the UserLAnd PRoot fork).
 *
 * ## What PRoot intercepts
 *
 * At syscall-enter PRoot checks the (already host-translated) path of these syscalls:
 * `open`, `openat`, `creat`, `mkdir`, `mkdirat`, `unlink`, `unlinkat`, `getdents`, `getdents64`,
 * `newfstatat`/`fstatat64` and `faccessat`/`faccessat2`. Nothing else is forwarded: `rename*`,
 * `chmod*`, `truncate`, `readlink*`, `statx`, `rmdir`, `stat`/`lstat`/`access` (non-`at` forms)
 * all go to the kernel natively. A request is forwarded when the path is strictly below the
 * host translation of guest `/sdcard/` (i.e. `/sdcard/<Top>` and anything under it), except:
 *  - anything whose host path starts with the translation of `/sdcard/Android` (prefix match,
 *    no trailing slash, so `/sdcard/AndroidX` is excluded too), and
 *  - exactly `/sdcard/Download` itself (equality only -- its *children* ARE forwarded).
 *  - `fstatat` is only forwarded two levels deep (`/sdcard/<Top>/<x>`); `stat /sdcard/<Top>`
 *    stays native.
 * For `getdents*` the decision uses `readlink(/proc/<pid>/fd/<fd>)`, so the directory fd this
 * server hands out for `open` must resolve to a path under the host `/sdcard` translation, or
 * under PRoot's learned "check_path2" prefix (see below). Handing out a real
 * `/storage/emulated/0/<Top>/...` directory fd satisfies both.
 *
 * ## Transport
 *
 * The *tracee* (not PRoot itself) is made to run: `socket(AF_UNIX, SOCK_STREAM|SOCK_NONBLOCK)`,
 * `connect()` to the host translation of guest `/support/common/droid_files_socket` (the launch
 * script binds `$ROOT_PATH/support:/support/common`, so that is `filesDir/support/droid_files_socket`),
 * one `write()` of a whole [request][DroidFilesRequest], one `read()` of a status word, optionally
 * one `recvmsg()`, then `close()`. **One request per connection.** The tracee side is
 * non-blocking: a status `read()` that returns `EAGAIN` is simply retried (PRoot busy-polls, so
 * the server may take as long as it needs), but `recvmsg()` is NOT retried -- `EAGAIN` there
 * turns into `EINVAL` for the guest. The server therefore sends the fd message immediately after
 * the status word, with nothing slow in between.
 *
 * ## Request: `sock_req_t`
 *
 * ```
 * typedef struct { word_t sysCall; char path[4096]; char new_path[4096]; word_t sysargs[5]; } sock_req_t;
 * ```
 * `word_t` is PRoot's (= the app process's) native `unsigned long`: 8 bytes on 64-bit, 4 on
 * 32-bit; native byte order (little-endian on every Android ABI). Sizes: 8240 bytes (64-bit),
 * 8216 bytes (32-bit). Only `sysCall`, `path`, `sysargs[0]` and `sysargs[1]` are initialised;
 * `new_path` and `sysargs[2..4]` are stack garbage and must be ignored.
 *
 * `sysCall` is PRoot's own enumeration, NOT a kernel syscall number (so it is the same on every
 * ABI) -- see [Op]. `path` is the guest-visible absolute path (PRoot `detranslate_path`s it),
 * NUL-terminated. Caveat: for the `*at` calls PRoot resolves a relative path against the cwd,
 * not against `dirfd`.
 *
 * `sysargs` per op:
 *  - OPEN/OPENAT: `[0]` = open flags (guest `O_*` values; `O_DIRECTORY` differs between ARM and
 *    x86), `[1]` = mode. CREAT: `[0]` = `O_CREAT|O_WRONLY|O_TRUNC`, `[1]` = mode.
 *  - MKDIR/MKDIRAT: `[0]` = 0, `[1]` = mode.
 *  - UNLINK/UNLINKAT: both 0 -- `AT_REMOVEDIR` is NOT forwarded.
 *  - GETDENTS/GETDENTS64: `[0]` = `lseek(fd, 0, SEEK_CUR)` of the directory fd. PRoot advances
 *    that offset by exactly 1 after every successful call, so it is a *page index*.
 *  - FSTATAT: both 0 (`AT_SYMLINK_NOFOLLOW` is not forwarded).
 *  - FACCESSAT: `[0]` = access mode (`F_OK`/`R_OK`/`W_OK`/`X_OK`), `[1]` = flags (faccessat2 only).
 *
 * ## Reply
 *
 * A single `word_t` status, same size/endianness: `0` = success, otherwise a **positive** errno
 * (PRoot returns `-status` to the guest). A short read makes the guest see a bogus result.
 *  - MKDIR*, UNLINK*, FACCESSAT: status only.
 *  - OPEN, OPENAT, CREAT, FSTATAT: after status 0, send one more message: 1 data byte with one
 *    fd attached via `SCM_RIGHTS`. It must be a separate send from the status word (the kernel
 *    drops fds attached to bytes consumed by a plain `read()`). For OPEN* the fd becomes the
 *    guest's fd. For FSTATAT PRoot `fstat()`s the fd into the guest's stat buffer and closes it.
 *    PRoot also records "check_path2": the prefix of `readlink(fd)` left after removing the
 *    longest common suffix with the guest path (e.g. `/storage/emulated/0` for a
 *    `/storage/emulated/0/Documents/x` fd opened as `/sdcard/Documents/x`); later getdents fds
 *    under that prefix are forwarded too, with the prefix mapped back to `/sdcard`.
 *  - GETDENTS/GETDENTS64: `0` = a page is ready in the host file behind guest
 *    `/support/common/droid_files_getdents` (`filesDir/support/droid_files_getdents`). PRoot
 *    reads at most [GETDENTS_READ_LIMIT] bytes of it into the guest buffer (ignoring the guest
 *    buffer size) and returns that byte count. `1` = end of directory (guest sees 0). Any other
 *    value is an errno -- so EPERM (1) cannot be reported for getdents. PRoot reads the file
 *    before it closes the socket, so the server holds the shared file until it sees EOF.
 *    Records: `struct linux_dirent64` for GETDENTS64, legacy `struct linux_dirent` for GETDENTS
 *    (see [Dirents]).
 */
object DroidFilesProtocol {
    const val SOCKET_NAME = "droid_files_socket"
    const val GETDENTS_NAME = "droid_files_getdents"
    const val PATH_FIELD_SIZE = 4096
    const val SYSARG_COUNT = 5
    /** PRoot reads the getdents file into a `char[1000]`. */
    const val GETDENTS_READ_LIMIT = 1000
    /** Status telling PRoot a getdents stream is exhausted. */
    const val STATUS_GETDENTS_EOF = 1L
    /** The data byte that carries the SCM_RIGHTS fd (PRoot sends '!' itself; any byte works). */
    const val FD_CARRIER_BYTE: Byte = '!'.code.toByte()

    /** `sock_req_t.sysCall` values: PRoot's own enumeration, identical on every ABI. */
    object Op {
        const val OPEN = 0
        const val OPENAT = 1
        const val CREAT = 2
        const val MKDIR = 3
        const val MKDIRAT = 4
        const val UNLINK = 5
        const val UNLINKAT = 6
        const val GETDENTS = 7
        const val GETDENTS64 = 8
        const val FSTATAT = 9
        const val FACCESSAT = 10

        fun name(op: Int): String = when (op) {
            OPEN -> "open"; OPENAT -> "openat"; CREAT -> "creat"
            MKDIR -> "mkdir"; MKDIRAT -> "mkdirat"
            UNLINK -> "unlink"; UNLINKAT -> "unlinkat"
            GETDENTS -> "getdents"; GETDENTS64 -> "getdents64"
            FSTATAT -> "fstatat"; FACCESSAT -> "faccessat"
            else -> "op$op"
        }
    }

    /** Linux errno values (identical on all Android ABIs); kept literal so JVM tests work. */
    object Errno {
        const val EPERM = 1
        const val ENOENT = 2
        const val EIO = 5
        const val EACCES = 13
        const val EEXIST = 17
        const val ENOTDIR = 20
        const val EISDIR = 21
        const val EINVAL = 22
        const val ENOSPC = 28
        const val EROFS = 30
        const val ENAMETOOLONG = 36
        const val ENOSYS = 38
        const val ENOTEMPTY = 39
    }

    fun requestSize(wordSize: Int): Int {
        require(wordSize == 4 || wordSize == 8) { "word size must be 4 or 8, was $wordSize" }
        return wordSize + 2 * PATH_FIELD_SIZE + SYSARG_COUNT * wordSize
    }

    fun parseRequest(bytes: ByteArray, wordSize: Int): DroidFilesRequest {
        val size = requestSize(wordSize)
        require(bytes.size >= size) { "request is ${bytes.size} bytes, expected $size" }
        val buf = ByteBuffer.wrap(bytes, 0, size).order(ByteOrder.LITTLE_ENDIAN)
        val sysCall = readWord(buf, wordSize)
        val path = cString(bytes, wordSize, PATH_FIELD_SIZE)
        val newPath = cString(bytes, wordSize + PATH_FIELD_SIZE, PATH_FIELD_SIZE)
        buf.position(wordSize + 2 * PATH_FIELD_SIZE)
        val args = LongArray(SYSARG_COUNT) { readWord(buf, wordSize) }
        return DroidFilesRequest(sysCall.toInt(), path, newPath, args)
    }

    /** Builds a request exactly as PRoot lays it out (used by tests). */
    fun encodeRequest(request: DroidFilesRequest, wordSize: Int): ByteArray {
        val out = ByteBuffer.allocate(requestSize(wordSize)).order(ByteOrder.LITTLE_ENDIAN)
        writeWord(out, request.sysCall.toLong(), wordSize)
        putCString(out, request.path)
        putCString(out, request.newPath)
        for (i in 0 until SYSARG_COUNT) writeWord(out, request.sysargs.getOrElse(i) { 0L }, wordSize)
        return out.array()
    }

    fun encodeStatus(status: Long, wordSize: Int): ByteArray {
        val out = ByteBuffer.allocate(wordSize).order(ByteOrder.LITTLE_ENDIAN)
        writeWord(out, status, wordSize)
        return out.array()
    }

    private fun readWord(buf: ByteBuffer, wordSize: Int): Long =
        if (wordSize == 8) buf.long else buf.int.toLong() and 0xffffffffL

    private fun writeWord(buf: ByteBuffer, value: Long, wordSize: Int) {
        if (wordSize == 8) buf.putLong(value) else buf.putInt(value.toInt())
    }

    private fun cString(bytes: ByteArray, offset: Int, max: Int): String {
        var end = offset
        val limit = offset + max
        while (end < limit && bytes[end] != 0.toByte()) end++
        return String(bytes, offset, end - offset, Charsets.UTF_8)
    }

    private fun putCString(buf: ByteBuffer, value: String) {
        val start = buf.position()
        val encoded = value.toByteArray(Charsets.UTF_8)
        require(encoded.size < PATH_FIELD_SIZE) { "path too long" }
        buf.put(encoded)
        buf.position(start + PATH_FIELD_SIZE)
    }
}

class DroidFilesRequest(
    val sysCall: Int,
    val path: String,
    val newPath: String,
    val sysargs: LongArray,
) {
    val flags: Int get() = sysargs[0].toInt()
    val mode: Int get() = sysargs[1].toInt()

    override fun toString(): String =
        "${DroidFilesProtocol.Op.name(sysCall)}($path, 0x${sysargs[0].toString(16)}, 0${sysargs[1].toString(8)})"
}

/**
 * Maps guest `open(2)` flags to a SAF / [android.os.ParcelFileDescriptor] mode string.
 */
object OpenFlags {
    const val O_ACCMODE = 3
    const val O_RDONLY = 0
    const val O_WRONLY = 1
    const val O_RDWR = 2
    const val O_CREAT = 0x40 // 0100
    const val O_EXCL = 0x80 // 0200
    const val O_TRUNC = 0x200 // 01000
    const val O_APPEND = 0x400 // 02000
    private const val O_DIRECTORY_ARM = 0x4000 // 040000 (arm, arm64)
    private const val O_DIRECTORY_X86 = 0x10000 // 0200000 (x86, x86_64)

    fun oDirectory(isX86: Boolean): Int = if (isX86) O_DIRECTORY_X86 else O_DIRECTORY_ARM

    data class Plan(
        val mode: String,
        val write: Boolean,
        val create: Boolean,
        val exclusive: Boolean,
        val directory: Boolean,
        /** Append requested but not expressible in [mode] (O_RDWR|O_APPEND): set O_APPEND on the fd. */
        val appendViaFcntl: Boolean,
    )

    /**
     * @param wSafe whether mode "w" is non-truncating (Android Q+). Before Q, "w" truncated, so a
     *  plain O_WRONLY is mapped to "rw" there.
     */
    fun plan(flags: Int, isX86: Boolean, wSafe: Boolean = true): Plan {
        val acc = flags and O_ACCMODE
        val trunc = flags and O_TRUNC != 0
        val append = flags and O_APPEND != 0
        val write = acc != O_RDONLY
        val mode = when (acc) {
            O_RDONLY -> "r"
            O_WRONLY -> when {
                trunc -> "wt"
                append -> "wa"
                else -> if (wSafe) "w" else "rw"
            }
            else -> if (trunc) "rwt" else "rw" // O_RDWR (and the invalid 3)
        }
        return Plan(
            mode = mode,
            write = write,
            create = flags and O_CREAT != 0,
            exclusive = flags and O_CREAT != 0 && flags and O_EXCL != 0,
            directory = flags and oDirectory(isX86) != 0,
            appendViaFcntl = append && write && !mode.contains('a'),
        )
    }
}
