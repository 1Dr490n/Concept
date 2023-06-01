package de.drgn.concept

fun parse(file: DFile) {
    val ite = file.lines.listIterator()

    for (line in ite) {
        if (line.matches(Regex("import\\s$nameRegex"))) {
            file.imports += DPackage[line.str().substringAfter(' ')] ?: illegal(
                "Package doesn't exist", line.substringAfter(' ')
            )
        } else break
    }

    if (!ite.hasPrevious()) return
    ite.previous()
    for (line in ite) {
        when {
            line.matches(Regex("fn\\s$nameRegex\\(.*}")) -> {
                val brackets = line.beforeBrackets()!!.let {
                    it.first to it.second.drop(1).dropLast(1)
                }
                val splitAtReturn = brackets.first.getOperators(":")?:(Triple("" to noC, brackets.first, Line("void", brackets.first.last())))
                val argsStr = splitAtReturn.second.substringAfter('(').substringBeforeLast(')').splitBrackets(',')
                val vararg = argsStr.lastOrNull()?.str() == "..."
                val args = (if (vararg) argsStr.dropLast(1) else argsStr).map {
                    if (!it.matches(Regex("$nameRegex:\\S.*"))) illegal("Expected argument", it)
                    val argName = it.substringBefore(':')
                    val argType = it.substringAfter(':')
                    argName to argType
                }
                val returnType = splitAtReturn.third
                val before = splitAtReturn.second.beforeBrackets()!!.let {
                    val spl = it.first.split(' ')
                    spl.last() to it.second
                }
                val name = before.first
                val f = ASTFuncDef(line, name, returnType, args, vararg)


                brackets.second.splitBrackets(';').filter { it.isNotBlank() }.forEach {
                    f.elements += parseElement(it)
                }
                ast += f
            }
            line.matches(Regex("fn\\s$nameRegex\\(.*")) -> {
                val splitAtReturn = line.getOperators(":")?:(Triple("" to noC, line, Line("void", line.last())))

                val before = splitAtReturn.second.beforeBrackets()!!.let {
                    val spl = it.first.split(' ')
                    spl.last() to it.second
                }
                val name = before.first.substringAfterLast('.')

                val argsStr = splitAtReturn.second.substringAfter('(').substringBeforeLast(')').splitBrackets(',')
                val vararg = argsStr.lastOrNull()?.str() == "..."
                val returnType = splitAtReturn.third

                val f = ASTMacroDec(line, name, returnType, if(vararg) argsStr.dropLast(1) else argsStr, vararg)
                ast += f
            }
            line.matches(Regex("struct $nameRegex\\{.*}")) -> {
                val brackets = line.beforeBrackets()!!
                val name = brackets.first.substringAfter(' ')
                val vars = mutableListOf<Triple<Line, Pair<Boolean, Line?>, ASTObject?>>()

                val funcs = mutableListOf<ASTFuncDef>()

                brackets.second.drop(1).dropLast(1).splitBrackets(';').filter { it.isNotBlank() }.forEach {
                    it.getFirstOperators("=")?.let { set ->
                        if (set.first.matches(Regex("(var|const)\\s$nameRegex(:.+)?"))) {
                            val type = if(set.first.l.any { it.c == ':' }) set.first.substringAfter(':') else null
                            val name = set.first.substringAfter(' ').substringBefore(':')
                            val obj = parseObject(set.second)

                            vars += Triple(name, set.first.startsWith("const") to type, obj)
                            return@forEach
                        }
                    }
                    if (it.matches(Regex("""(var|const)\s$nameRegex:.+"""))) {
                        val name = it.substringAfter(' ').substringBefore(':')
                        val type = it.substringAfter(':')
                        vars += Triple(name, it.startsWith("const") to type, null)
                        return@forEach
                    }
                    if (it.matches(Regex("(const\\s)?fn\\s$nameRegex\\(.+}"))) {
                        val brackets = it.beforeBrackets()!!.let {
                            it.first to it.second.drop(1).dropLast(1)
                        }
                        val splitAtReturn = brackets.first.getOperators(":") ?: (Triple(
                            "" to noC,
                            brackets.first,
                            Line("void", brackets.first.last())
                        ))
                        val argsStr =
                            splitAtReturn.second.substringAfter('(').substringBeforeLast(')').splitBrackets(',')
                        val vararg = argsStr.lastOrNull()?.str() == "..."
                        val args = (if (vararg) argsStr.dropLast(1) else argsStr).map {
                            if (!it.matches(Regex("$nameRegex:\\S.*"))) illegal("Expected argument", it)
                            val argName = it.substringBefore(':')
                            val argType = it.substringAfter(':')
                            argName to argType
                        }
                        val returnType = splitAtReturn.third
                        var constant = false
                        val before = splitAtReturn.second.beforeBrackets()!!.let {
                            val spl = it.first.split(' ')
                            if(spl[0].str() == "const") constant = true
                            spl.last() to it.second
                        }
                        val name = before.first
                        val f = ASTFuncDef(it, name, returnType, args, vararg, constant)


                        brackets.second.splitBrackets(';').filter { it.isNotBlank() }.forEach {
                            f.elements += parseElement(it)
                        }
                        ast += f
                        funcs += f
                        return@forEach
                    }
                    illegal ("Expected member declaration", it)
                }

                ast += ASTStructDef(line, name, vars, funcs)
            }
            line.matches(Regex("typealias $nameRegex=.+")) -> {
                val name = line.substringAfter(' ').substringBefore('=')
                val type = line.substringAfter('=')
                ast += ASTTypeAlias(line, name, type)
            }
            else -> illegal("Expected top level object", line)
        }
    }
}
fun parseElement(line: Line): ASTElement {
    if(line.startsWith("return ")) {
        return ASTReturn(line, parseObject(line.substringAfter(' ')))
    }
    line.getFirstOperators("=")?.let { set ->
        if(set.first.matches(Regex("(var|const)\\s$nameRegex.*"))) {
            val split = set.first.splitBrackets(':')
            val type = if(split.size > 1) split[1] else null
            val name = split[0].substringAfter(' ')
            val obj = parseObject(set.second)
            return ASTVarDef(line, name, type, obj, set.first.startsWith("const"))
        }
        val strg = parseStorage(set.first)
        val obj = parseObject(set.second)
        return ASTSet(line, strg, obj)
    }
    line.beforeBrackets()?.let {
        when(it.second[0].c) {
            '(' -> {
                val args = it.second.drop(1).dropLast(1).splitBrackets(',').map {
                    parseObject(it)
                }
                if(it.first.last().c == '!') {
                    return ASTMacroCall(line, it.first.dropLast(1), args)
                }
                val o = parseObject(it.first)
                return ASTFuncCall(line, o, args)
            }
            '{' -> {
                return ASTScope(line, it.second.drop(1).dropLast(1).splitBrackets(';').filter { it.isNotBlank() }.map {
                    parseElement(it)
                })
            }
            else -> {}
        }
    }

    illegal("Expected statement", line)
}
fun parseObject(line: Line): ASTObject {
    line.str().toLongOrNull()?.let { return ASTInt(line, it) }
    if(line.matches(Regex("($nameRegex::)?$nameRegex"))) return ASTVarUse(line)

    if(line.startsWith("&const ")) return ASTSharedPtr(line, parseStorage(line.substringAfter(' ')), true)
    if(line[0].c == '&') return ASTSharedPtr(line, parseStorage(line.drop(1)), false)
    if(line[0].c == '*') return ASTDereference(line, parseObject(line.drop(1)))

    line.beforeBrackets()?.let {
        when (it.second[0].c) {
            '(' -> {
                if(it.first.isEmpty()) return parseObject(line.drop(1).dropLast(1))

                if(it.first.startsWith("new ")) {
                    return ASTNew(line, it.first.substringAfter(' '), parseObject(it.second.drop(1).dropLast(1)))
                }


                val args = it.second.drop(1).dropLast(1).splitBrackets(',').map {
                    parseObject(it)
                }
                val o = parseObject(it.first)
                return ASTFuncCallObj(line, o, args)
            }
            '[' -> {
                val index = parseObject(it.second.drop(1).dropLast(1))
                val obj = parseStorage(it.first)
                return ASTArrayIndex(line, obj, index)
            }
            '{' -> {
                if(it.first.endsWith("[]")) {
                    return ASTArrayInit(line, it.first.dropLast(2), it.second.drop(1).dropLast(1).splitBrackets(',').map { parseObject(it) })
                }
                it.first.beforeBrackets()?.let { arr ->
                    if(arr.second[0].c != '[') return@let
                    val size = parseObject(arr.second.drop(1).dropLast(1))
                    return ASTArrayFillInit(line, arr.first, size, parseObject(it.second.drop(1).dropLast(1)))
                }

                val values = it.second.drop(1).dropLast(1).splitBrackets(',').map {
                    if (!it.matches(Regex("$nameRegex:\\S.*"))) illegal("Expected property", it)
                    val property = it.substringBefore(':')
                    val value = parseObject(it.substringAfter(':'))
                    property to value
                }
                return ASTStructInit(line, it.first, values)
            }
            else -> {}
        }
    }
    if (line.matches(Regex("\"[^\"]*\""))) {
        val sb = StringBuilder()
        val ite = line.l.drop(1).dropLast(1).iterator()
        for(c in ite) {
            if(c.c == '\\') {
                sb.append(("" + ite.next().c + ite.next().c).toInt(16).toChar())
            }
            else sb.append(c.c)
        }
        return ASTString(line, sb.toString())
    }

    line.getOperators(".")?.let {
        val obj = parseObject(it.second)
        return ASTMember(line, obj, it.third)
    }

    illegal("Expected object", line)
}
fun parseStorage(line: Line): ASTStorage {
    line.getOperators(".")?.let {
        val obj = ASTSharedPtr(line, parseStorage(it.second), false)
        return ASTSMember(line, obj, it.third)
    }
    line.beforeBrackets()?.let {
        when(it.second[0].c) {
            '[' -> {
                val i = parseObject(it.second.drop(1).dropLast(1))
                val obj = parseObject(it.first)
                return ASTSArrayIndex(line, obj, i)
            }
            '(' -> if(it.first.isEmpty()) return parseStorage(line.dropLast(1).drop(1))
        }
        Unit
    }

    return when {
        line.matches(Regex("($nameRegex::)?$nameRegex")) -> ASTSVarUse(line)
        line[0].c == '*' -> ASTSPtr(line, parseObject(line.drop(1)))
        else -> illegal("Expected variable", line)
    }
}