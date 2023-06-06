package de.drgn.concept

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.TPtr
import de.drgn.irbuilder.values.VGlobal
import de.drgn.irbuilder.values.VString
import de.drgn.irbuilder.values.VValue

val ast = mutableListOf<ASTGlobalElement>()

abstract class ASTGlobalElement(val line: Line, val name: Line, val templates: List<Line>?) {
    val templateCopies = mutableListOf<Pair<ASTGlobalElement, List<Type>>>()
    abstract fun tree(): TreeGlobalElement?
    open fun typesDone(templateTypes: List<Pair<Line, Type>>?) {}
    open fun code(templateTypes: List<Pair<Line, Type>>?) {}
    fun copy(num: Int) : ASTGlobalElement {
        val e = parseTopLevel(line.file, line, null)
        e.name.l.addAll(0, num.toString().map { C(noFile, 0, 0, it) })
        return e
    }
}
abstract class ASTGlobalElementVar(line: Line, name: Line, templates: List<Line>?) : ASTGlobalElement(line, name, templates) {
    abstract fun variable(): DGlobal
}
abstract class ASTFunc(line: Line, templates: List<Line>?, name: Line) : ASTGlobalElementVar(line, name, templates) {
    var inStruct: DTStruct? = null
    var inStaticStruct: DTStruct? = null

