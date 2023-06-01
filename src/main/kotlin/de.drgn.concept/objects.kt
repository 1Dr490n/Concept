package de.drgn.concept

import de.drgn.irbuilder.IRBuilder
import de.drgn.irbuilder.types.TPtr
import de.drgn.irbuilder.values.VLocal
import java.util.Stack

class Block {
    val elements = mutableListOf<TreeElement>()
    val vars = mutableListOf<DLocal>()
    operator fun plusAssign(e: TreeElement) {
        elements += e
    }
    operator fun plusAssign(e: DLocal) {
        vars += e
    }
}
val blocks = Stack<Block>()

abstract class DVar(val name: Line, val type: AliasedType, val constant: Boolean) {
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
class DGlobal(val pckg: DPackage, name: Line, type: AliasedType, constant: Boolean, irName: String? = null) : DVar(name, type, constant) {
    val irName = irName?:"\"$pckg::$name\""
}
class DLocal(name: Line, type: AliasedType, constant: Boolean) : DVar(name, type, constant) {
    lateinit var vLocal: VLocal
    fun free(builder: IRBuilder.FunctionBuilder) {
        if(owner == true && type.type is DTOwnerPointer) {
            builder.callFunc(free.first, free.second, builder.load(TPtr, vLocal))
        }
    }
}
fun getVar(name: Line): DVar {
    if("::" in name.str()) {
        val pckg = DPackage[name.substringBefore("::").str()]?: illegal("Package doesn't exist", name.substringBefore("::"))
        val v = pckg.globals.find { name.substringAfter("::").str() == it.name.str() }?: illegal("Variable doesn't exist", name)
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