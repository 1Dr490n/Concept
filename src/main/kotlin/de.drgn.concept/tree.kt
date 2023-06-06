package de.drgn.concept

import de.drgn.concept.ASTOperatorCalc.Operation.*
import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.*
import de.drgn.irbuilder.values.*
import java.util.Stack

val tree = mutableListOf<TreeGlobalElement>()

abstract class TreeGlobalElement(val line: Line) {
    abstract fun ir()
}
val openFuncDefs = Stack<TreeFuncDef>()
class TreeFuncDef(
    line: Line,
    val name: Line,
    val isVararg: Boolean,
) : TreeGlobalElement(line) {
    val block = Block()
    lateinit var returnValue: VLocal
    lateinit var global: DGlobal
    lateinit var returns: AliasedType<*>
    lateinit var args: List<Pair<Line, AliasedType<*>>>
    var inStruct: DTStruct? = null

    val argVars = mutableListOf<DLocal>()

    override fun ir() {
        openFuncDefs += this
        IRBuilder.func(global.irName, returns.ir(), *args.map { it.second.ir() }.toTypedArray(), isVararg = isVararg) {
            if(returns.type != DTVoid)
                returnValue = alloca(returns.ir())

            argVars.forEachIndexed { i, it ->
                it.vLocal = alloca(it.type.ir())
                store(it.vLocal, args[i])
            }
            blocks += block
            block.elements.forEach {
                it.ir(this)
            }
            blocks.pop()
            branch(".ret")
            label(".ret")
            ret(if(returns.type != DTVoid)
                load(returns.ir(), returnValue)
            else null)
        }
        openFuncDefs.pop()
    }
}
class TreeFuncDec(
    line: Line,
    val isVararg: Boolean
) : TreeGlobalElement(line) {
    lateinit var global: DGlobal
    lateinit var returns: AliasedType<*>
    lateinit var args: List<AliasedType<*>>
    var inStruct: DTStruct? = null

    override fun ir() {
        IRBuilder.declareFunc(global.irName, returns.ir(), *args.map { it.ir() }.toTypedArray(), isVararg = isVararg)
    }
}
class TreeMacroDec(
    line: Line,
    val name: Line,
    val isVararg: Boolean,
    val macro: (line: Line, args: List<TreeObject>, builder: IRBuilder.FunctionBuilder) -> VValue?
) : TreeGlobalElement(line) {
    lateinit var returns: AliasedType<*>
    lateinit var args: List<Pair<AliasedType<*>, Boolean>>

    override fun ir() {}
}
class TreeGlobalDef(line: Line) : TreeGlobalElement(line) {
    lateinit var global: DGlobal
    lateinit var obj: TreeConstant
    override fun ir() {
        IRBuilder.global(global.irName, obj.ir())
    }
}


