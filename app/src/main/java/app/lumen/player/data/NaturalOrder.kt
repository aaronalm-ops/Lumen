package app.lumen.player.data

/**
 * 0.10.0: titles in the order people expect: "Part 2" before "Part 10", ignoring case. Runs of
 * digits compare by their value; everything else compares letter by letter.
 */
object NaturalOrder : Comparator<String> {
    override fun compare(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                val si = i
                val sj = j
                while (i < a.length && a[i].isDigit()) i++
                while (j < b.length && b[j].isDigit()) j++
                val na = a.substring(si, i).trimStart('0')
                val nb = b.substring(sj, j).trimStart('0')
                if (na.length != nb.length) return na.length - nb.length
                val byValue = na.compareTo(nb)
                if (byValue != 0) return byValue
                // Same value: fewer leading zeros first, so the order stays total.
                val byWidth = (i - si) - (j - sj)
                if (byWidth != 0) return byWidth
            } else {
                val byLetter = ca.lowercaseChar().compareTo(cb.lowercaseChar())
                if (byLetter != 0) return byLetter
                i++
                j++
            }
        }
        val byLength = (a.length - i) - (b.length - j)
        return if (byLength != 0) byLength else a.compareTo(b)
    }
}
