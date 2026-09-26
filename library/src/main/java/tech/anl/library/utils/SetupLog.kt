package tech.anl.library.utils

/**
 * The lines shown in the setup progress terminal. Each step starts a "==> step" header and its
 * details follow as lines; a detail that only differs from the previous line by its "(NN%)"
 * figure replaces that line, the way a `\r` progress bar redraws in a real terminal.
 */
class SetupLog(private val maxLines: Int = 500) {
    private val lines = ArrayDeque<String>()
    private var lastStep = ""

    fun clear() {
        lines.clear()
        lastStep = ""
    }

    /** Records [step] (if it changed) and [details] (if any); returns whether anything changed. */
    fun append(step: String, details: String): Boolean {
        var changed = false
        if (step.isNotBlank() && step != lastStep) {
            lastStep = step
            add("==> $step")
            changed = true
        }
        for (line in details.lines().map { it.trimEnd() }.filter { it.isNotBlank() }) {
            val last = lines.lastOrNull()
            when {
                last == line -> Unit
                last != null && isProgressUpdate(last, line) -> {
                    lines[lines.size - 1] = line
                    changed = true
                }
                else -> {
                    add(line)
                    changed = true
                }
            }
        }
        return changed
    }

    fun text(): String = lines.joinToString("\n")

    private fun add(line: String) {
        lines.addLast(line)
        while (lines.size > maxLines) lines.removeFirst()
    }

    companion object {
        private val PERCENT = Regex("""\d{1,3}(\.\d+)?\s?%""")

        internal fun isProgressUpdate(previous: String, next: String): Boolean =
            PERCENT.containsMatchIn(next) && PERCENT.replace(previous, "%") == PERCENT.replace(next, "%")
    }
}