abstract class TreeElement(val line: Line) {
    var unreachable = false
    abstract fun _ir(builder: IRBuilder.FunctionBuilder)
    fun ir(builder: IRBuilder.FunctionBuilder) {
        if(!unreachable) _ir(builder)
    }
}
class TreeReturn(line: Line, val obj: TreeObject?) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        if(obj != null) {
            builder.store(openFuncDefs.peek().returnValue, obj.ir(builder))
            if(obj is TreeLocalVarUse) obj.v.move(obj.line)
        }
        blocks.forEach {
            it.vars.forEach {
                it.free(builder)
            }
        }
        builder.branch(".ret")
    }
}
class TreeFuncCall(line: Line, val funcPtr: TreeObject, val args: List<TreeObject>) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        builder.callFunc((funcPtr.type.type as DTFunc).signature, funcPtr.ir(builder), *args.map { it.ir(builder) }.toTypedArray())
    }
}
class TreeVarDef(line: Line, val v: DLocal, val obj: TreeObject) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        v.vLocal = builder.alloca(v.type.ir())
        builder.store(v.vLocal, obj.ir(builder))
    }
}
class TreeSet(line: Line, val strg: TreeStorage, val obj: TreeObject) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        builder.store(strg.ir(builder), obj.ir(builder))
    }
}
class TreeScope(line: Line) : TreeElement(line) {
    val block = Block()
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        blocks += block
        block.elements.forEach {
            it.ir(builder)
        }
        blocks.pop()
        block.vars.forEach {
            it.free(builder)
        }
    }
}
class TreeMacroCall(line: Line, val macro: TreeMacroDec, val args: List<TreeObject>) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        macro.macro(line, args, builder)
    }
}
class TreeIf(line: Line, val ifs: List<TreeElseIf>, val elseE: TreeElse?) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        val e = builder.register++
        ifs.forEachIndexed { i, it ->
            val y = builder.register++
            val n = if(i + 1 == ifs.size && elseE == null) e else builder.register++
            builder.branch(it.condition.ir(builder), ".$y", ".$n")
            builder.label(".$y")
            blocks += it.block
            it.block.elements.forEach { it.ir(builder) }
            blocks.pop()
            it.block.vars.forEach {
                it.free(builder)
            }
            builder.branch(".$e")
            builder.label(".$n")
        }
        if(elseE != null) {
            blocks += elseE.block
            elseE.block.elements.forEach { it.ir(builder) }
            blocks.pop()
            elseE.block.vars.forEach {
                it.free(builder)
            }
            builder.branch(".$e")
            builder.label(".$e")
        }
    }
}
class TreeElseIf(val condition: TreeObject) {
    val block = Block()
}
class TreeElse {
    val block = Block()
}
abstract class TreeLoop(line: Line, val label: Line?) : TreeElement(line) {
    var end = 0
    var gotoContinue = 0
    abstract var infinite: Boolean
    val block = Block()
}
val loops = Stack<TreeLoop>()
class TreeWhile(line: Line, val condition: TreeObject, label: Line?) : TreeLoop(line, label) {
    override var infinite = condition.constant == true
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        val start = builder.register++
        gotoContinue = builder.register++
        end = builder.register++
        builder.branch(".$gotoContinue")
        builder.label(".$start")
        blocks += block
        block.elements.forEach {
            it.ir(builder)
        }
        blocks.pop()
        block.vars.forEach {
            it.free(builder)
        }
        builder.branch(".$gotoContinue")
        builder.label(".$gotoContinue")
        builder.branch(this.condition.ir(builder), ".$start", ".$end")
        builder.label(".$end")
    }
}
class TreeFor(line: Line, val before: TreeElement?, val condition: TreeObject, val after: TreeElement?, label: Line?) : TreeLoop(line, label) {
    override var infinite = condition.constant == true
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        val start = builder.register++
        val condition = builder.register++
        gotoContinue = builder.register++
        end = builder.register++
        before?.ir(builder)
        builder.branch(".$condition")
        builder.label(".$condition")
        builder.branch(this.condition.ir(builder), ".$start", ".$end")
        builder.label(".$start")
        blocks += block
        block.elements.forEach {
            it.ir(builder)
        }
        blocks.pop()
        block.vars.forEach {
            it.free(builder)
        }
        builder.branch(".$gotoContinue")
        builder.label(".$gotoContinue")
        after?.ir(builder)
        builder.branch(".$condition")
        builder.label(".$end")
        if(before is TreeVarDef && before.v.owner == true) before.v.free(builder)
    }
}
class TreeBreak(line: Line, val loop: TreeLoop, val blockIndex: Int) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        for(i in blockIndex until blocks.size) {
            blocks[i].vars.forEach {
                it.free(builder)
            }
        }
        builder.branch(".${loop.end}")
    }
}
class TreeContinue(line: Line, val loop: TreeLoop, val blockIndex: Int) : TreeElement(line) {
    override fun _ir(builder: IRBuilder.FunctionBuilder) {
        for(i in blockIndex until blocks.size) {
            blocks[i].vars.forEach {
                it.free(builder)
            }
        }
        builder.branch(".${loop.gotoContinue}")
    }
}




abstract class TreeObject(val line: Line, val type: AliasedType<*>, val constant: Any? = null) {
    constructor(line: Line, type: Type, constant: Any? = null) : this(line, AliasedType(type), constant)
    abstract fun ir(builder: IRBuilder.FunctionBuilder): VValue

