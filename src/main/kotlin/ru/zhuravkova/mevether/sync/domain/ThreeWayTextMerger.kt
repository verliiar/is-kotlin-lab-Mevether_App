package ru.zhuravkova.mevether.sync.domain

class ThreeWayTextMerger {
    private data class Edit(val start: Int, val end: Int, val replacement: String)

    fun merge(base: String, current: String, proposed: String): String? {
        if (current == proposed || proposed == base) return current
        if (current == base) return proposed
        val server = edits(base, current)
        val client = edits(base, proposed)
        if (server.any { left -> client.any { right -> left != right && overlaps(left, right) } }) return null
        val edits = (server + client).distinct().sortedWith(compareBy<Edit> { it.start }.thenBy { it.end })
        val basePoints = base.codePoints().toArray()
        val result = StringBuilder()
        var offset = 0
        for (edit in edits) {
            result.append(String(basePoints, offset, edit.start - offset)).append(edit.replacement)
            offset = edit.end
        }
        return result.append(String(basePoints, offset, basePoints.size - offset)).toString()
    }

    private fun overlaps(left: Edit, right: Edit): Boolean {
        if (left.start == left.end) return left.start in right.start..right.end
        if (right.start == right.end) return right.start in left.start..left.end
        return maxOf(left.start, right.start) < minOf(left.end, right.end)
    }

    private fun edits(base: String, changed: String): List<Edit> {
        val basePoints = base.codePoints().toArray()
        val changedPoints = changed.codePoints().toArray()
        var prefix = 0
        while (prefix < minOf(basePoints.size, changedPoints.size) && basePoints[prefix] == changedPoints[prefix]) prefix++
        var suffix = 0
        while (suffix < minOf(basePoints.size, changedPoints.size) - prefix && basePoints[basePoints.lastIndex - suffix] == changedPoints[changedPoints.lastIndex - suffix]) suffix++
        val source = basePoints.copyOfRange(prefix, basePoints.size - suffix)
        val target = changedPoints.copyOfRange(prefix, changedPoints.size - suffix)
        if (source.size.toLong() * target.size > 1_000_000) return listOf(Edit(prefix, basePoints.size - suffix, String(target, 0, target.size)))
        val lcs = Array(source.size + 1) { IntArray(target.size + 1) }
        for (i in source.lastIndex downTo 0) for (j in target.lastIndex downTo 0) {
            lcs[i][j] = if (source[i] == target[j]) lcs[i + 1][j + 1] + 1 else maxOf(lcs[i + 1][j], lcs[i][j + 1])
        }
        val result = mutableListOf<Edit>()
        var i = 0
        var j = 0
        while (i < source.size || j < target.size) {
            if (i < source.size && j < target.size && source[i] == target[j]) {
                i++
                j++
            } else {
                val start = i
                val replacement = StringBuilder()
                while (i < source.size || j < target.size) {
                    if (i < source.size && j < target.size && source[i] == target[j]) break
                    if (j < target.size && (i == source.size || lcs[i][j + 1] >= lcs[i + 1][j])) {
                        replacement.appendCodePoint(target[j++])
                    } else {
                        i++
                    }
                }
                result.add(Edit(prefix + start, prefix + i, replacement.toString()))
            }
        }
        return result
    }
}
