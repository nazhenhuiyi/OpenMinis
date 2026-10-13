package com.openminis.app.data.species

/**
 * [T-today-species] The species recorded for a photo (SPEC §17.2): a tag on
 * the photo itself, so recording never pulls a photo out of its group.
 */
data class PhotoTag(val latin: String, val name: String, val group: String?) {
    /** Only the genus was claimed ("a kind of X"): a one-word name. The phrase itself is added when shown, in the current language. */
    val genus: Boolean get() = ' ' !in latin.trim()
}

/**
 * Tags live in the front matter of the photo's own day file, one line for
 * the whole day, so a backup or the agent sees them with everything else:
 *
 * ```
 * species: "DCIM/Camera/IMG_1.jpg|Pycnonotus sinensis|白头鹎|Aves; DCIM/Camera/IMG_2.jpg|Nelumbo nucifera|莲|Plantae"
 * ```
 */
object PhotoSpecies {
    const val KEY = "species"
    private const val SEP = "; "

    fun parse(frontMatter: Map<String, String>): Map<String, PhotoTag> {
        val raw = frontMatter[KEY]?.takeIf { it.isNotBlank() } ?: return emptyMap()
        val out = LinkedHashMap<String, PhotoTag>()
        for (item in raw.split(';')) {
            val f = item.trim().split('|')
            if (f.size < 3 || f[0].isBlank() || f[1].isBlank()) continue
            out[unescapePath(f[0])] = PhotoTag(f[1], f[2].ifBlank { f[1] }, f.getOrNull(3)?.ifBlank { null })
        }
        return out
    }

    /** The front-matter value for [tags]; null removes the line. */
    fun render(tags: Map<String, PhotoTag>): String? =
        tags.entries.takeIf { it.isNotEmpty() }?.joinToString(SEP) { (path, t) ->
            escapePath(path) + "|" + listOf(t.latin, t.name, t.group.orEmpty()).joinToString("|") { it.clean() }
        }

    // The separators can't appear inside a field. Names are just tidied; a
    // path must come back byte for byte, so it is escaped instead.
    private fun String.clean() = replace('|', '/').replace(';', ',').replace('"', '\'').trim()
    private fun escapePath(p: String) = p.replace("%", "%25").replace("|", "%7C").replace(";", "%3B").replace("\"", "%22")
    private fun unescapePath(p: String) = p.replace("%22", "\"").replace("%3B", ";").replace("%7C", "|").replace("%25", "%")
}