    abstract override fun tree(): TreeGlobalElement
}
class ASTFuncDef(
    line : Line,
    templates: List<Line>?,
    name: Line,
    val returns: Line,
    val args: List<Pair<Line, Line>>,
    val isVararg: Boolean,
    val constant: Boolean = false
) : ASTFunc(line, templates, name) {
    val elements = mutableListOf<ASTElement>()
    lateinit var f: TreeFuncDef

    override fun tree(): TreeGlobalElement {
        f = TreeFuncDef(line, name, isVararg)
        return f
    }

    override fun typesDone(templateTypes: List<Pair<Line, Type>>?) {
        f.returns = Type[returns, templateTypes]
        f.args = (if(inStruct != null) listOf(Line("this", noC) to AliasedType(DTSharedPointer(AliasedType(inStruct!!), constant))) else listOf()) + this.args.map { it.first to Type[it.second, templateTypes] }
        f.inStruct = inStruct
        f.global = DGlobal(line.file.pckg, name, AliasedType(DTFunc(f.args.map { it.second }, isVararg, f.returns)), true,
            when {
                inStruct != null -> "\"$inStruct.$name\""
                inStaticStruct != null -> "\"$inStaticStruct::$name\""
                else -> null
            }, function = true, global = inStruct == null && inStaticStruct == null)

        if(inStruct != null) inStruct!!.funcs += f.global
        else if(inStaticStruct != null) inStaticStruct!!.statics += f.global
        else line.file.pckg.globals += f.global

        f.args.forEach {
            val v = DLocal(it.first, it.second, false)
            if(!it.second.copyable) v.setOwner()
            f.argVars += v
        }
    }

    override fun code(templateTypes: List<Pair<Line, Type>>?) {
        super.code(templateTypes)
        openFuncDefs += f
        blocks += f.block
        f.argVars.forEach {
            f.block += it
        }
        elements.forEach {
            f.block += it.tree(false, templateTypes)
        }
        val returned = blocks.peek().pop(false)
        if(f.returns.type != DTVoid && !returned) illegal("Function has to return", name)
        if(f.returns.type is DTVoid && f.block.elements.lastOrNull() !is TreeReturn)
            f.block += TreeReturn(noLine, null)

        openFuncDefs.pop()
    }

    override fun variable() = f.global
}
class ASTFuncDec(
    line : Line,
    templates: List<Line>?,
    name: Line,
    val returns: Line,
    val args: List<Line>,
    val isVararg: Boolean,
    val constant: Boolean = false
) : ASTFunc(line, templates, name) {
    lateinit var f: TreeFuncDec

    override fun tree(): TreeGlobalElement {
        f = TreeFuncDec(line, isVararg)
        return f
    }

    override fun typesDone(templateTypes: List<Pair<Line, Type>>?) {
        f.returns = Type[returns, templateTypes]
        f.args = (if(inStruct != null) listOf(AliasedType(DTSharedPointer(AliasedType(inStruct!!), constant))) else listOf()) + this.args.map { Type[it, templateTypes] }
        f.inStruct = inStruct
        f.global = DGlobal(line.file.pckg, name, AliasedType(DTFunc(f.args, isVararg, f.returns)), true,
            when {
                inStruct != null -> "\"$inStruct.$name\""
                inStaticStruct != null -> "\"$inStaticStruct::$name\""
                else -> null
            }, function = true, global = inStruct == null && inStaticStruct == null)

        if(inStruct != null) inStruct!!.funcs += f.global
        else if(inStaticStruct != null) inStaticStruct!!.statics += f.global
        else line.file.pckg.globals += f.global
    }
    override fun variable() = f.global
}
class ASTMacroDec(
    line : Line,
    name: Line,
    val returns: Line,
    val args: List<Line>,
    val isVararg: Boolean
) : ASTGlobalElement(line, name, null) {
    lateinit var f: TreeMacroDec
    override fun tree(): TreeGlobalElement {
        f = TreeMacroDec(line, name, isVararg, macros["${line.file.pckg}::$name"]?:illegal("Macro doesn't have a body", line))
        return f
    }

    override fun typesDone(templateTypes: List<Pair<Line, Type>>?) {
        f.returns = Type[returns, null]
        f.args = this.args.map {
            val const = it[0].c == '#'
            Type[if(const) it.drop(1) else it, null] to const
        }
        line.file.pckg.macros += f
    }

    companion object {
        val macros =
            mapOf<String, (line: Line, args: List<TreeObject>, builder: IRBuilder.FunctionBuilder) -> VValue?>(
                "std::print" to { line, args, builder ->
                    format(line, builder, args[0].constant as String, args[0], args.drop(1))
                    null
                },
                "std::println" to { line, args, builder ->
                    format(line, builder, args[0].constant as String + '\n', args[0], args.drop(1))
                    null
                },
                "std::stoi" to { line, args, builder ->
                    builder.callFunc(atoi.first, atoi.second, args[0].ir(builder))
                },
                "rnd::seed" to { line, args, builder ->
                    builder.callFunc(srand.first, srand.second, args[0].ir(builder))
                    null
                },
                "rnd::randint" to { line, args, builder ->
                    builder.callFunc(rand.first, rand.second)
                },
            )

        private fun format(line: Line, builder: IRBuilder.FunctionBuilder, format: String, formatObj: TreeObject, args: List<TreeObject>) {
            val sb = StringBuilder()

            var n = 0

            val resArgs = mutableListOf<VValue>()

            val ite = format.withIndex().iterator()

            for ((i, c) in ite) {
                if (c == '{') {
                    if (n == args.size) illegal("More positional arguments in format string than arguments", formatObj.line)
                    var j = i + 1
                    while(ite.next().value != '}') j++
                    val p = formatObj(args[n], builder, format.substring(i + 1, j).trim())
                    sb.append(p.first)
                    resArgs += p.second
                    n++
                } else sb.append(c)
            }
            if (n != args.size) {
                val l = mutableListOf<C>()
                args.drop(n).forEach { l += it.line.l }
                illegal("More arguments that positional arguments in format string", Line(l))
            }
            builder.callFunc(printf.first, printf.second, *(listOf(VString(sb.toString())) + resArgs).toTypedArray())
        }

        private fun formatObj(obj: TreeObject, builder: IRBuilder.FunctionBuilder, parameter: String = ""): Pair<String, List<VValue>> {
            if(obj.constant is String) return obj.constant to emptyList()
            val sb = StringBuilder()
            val resArgs = mutableListOf<VValue>()
            val type = obj.type
            when {
                type.type is DTInt -> {
                    val t = when(type.type) {
                        is DTI8 -> {
                            (if(parameter == "c") "c" else "h") to TreeCast(obj.line, obj, AliasedType(DTI16))
                        }
                        is DTI16 -> "h" to obj
                        is DTI32 -> "" to obj
                        is DTI64 -> "ll" to obj
                        else -> TODO()
                    }
                    sb.append('%').append(t.first)
                    resArgs += t.second.ir(builder)
                    if (parameter == "x") sb.append('x') else if(parameter.isEmpty()) sb.append('d')
                }
                (type.type is DTArray && type.type.of.type == DTI8) || (type.type is DTSharedPointer && type.type.to.type is DTSizedArray && type.type.to.type.of.type == DTI8) -> {
                    sb.append("%s")
                    resArgs += obj.ir(builder)
                }

                type.type is DTSizedArray -> {
                    if(type.type.size == null) illegal("Cannot print objects of type '${type.type}'", obj.line)
                    sb.append(
                        "[${
                            (0 until type.type.size).joinToString {
                                val o = formatObj(
                                    TreeArrayIndex(
                                        obj.line,
                                        obj.toStorage(),
                                        TreeInt(obj.line, it, AliasedType(DTI64)),
                                        type.type.of
                                    ), builder
                                )
                                resArgs += o.second
                                o.first
                            }
                        }]"
                    )
                }
                type.type is DTStruct -> {
                    sb.append(
                        "${type.type.name} { ${run {
                            var i = 0
                            type.type.vars.joinToString {
                                val o = formatObj(
                                    TreeMember(
                                        obj.line,
                                        obj,
                                        i++
                                    ), builder
                                )
                                resArgs += o.second
                                "${it.first}: ${o.first}"
                            }
                        }
                        } }"
                    )
                }
                type.type is DTSharedPointer && type.type.to.type is DTStruct -> {
                    val stringFunc = type.type.to.type.funcs.find {
                        it.type.type is DTFunc && it.name.str() == "_string" && it.type.type.args.size == 1 && it.type.type.returns.type == DTArray(AliasedType(DTI8), true)
                    }?:illegal("Cannot print objects of type '$type' because they don't implement the 'const _string(): const i8[]' function", obj.line)

                    sb.append("%s")
                    resArgs += builder.callFunc(FuncSignature(TPtr, TPtr), VGlobal(stringFunc.irName), obj.ir(builder))!!
                }
                else -> illegal("Cannot print objects of type '$type'", obj.line)
            }
            return sb.toString() to resArgs
        }
    }
}
class ASTStructDef(
    line: Line,
    name: Line,
    val vars: List<Triple<Line, Pair<Boolean, Line?>, ASTObject?>>,
    val funcs: List<ASTFunc>,
    val staticFuncs: List<ASTFunc>,
    templates: List<Line>?
) : ASTGlobalElement(line, name, templates) {
    lateinit var struct: DTStruct
    override fun tree(): TreeGlobalElement? {
        line.file.pckg.structs.find { it.name.str() == name.str() }?.let { illegalExists("Struct", name, it.name) }
        struct = DTStruct(line.file.pckg, name)
        line.file.pckg.structs += struct
        funcs.forEach {
            it.inStruct = struct
            tree += it.tree()
        }
        staticFuncs.forEach {
            it.inStaticStruct = struct
            tree += it.tree()
        }
        return null
    }

    override fun typesDone(templateTypes: List<Pair<Line, Type>>?) {
        vars.forEach {

            struct.vars.find { v -> v.first.str() == it.first.str() }?.let { v ->
                illegalExists("Property", it.first, v.first)
            }

            val t: AliasedType<*>
            val o: TreeObject?
            if(it.second.second == null) {
                o = it.third!!.obj(null, templateTypes)
                t = o.type
            } else {
                t = Type[it.second.second!!, templateTypes]
                o = it.third?.obj(t, templateTypes)
            }

            if(!t.copyable) illegal("Properties cannot be of unique type", it.second.second!!)
            struct.vars += Triple(it.first, it.second.first to t, o)
        }
        struct.complete = true
        funcs.forEach { it.typesDone(templateTypes) }
        staticFuncs.forEach { it.typesDone(templateTypes) }
    }

    override fun code(templateTypes: List<Pair<Line, Type>>?) {
        super.code(templateTypes)
        funcs.forEach { it.code(templateTypes) }
        staticFuncs.forEach { it.code(templateTypes) }
    }
}
class ASTTypeAlias(line: Line, name: Line, val type: Line) : ASTGlobalElement(line, name, null) {
    override fun tree(): TreeGlobalElement? {
        val t = Type[type, null]
        line.file.pckg.aliases += (line.file.pckg to name.str()) to t.type
        return null
    }
}
class ASTGlobalDef(
    line: Line,
    name: Line,
    val constant: Boolean,
    val type: Line?,
    val obj: ASTObject,
    templates: List<Line>?
) : ASTGlobalElementVar(line, name, templates) {

    lateinit var tree: TreeGlobalDef
    override fun tree(): TreeGlobalElement {
        tree = TreeGlobalDef(line)
        return tree
    }

    override fun typesDone(templateTypes: List<Pair<Line, Type>>?) {
        val t: AliasedType<*>
        val o: TreeObject
        if(type == null) {
            o = obj.obj(null, templateTypes)
            t = o.type
        } else {
            t = Type[type, templateTypes]
            o = obj.obj(t, templateTypes)
        }
        if(o !is TreeConstant) illegal("Expected constant", o.line)
        tree.obj = o
        tree.global = DGlobal(line.file.pckg, name, t, constant, "\"${line.file.pckg}::$name\"", if(constant) o.constant else null)
        line.file.pckg.globals += tree.global
    }

    override fun variable() = tree.global
}

