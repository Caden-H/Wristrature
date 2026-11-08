package io.github.cadenh.wristrature.core.position

/** One step of an element path: a name and a 1-based index among same-name siblings. */
data class PathStep(val name: String, val index: Int = 1)

/**
 * A KOReader (crengine) position inside an EPUB, taken apart.
 *
 * Examples: `/body/DocFragment[12]/body/div/p[3]/text().18`, `/body/DocFragment[3]/body/div[2]/p/i/text()[2].5`,
 * `/body/DocFragment[7]/body/p[4]/img.0`, `/body/DocFragment[2]/body/text().0`.
 *
 * @property fragment 1-based chapter number from `DocFragment[N]`, or null if the string has none.
 * @property steps the element path below the chapter's `body`.
 * @property textIndex the 1-based number of the text node, or null if the path does not end in `text()`.
 * @property offset the character offset after the final dot, or null if there is none.
 */
data class XPointer(
    val fragment: Int?,
    val steps: List<PathStep>,
    val textIndex: Int?,
    val offset: Int?,
) {
    val endsInText: Boolean get() = textIndex != null

    companion object {
        private val OFFSET = Regex("""\.(\d+)$""")
        private val STEP = Regex("""^([A-Za-z_][\w.\-:]*)(?:\[(\d+)])?$""")
        private val TEXT = Regex("""^text\(\)(?:\[(\d+)])?$""")

        /** Returns null if [s] is not an XPointer of this kind (for example a PDF page number). */
        fun parse(s: String): XPointer? {
            var t = s.trim()
            if (!t.startsWith("/")) return null
            var offset: Int? = null
            OFFSET.find(t)?.let {
                offset = it.groupValues[1].toIntOrNull()
                t = t.substring(0, it.range.first)
            }
            val parts = t.split('/').filter { it.isNotEmpty() }
            if (parts.isEmpty()) return null
            var i = 0
            if (parts[i] == "body") i++
            var fragment: Int? = null
            if (i < parts.size && parts[i].startsWith("DocFragment")) {
                val m = STEP.matchEntire(parts[i]) ?: return null
                fragment = m.groupValues[2].toIntOrNull() ?: 1
                i++
            }
            if (i < parts.size && parts[i] == "body") i++
            val steps = ArrayList<PathStep>()
            var textIndex: Int? = null
            while (i < parts.size) {
                val part = parts[i]
                val text = TEXT.matchEntire(part)
                if (text != null) {
                    textIndex = text.groupValues[1].toIntOrNull() ?: 1
                    break
                }
                val m = STEP.matchEntire(part) ?: return null
                steps += PathStep(m.groupValues[1].lowercase(), m.groupValues[2].toIntOrNull() ?: 1)
                i++
            }
            return XPointer(fragment, steps, textIndex, offset)
        }

        /** Takes apart a stored path such as `div[2]/p/i[1]`. Empty string means the body itself. */
        fun steps(path: String): List<PathStep> = path.split('/').filter { it.isNotEmpty() }.map {
            val m = STEP.matchEntire(it)
            if (m == null) PathStep(it) else PathStep(m.groupValues[1].lowercase(), m.groupValues[2].toIntOrNull() ?: 1)
        }

        /**
         * Builds the string KOReader expects. [path] is the stored element path (indexes only where siblings exist).
         * [chapterCount] decides whether `DocFragment` carries an index.
         */
        fun build(chapterNumber: Int, chapterCount: Int, path: String, textIndex: Int?, offset: Int, isElement: Boolean = false): String {
            val sb = StringBuilder("/body/DocFragment")
            if (chapterCount > 1) sb.append('[').append(chapterNumber).append(']')
            sb.append("/body")
            if (path.isNotEmpty()) sb.append('/').append(path)
            if (!isElement) {
                sb.append("/text()")
                if (textIndex != null && textIndex > 0) sb.append('[').append(textIndex).append(']')
            }
            sb.append('.').append(offset)
            return sb.toString()
        }
    }
}
