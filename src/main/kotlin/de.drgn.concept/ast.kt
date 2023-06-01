package de.drgn.concept

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.TPtr
import de.drgn.irbuilder.values.VGlobal
import de.drgn.irbuilder.values.VString
import de.drgn.irbuilder.values.VValue

val ast = mutableListOf<ASTGlobalElement>()

abstract class ASTGlobalElement(val line: Line) {
    abstract fun tree(): TreeGlobalElement
    open fun typesDone() {}
    open fun code() {}
}

class ASTFuncDef(
    line : Line,
    val name: Line,
    val returns: Line,
    val args: List<Pair<Line, Line>>,
    val isVararg: Boolean,
    val constant: Boolean = false
) : ASTGlobalElement(line) {
    val elements = mutableListOf<ASTElement>()
    lateinit var f: TreeFuncDef
    var inStruct: DTStruct? = null
    override fun tree(): TreeGlobalElement {
        f = TreeFuncDef(line, name, isVararg)
        return f
    }

    override fun typesDone() {
        f.returns = Type[returns]
        f.args = (if(inStruct != null) listOf(Line("this", noC) to DTSharedPointer(inStruct!!, constant)) else listOf()) + this.args.map { it.first to Type[it.second] }
        f.inStruct = inStruct
        f.global = DGlobal(line.file.pckg, name, DTFunc(f.args.map { it.second }, isVararg, f.returns), true, if(inStruct == null) null else "\"${inStruct!!.name}.$name\"")

        if(inStruct != null) inStruct!!.statics += f.global
        else line.file.pckg.globals += f.global

        f.args.forEach {
            val v = DLocal(it.first, it.second, false)
            if(!it.second.copyable) v.setOwner()
            f.argVars += v
        }
    }

    override fun code() {
        openFuncDef = f
        blocks += f.block
        f.argVars.forEach {
            f.block += it
        }
        elements.forEach {
            f.block += it.tree()
        }
        blocks.pop()
        if(f.block.elements.lastOrNull() !is TreeReturn)
            f.block += TreeReturn(noLine, null)
    }
}
class ASTMacroDec(
    line : Line,
    val name: Line,
    val returns: Line,
    val args: List<Line>,
    val isVararg: Boolean
) : ASTGlobalElement(line) {
    lateinit var f: TreeMacroDec
    override fun tree(): TreeGlobalElement {
        f = TreeMacroDec(line, name, isVararg, macros["${line.file.pckg}::$name"]?:illegal("Macro doesn't have a body", line))
        return f
    }

    override fun typesDone() {
        f.returns = Type[returns]
        f.args = this.args.map {
            val const = it[0].c == '#'
            Type[if(const) it.drop(1) else it] to const
        }
        line.file.pckg.macros += f
    }

    companion object {
        val macros =
            mapOf<String, (line: Line, args: List<TreeObject>, builder: IRBuilder.FunctionBuilder) -> TreeObject?>(
                "std::print" to { line, args, builder ->
                    format(line, builder, args[0].constant as String, args.drop(1))
                    null
                },
                "std::println" to { line, args, builder ->
                    format(line, builder, args[0].constant as String + '\n', args.drop(1))
                    null
                }
            )

        private fun format(line: Line, builder: IRBuilder.FunctionBuilder, format: String, args: List<TreeObject>) {
            val sb = StringBuilder()

            var n = 0

            val resArgs = mutableListOf<VValue>()

            for ((i, c) in format.withIndex()) {
                if (c == '%') {
                    if (n > args.size) illegal("More positional arguments in format string than arguments", line)
                    val p = formatObj(args[n], builder)
                    sb.append(p.first)
                    resArgs += p.second
                    n++
                } else sb.append(c)
            }
            if (n != args.size) illegal("More arguments that positional arguments in format string", line)
            builder.callFunc(printf.first, printf.second, *(listOf(VString(sb.toString())) + resArgs).toTypedArray())
        }

        private fun formatObj(obj: TreeObject, builder: IRBuilder.FunctionBuilder): Pair<String, List<VValue>> {
            val sb = StringBuilder()
            val resArgs = mutableListOf<VValue>()
            val type = obj.type
            when {
                type is DTInt -> {
                    sb.append("%d")
                    resArgs += obj.ir(builder)
                }

                type is DTPointerArray && type.of == DTI8 -> {
                    sb.append("%s")
                    resArgs += obj.ir(builder)
                }

                type is DTSizedArray -> {
                    sb.append(
                        "[${
                            (0 until type.size).joinToString {
                                val o = formatObj(
                                    TreeArrayIndex(
                                        obj.line,
                                        obj.toStorage(),
                                        TreeInt(obj.line, it, DTI64),
                                        type.of
                                    ), builder
                                )
                                resArgs += o.second
                                o.first
                            }
                        }]"
                    )
                }
                type is DTStruct -> {
                    sb.append(
                        "${type.name} { ${run {
                            var i = 0
                            type.vars.joinToString {
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
                type is DTPointer && type.to is DTStruct -> {
                    val stringFunc = type.to.statics.find {
                        it.type is DTFunc && it.name.str() == "_string" && it.type.args.size == 1 && it.type.returns == DTPointerArray(DTI8, true)
                    }?:illegal("Cannot print objects of type '$type' because they don't implement the 'const _string(): const i8[]' function", obj.line)

                    sb.append("%s")
                    resArgs += builder.callFunc(FuncSignature(TPtr, TPtr), VGlobal(stringFunc.irName, TPtr), obj.ir(builder))!!
                }
                else -> illegal("Cannot print objects of type '$type'", obj.line)
            }
            return sb.toString() to resArgs
        }
    }
}
class ASTStructDef(line: Line, val name: Line, val vars: List<Triple<Line, Pair<Boolean, Line?>, ASTObject?>>, val funcs: List<ASTFuncDef>) : ASTGlobalElement(line) {
    lateinit var tree: TreeStructDef
    lateinit var struct: DTStruct
    override fun tree(): TreeGlobalElement {
        struct = DTStruct(line.file.pckg, name)
        tree = TreeStructDef(line)
        line.file.pckg.structs += struct
        funcs.forEach {
            it.inStruct = struct
        }
        return tree
    }

    override fun typesDone() {
        super.typesDone()
        vars.forEach {

            struct.vars.find { v -> v.first.str() == it.first.str() }?.let { v ->
                illegalExists("Property", it.first, v.first)
            }

            val t: Type
            val o: TreeObject?
            if(it.second.second == null) {
                o = it.third!!.obj()
                t = o.type
            } else {
                t = Type[it.second.second!!]
                o = it.third?.obj(t)
            }

            if(!t.copyable) illegal("Properties cannot be of unique type", it.second.second!!)
            struct.vars += Triple(it.first, it.second.first to t, o)
        }
        struct.complete = true
    }
}
class ASTTypeAlias(line: Line, val name: Line, val type: Line) : ASTGlobalElement(line) {
    override fun tree(): TreeGlobalElement {
        val t = Type[type]
        line.file.pckg.aliases += name.str() to t
        return TreeStructDef(line)
    }
}

abstract class ASTElement(val line: Line) {
    abstract fun tree(): TreeElement
}
class ASTReturn(line: Line, val obj: ASTObject?) : ASTElement(line) {
    override fun tree() = TreeReturn(line, obj?.obj(openFuncDef.returns))
}
class ASTFuncCall(line: Line, val func: ASTObject, val args: List<ASTObject>) : ASTElement(line) {
    override fun tree(): TreeElement {
        val o = func.obj()
        if(o.type !is DTFunc) illegal("Expected function pointer but found '${o.type}'", o.line)

        val memberOff = if(o is TreeStaticMember) 1 else 0

        if(args.size < o.type.args.size - memberOff || (args.size != o.type.args.size - memberOff && !o.type.isVararg))
            illegal("Expected ${o.type.args.size - memberOff} argument(s) but found ${args.size}", line)

        val args = args.mapIndexed { i, it ->
            val o = it.obj(if(i + memberOff < o.type.args.size) o.type.args[i + memberOff] else null)
            if(o is TreeLocalVarUse) {
                if(o.v.owner == true) o.v.move(o.line)
            }
            o
        }.toMutableList()
        if(o is TreeStaticMember) {
            if(!o.type.args[0].constant && o.obj.type.constant) illegal("Cannot call constant function on non-constant object", o.line)
            args.add(0, o.obj)
        }

        return TreeFuncCall(line, o, args)
    }
}
class ASTVarDef(line: Line, val name: Line, val type: Line?, val obj: ASTObject, val constant: Boolean) : ASTElement(line) {
    override fun tree(): TreeElement {
        blocks.peek().vars.find { it.name.str() == name.str() }?.let {
            illegalExists("Variable", name, it.name)
        }
        val t: Type
        val o: TreeObject?
        if(type == null) {
            o = obj.obj()
            t = o.type
        } else {
            t = Type[type]
            o = obj.obj(t)
        }
        val v = DLocal(name, t, constant)
        if(o is TreeLocalVarUse) {
            if(o.v.owner == true) o.v.move(o.line)
        }
        if(!t.copyable) v.setOwner()
        blocks.peek() += v
        return TreeVarDef(line, v, o)
    }
}
class ASTSet(line: Line, val strg: ASTStorage, val obj: ASTObject) : ASTElement(line) {
    override fun tree(): TreeElement {
        val s = strg.tree()
        if(s.constant) illegal("Cannot modify constant", strg.line)
        val o = obj.obj(s.type)
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
    override fun tree(): TreeElement {
        val f = TreeScope(line)
        blocks += f.block
        elements.forEach {
            f.block += it.tree()
        }
        blocks.pop()
        return f
    }
}
class ASTMacroCall(line: Line, val macro: Line, val args: List<ASTObject>) : ASTElement(line) {
    override fun tree(): TreeElement {
        val macro = getMacro(macro)
        if(args.size < macro.args.size || (args.size != macro.args.size && !macro.isVararg))
            illegal("Expected ${macro.args.size} argument(s) but found ${args.size}", line)

        val args = args.mapIndexed { i, it ->
            val o = it.obj(if(i < macro.args.size) macro.args[i].first else null)
            if(i < macro.args.size && macro.args[i].second && o.constant == null) illegal("Expected constant", o.line)
            if(o is TreeLocalVarUse) {
                if(o.v.owner == true) o.v.move(o.line)
            }
            o
        }

        return TreeMacroCall(line, macro, args)
    }
}


abstract class ASTObject(val line: Line) {
    fun obj(type: Type? = null): TreeObject {
        val o = _treeObj(type)
        if(type != null && type != o.type) {
            if(o.type is DTOwnerPointer && type is DTSharedPointer && o.type.to == type.to) return TreeCast(o.line, o, type)
            if(o.type is DTSharedPointer && !o.type.constant && type is DTSharedPointer && type.constant && o.type.to == type.to) return TreeCast(o.line, o, type)
            if(o.type is DTPointer && o.type.to is DTSizedArray && type is DTPointerArray && (if(!type.constant) !o.type.constant else true)) return TreeCast(o.line, o, type)
            illegal("Expected '$type' but found '${o.type}'", line)
        }
        return o
    }
    abstract fun _treeObj(type: Type?): TreeObject
}
class ASTInt(line: Line, val value: Long) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        if(type !is DTInt?) illegal("Expected '$type' but found 'i32'", line)
        return TreeInt(line, value, type?:DTI32)
    }
}
class ASTVarUse(line: Line) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
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
    override fun _treeObj(type: Type?): TreeObject {
        val o = func.obj()
        if(o.type !is DTFunc) illegal("Expected function pointer but found '${o.type}'", o.line)

        if(o.type.returns == DTVoid) illegal("Illegal object type 'void'", line)

        if(args.size < o.type.args.size || (args.size != o.type.args.size && !o.type.isVararg))
            illegal("Expected ${o.type.args.size} arguments but found ${args.size}", line)

        val args = args.mapIndexed { i, it ->
            val o = it.obj(if(i < o.type.args.size) o.type.args[i] else null)
            if(o is TreeLocalVarUse) {
                if(o.v.owner == true) o.v.move(o.line)
            }
            o
        }

        return TreeFuncCallObj(line, o, args)
    }
}
class ASTString(line: Line, val value: String) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        return TreeString(line, value)
    }
}
class ASTSharedPtr(line: Line, val strg: ASTStorage, val constant: Boolean) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        val s = strg.tree()
        return TreeSharedPtr(line, s, constant || s.constant)
    }
}
class ASTDereference(line: Line, val pointer: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        val p = pointer.obj()
        if(p.type !is DTPointer) illegal("Expected pointer but found '${p.type}'", p.line)
        if(!p.type.to.copyable) illegal("Cannot dereference pointer to unique object", line)
        return TreeDereference(line, p)
    }
}
class ASTArrayIndex(line: Line, val array: ASTStorage, val index: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        val o = array.tree()

        val i = index.obj(DTI64)

        return when(o.type) {
            is DTArray -> TreeArrayIndex(line, o, i, o.type.of)
            is DTPointer -> {
                if(o.type.to !is DTArray) illegal("Expected pointer to array but found '${o.type}'", o.line)
                TreeArrayIndex(line, TreeSPtr(o.line, parseObject(o.line).obj(o.type)), i, o.type.to.of)
            }
            else -> illegal("Expected array but found '${o.type}'", o.line)
        }
    }
}
class ASTStructInit(line: Line, val struct: Line, val values: List<Pair<Line, ASTObject>>) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {

