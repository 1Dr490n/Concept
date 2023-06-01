package de.drgn.concept

import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.TInt
import de.drgn.irbuilder.types.TPtr
import de.drgn.irbuilder.types.tI32
import de.drgn.irbuilder.types.tI64
import de.drgn.irbuilder.values.*

val tree = mutableListOf<TreeGlobalElement>()

abstract class TreeGlobalElement(val line: Line) {
    abstract fun ir()
}
lateinit var openFuncDef: TreeFuncDef
class TreeFuncDef(
    line: Line,
    val name: Line,
    val isVararg: Boolean,
) : TreeGlobalElement(line) {
    val block = Block()
    lateinit var returnValue: VLocal
    lateinit var global: DGlobal
    lateinit var returns: AliasedType
    lateinit var args: List<Pair<Line, AliasedType>>
    var inStruct: DTStruct? = null

    val argVars = mutableListOf<DLocal>()

    override fun ir() {
        openFuncDef = this
        IRBuilder.func(global.irName, returns.ir(), *args.map { it.second.ir() }.toTypedArray(), isVararg = isVararg) {
            if(returns != DTVoid)
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
            label(".ret")
            if(returns != DTVoid)
                load(returns.ir(), returnValue)
            else null
        }
    }
}
class TreeMacroDec(
    line: Line,
    val name: Line,
    val isVararg: Boolean,
    val macro: (line: Line, args: List<TreeObject>, builder: IRBuilder.FunctionBuilder) -> TreeObject?
) : TreeGlobalElement(line) {
    lateinit var returns: AliasedType
    lateinit var args: List<Pair<AliasedType, Boolean>>

    override fun ir() {}
}
class TreeStructDef(
    line: Line
) : TreeGlobalElement(line) {
    override fun ir() {}
}

abstract class TreeElement(val line: Line) {
    abstract fun ir(builder: IRBuilder.FunctionBuilder)
}
class TreeReturn(line: Line, val obj: TreeObject?) : TreeElement(line) {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        if(obj != null) {
            builder.store(openFuncDef.returnValue, obj.ir(builder))
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
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        builder.callFunc((funcPtr.type as DTFunc).signature, funcPtr.ir(builder), *args.map { it.ir(builder) }.toTypedArray())
    }
}
class TreeVarDef(line: Line, val v: DLocal, val obj: TreeObject) : TreeElement(line) {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        v.vLocal = builder.alloca(v.type.ir())
        builder.store(v.vLocal, obj.ir(builder))
    }
}
class TreeSet(line: Line, val strg: TreeStorage, val obj: TreeObject) : TreeElement(line) {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        builder.store(strg.ir(builder), obj.ir(builder))
    }
}
class TreeScope(line: Line) : TreeElement(line) {
    val block = Block()
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        block.elements.forEach {
            it.ir(builder)
        }
        block.vars.forEach {
            it.free(builder)
        }
    }
}
class TreeMacroCall(line: Line, val macro: TreeMacroDec, val args: List<TreeObject>) : TreeElement(line) {
    override fun ir(builder: IRBuilder.FunctionBuilder) {
        macro.macro(line, args, builder)
    }
}


abstract class TreeObject(val line: Line, val type: AliasedType, val constant: Any? = null) {
    abstract fun ir(builder: IRBuilder.FunctionBuilder): VValue

