package de.drgn.concept

import java.io.File
import java.util.*

val nameRegex = Regex("[a-zA-Z_][0-9a-zA-Z_]*")

fun main(args: Array<String>) {
    val files = mutableListOf<DFile>()

    args.let {
        val l = mutableListOf<File>()
        fun addAll(file: File) {
            if(file.isDirectory) file.listFiles().forEach { addAll(it) }
            else if(file.extension == "ccpt") {
                l += file
            }
        }
        it.forEach {
            addAll(File(it))
        }
        l
    }.forEach { file ->
        val dfile = DFile(file)
        val withoutComments = Line(file = dfile)

        var inString = false

        var inComment = 0
        val text = file.readText()
        val ite = text.iterator()

        var line = 0
        var column = 0

        val brackets = Stack<Char>()

        for ((i, c) in ite.withIndex()) {
            when {
                inComment == 0 && c == '"' -> {
                    inString = !inString
                    withoutComments += '"'
                }

                inString && c == '\\' -> {
                    val escapeChar = ite.nextChar()
                    withoutComments += Line(
                        dfile, "\\" + String.format(
                            "%02x", when (escapeChar) {
                                '"' -> '"'
                                '\\' -> '\\'
                                'n' -> '\n'
                                'b' -> '\b'
                                'r' -> '\r'
                                't' -> '\t'
                                else -> illegal(
                                    "Illegal escape character '\\$escapeChar'",
                                    Line(listOf(C(dfile, line, column, '\\'), C(dfile, line, column + 1, escapeChar)))
                                )
                            }.code
                        ), line, column++
                    )
                }
                c == '\\' -> {
                    val char = ite.nextChar()
                    withoutComments += Line(
                        dfile, "\\" + String.format(
                            "%02x", char.code
                        ), line, column++
                    )
                }

                inString -> withoutComments += c
                i + 1 < text.length && c == '/' && text[i + 1] == '/' && inComment == 0 -> inComment = 1
                c == '\n' && inComment == 1 -> {
                    inComment = 0
                    if(withoutComments.isNotEmpty())
                        withoutComments += ';'
                }

                i + 1 < text.length && c == '/' && text[i + 1] == '*' -> inComment = 2
                inComment == 2 && c == '/' && text[i - 1] == '*' -> inComment = 0
                inComment != 0 -> {}
                c == '\n' && (brackets.lastOrNull()?:'{') == '{' -> withoutComments += C(dfile, line, column, ';')
                c.isWhitespace() -> withoutComments += C(dfile, line, column, ' ')
                c in opens -> {
                    brackets += c
                    withoutComments += c
                }
                c in closes -> {
                    val b = closes[opens.indexOf(brackets.pop())]
                    if(b != c) illegal("Expected '$b' but found '$c'", C(dfile, line, column, c))
                    withoutComments += c
                }
                else -> withoutComments += C(dfile, line, column, c)
            }
            if (c == '\n') {
                line++
                column = 0
            } else column++
        }
        dfile.lines += withoutComments.splitBrackets(';').filter { it.isNotBlank() }

        val pckg = if (dfile.lines.firstOrNull()?.matches(Regex("package $nameRegex")) == true) {
            dfile.lines.first().str().substringAfter(' ').also {
                dfile.lines.removeFirst()
            }
        } else "main"

        dfile.pckg = DPackage[pckg] ?: DPackage(pckg)
        files += dfile
    }
    files.forEach {
        println(it.lines)
    }
}
const val RESET = "\u001b[0m"
const val RED = "\u001b[0;31m"

fun illegal(reason: String, l: Line): Nothing {
    println("${RED}Error: $RESET$reason")
    throw Exception()
}
fun illegal(reason: String, c: C): Nothing {
    println("${RED}Error: $RESET$reason")
    throw Exception()
}