        val new = struct.startsWith("new ")

        val t = if(new) {
            Type[struct.substringAfter(' ')]
        } else Type[struct]

        if(t !is DTStruct) illegal("Expected struct type", struct)
        if(!t.complete) illegal("Cannot initialize incomplete type", line)

        val v = mutableMapOf<Int, Pair<Line, TreeObject>>()

        values.forEach {
            val i = t.vars.indexOfFirst { v -> v.first.str() == it.first.str() }
            if(i == -1) illegal("Undefined property", it.first)
            if(v.containsKey(i)) illegalExists("Property", it.first, v[i]!!.first)
            v[i] = it.first to it.second.obj(t.vars[i].second.second)
        }
        val properties = t.vars.mapIndexed { i, p ->
            v[i]?.second?:p.third?:illegal("Property '${p.first}' has to be initialized", line)
        }
        return if(new) TreeNewStruct(line, t, properties)
        else TreeStructInit(line, t, properties)
    }
}
class ASTArrayInit(line: Line, val type: Line, val values: List<ASTObject>) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        if(this.type.startsWith("new ")) {
            val t = Type[this.type.substringAfter(' ')]
            val v = values.map { it._treeObj(t) }
            return TreeNewArray(line, t, v)
        }
        val t = Type[this.type]
        val v = values.map { it._treeObj(t) }
        return TreeArrayInit(line, t, v)
    }
}
class ASTArrayFillInit(line: Line, val type: Line, val size: ASTObject, val with: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        val size = ((size.obj(DTI32).constant as Long?)?:illegal("Expected constant", this.size.line)).toInt()

        if(this.type.startsWith("new ")) {
            val t = Type[this.type.substringAfter(' ')]
            val v = with.obj(t)
            return TreeNewArrayFill(line, t, size, v)
        }
        val t = Type[this.type]
        val v = with.obj(t)
        return TreeArrayFillInit(line, t, size, v)
    }
}
class ASTMember(line: Line, val obj: ASTObject, val member: Line) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        val o = obj.obj()
        return when (o.type) {
            is DTPointer -> {
                if(o.type.to !is DTStruct) illegal("Expected pointer to struct but found '${o.type}'", o.line)
                val index = o.type.to.vars.indexOfFirst { it.first.str() == member.str() }
                if(index == -1) {
                    val static = o.type.to.statics.find { it.name.str() == member.str() }?:illegal("Member doesn't exist", member)
                    TreeStaticMember(line, static, o)
                }
                else TreePtrMember(line, o, index, o.type.to)
            }
            is DTStruct -> {
                val index = o.type.vars.indexOfFirst { it.first.str() == member.str() }
                if(index == -1) {
                    val static = o.type.statics.find { it.name.str() == member.str() }?:illegal("Member doesn't exist", member)
                    val strg = parseStorage(o.line).tree()
                    TreeStaticMember(line, static, TreeSharedPtr(o.line, strg, strg.constant))
                }
                else TreeMember(line, o, index)
            }
            else -> illegal("Expected struct but found '${o.type}'", o.line)
        }
    }
}
class ASTNew(line: Line, val type: Line, val value: ASTObject) : ASTObject(line) {
    override fun _treeObj(type: Type?): TreeObject {
        val t = Type[this.type]
        val v = value.obj(t)
        return TreeNew(line, t, v)
    }
}