    fun toStorage() = parseStorage(line).tree(null)
}
abstract class TreeConstant(line: Line, type: AliasedType<*>, constant: Any) : TreeObject(line, type, constant) {
    constructor(line: Line, type: Type, constant: Any) : this(line, AliasedType(type), constant)
    abstract fun ir(): VValue
    override fun ir(builder: IRBuilder.FunctionBuilder) = ir()
}
class TreeInt(line: Line, val value: Long, type: AliasedType<out DTInt>) : TreeConstant(line, type, value) {
    override fun ir() = VInt(type.ir() as TInt, value)
}
class TreeBool(line: Line, val value: Boolean) : TreeConstant(line, DTBool, value) {
    override fun ir() = VInt(tI1, if(value) 1 else 0)
}
class TreeGlobalVarUse(line: Line, val global: DGlobal) : TreeObject(line, global.type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val v = VGlobal(global.irName)
        return if(global.function) v else builder.load(global.type.ir(), v)
    }
}
class TreeLocalVarUse(line: Line, val v: DLocal) : TreeObject(line, v.type, v.constValue) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.load(v.type.ir(),  v.vLocal)
    }
}
class TreeFuncCallObj(line: Line, val funcPtr: TreeObject, val args: List<TreeObject>) : TreeObject(line, (funcPtr.type.type as DTFunc).returns) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.callFunc((funcPtr.type.type as DTFunc).signature, funcPtr.ir(builder), *args.map { it.ir(builder) }.toTypedArray())!!
    }
}
class TreeString(line: Line, val value: String) : TreeObject(line, DTArray(AliasedType(DTI8), true), value) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return VString(value)
    }
}
class TreeSharedPtr(line: Line, val strg: TreeStorage, constant: Boolean) : TreeObject(line, DTSharedPointer(strg.type, constant)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return strg.ir(builder)
    }
}
class TreeNew(line: Line, val objType: AliasedType<*>, val value: TreeObject) : TreeObject(line, DTOwnerPointer(objType)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val o = builder.callFunc(
            malloc.first,
            malloc.second,
            VInt(tI64, objType.size())
        )!!
        builder.store(o, value.ir(builder))
        return o
    }
}
class TreeDereference(line: Line, val pointer: TreeObject) : TreeObject(line, (pointer.type.type as DTPointer).to) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val t = (pointer.type.type as DTPointer).to.type
        if(t is DTSizedArray && t.size == null) illegal("Cannot dereference pointer to unsized array", pointer.line)
        return builder.load(t.ir(), pointer.ir(builder))
    }
}
class TreeArrayIndex(line: Line, val array: TreeStorage, val index: TreeObject, type: AliasedType<*>) : TreeObject(line, type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val t = type.ir()
        return builder.load(t, builder.elementPtr(t, if(array.type.type is DTSizedArray) array.ir(builder) else builder.load(TPtr, array.ir(builder)), index.ir(builder)))
    }
}
class TreeCast(line: Line, val obj: TreeObject, type: AliasedType<*>) : TreeObject(line, type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return when {
            obj.type.type is DTInt -> {
                if(obj.type.size() < type.size()) builder.sext(obj.ir(builder), type.ir())
                else builder.trunc(obj.ir(builder), type.ir())
            }
            else -> obj.ir(builder)
        }
    }
}
class TreeStructInit(line: Line, type: AliasedType<DTStruct>, val values: List<TreeObject>) : TreeObject(line, type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        var s: VValue = VUndef(type.ir())
        values.forEachIndexed { i, it ->
            s = builder.insertValue(s, it.ir(builder), i)
        }
        return s
    }
}
class TreeArrayInit(line: Line, val arrayType: AliasedType<*>, val values: List<TreeObject>) : TreeObject(line, DTSizedArray(arrayType, values.size.toLong(), false)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        var s: VValue = VUndef(type.ir())
        values.forEachIndexed { i, it ->
            s = builder.insertValue(s, it.ir(builder), i)
        }
        return s
    }
}
class TreeArrayFillInit(line: Line, val arrayType: AliasedType<*>, val size: Int, val obj: TreeObject) : TreeObject(line, DTSizedArray(arrayType, size.toLong(), false)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        var s: VValue = VUndef(type.ir())
        for(i in 0 until size) {
            s = builder.insertValue(s, obj.ir(builder), i)
        }
        return s
    }
}
class TreeNewArray(line: Line, val arrayType: AliasedType<*>, val values: List<TreeObject>) : TreeObject(line, DTOwnerPointer(AliasedType(DTSizedArray(arrayType, values.size.toLong(), false)))) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val l = builder.callFunc(
            malloc.first,
            malloc.second,
            VInt(tI64, values.size * arrayType.size())
        )!!
        values.forEachIndexed { i, it ->
            builder.store(builder.elementPtr(arrayType.ir(), l, VInt(tI64, i.toLong())), it.ir(builder))
        }
        return l
    }
}
class TreeNewArrayFill(line: Line, val arrayType: AliasedType<*>, val size: Int, val obj: TreeObject) : TreeObject(line, DTOwnerPointer(AliasedType(DTSizedArray(arrayType, size.toLong(), false)))) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val l = builder.callFunc(
            malloc.first,
            malloc.second,
            VInt(tI64, size * arrayType.size())
        )!!
        for(i in 0 until size)
            builder.store(builder.elementPtr(arrayType.ir(), l, VInt(tI64, i.toLong())), obj.ir(builder))
        return l
    }
}
class TreeNewStruct(line: Line, val objType: AliasedType<DTStruct>, val values: List<TreeObject>, constant: Boolean) : TreeObject(line, DTOwnerPointer(objType, constant)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val l = builder.callFunc(
            malloc.first,
            malloc.second,
            VInt(tI64, objType.size())
        )!!
        values.forEachIndexed { i, it ->
            builder.store(builder.elementPtr(objType.ir(), l, VInt(tI32, 0), VInt(tI32, i.toLong())), it.ir(builder))
        }
        return l
    }
}
class TreeMember(line: Line, val obj: TreeObject, val index: Int) : TreeObject(line, (obj.type.type as DTStruct).vars[index].second.second) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.extractValue(obj.ir(builder), index)
    }
}
class TreeStaticMember(line: Line, val v: DGlobal, val obj: TreeObject) : TreeObject(line, v.type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return VGlobal(v.irName)
    }
}
class TreePtrMember(line: Line, val obj: TreeObject, val index: Int, val struct: DTStruct) : TreeObject(line, struct.vars[index].second.second) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.load(type.ir(), builder.elementPtr(struct.ir(), obj.ir(builder), VInt(tI32, 0), VInt(tI32, index.toLong())))
    }
}
class TreeOperatorCalc(line: Line, val a: TreeObject, val b: TreeObject, val operation: ASTOperatorCalc.Operation) : TreeObject(line, a.type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val a = a.ir(builder)
        val b = b.ir(builder)
        return when(operation) {
            ADD -> builder.add(a, b)
            SUB -> builder.sub(a, b)
            MUL -> builder.multiply(a, b)
            DIV -> builder.divide(a, b, true)
            MOD -> builder.modulo(a, b, true)
        }
    }
}
class TreeOperatorCmp(line: Line, val a: TreeObject, val b: TreeObject, val operation: ASTOperatorCmp.Operation) : TreeObject(line, DTBool) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.icmp(operation.name.lowercase(), a.ir(builder), b.ir(builder))
    }
}
class TreeMacroCallObj(line: Line, val macro: TreeMacroDec, val args: List<TreeObject>) : TreeObject(line, macro.returns) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return macro.macro(line, args, builder)!!
    }
}