abstract class ASTElement(val line: Line) {
    abstract fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement
    fun tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val done = blocks.peek().breakFrom != null
        val returned = blocks.peek().returned
        val t = _tree(unreachable, templateTypes)
        if(returned || done) {
            t.unreachable = true
            blocks.peek().unreachableElements += t
        }
        return t
    }
}
class ASTReturn(line: Line, val obj: ASTObject?) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        blocks.peek().returned = true
        return TreeReturn(line, obj?.obj(openFuncDefs.peek().returns, templateTypes))
    }
}
private fun funcCall(line: Line, o: TreeObject, args: List<ASTObject>, templateTypes: List<Pair<Line, Type>>?): List<TreeObject> {
    if(o.type.type !is DTFunc) illegal("Expected function pointer but found '${o.type}'", o.line)

    val memberOff = if(o is TreeStaticMember) 1 else 0
    if(args.size < o.type.type.args.size - memberOff || (args.size != o.type.type.args.size - memberOff && !o.type.type.isVararg))
        illegal("Expected ${o.type.type.args.size - memberOff} argument(s) but found ${args.size}", line)

    val args = args.mapIndexed { i, it ->
        val o = it.obj(if(i + memberOff < o.type.type.args.size) o.type.type.args[i + memberOff] else null, templateTypes)
        if(o is TreeLocalVarUse) {
            if(o.v.owner == true) o.v.move(o.line)
        }
        o
    }.toMutableList()
    if(o is TreeStaticMember) {
        if(!o.type.type.args[0].constant && o.obj.type.constant) illegal("Cannot call non-constant function on constant object", o.line)
        args.add(0, o.obj)
    }
    return args
}
class ASTFuncCall(line: Line, val func: ASTObject, val args: List<ASTObject>) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val o = func.obj(null, templateTypes)

        return TreeFuncCall(line, o, funcCall(line, o, args, templateTypes))
    }
}
class ASTVarDef(line: Line, val name: Line, val type: Line?, val obj: ASTObject, val constant: Boolean) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        blocks.peek().vars.find { it.name.str() == name.str() }?.let {
            illegalExists("Variable", name, it.name)
        }
        val t: AliasedType<*>
        val o: TreeObject
        if(type == null) {
            o = obj.obj(null, templateTypes)
            t = o.type
        } else {
            t = Type[type, templateTypes]
            o = obj.obj(t, templateTypes)
        }
        val v = DLocal(name, t, constant, if(constant) o.constant else null)
        if(o is TreeLocalVarUse) {
            if(o.v.owner == true) o.v.move(o.line)
        }
        if(!t.copyable) v.setOwner()
        blocks.peek() += v
        return TreeVarDef(line, v, o)
    }
}
class ASTSet(line: Line, val strg: ASTStorage, val obj: ASTObject) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val s = strg.tree(templateTypes)
        if(s.constant) illegal("Cannot modify constant", strg.line)
        val o = obj.obj(s.type, templateTypes)
        if(o is TreeLocalVarUse) {
            if(o.v.owner == true) o.v.move(o.line)
        }
        if(s is TreeSLocalVarUse) {
            if(s.v.owner != null) s.v.setOwner()
        }
        return TreeSet(line, s, o)
    }
}
class ASTScope(line: Line, val elements: List<ASTElement>) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val f = TreeScope(line)
        blocks += f.block
        elements.forEach {
            f.block += it.tree(unreachable, templateTypes)
        }
        if(blocks.peek().pop(unreachable)) blocks.peek().returned = true
        return f
    }
}
class ASTMacroCall(line: Line, val macro: Line, val args: List<ASTObject>) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val macro = getMacro(macro)
        if(args.size < macro.args.size || (args.size != macro.args.size && !macro.isVararg))
            illegal("Expected ${macro.args.size} argument(s) but found ${args.size}", line)

        val args = args.mapIndexed { i, it ->
            val o = it.obj(if(i < macro.args.size) macro.args[i].first else null, templateTypes)
            if(i < macro.args.size && macro.args[i].second && o.constant == null) illegal("Expected constant", o.line)
            if(o is TreeLocalVarUse) {
                if(o.v.owner == true) o.v.move(o.line)
            }
            o
        }

        return TreeMacroCall(line, macro, args)
    }
}
class ASTIf(line: Line, val ifs: List<ASTElseIf>, val elseElement: ASTElse?) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val treeIfs = mutableListOf<TreeElseIf>()
        var returns = true
        ifs.forEach {
            val c = it.condition.obj(AliasedType(DTBool), templateTypes)
            val t = TreeElseIf(c)
            blocks += t.block
            it.elements.forEach {
                t.block += it.tree(unreachable || c.constant == false, templateTypes)
            }
            val breakFrom = blocks.peek().breakFrom
            if(!blocks.peek().pop(unreachable || c.constant == false)) returns = false
            if(breakFrom != null) blocks.peek().breakFrom = breakFrom

            if((c.constant == false || treeIfs.any { it.condition.constant == true }) && !unreachable) {
                val l = mutableListOf<C>()
                t.block.elements.forEach {
                    it.unreachable = true
                    l += it.line.l
                }
                if(l.isNotEmpty()) unreachable(Line(l))
            }

            treeIfs += t
        }
        val constantIf = treeIfs.find { it.condition.constant == true }
        val elseE = if(elseElement == null) null else {
            val t = TreeElse()
            blocks += t.block
            elseElement.elements.forEach {
                t.block += it.tree(unreachable || constantIf != null, templateTypes)
            }
            if(!blocks.peek().pop(unreachable || constantIf != null)) returns = false
            t
        }
        if(returns) blocks.peek().returned = true

        if(constantIf != null && !unreachable) {
            if(constantIf.block.returned) blocks.peek().returned = true
            if(elseE != null) {
                val l = mutableListOf<C>()
                elseE.block.elements.forEach {
                    it.unreachable = true
                    l += it.line.l
                }
                if(l.isNotEmpty()) unreachable(Line(l))
            }
        }

        return TreeIf(line, treeIfs, elseE)

    }
}
class ASTElseIf(val condition: ASTObject) {
    val elements = mutableListOf<ASTElement>()
}
class ASTElse {
    val elements = mutableListOf<ASTElement>()
}
class ASTWhile(line: Line, val condition: ASTObject, val label: Line?) : ASTElement(line) {
    val elements = mutableListOf<ASTElement>()
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val cond = condition.obj(AliasedType(DTBool), templateTypes)
        val e = TreeWhile(line, cond, label)
        loops += e
        blocks += e.block
        elements.forEach {
            e.block += it.tree(unreachable || cond.constant == false, templateTypes)
        }
        blocks.peek().pop(unreachable)
        loops.pop()
        if(e.infinite) {
            blocks.peek().returned = true
        }
        else if(cond.constant == false && !unreachable) {
            val l = mutableListOf<C>()
            elements.forEach { l += it.line.l }
            if(l.isNotEmpty()) unreachable(Line(l))
        }
        return e
    }
}
class ASTFor(line: Line, val before: ASTElement?, val condition: ASTObject?, val after: ASTElement?, val label: Line?) : ASTElement(line) {
    val elements = mutableListOf<ASTElement>()
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val before = before?.tree(unreachable, templateTypes)
        val cond = condition?.obj(AliasedType(DTBool), templateTypes)?:TreeBool(line, true)
        val after = after?.tree(unreachable, templateTypes)
        val e = TreeFor(line, before, cond, after, label)
        loops += e
        blocks += e.block
        elements.forEach {
            e.block += it.tree(unreachable || cond.constant == false, templateTypes)
        }
        blocks.peek().pop(cond.constant == false)
        loops.pop()
        if(e.infinite) {
            blocks.peek().returned = true
        }
        else if(cond.constant == false && !unreachable) {
            val l = mutableListOf<C>()
            elements.forEach { l += it.line.l }
            if(l.isNotEmpty()) unreachable(Line(l))
        }
        if(before is TreeVarDef) blocks.peek().vars.removeLast()
        return e
    }
}
class ASTBreak(line: Line, val label: Line?) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val error = if(label != null && !loops.isEmpty()) "with that label" to label else "" to line
        val loop = loops.findLast {
            it.label?.str() == (label?:it.label)?.str()
        }?:illegal("break not in loop ${error.first}", error.second)

        if(!unreachable) loop.infinite = false
        blocks.peek().breakFrom = loop

        return TreeBreak(line, loop, blocks.indexOf(loop.block))
    }
}
class ASTContinue(line: Line, val label: Line?) : ASTElement(line) {
    override fun _tree(unreachable: Boolean, templateTypes: List<Pair<Line, Type>>?): TreeElement {
        val error = if(label != null && !loops.isEmpty()) "with that label" to label else "" to line
        val loop = loops.findLast {
            it.label?.str() == (label?:it.label)?.str()
        }?:illegal("continue not in loop ${error.first}", error.second)

        blocks.peek().breakFrom = loop

        return TreeContinue(line, loop, blocks.indexOf(loop.block))
    }
}