    fun toStorage() = parseStorage(line).tree()
}
class TreeInt(line: Line, val value: Long, type: DTInt) : TreeObject(line, type, value) {
    override fun ir(builder: IRBuilder.FunctionBuilder) = VInt(type.ir() as TInt, value)
}
class TreeGlobalVarUse(line: Line, val v: DGlobal) : TreeObject(line, v.type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return VGlobal(v.irName, v.type.ir())
    }
}
class TreeLocalVarUse(line: Line, val v: DLocal) : TreeObject(line, v.type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.load(v.type.ir(),  v.vLocal)
    }
}
class TreeFuncCallObj(line: Line, val funcPtr: TreeObject, val args: List<TreeObject>) : TreeObject(line, (funcPtr.type as DTFunc).returns) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.callFunc((funcPtr.type as DTFunc).signature, funcPtr.ir(builder), *args.map { it.ir(builder) }.toTypedArray())!!
    }
}
class TreeString(line: Line, val value: String) : TreeObject(line, DTPointerArray(DTI8, true), value) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return VString(value)
    }
}
class TreeSharedPtr(line: Line, val strg: TreeStorage, constant: Boolean) : TreeObject(line, DTSharedPointer(strg.type, constant)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return strg.ir(builder)
    }
}
class TreeNew(line: Line, val objType: AliasedType, val value: TreeObject) : TreeObject(line, DTOwnerPointer(objType)) {
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
class TreeDereference(line: Line, val pointer: TreeObject) : TreeObject(line, (pointer.type as DTPointer).to) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.load((pointer.type as DTPointer).to.ir(), pointer.ir(builder))
    }
}
class TreeArrayIndex(line: Line, val array: TreeStorage, val index: TreeObject, type: AliasedType) : TreeObject(line, type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val t = type.ir()
        return builder.load(t, builder.elementPtr(t, if(array.type is DTSizedArray) array.ir(builder) else builder.load(TPtr, array.ir(builder)), index.ir(builder)))
    }
}
class TreeOwnerIndex(line: Line, val pointer: TreeObject, val index: TreeObject) : TreeObject(line, ((pointer.type as DTOwnerPointer).to as DTPointerArray).of) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val t = type.ir()
        return builder.load(t, builder.elementPtr(t, pointer.ir(builder), index.ir(builder)))
    }
}
class TreeCast(line: Line, val obj: TreeObject, type: AliasedType) : TreeObject(line, type) {
    override fun ir(builder: IRBuilder.FunctionBuilder) = obj.ir(builder)
}
class TreeStructInit(line: Line, type: DTStruct, val values: List<TreeObject>) : TreeObject(line, type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        var s: VValue = VUndef(type.ir())
        values.forEachIndexed { i, it ->
            s = builder.insertValue(s, it.ir(builder), i)
        }
        return s
    }
}
class TreeArrayInit(line: Line, val arrayType: AliasedType, val values: List<TreeObject>) : TreeObject(line, DTSizedArray(arrayType, values.size.toLong(), false)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        var s: VValue = VUndef(type.ir())
        values.forEachIndexed { i, it ->
            s = builder.insertValue(s, it.ir(builder), i)
        }
        return s
    }
}
class TreeArrayFillInit(line: Line, val arrayType: AliasedType, val size: Int, val obj: TreeObject) : TreeObject(line, DTSizedArray(arrayType, size.toLong(), false)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        var s: VValue = VUndef(type.ir())
        for(i in 0 until size) {
            s = builder.insertValue(s, obj.ir(builder), i)
        }
        return s
    }
}
class TreeNewArray(line: Line, val arrayType: AliasedType, val values: List<TreeObject>) : TreeObject(line, DTOwnerPointer(DTSizedArray(arrayType, values.size.toLong(), false))) {
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
class TreeNewArrayFill(line: Line, val arrayType: AliasedType, val size: Int, val obj: TreeObject) : TreeObject(line, DTOwnerPointer(DTSizedArray(arrayType, size.toLong(), false))) {
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
class TreeNewStruct(line: Line, val objType: DTStruct, val values: List<TreeObject>) : TreeObject(line, DTOwnerPointer(objType)) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        val l = builder.callFunc(
            malloc.first,
            malloc.second,
            VInt(tI64, objType.size())
        )!!
        values.forEachIndexed { i, it ->
            builder.store(builder.elementPtr(objType.ir(), l, VInt(tI64, i.toLong())), it.ir(builder))
        }
        return l
    }
}
class TreeMember(line: Line, val obj: TreeObject, val index: Int) : TreeObject(line, (obj.type as DTStruct).vars[index].second.second) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.extractValue(obj.ir(builder), index)
    }
}
class TreeStaticMember(line: Line, val v: DGlobal, val obj: TreeObject) : TreeObject(line, v.type) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return VGlobal(v.irName, v.type.ir())
    }
}
class TreePtrMember(line: Line, val obj: TreeObject, val index: Int, val struct: DTStruct) : TreeObject(line, struct.vars[index].second.second) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.load(type.ir(), builder.elementPtr(struct.ir(), obj.ir(builder), VInt(tI32, 0), VInt(tI32, index.toLong())))
    }
}


abstract class TreeStorage(val line: Line, val type: AliasedType, val constant: Boolean) {
    abstract fun ir(builder: IRBuilder.FunctionBuilder): VValue
}
class TreeSLocalVarUse(line: Line, val v: DLocal) : TreeStorage(line, v.type, v.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return v.vLocal
    }
}
class TreeSPtr(line: Line, val pointer: TreeObject) : TreeStorage(line, (pointer.type as DTPointer).to, pointer.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return pointer.ir(builder)
    }
}
class TreeSArrayIndex(line: Line, val array: TreeObject, val index: TreeObject) : TreeStorage(line, (array.type as DTArray).of, array.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(type.ir(), array.ir(builder), index.ir(builder))
    }
}
class TreeSPointerIndex(line: Line, val pointer: TreeObject, val index: TreeObject) : TreeStorage(line, ((pointer.type as DTPointer).to as DTArray).of, pointer.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(type.ir(), pointer.ir(builder), index.ir(builder))
    }
}
class TreeSMember(line: Line, val obj: TreeObject, val index: Int, val struct: DTStruct) : TreeStorage(line, struct.vars[index].second.second, struct.vars[index].second.first || obj.type.constant) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(((obj.type as DTSharedPointer).to as DTStruct).ir(), obj.ir(builder), VInt(tI32, 0), VInt(tI32, index.toLong()))
    }
}
class TreeSPtrMember(line: Line, val obj: TreeObject, val index: Int, val struct: DTStruct)
    : TreeStorage(line, struct.vars[index].second.second, (obj.type as DTSharedPointer).to.constant || struct.vars[index].second.first) {
    override fun ir(builder: IRBuilder.FunctionBuilder): VValue {
        return builder.elementPtr(struct.ir(), builder.load(TPtr, obj.ir(builder)), VInt(tI32, 0), VInt(tI32, index.toLong()))
    }
}