abstract class TreeStorage(val line: Line, val type: AliasedType<*>, val constant: Boolean) {
    abstract fun ir(builder: IRBuilder.FunctionBuilder): VValue
}
class TreeSLocalVarUse(line: Line, val v: DLocal) : TreeStorage(line, v.type, v.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return v.vLocal
    }
}
class TreeSGlobalVarUse(line: Line, val v: DGlobal) : TreeStorage(line, v.type, v.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return VGlobal(v.irName)
    }
}
class TreeSPtr(line: Line, val pointer: TreeObject) : TreeStorage(line, (pointer.type.type as DTPointer).to, pointer.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return pointer.ir(builder)
    }
}
class TreeSArrayIndex(line: Line, val array: TreeObject, val index: TreeObject) : TreeStorage(line, (array.type.type as DTAnyArray).of, array.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(type.ir(), array.ir(builder), index.ir(builder))
    }
}
class TreeSPointerIndex(line: Line, val pointer: TreeObject, val index: TreeObject) : TreeStorage(line, ((pointer.type.type as DTPointer).to.type as DTAnyArray).of, pointer.type.constant || pointer.type.type.to.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(type.ir(), pointer.ir(builder), index.ir(builder))
    }
}
class TreeSMember(line: Line, val obj: TreeObject, val index: Int, val struct: DTStruct) : TreeStorage(line, struct.vars[index].second.second, struct.vars[index].second.first || obj.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(((obj.type.type as DTSharedPointer).to.type as DTStruct).ir(), obj.ir(builder), VInt(tI32, 0), VInt(tI32, index.toLong()))
    }
}
class TreeSPtrMember(line: Line, val obj: TreeObject, val index: Int, val struct: DTStruct)
    : TreeStorage(line, struct.vars[index].second.second, (obj.type.type as DTSharedPointer).to.constant || struct.vars[index].second.first) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(struct.ir(), builder.load(TPtr, obj.ir(builder)), VInt(tI32, 0), VInt(tI32, index.toLong()))
    }
}