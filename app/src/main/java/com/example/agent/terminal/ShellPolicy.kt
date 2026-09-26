package com.example.agent.terminal

/**
 * Terminal policy — decides whether a shell command may run automatically or needs the
 * user's approval first.
 *
 * The rule the product asked for is: *only destructive commands need approval*. Installing
 * / upgrading / removing packages, deleting data, killing processes, writing outside the
 * workspace or piping the network into a shell are the cases that qualify. Everything else
 * (ls, cat, grep, git status, curl to stdout, running a script) runs on its own.
 *
 * This class is intentionally plain JVM code: no Android, no regex engine beyond the JDK,
 * so it is unit-testable and its verdict is deterministic. It is a *heuristic gate*, not a
 * sandbox: a hostile command can be written in ways this does not recognise. It exists to
 * keep an honest agent from silently mutating the device, not to stop a determined attacker.
 */
object ShellPolicy {

    /** A policy decision for one command line. */
    data class Verdict(
        val destructive: Boolean,
        /** Short Indonesian reason shown to the user, empty when [destructive] is false. */
        val reason: String = ""
    )

    /** Package managers: only their mutating sub-commands are destructive. */
    private val PACKAGE_MANAGERS = setOf(
        "apt", "apt-get", "aptitude", "apk", "pkg", "dpkg", "yum", "dnf", "pacman", "zypper",
        "pip", "pip3", "pipx", "poetry", "uv", "conda", "npm", "npx", "yarn", "pnpm", "bun",
        "gem", "cargo", "brew", "composer", "dotnet", "go", "gradle", "mvn", "sdkmanager"
    )

    /** Sub-commands of a package manager that change the installed software set. */
    private val MUTATING_SUBCOMMANDS = setOf(
        "install", "i", "uninstall", "remove", "rm", "update", "upgrade", "dist-upgrade",
        "full-upgrade", "autoremove", "purge", "add", "del", "delete", "global", "get",
        "publish", "ci", "prune", "sync", "refresh", "clean"
    )

    /** Commands that are destructive no matter how they are called. */
    private val ALWAYS_DESTRUCTIVE = setOf(
        "rm", "rmdir", "shred", "dd", "mkfs", "mkfs.ext4", "wipefs", "fdisk", "parted",
        "kill", "pkill", "killall", "reboot", "shutdown", "halt", "poweroff",
        "su", "sudo", "doas", "mount", "umount", "insmod", "rmmod", "modprobe",
        "setenforce", "fastboot", "pm", "am", "svc", "ifconfig", "iptables", "nft"
    )

    /** Commands whose whole purpose is to move data off the device. */
    private val EXFILTRATION = setOf("scp", "rsync", "ssh", "sftp", "nc", "ncat", "telnet")

    /** Prefixes that make any following command privileged. */
    private val PRIVILEGE_PREFIXES = setOf("sudo", "doas", "su")

    /** Env-assignment / wrapper words skipped when looking for the real command name. */
    private val SKIPPED_HEADS = setOf(
        "env", "nohup", "setsid", "time", "nice", "ionice", "stdbuf", "command", "exec",
        "builtin", "eval", "xargs", "watch"
    )

    /** Descending into a shell also counts. */
    private val SHELLS = setOf("sh", "bash", "zsh", "ash", "dash", "mksh", "ksh")

    private val SEPARATORS = Regex("(\\|\\||&&|;|\\||\\n)")

    fun assess(command: String): Verdict {
        val raw = command.trim()
        if (raw.isEmpty()) return Verdict(destructive = false)

        val lowered = raw.lowercase()

        // 1. Network piped into a shell: `curl ... | sh`, `wget -O- ... | bash`.
        if (pipesIntoShell(lowered)) {
            return Verdict(true, "mengunduh skrip dari internet lalu menjalankannya")
        }

        // 2. Writing to an absolute path outside /dev/null — the shell's cwd is the workspace,
        //    so absolute redirects are the way out of it.
        if (writesOutsideWorkspace(raw)) {
            return Verdict(true, "menulis file di luar workspace agent")
        }

        // 3. git operations that rewrite history or publish.
        if (lowered.contains("git push") || lowered.contains("git reset --hard") ||
            lowered.contains("git clean -") || lowered.contains("git checkout --")
        ) {
            return Verdict(true, "git push / reset / clean mengubah riwayat atau mengirim data keluar")
        }

        // 4. Per-segment command classification.
        val segments = SEPARATORS.split(lowered)
        for (segment in segments) {
            val words = segment.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
            if (words.isEmpty()) continue

            // Skip leading env assignments (KEY=value) and wrappers.
            var index = 0
            while (index < words.size &&
                (words[index].contains('=') || SKIPPED_HEADS.contains(words[index]))
            ) {
                index++
            }
            if (index >= words.size) continue

            // Quotes are stripped so `sh -c "rm -rf x"` is seen as `rm`, not as `"rm`.
            val head = words[index].substringAfterLast('/').trim('"', '\'')
            val rest = words.drop(index + 1)

            if (PRIVILEGE_PREFIXES.contains(head)) {
                return Verdict(true, "perintah dijalankan dengan hak akses lebih tinggi (sudo/su)")
            }
            if (ALWAYS_DESTRUCTIVE.contains(head)) {
                return Verdict(true, "perintah '$head' dapat menghapus data atau mengubah sistem")
            }
            if (EXFILTRATION.contains(head)) {
                return Verdict(true, "perintah '$head' memindahkan data keluar dari perangkat")
            }
            if (SHELLS.contains(head) && rest.any { it == "-c" || it == "-lc" || it == "-ic" }) {
                // `sh -c "<something>"` — classify the inner script instead of allowing a bypass.
                val inner = segment.substringAfter("-c", "").trim()
                if (inner.isNotEmpty()) {
                    val innerVerdict = assess(inner)
                    if (innerVerdict.destructive) return innerVerdict
                }
                continue
            }
            if (PACKAGE_MANAGERS.contains(head)) {
                val sub = rest.firstOrNull { !it.startsWith("-") }?.trim()
                if (sub != null && MUTATING_SUBCOMMANDS.contains(sub)) {
                    return Verdict(true, "mengubah paket yang terpasang ('$head $sub')")
                }
            }
        }

        return Verdict(destructive = false)
    }

    /** Convenience for callers that only need the boolean. */
    fun isDestructive(command: String): Boolean = assess(command).destructive

    private fun pipesIntoShell(lowered: String): Boolean {
        if (!lowered.contains('|')) return false
        return Regex("\\|\\s*(sudo\\s+)?(sh|bash|zsh|ash|dash|mksh)\\b").containsMatchIn(lowered) &&
            (lowered.contains("curl") || lowered.contains("wget") || lowered.contains("fetch"))
    }

    private fun writesOutsideWorkspace(command: String): Boolean {
        val matches = Regex(">>?\\s*([^\\s|;&]+)").findAll(command)
        for (match in matches) {
            val target = match.groupValues[1].trim().trim('"', '\'')
            if (target.isEmpty()) continue
            if (target == "/dev/null" || target == "/dev/stdout" || target == "/dev/stderr") continue
            if (target.startsWith("/")) return true
        }
        return false
    }
}