abstract class ASTObject(val line: Line) {
    fun obj(type: AliasedType<*>? = null, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val o = _treeObj(type, templateTypes)
        if(type != null && type.type != o.type.type) {
            if (
                (o.type.type is DTOwnerPointer && type.type is DTSharedPointer && o.type.type.to.type == type.type.to.type)
                || (o.type.type is DTPointer && !o.type.constant && type.type is DTSharedPointer && type.constant && o.type.type.to.type == type.type.to.type)
                || (o.type.type is DTPointer && o.type.type.to.type is DTSizedArray && type.type is DTArray && (if (!type.constant) !o.type.constant else true))
                || (o.type.type is DTInt && type.type is DTInt)
            )
                return TreeCast(o.line, o, type)
            illegal("Expected '$type' but found '${o.type}'", line)
        }
        return o
    }
    abstract fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject
}
class ASTInt(line: Line) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        if(type?.type !is DTInt?) illegal("Expected '$type' but found 'i32'", line)
        when(type?.type?:DTI64) {
            is DTI8 -> line.str().toByteOrNull()?:illegal("Integer literal cannot be stored in a i8", line)
            is DTI16 -> line.str().toShortOrNull()?:illegal("Integer literal cannot be stored in a i16", line)
            is DTI32 -> line.str().toIntOrNull()?:illegal("Integer literal cannot be stored in a i32", line)
            is DTI64 -> line.str().toLongOrNull()?:illegal("Integer literal cannot be stored in a i64", line)
        }
        val type = when {
            type != null -> type as AliasedType<DTInt>
            line.str().toIntOrNull() != null -> AliasedType(DTI32)
            else -> AliasedType(DTI64)
        }
        val value = line.str().toLong()
        return TreeInt(line, value, type)
    }
}
class ASTBool(line: Line, val value: Boolean) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?) = TreeBool(line, value)
}
class ASTVarUse(line: Line) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val v = getVar(line)
        if(v.owner == false) illegalMoved(line, v.movedAt)
        return when (v) {
            is DGlobal -> TreeGlobalVarUse(line, v)
            is DLocal -> TreeLocalVarUse(line, v)
            else -> TODO()
        }
    }
}
class ASTFuncCallObj(line: Line, val func: ASTObject, val args: List<ASTObject>) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val o = func.obj(null, templateTypes)

        return TreeFuncCallObj(line, o, funcCall(line, o, args, templateTypes))
    }
}
class ASTString(line: Line, val value: String) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        return TreeString(line, value)
    }
}
class ASTSharedPtr(line: Line, val strg: ASTStorage, val constant: Boolean) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val s = strg.tree(templateTypes)
        return TreeSharedPtr(line, s, constant || s.constant)
    }
}
class ASTDereference(line: Line, val pointer: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val p = pointer.obj(null, templateTypes)
        if(p.type.type !is DTPointer) illegal("Expected pointer but found '${p.type}'", p.line)
        if(!p.type.type.to.copyable) illegal("Cannot dereference pointer to unique object", line)
        return TreeDereference(line, p)
    }
}
class ASTArrayIndex(line: Line, val array: ASTStorage, val index: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val o = array.tree(templateTypes)

        val i = index.obj(AliasedType(DTI64), templateTypes)

        return when(o.type.type) {
            is DTAnyArray -> TreeArrayIndex(line, o, i, o.type.type.of)
            is DTPointer -> {
                if(o.type.type.to.type !is DTAnyArray) illegal("Expected pointer to array but found '${o.type}'", o.line)
                TreeArrayIndex(line, TreeSPtr(o.line, parseObject(o.line).obj(o.type, templateTypes)), i, o.type.type.to.type.of)
            }
            else -> illegal("Expected array but found '${o.type}'", o.line)
        }
    }
}
class ASTStructInit(line: Line, val struct: Line, val values: List<Pair<Line, ASTObject>>) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {

        val new = struct.startsWith("new ")

        val constant: Boolean
        val t = if(new) {
            constant = struct.substringAfter(' ').startsWith("const ")
            Type[if(constant) struct.substringAfter("const ") else struct.substringAfter(' '), templateTypes]
        } else {
            constant = false
            Type[struct, templateTypes]
        }

        if(t.type !is DTStruct) illegal("Expected struct type", struct)
        if(!t.type.complete) illegal("Cannot initialize incomplete type", line)

        val v = mutableMapOf<Int, Pair<Line, TreeObject>>()

        values.forEach {
            val i = t.type.vars.indexOfFirst { v -> v.first.str() == it.first.str() }
            if(i == -1) illegal("Undefined property", it.first)
            if(v.containsKey(i)) illegalExists("Property", it.first, v[i]!!.first)
            v[i] = it.first to it.second.obj(t.type.vars[i].second.second, templateTypes)
        }
        val properties = t.type.vars.mapIndexed { i, p ->
            v[i]?.second?:p.third?:illegal("Property '${p.first}' has to be initialized", line)
        }
        return if(new) TreeNewStruct(line, t as AliasedType<DTStruct>, properties, constant)
        else TreeStructInit(line, t as AliasedType<DTStruct>, properties)
    }
}
class ASTArrayInit(line: Line, val type: Line, val values: List<ASTObject>) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        if(this.type.startsWith("new ")) {
            val t = Type[this.type.substringAfter(' '), templateTypes]
            val v = values.map { it.obj(t, templateTypes) }
            return TreeNewArray(line, t, v)
        }
        val t = Type[this.type, templateTypes]
        val v = values.map { it.obj(t, templateTypes) }
        return TreeArrayInit(line, t, v)
    }
}
class ASTArrayFillInit(line: Line, val type: Line, val size: ASTObject, val with: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val size = ((size.obj(AliasedType(DTI32), templateTypes).constant as Long?)?:illegal("Expected constant", this.size.line)).toInt()

        if(this.type.startsWith("new ")) {
            val t = Type[this.type.substringAfter(' '), templateTypes]
            val v = with.obj(t, templateTypes)
            return TreeNewArrayFill(line, t, size, v)
        }
        val t = Type[this.type, templateTypes]
        val v = with.obj(t, templateTypes)
        return TreeArrayFillInit(line, t, size, v)
    }
}
class ASTMember(line: Line, val obj: ASTObject, val member: Line) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val o = obj.obj(null, templateTypes)
        return when (o.type.type) {
            is DTPointer -> {
                if(o.type.type.to.type !is DTStruct) illegal("Expected pointer to struct but found '${o.type}'", o.line)
                val index = o.type.type.to.type.vars.indexOfFirst { it.first.str() == member.str() }
                if(index == -1) {
                    val static = o.type.type.to.type.funcs.find { it.name.str() == member.str() }?:illegal("Member doesn't exist", member)
                    TreeStaticMember(line, static, o)
                }
                else TreePtrMember(line, o, index, o.type.type.to.type)
            }
            is DTStruct -> {
                val index = o.type.type.vars.indexOfFirst { it.first.str() == member.str() }
                if(index == -1) {
                    val static = o.type.type.funcs.find { it.name.str() == member.str() }?:illegal("Member doesn't exist", member)
                    val strg = parseStorage(o.line).tree(templateTypes)
                    TreeStaticMember(line, static, TreeSharedPtr(o.line, strg, strg.constant))
                }
                else TreeMember(line, o, index)
            }
            else -> illegal("Expected struct but found '${o.type}'", o.line)
        }
    }
}
class ASTNew(line: Line, val type: Line, val value: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val t = Type[this.type, templateTypes]
        val v = value.obj(t, templateTypes)
        return TreeNew(line, t, v)
    }
}
class ASTOperatorCalc(line: Line, val a: ASTObject, val b: ASTObject, val operation: Operation) : ASTObject(line) {
    enum class Operation {
        ADD, SUB, MUL, DIV, MOD
    }

    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        var a = a.obj(null, templateTypes)
        var b = b.obj(null, templateTypes)
        val typeA = a.type
        val typeB = b.type
        if(typeA.type !is DTInt) illegal("Expected integer but found '${a.type}'", a.line)
        if(typeB.type !is DTInt) illegal("Expected integer but found '${b.type}'", b.line)

