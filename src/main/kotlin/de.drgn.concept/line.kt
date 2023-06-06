package de.drgn.concept

val noC = C(noFile, 0, 0, ' ')
data class C(val file: DFile, val line: Int, val column: Int, val c: Char) {
	override fun toString() = "${file.f}:${line + 1}:${column + 1}"
}

val noLine = Line(listOf(noC))
class Line(l: List<C> = mutableListOf(), val file: DFile = l[0].file) {
	constructor(file: DFile, s: String, l: Int, c: Int) : this(s.mapIndexed { i, it -> C(file, l, c + i, it) }, file)
	//constructor(file: DFile, s: String) : this(file, s, 0, 0)
	constructor(s: String, c: C) : this(c.file, s, c.line, c.column)

	val l = l.toMutableList()
	override fun toString() = str()

	inline fun forEachIndexed(u: (Int, C) -> Unit) = l.forEachIndexed(u)

	fun trim() = Line(l.dropWhile { it.c.isWhitespace() }.dropLastWhile { it.c.isWhitespace() }, file)
	fun trimStart() = Line(l.dropWhile { it.c.isWhitespace() })
	fun trimEnd() = Line(l.dropLastWhile { it.c.isWhitespace() })
	fun str() = l.joinToString("") { "${it.c}" }
	fun ifEmpty(u: () -> Line) = if(l.isEmpty()) u() else this
	fun split(c: Char): List<Line> {
		val res = mutableListOf<Line>()
		val sb = Line(file = file)
		l.forEach {
			if(it.c == c) {
				res += Line(sb.l)
				sb.clear()
			} else sb += it
		}
		if(sb.isNotEmpty()) res += sb
		return res
	}
	val length: Int
		get() = l.size

	fun substringBefore(c: Char) = Line(l.takeWhile { it.c != c })
	fun substringBefore(s: String): Line {
		var i = 0
		var end = 0
		for (it in l) {
			if(s[i] == it.c) i++
			else i = 0
			end++
			if(i == s.length) break
		}
		return Line(l.subList(0, end - s.length))
	}
	fun substringBeforeLast(s: String): Line {
		var lastEnd = -1
		var i = 0
		var end = 0
		for (it in l) {
			if(s[i] == it.c) i++
			else i = 0
			end++
			if(i == s.length) {
				i = 0
				lastEnd = end
			}
		}
		return Line(l.subList(0, lastEnd - s.length))
	}
	fun substringAfter(c: Char, alt: Line = this) = if(l.any { it.c == c }) Line(l.dropWhile { it.c != c }.drop(1), file) else alt
	fun substringAfter(s: String, alt: Line = this): Line {
		var o = 0
		for(i in l.indices) {
			if(l[i].c == s[o]) o++
			else o = 0
			if(o == s.length) return substring(i + 1)
		}
		return alt
	}
	fun substringBeforeLast(c: Char): Line {
		val lastIndex = l.indexOfLast { it.c == c }
		return if (lastIndex >= 0) {
			Line(l.subList(0, lastIndex), file)
		} else this
	}
	fun substringAfterLast(c: Char): Line {
		val lastIndex = l.indexOfLast { it.c == c }
		return if (lastIndex >= 0) {
			Line(l.subList(lastIndex + 1, l.size), file)
		} else this
	}
	fun substringAfterLast(s: String, alt: Line = this): Line {
		var o = 0
		for(i in length - 1 downTo 0) {
			if(l[i].c == s[s.length - o - 1]) {
				if(++o == s.length) return substring(i + s.length)
			}
			else o = 0
		}
		return alt
	}
	fun clear() = l.clear()
	fun isEmpty() = l.isEmpty()
	fun isNotEmpty() = l.isNotEmpty()
	fun drop(n: Int) = Line(l.drop(n), file)
	fun dropLast(n: Int) = Line(l.dropLast(n), file)
	fun last() = l.last()
	fun first() = l.first()
	fun isBlank() = str().isBlank()
	fun isNotBlank() = str().isNotBlank()
	fun startsWith(c: Char) = l.firstOrNull()?.c == c
	fun startsWith(s: String) = str().startsWith(s)
	fun endsWith(c: Char) = l.lastOrNull()?.c == c
	fun endsWith(s: String) = str().endsWith(s)
	fun substring(start: Int, end: Int = l.size) = Line(l.subList(start, end))
	fun removeFirst(u: (C) -> Boolean) {
		l.toList().forEach {
			if(u(it)) l.remove(it)
		}
	}
	operator fun contains(c: Char) = l.find { it.c == c } != null
	operator fun contains(s: String) = s in str()
	fun matches(r: Regex) = str().matches(r)
	fun indexOfFirst(u: (C) -> Boolean) = l.indexOfFirst(u)
	fun findLast(u: (C) -> Boolean) = l.findLast(u)
	fun removeRange(start: Int, end: Int) = l.subList(start, end).clear()
	operator fun get(i: Int) = l[i]
	operator fun plusAssign(c: C) {
		l += c
	}
	operator fun plusAssign(line: Line) {
		l += line.l
	}
	operator fun plusAssign(c: Char) {
		l += C(file, l.last().line, l.last().column, c)
	}
}
fun List<Line>.isBlank() = size == 1 && this[0].isEmpty()
fun List<Line>.isNotBlank() = !(size == 1 && this[0].isEmpty())
fun String.line() = Line(this, noC)