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
        var line = line
        val template = if (line[0].c == '<' && line.last().c == '>') {
            val g = line.drop(1).dropLast(1).splitBrackets(',')
            line = ite.next()
            g
        } else null
        val e = parseTopLevel(file, line, template)
        if (e.templates != null) {
            when(e) {
                is ASTGlobalElementVar -> file.pckg.templateVars += e
                is ASTStructDef -> file.pckg.templateStructs += e
                else -> TODO(e.toString())
            }
        }
        else ast += e
    }
}
fun parseTopLevel(file: DFile, line: Line, template: List<Line>?): ASTGlobalElement {
    val set = line.getFirstOperators("=")
    return when {
        set?.first?.matches(Regex("(var|const)\\s$nameRegex(:.+)?")) == true -> {
            val type = if (set.first.l.any { it.c == ':' }) set.first.substringAfter(':') else null
            val name = set.first.substringAfter(' ').substringBefore(':')
            val obj = parseObject(set.second)

            ASTGlobalDef(line, name, set.first.startsWith("const"), type, obj, template)
        }
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
            val f = ASTFuncDef(line, template, name, returnType, args, vararg)


            brackets.second.splitBrackets(';').filter { it.isNotBlank() }.forEach {
                f.elements += parseElement(it)
            }
            f
        }
        line.matches(Regex("(macro|fn)\\s$nameRegex\\(.*")) -> {
            val splitAtReturn = line.getOperators(":")?:(Triple("" to noC, line, Line("void", line.last())))

            val before = splitAtReturn.second.beforeBrackets()!!.let {
                val spl = it.first.split(' ')
                spl.last() to it.second
            }
            val name = before.first.substringAfterLast('.')

            val argsStr = splitAtReturn.second.substringAfter('(').substringBeforeLast(')').splitBrackets(',')
            val vararg = argsStr.lastOrNull()?.str() == "..."
            val returnType = splitAtReturn.third


            if(line.startsWith("macro")) ASTMacroDec(line, name, returnType, if(vararg) argsStr.dropLast(1) else argsStr, vararg)
            else ASTFuncDec(line, template, name, returnType, if(vararg) argsStr.dropLast(1) else argsStr, vararg)
        }
        line.matches(Regex("struct $nameRegex\\{.*}")) -> {
            val brackets = line.beforeBrackets()!!
            val name = brackets.first.substringAfter(' ')
            val vars = mutableListOf<Triple<Line, Pair<Boolean, Line?>, ASTObject?>>()

            val funcs = mutableListOf<ASTFunc>()
            val staticFuncs = mutableListOf<ASTFunc>()

            brackets.second.drop(1).dropLast(1).splitBrackets(';').filter { it.isNotBlank() }.forEach {
                it.getFirstOperators("=")?.let { set ->
                    if (set.first.matches(Regex("(var|const)\\s$nameRegex(:.+)?"))) {
                        val type = if (set.first.l.any { it.c == ':' }) set.first.substringAfter(':') else null
                        val name = set.first.substringAfter(' ').substringBefore(':')
                        val obj = parseObject(set.second)

                        vars += Triple(name, set.first.startsWith("const") to type, obj)
                        return@forEach
                    }
                }
                if (it.matches(Regex("""(var|const)\s$nameRegex:.+"""))) {
                    val name = it.substringAfter(' ').substringBefore(':')
                    val type = it.substringAfter(':')
                    vars.find { it.first.str() == name.str() }
                        ?.let { illegalExists("Member variable", name, it.first) }
                    funcs.find { it.name.str() == name.str() }
                        ?.let { illegalExists("Member variable", name, it.name) }
                    vars += Triple(name, it.startsWith("const") to type, null)
                    return@forEach
                }
                if (it.matches(Regex("((static|const)\\s)?fn\\s$nameRegex\\(.+}"))) {
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
                    var mode = 0
                    val before = splitAtReturn.second.beforeBrackets()!!.let {
                        val spl = it.first.split(' ')
                        if (spl[0].str() == "const") mode = 1
                        else if (spl[0].str() == "static") mode = 2
                        spl.last() to it.second
                    }
                    val name = before.first
                    val f = ASTFuncDef(it, null, name, returnType, args, vararg, mode == 1)


                    brackets.second.splitBrackets(';').filter { it.isNotBlank() }.forEach {
                        f.elements += parseElement(it)
                    }
                    if (mode == 2) {
                        staticFuncs.find { it.name.str() == name.str() }
                            ?.let { illegalExists("Static variable", name, it.name) }
                        staticFuncs += f
                    } else {
                        funcs.find { it.name.str() == name.str() }
                            ?.let { illegalExists("Member variable", name, it.name) }
                        vars.find { it.first.str() == name.str() }
                            ?.let { illegalExists("Member variable", name, it.first) }

                        funcs += f
                    }
                    return@forEach
                }
                if (it.matches(Regex("((static|const)\\s)?fn $nameRegex\\(.*"))) {
                    val splitAtReturn = it.getOperators(":") ?: (Triple("" to noC, it, Line("void", it.last())))


                    var mode = 0
                    val before = splitAtReturn.second.beforeBrackets()!!.let {
                        val spl = it.first.split(' ')
                        if(spl[0].str() == "const") mode = 1
                        else if(spl[0].str() == "static") mode = 2
                        spl.last() to it.second
                    }
                    val name = before.first.substringAfterLast('.')

                    val argsStr = splitAtReturn.second.substringAfter('(').substringBeforeLast(')').splitBrackets(',')
                    val vararg = argsStr.lastOrNull()?.str() == "..."
                    val returnType = splitAtReturn.third

                    val f = ASTFuncDec(
                        it,
                        template,
                        name,
                        returnType,
                        if (vararg) argsStr.dropLast(1) else argsStr,
                        vararg,
                        mode == 1
                    )

                    if (mode == 2) {
                        staticFuncs.find { it.name.str() == name.str() }
                            ?.let { illegalExists("Static variable", name, it.name) }
                        staticFuncs += f
                    } else {
                        funcs.find { it.name.str() == name.str() }
                            ?.let { illegalExists("Member variable", name, it.name) }
                        vars.find { it.first.str() == name.str() }
                            ?.let { illegalExists("Member variable", name, it.first) }

                        funcs += f
                    }
                    return@forEach
                }
                illegal("Expected member declaration", it)
            }

            ASTStructDef(line, name, vars, funcs, staticFuncs, template)
        }
        line.matches(Regex("typealias $nameRegex=.+")) -> {
            val name = line.substringAfter(' ').substringBefore('=')
            val type = line.substringAfter('=')
            ASTTypeAlias(line, name, type)
        }
        else -> illegal("Expected top level object", line)
    }
}
fun parseElement(line: Line): ASTElement {
    if(line.matches(Regex("if\\(.+\\)\\s*\\{.+}"))) {
        val s = Line(line.l)
        val elseIfs = mutableListOf<ASTElseIf>()
        var elseE: ASTElse? = null

        while (s.isNotEmpty() && elseE == null) {
            val name = s.substring(0, s.indexOfFirst { it.c == '(' || it.c == '{' }).trim()
            if (elseIfs.isEmpty() || name.str() == "else if") {
                var i = s.indexOfFirst { it.c == '(' } - 1
                val conditionStart = s.indexOfFirst { it.c == '(' } + 1
                var parens = 0
                var inString = false
                do {
                    val c = s[++i]
                    when {
                        c.c == '"' -> if (s[i - 1].c != '\\') inString = !inString
                        inString -> {
                        }
                        c.c == '(' -> parens++
                        c.c == ')' -> parens--
                    }
                } while (parens > 0)
                val condition = parseObject(s.substring(conditionStart, i))
                while (s[i + 1].c.isWhitespace()) i++
                val ifStart = i + 1
                if (s[ifStart].c != '{') illegal("Expected '{' but found '${s[ifStart].c}'", s[ifStart])
                parens = 0

                do {
                    val c = s[++i]
                    when {
                        c.c == '"' -> if (s[i - 1].c != '\\') inString = !inString
                        inString -> {
                        }
                        c.c == '{' -> parens++
                        c.c == '}' -> parens--
                    }
                } while (parens > 0)

                val innerLines = s.substring(ifStart, i).trim().let {
                    if (it[0].c != '{') illegal("Expected '{' but found '$it'", it[0])
                    it.drop(1).dropLast(1)
                }.splitBrackets(';')

                val e = ASTElseIf(condition)
                s.removeRange(0, i + 1)

                innerLines.forEach {
                    if(it.isNotBlank())
                        e.elements += parseElement(it)
                }
                elseIfs += e
            } else if (name.str() == "else") {
                val first = s.trimStart().drop(4).trimStart()
                if (first[0].c != '{') illegal("Expected '{' but found '$first'", first[0])

                val innerLines = first.trimEnd().let {
                    if (it[0].c != '{') illegal("Expected '{' but found '$it'", it[0])
                    it.drop(1).dropLast(1)
                }.splitBrackets(';')

                val e = ASTElse()

                innerLines.forEach {
                    if(it.isNotBlank())
                        e.elements += parseElement(it)
                }
                elseE = e
            } else illegal("Expected 'else' but found '$name'", name[0])
        }
        return ASTIf(line, elseIfs, elseE)
    }
    line.beforeBrackets()?.let {
        if (it.second[0].c != '{') return@let
        if (it.first.isEmpty())
            return ASTScope(line, it.second.drop(1).dropLast(1).splitBrackets(';').filter { it.isNotBlank() }.map {
                parseElement(it)
            })
        if (it.first.matches(Regex("while(@$nameRegex)?\\(.+\\)"))) {
            val e = ASTWhile(
                it.first,
                parseObject(it.first.substringAfter('(').dropLast(1)),
                if ('@' in it.first.substringBefore('(')) it.first.substringAfter('@')
                    .substringBefore('(') else null
            )
            it.second.drop(1).dropLast(1).splitBrackets(';').forEach {
                if (it.isNotEmpty())
                    e.elements += parseElement(it)
            }
            return e
        }
        if (it.first.matches(Regex("for\\(.+\\)"))) {
            val split = it.first.substringAfter('(').dropLast(1).splitBrackets(';')
            if (split.size < 3) illegal("Expected ';'", it.first.last())
            val before = if (split[0].isBlank()) null else parseExpression(split[0])
            val condition = if (split[1].isBlank()) null else parseObject(split[1])
            val after = if (split[2].isBlank()) null else parseExpression(split[2])
            val e = ASTFor(line, before, condition, after, null)
            it.second.drop(1).dropLast(1).splitBrackets(';').forEach {
                if (it.isNotEmpty())
                    e.elements += parseElement(it)
            }
            return e
        }
    }
    return parseExpression(line, false)
}
fun parseExpression(line: Line, expression: Boolean = true): ASTElement {
    if(line.matches(Regex("return\\W.*"))) {
        return ASTReturn(line, parseObject(line.drop(6)))
    }
    if(line.matches(Regex("break(@$nameRegex)?"))) return ASTBreak(line, if('@' in line) line.substringAfter('@') else null)
    if(line.matches(Regex("continue(@$nameRegex)?"))) return ASTContinue(line, if('@' in line) line.substringAfter('@') else null)

    line.getFirstOperators("+=")?.let { (storage, value) ->
        val strg = parseStorage(storage)
        val a = parseObject(storage)
        val b = parseObject(value)
        return ASTSet(line, strg, ASTOperatorCalc(line, a, b, ASTOperatorCalc.Operation.ADD))
    }
    line.getFirstOperators("-=")?.let { (storage, value) ->
        val strg = parseStorage(storage)
        val a = parseObject(storage)
        val b = parseObject(value)
        return ASTSet(line, strg, ASTOperatorCalc(line, a, b, ASTOperatorCalc.Operation.SUB))
    }
    line.getFirstOperators("*=")?.let { (storage, value) ->
        val strg = parseStorage(storage)
        val a = parseObject(storage)
        val b = parseObject(value)
        return ASTSet(line, strg, ASTOperatorCalc(line, a, b, ASTOperatorCalc.Operation.MUL))
    }
    line.getFirstOperators("/=")?.let { (storage, value) ->
        val strg = parseStorage(storage)
        val a = parseObject(storage)
        val b = parseObject(value)
        return ASTSet(line, strg, ASTOperatorCalc(line, a, b, ASTOperatorCalc.Operation.DIV))
    }
    line.getFirstOperators("%=")?.let { (storage, value) ->
        val strg = parseStorage(storage)
        val a = parseObject(storage)
        val b = parseObject(value)
        return ASTSet(line, strg, ASTOperatorCalc(line, a, b, ASTOperatorCalc.Operation.MOD))
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
        if (it.second[0].c != '(') return@let

        val args = it.second.drop(1).dropLast(1).splitBrackets(',').map {
            parseObject(it)
        }
        if (it.first.last().c == '!') {
            return ASTMacroCall(line, it.first.dropLast(1), args)
        }

        val o = parseObject(it.first)
        return ASTFuncCall(line, o, args)
    }
    illegal("Expected ${if(expression) "expression" else "statement"}", line)
}
fun parseObject(line: Line): ASTObject {
    val line = line.trim()
    if(line.matches(Regex("[+-]?\\d+"))) return ASTInt(line)
    if(line.str() == "true") return ASTBool(line, true)
    if(line.str() == "false") return ASTBool(line, false)
    if(line.matches(Regex("($nameRegex::){0,2}$nameRegex"))) return ASTVarUse(line)

    line.template()?.let {
        return ASTTemplateVar(line, it.first, it.second)
    }

    line.beforeBrackets()?.let {
        when (it.second[0].c) {
            '(' -> {
                if(it.first.isEmpty()) return parseObject(line.drop(1).dropLast(1))

                if(it.first.matches(Regex("new\\W.*"))) {
                    return ASTNew(line, it.first.drop(3), parseObject(it.second.drop(1).dropLast(1)))
                }

                val args = it.second.drop(1).dropLast(1).splitBrackets(',').map {
                    parseObject(it)
                }
                if(it.first.last().c == '!') {
                    return ASTMacroCallObj(line, it.first.dropLast(1), args)
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
                if(it.first.startsWith("[]")) {
                    return ASTArrayInit(line, it.first.drop(2), it.second.drop(1).dropLast(1).splitBrackets(',').map { parseObject(it) })
                }
                it.first.afterBrackets()?.let { arr ->
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

    line.getOperators("!=", "==")?.let {
        val a = parseObject(it.second)
        val b = parseObject(it.third)
        return ASTOperatorCmp(line, a, b, when(it.first.str()) {
            "==" -> ASTOperatorCmp.Operation.EQ
            "!=" -> ASTOperatorCmp.Operation.NE
            else -> TODO()
        }
        )
    }
    line.getOperators("<", "<=", ">=", ">")?.let {
        val a = parseObject(it.second)
        val b = parseObject(it.third)
        return ASTOperatorCmp(line, a, b, when(it.first.str()) {
            "<" -> ASTOperatorCmp.Operation.SLT
            "<=" -> ASTOperatorCmp.Operation.SLE
            ">=" -> ASTOperatorCmp.Operation.SGT
            ">" -> ASTOperatorCmp.Operation.SGE
            else -> TODO()
        }
        )
    }
    line.getOperators("+", "-")?.let {
        val a = parseObject(it.second)
        val b = parseObject(it.third)
        return ASTOperatorCalc(line, a, b, if(it.first.str() == "+") ASTOperatorCalc.Operation.ADD else ASTOperatorCalc.Operation.SUB)
    }
    line.getOperators("*", "/", "%")?.let {
        val a = parseObject(it.second)
        val b = parseObject(it.third)
        return ASTOperatorCalc(line, a, b, when(it.first.str()) {
            "%" -> ASTOperatorCalc.Operation.MUL
            "/" -> ASTOperatorCalc.Operation.DIV
            else -> ASTOperatorCalc.Operation.MOD
        })
    }

    if(line.startsWith("&const ")) return ASTSharedPtr(line, parseStorage(line.substringAfter(' ')), true)
    if(line[0].c == '&' && line.length > 1) return ASTSharedPtr(line, parseStorage(line.drop(1)), false)
    if(line[0].c == '*' && line.length > 1) return ASTDereference(line, parseObject(line.drop(1)))
    if(line[0].c == '#' && line.length > 1) return ASTShare(line, parseObject(line.drop(1)))

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
    line.template()?.let { (name, template) ->
        return ASTSTemplateVar(line, name, template)
    }

    return when {
        line.matches(Regex("($nameRegex::)?$nameRegex")) -> ASTSVarUse(line)
        line[0].c == '*' -> ASTSPtr(line, parseObject(line.drop(1)))
        else -> illegal("Expected variable", line)
    }
}