        if(typeA.type.size > typeB.type.size) b = TreeCast(b.line, b, a.type)
        else if(typeB.type.size > typeA.type.size) a = TreeCast(a.line, a, b.type)

        return TreeOperatorCalc(line, a, b, operation)
    }
}
class ASTOperatorCmp(line: Line, val a: ASTObject, val b: ASTObject, val operation: Operation) : ASTObject(line) {
    enum class Operation {
        SLT, SLE, SGT, SGE, EQ, NE
    }

    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        var a = a.obj(null, templateTypes)
        var b = b.obj(null, templateTypes)
        val typeA = a.type
        val typeB = b.type
        if(typeA.type !is DTInt) illegal("Expected integer", a.line)
        if(typeB.type !is DTInt) illegal("Expected integer", b.line)

        if(typeA.type.size > typeB.type.size) b = TreeCast(b.line, b, a.type)
        else if(typeB.type.size > typeA.type.size) a = TreeCast(a.line, a, b.type)

        return TreeOperatorCmp(line, a, b, operation)
    }
}
class ASTShare(line: Line, val obj: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val o = obj.obj(null, templateTypes)
        if(o.type.type !is DTOwnerPointer) illegal("Expected owner pointer", obj.line)
        return TreeCast(line, o, AliasedType(DTSharedPointer(o.type.type.to, o.type.constant)))
    }
}
class ASTMacroCallObj(line: Line, val macro: Line, val args: List<ASTObject>) : ASTObject(line) {
    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val macro = getMacro(macro)
        if(args.size < macro.args.size || (args.size != macro.args.size && !macro.isVararg))
            illegal("Expected ${macro.args.size} argument(s) but found ${args.size}", line)

