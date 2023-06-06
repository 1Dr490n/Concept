package de.drgn.concept

import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.TPtr
import de.drgn.irbuilder.values.VLocal
import java.util.Stack

class Block {
    val elements = mutableListOf<TreeElement>()
    val vars = mutableListOf<DLocal>()
    var returned = false
    var breakFrom: TreeLoop? = null
    val unreachableElements = mutableListOf<TreeElement>()
    operator fun plusAssign(e: TreeElement) {
        elements += e
    }
    operator fun plusAssign(e: DLocal) {
        vars += e
    }
    fun pop(unreachable: Boolean): Boolean {
        if(!unreachable && unreachableElements.isNotEmpty()) {
            val l = mutableListOf<C>()
            unreachableElements.forEach {
                l += it.line.l
            }
            unreachable(Line(l))
        }
        blocks.pop()
        return returned && !unreachable
    }
}
val blocks = Stack<Block>()

abstract class DVar(val name: Line, val type: AliasedType<*>, val constant: Boolean, val constValue: Any?) {
    var owner: Boolean? = null
        private set
    fun setOwner() {
        owner = true
    }
    fun move(movedAt: Line) {
        owner = false
        this.movedAt = movedAt
    }

    lateinit var movedAt: Line
}
class DGlobal(val pckg: DPackage, name: Line, type: AliasedType<*>, constant: Boolean, irName: String? = null, constValue: Any? = null, val function: Boolean = false, val global: Boolean = true) : DVar(name, type, constant, constValue) {
    val irName = irName?:"\"$pckg::$name\""
    init {
        if(global) {
            pckg.globals.find {
                it !== this && it.name.str() == name.str()
            }?.let {
                illegalExists("Variable", name, it.name)
            }
        }
    }
}
class DLocal(name: Line, type: AliasedType<*>, constant: Boolean, constValue: Any? = null) : DVar(name, type, constant, constValue) {
    lateinit var vLocal: VLocal
    fun free(builder: IRBuilder.FunctionBuilder) {
        if(owner == true && type.type is DTOwnerPointer) {
            builder.callFunc(free.first, free.second, builder.load(TPtr, vLocal))
        }
    }
}
fun getVar(name: Line): DVar {
    if("::" in name.str()) {
        val vars = DPackage[name.substringBeforeLast("::").str()]?.globals?:run {
            val t = Type[name.substringBeforeLast("::"), null]
            if(t.type !is DTStruct) illegal("Expected struct but found '$t'", name.substringBefore("::"))
            t.type.statics
        }
        val v = vars.find { name.substringAfterLast("::").str() == it.name.str() }?: illegal("Variable doesn't exist", name)
        return v
    }
    var i = blocks.size - 1
    while(i >= 0) {
        val v = blocks[i].vars.find { it.name.str() == name.str() }
        if(v != null) return v
        i--
    }

    name.file.pckg.globals.forEach { if(it.name.str() == name.str()) return it }

    name.file.imports.forEach {
        it.globals.forEach {
            if(it.name.str() == name.str()) return it
        }
    }

    illegal("Variable doesn't exist", name)
}
fun getMacro(name: Line): TreeMacroDec {
    if("::" in name.str()) {
        val pckg = DPackage[name.substringBefore("::").str()]?: illegal("Package doesn't exist", name.substringBefore("::"))
        val v = pckg.macros.find { name.substringAfter("::").str() == it.name.str() }?: illegal("Macro doesn't exist", name)
        return v
    }

    name.file.pckg.macros.forEach { if(it.name.str() == name.str()) return it }

    name.file.imports.forEach {
        it.macros.forEach {
            if(it.name.str() == name.str()) return it
        }
    }

    illegal("Macro doesn't exist", name)
}