abstract class ASTStorage(val line: Line) {
    abstract fun tree(): TreeStorage
}
class ASTSVarUse(line: Line) : ASTStorage(line) {
    override fun tree(): TreeStorage {
        return when (val v = getVar(line)) {
            is DLocal -> TreeSLocalVarUse(line, v)
            else -> TODO()
        }
    }
}
class ASTSPtr(line: Line, val pointer: ASTObject) : ASTStorage(line) {
    override fun tree(): TreeStorage {
        val p = pointer.obj()
        if(p.type !is DTPointer) illegal("Expected pointer but found '${p.type}'", p.line)
        return TreeSPtr(line, p)
    }
}
class ASTSArrayIndex(line: Line, val array: ASTObject, val index: ASTObject) : ASTStorage(line) {
    override fun tree(): TreeStorage {
        val o = array.obj()

        val i = index.obj(DTI64)

        return when(o.type) {
            is DTPointerArray -> TreeSArrayIndex(line, o, i)
            is DTSizedArray -> {
                val strg = parseStorage(o.line).tree()
                TreeSPointerIndex(line, TreeSharedPtr(o.line, strg, strg.constant), i)
            }
            is DTPointer -> {
                if(o.type.to !is DTPointerArray) illegal("Expected pointer to array but found '${o.type}'", o.line)
                TreeSPointerIndex(line, o, i)
            }
            else -> illegal("Expected array but found '${o.type}'", o.line)
        }
    }
}
class ASTSMember(line: Line, val obj: ASTObject, val member: Line) : ASTStorage(line) {
    override fun tree(): TreeStorage {
        val o = obj.obj()
        o.type as DTSharedPointer
        if(o.type.to is DTPointer) {
            val struct = o.type.to.to
            if (struct !is DTStruct) illegal("Expected pointer to struct but found '${o.type.to}'", o.line)

            val index = struct.vars.indexOfFirst { it.first.str() == member.str() }
            if (index == -1) illegal("Member doesn't exist", member)
            return TreeSPtrMember(line, o, index, struct)
        }
        else {
            val struct = (o.type as DTPointer).to
            if (struct !is DTStruct) illegal("Expected struct but found '$struct'", o.line)

            val index = struct.vars.indexOfFirst { it.first.str() == member.str() }
            if (index == -1) illegal("Member doesn't exist", member)
            return TreeSMember(line, o, index, struct)
        }
    }
}