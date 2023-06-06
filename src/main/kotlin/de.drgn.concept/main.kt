package de.drgn.concept

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.*
import de.drgn.irbuilder.values.VGlobal
import java.io.File
import java.util.*
import kotlin.system.exitProcess

val nameRegex = Regex("[a-zA-Z_][0-9a-zA-Z_]*")
val malloc = FuncSignature(TPtr, tI64) to VGlobal("malloc")
val free = FuncSignature(TVoid, TPtr) to VGlobal("free")
val printf = FuncSignature(TVoid, TPtr, isVararg = true) to VGlobal("printf")
val atoi = FuncSignature(tI32, TPtr) to VGlobal("atoi")
val srand = FuncSignature(TVoid, tI32) to VGlobal("srand")
val rand = FuncSignature(tI32) to VGlobal("rand")

fun main(args: Array<String>) {

    IRBuilder.declareFunc("malloc", malloc.first)
    IRBuilder.declareFunc("free", free.first)
    IRBuilder.declareFunc("printf", printf.first)
    IRBuilder.declareFunc("atoi", atoi.first)
    IRBuilder.declareFunc("srand", srand.first)
    IRBuilder.declareFunc("rand", rand.first)

    val files = mutableListOf<DFile>()

    val stdFile = File("_TEMP_std.ccpt")
    stdFile.writeBytes(object{}.javaClass.getResourceAsStream("/std.ccpt").readBytes())
    stdFile.deleteOnExit()
    val rndFile = File("_TEMP_rnd.ccpt")
    rndFile.writeBytes(object{}.javaClass.getResourceAsStream("/random.ccpt").readBytes())
    rndFile.deleteOnExit()
    args.let {
        val l = mutableListOf<File>()
        fun addAll(file: File) {
            if(file.isDirectory) file.listFiles().forEach { addAll(it) }
            else if(file.extension == "ccpt") {
                l += file
            }
        }

        addAll(stdFile)
        addAll(rndFile)
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
                    withoutComments += C(dfile, line, column, '"')
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

                inString -> withoutComments += C(dfile, line, column, c)
                i + 1 < text.length && c == '/' && text[i + 1] == '/' && inComment == 0 -> inComment = 1
                c == '\n' && inComment == 1 -> {
                    inComment = 0
                    if(withoutComments.isNotEmpty())
                        withoutComments += C(dfile, line, column, ';')
                }

                i + 1 < text.length && c == '/' && text[i + 1] == '*' -> inComment = 2
                inComment == 2 && c == '/' && text[i - 1] == '*' -> inComment = 0
                inComment != 0 -> {}
                c == '\n' && (brackets.lastOrNull()?:'{') == '{' -> withoutComments += C(dfile, line, column, ';')
                c.isWhitespace() -> withoutComments += C(dfile, line, column, ' ')
                c in opens -> {
                    brackets += c
                    withoutComments += C(dfile, line, column, c)
                }
                c in closes -> {
                    val b = closes[opens.indexOf(brackets.pop())]
                    if(b != c) illegal("Expected '$b'", C(dfile, line, column, c))
                    withoutComments += C(dfile, line, column, c)
                }
                else -> withoutComments += C(dfile, line, column, c)
            }
            if (c == '\n') {
                line++
                column = 0
            } else column++
        }
        val cleanedUp = Line(file = dfile)
        inString = false
        withoutComments.forEachIndexed { i, c ->
            if(c.c == '"') inString = !inString
            if(inString || (!c.c.isWhitespace() || (cleanedUp.last().c.isName() && (i + 1 < withoutComments.length && withoutComments[i + 1].c.isName())))) {
                cleanedUp += c
            }
        }
        dfile.lines += cleanedUp.splitBrackets(';').filter { it.isNotBlank() }

        val pckg = if (dfile.lines.firstOrNull()?.matches(Regex("package $nameRegex")) == true) {
            dfile.lines.first().str().substringAfter(' ').also {
                dfile.lines.removeFirst()
            }
        } else "main"

        dfile.pckg = DPackage[pckg] ?: DPackage(pckg)
        files += dfile
    }
    files.forEach {
        parse(it)
    }
    var i = 0
    while(i < ast.size) {
        val t = ast[i++].tree()
        if(t != null) tree += t
    }
    ast.forEach {
        it.typesDone(null)
    }
    for(e in ast) {
        e.code(null)
    }
    var mainFunc: TreeFuncDef? = null
    tree.forEach {
        if(it is TreeFuncDef && it.name.str() == "main" && it.line.file.pckg.name == "main") {
            mainFunc = it
        }
        it.ir()
    }
    if(mainFunc == null) {
        println("${RED}No main function found")
        exitProcess(1)
    }
    IRBuilder.func("main", mainFunc!!.returns.ir()) {
        ret(callFunc(FuncSignature(mainFunc!!.returns.ir()), VGlobal("\"main::main\"")))
    }
    File("test.ll").writeText(IRBuilder.build())
    val compiled = File("compiled.o")
    compiled.deleteOnExit()
    compiled.writeBytes(object{}.javaClass.getResourceAsStream("/compiled.o").readBytes())
    val compile = Runtime.getRuntime().exec("clang test.ll compiled.o")
    compile.inputStream.bufferedReader().forEachLine {
        println(it)
    }
    compile.errorStream.bufferedReader().forEachLine {
        println("$RED$it$RESET")
    }
}
const val RESET = "\u001b[0m"
const val RED = "\u001b[0;31m"
const val CYAN = "\u001b[0;36m"
const val YELLOW = "\u001b[0;33m"

