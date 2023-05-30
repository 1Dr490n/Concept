package de.drgn.concept

import java.util.*

val opens = listOf('(', '{', '[')
val closes = listOf(')', '}', ']')

fun Line.splitBrackets(vararg splitAt: Char): List<Line> {
	val result = mutableListOf<Line>()
	val sb = Line(file = file)
	var inQuotes = false
	var inBrackets = 0

	forEachIndexed { i, c ->
		when {
			c.c == '"' -> {
				inQuotes = !inQuotes
			}

			inQuotes -> {}
			c.c in opens -> {
				inBrackets++
			}

			c.c in closes -> {
				inBrackets--
			}

			splitAt.any { it == c.c } && inBrackets == 0 -> {
				result.add(sb.trim())
				sb.clear()
				return@forEachIndexed
			}
		}
		sb += c
	}

	result.add(sb.trim())
	return result.takeUnless { it.size == 1 && it[0].isEmpty() }?: emptyList()
}
fun Line.beforeBrackets(): Pair<Line, Line>? {
	if(length < 2 || last().c !in closes) return null
	var inQuotes = false
	val inParens = Stack<Pair<Int, C>>()

	for(i in length - 1 downTo 0) {
		val c = get(i)
		when {
			c.c == '"' -> inQuotes = !inQuotes
			inQuotes -> {}
			c.c in opens -> {
				if(opens.indexOf(c.c) != inParens.peek().first) illegal("Expected '${opens[opens.indexOf(c.c)]}'", inParens.peek().second)
				inParens.pop()
				if(inParens.isEmpty()) return (if(i == 0) Line(file = file) else substring(0, i).trim()) to substring(i).trim()
			}
			c.c in closes -> inParens += closes.indexOf(c.c) to c
		}
	}
	return null
}


fun Line.getFirstOperators(str: String): Triple<Line, Line, Line>? {
	var inQuotes = false
	val inParens = Stack<Int>()

	forEachIndexed { i, c ->
		when {
			c.c == '"' -> inQuotes = !inQuotes
			inQuotes -> {}
			c.c in opens -> inParens += opens.indexOf(c.c)
			c.c in closes -> {
				if(inParens.peek() != closes.indexOf(c.c)) illegal("Expected '${opens[inParens.peek()]}'", c)
				inParens.pop()
			}
			drop(i).startsWith(str) && !drop(i).startsWith("==") && !drop(i - 1).startsWith("==") && inParens.empty() -> {
				return Triple(substring(0, i).trim(), substring(i + str.length).trim(), substring(i, i + str.length))
			}
		}
	}
	return null
}




fun Line.getOperators(vararg operators: String): Triple<Pair<C, String>, Line, Line>? {
	var last: Triple<Pair<C, String>, Line, Line>? = null
	var inQuotes = false
	val inParens = Stack<Int>()
	var lastOperator = 2

	forEachIndexed { i, c ->
		if (!c.c.isWhitespace() && lastOperator > 0) lastOperator--
		when {
			c.c == '"' -> inQuotes = !inQuotes
			inQuotes -> {}
			c.c in opens -> inParens += opens.indexOf(c.c)
			c.c in closes -> {
				if (inParens.peek() != closes.indexOf(c.c)) illegal("Expected '${opens[inParens.peek()]}'", c)
				inParens.pop()
			}
			c.c in listOf('+', '-', '*') && inParens.isEmpty() && lastOperator != 0 -> lastOperator = 2
			operators.any {
				drop(i).startsWith(it)
			} && inParens.isEmpty() -> {
				val o = operators.find { drop(i).startsWith(it) }!!
				if(o == ":" && (substring(i).startsWith("::") || substring(i - 1).startsWith("::"))) return@forEachIndexed
				if(o == "!" && (substring(i).startsWith("!!") || substring(i - 1).startsWith("!!"))) return@forEachIndexed
				if(o == "?" && substring(i).startsWith("?.")) return@forEachIndexed
				last = Triple(c to o, if(i == 0) Line(file, "", 0, 0) else substring(0, i).trim(), substring(i + o.length).trim())
				lastOperator = 2
			}
		}
	}
	return last
}