        val args = args.mapIndexed { i, it ->
            val o = it.obj(if(i < macro.args.size) macro.args[i].first else null, templateTypes)
            if(i < macro.args.size && macro.args[i].second && o.constant == null) illegal("Expected constant", o.line)
            if(o is TreeLocalVarUse) {
                if(o.v.owner == true) o.v.move(o.line)
            }
            o
        }

        return TreeMacroCallObj(line, macro, args)
    }
}
class ASTTemplateVar(line: Line, val name: Line, val template: List<Line>) : ASTObject(line) {
    companion object {
        var templates = 0
    }
    val num = templates++

    override fun _treeObj(type: AliasedType<*>?, templateTypes: List<Pair<Line, Type>>?): TreeObject {
        val original = run {
            if("::" in name.str()) {
                val pckg = DPackage[name.substringBefore("::").str()]?: illegal("Package doesn't exist", name.substringBefore("::"))
                val v = pckg.templateVars.find { name.substringAfter("::").str() == it.name.str() }?: illegal("Macro doesn't exist", name)
                return@run v
            }

            name.file.pckg.templateVars.forEach { if(it.name.str() == name.str()) return@run it }

            name.file.imports.forEach {
                it.templateVars.forEach {
                    if(it.name.str() == name.str()) return@run it
                }
            }

            illegal("Template doesn't exist", name)
        }


        val types = mutableListOf<Pair<Line, Type>>()

        if(template.size != original.templates!!.size)
            illegal("Expected ${original.templates.size} template arguments but found ${template.size}", line)
        template.forEachIndexed { i, it ->
            types += original.templates[i] to Type[it, templateTypes].type
        }
        if(templateTypes != null) types += templateTypes

        original.templateCopies.find {
            it.second.forEachIndexed { i, type ->
                if(type != types[i].second) return@find false
            }
            true
        }?.let { return TreeGlobalVarUse(line, (it.first as ASTGlobalElementVar).variable()) }

        val element = original.copy(num) as ASTGlobalElementVar

        val tree = element.tree()!!
        element.typesDone(types)
        element.code(types)
        de.drgn.concept.tree += tree

        original.templateCopies += element to types.map { it.second }

        return TreeGlobalVarUse(line, element.variable())
    }
}