fun illegal(reason: String, l: Line): Nothing {
    println("${RED}Error: $reason")
    val lines = mutableMapOf<Pair<DFile, Int>, Unit>()
    l.l.forEach {
        lines[it.file to it.line] = Unit
    }
    val errorLines = lines.map { line ->
        val sb = StringBuilder()
        line.key.first.f.readLines()[line.key.second].forEachIndexed { i, c ->
            if(!c.isWhitespace() && l.l.any {
                it.line == line.key.second && it.column == i }) sb.append("$RED$c$RESET")
            else sb.append(c)
        }
        Triple(line.key, sb.toString().trimEnd().replace("\t", "    "), "${line.key.first.f}:${line.key.second + 1}")
    }
    val trim = errorLines.minOf { it.second.takeWhile { it.isWhitespace() }.length }
    val add = errorLines.maxOf { it.third.length }
    errorLines.forEach {
        println("$CYAN${it.third} ${" ".repeat(add - it.third.length)}$RESET${it.second.drop(trim)}")
    }
    throw Exception()
    //exitProcess(1)
}
fun unreachable(l: Line) {
    println("${YELLOW}Warning: Unreachable code")
    val lines = mutableMapOf<Pair<DFile, Int>, Unit>()
    l.l.forEach {
        lines[it.file to it.line] = Unit
    }
    val errorLines = lines.map { line ->
        val sb = StringBuilder()
        line.key.first.f.readLines()[line.key.second].forEachIndexed { i, c ->
            if(!c.isWhitespace() && l.l.any {
                    it.line == line.key.second && it.column == i }) sb.append("$YELLOW$c$RESET")
            else sb.append(c)
        }
        Triple(line.key, sb.toString().trimEnd().replace("\t", "    "), "${line.key.first.f}:${line.key.second + 1}")
    }
    val trim = errorLines.minOf { it.second.takeWhile { it.isWhitespace() }.length }
    val add = errorLines.maxOf { it.third.length }
    errorLines.forEach {
        println("$CYAN${it.third} ${" ".repeat(add - it.third.length)}$RESET${it.second.drop(trim)}")
    }
    //Exception().printStackTrace()
}
fun illegalExists(type: String, l: Line, at: Line): Nothing {
    println("${RED}Error: $RESET$type already exists")

    val sb = StringBuilder()
    l.file.f.readLines()[l[0].line].forEachIndexed { i, c ->
        if (!c.isWhitespace() && l.l.any { it.column == i }) sb.append("$RED$c$RESET")
        else sb.append(c)
    }
    println("$CYAN${l.file.f}:${l[0].line + 1} $RESET${sb.toString().trim()}")

    sb.clear()
    at.file.f.readLines()[at[0].line].forEachIndexed { i, c ->
        if (!c.isWhitespace() && at.l.any { it.column == i }) sb.append("$RED$c$RESET")
        else sb.append(c)
    }
    println("Already declared here:")
    println("$CYAN${at.file.f}:${at[0].line + 1} $RESET${sb.toString().trim()}")
    throw Exception()
    //exitProcess(1)
}
fun illegalMoved(l: Line, at: Line): Nothing {
    println("${RED}Error: ${RESET}Cannot use moved value")

    val sb = StringBuilder()
    l.file.f.readLines()[l[0].line].forEachIndexed { i, c ->
        if (!c.isWhitespace() && l.l.any { it.column == i }) sb.append("$RED$c$RESET")
        else sb.append(c)
    }
    println("$CYAN${l.file.f}:${l[0].line + 1} $RESET${sb.toString().trim()}\t")

    sb.clear()
    at.file.f.readLines()[at[0].line].forEachIndexed { i, c ->
        if (!c.isWhitespace() && at.l.any { it.column == i }) sb.append("$RED$c$RESET")
        else sb.append(c)
    }
    println("Moved here:")
    println("$CYAN${at.file.f}:${at[0].line + 1} $RESET${sb.toString().trim()}")
    throw Exception()
    //exitProcess(1)
}
fun illegal(reason: String, c: C): Nothing = illegal(reason, Line(listOf(c)))