abstract class ASTStorage(val line: Line) {
    abstract fun tree(templateTypes: List<Pair<Line, Type>>?): TreeStorage
}
class ASTSVarUse(line: Line) : ASTStorage(line) {
    override fun tree(templateTypes: List<Pair<Line, Type>>?): TreeStorage {
        return when (val v = getVar(line)) {
            is DLocal -> TreeSLocalVarUse(line, v)
            is DGlobal -> TreeSGlobalVarUse(line, v)
            else -> TODO()
        }
    }
}
class ASTSPtr(line: Line, val pointer: ASTObject) : ASTStorage(line) {
    override fun tree(templateTypes: List<Pair<Line, Type>>?): TreeStorage {
        val p = pointer.obj(null, templateTypes)
        if(p.type.type !is DTPointer) illegal("Expected pointer but found '${p.type}'", p.line)
        return TreeSPtr(line, p)
    }
}
class ASTSArrayIndex(line: Line, val array: ASTObject, val index: ASTObject) : ASTStorage(line) {
    override fun tree(templateTypes: List<Pair<Line, Type>>?): TreeStorage {
        val o = array.obj(null, templateTypes)

        val i = index.obj(AliasedType(DTI64), templateTypes)

        return when(o.type.type) {
            is DTArray -> TreeSArrayIndex(line, o, i)
            is DTSizedArray -> {
                val strg = parseStorage(o.line).tree(templateTypes)
                TreeSPointerIndex(line, TreeSharedPtr(o.line, strg, strg.constant), i)
            }
            is DTPointer -> {
                val t = o.type.type.to.type
                if(t !is DTArray && (t !is DTSizedArray || t.size != null)) illegal("Expected pointer to unsized array but found '${o.type}'", o.line)
                TreeSPointerIndex(line, o, i)
            }
            else -> illegal("Expected array but found '${o.type}'", o.line)
        }
    }
}
class ASTSMember(line: Line, val obj: ASTObject, val member: Line) : ASTStorage(line) {
    override fun tree(templateTypes: List<Pair<Line, Type>>?): TreeStorage {
        val o = obj.obj(null, templateTypes)
        o.type.type as DTSharedPointer
        if(o.type.type.to.type is DTPointer) {
            val struct = o.type.type.to.type.to
            if (struct.type !is DTStruct) illegal("Expected pointer to struct but found '${o.type.type.to}'", o.line)

            val index = struct.type.vars.indexOfFirst { it.first.str() == member.str() }
            if (index == -1) illegal("Member doesn't exist", member)
            return TreeSPtrMember(line, o, index, struct.type)
        }
        else {
            val struct = (o.type.type as DTPointer).to
            if (struct.type !is DTStruct) illegal("Expected struct but found '$struct'", o.line)

            val index = struct.type.vars.indexOfFirst { it.first.str() == member.str() }
            if (index == -1) illegal("Member doesn't exist", member)
            return TreeSMember(line, o, index, struct.type)
        }
    }
}
class ASTSTemplateVar(line: Line, val name: Line, val template: List<Line>) : ASTStorage(line) {
    companion object {
        var templates = 0
    }
    val num = templates++

    override fun tree(templateTypes: List<Pair<Line, Type>>?): TreeStorage {
        val original = run {
            if("::" in name.str()) {
                val pckg = DPackage[name.substringBefore("::").str()]?: illegal("Package doesn't exist", name.substringBefore("::"))
                val v = pckg.templateVars.find { name.substringAfter("::").str() == it.name.str() }?: illegal("Macro doesn't exist", name)
                return@run v
            }

            name.file.pckg.templateVars.forEach { if(it.name.str() == name.str()) return@run it }

            name.file.imports.forEach {
                it.templateVars.forEach {
                    if(it.name.str() == name.str()) return@run it
                }
            }

            illegal("Template doesn't exist", name)
        }


        val types = mutableListOf<Pair<Line, Type>>()

        if(template.size != original.templates!!.size)
            illegal("Expected ${original.templates.size} template arguments but found ${template.size}", line)
        template.forEachIndexed { i, it ->
            types += original.templates[i] to Type[it, templateTypes].type
        }
        if(templateTypes != null) types += templateTypes

        original.templateCopies.find {
            it.second.forEachIndexed { i, type ->
                if(type != types[i].second) return@find false
            }
            true
        }?.let { return TreeSGlobalVarUse(line, (it.first as ASTGlobalElementVar).variable()) }

        val element = original.copy(num) as ASTGlobalElementVar

        val tree = element.tree()!!
        element.typesDone(types)
        element.code(types)
        de.drgn.concept.tree += tree

        original.templateCopies += element to types.map { it.second }

        return TreeSGlobalVarUse(line, element.variable())
    }
}