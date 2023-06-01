package de.drgn.concept

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.types.*

data class AliasedType(val type: Type, val alias: String? = null) {
    constructor(pair: Pair<String, Type>) : this(pair.second, pair.first)

    override fun toString() = if(alias == null) type.toString() else "$alias (aka $type)"

    fun ir() = type.ir()

    fun size() = type.size()
}

abstract class Type(val copyable: Boolean, val constant: Boolean = false) {
    abstract fun ir(): TType
    abstract override fun toString(): String
    companion object {
        operator fun get(name: Line): AliasedType {
            when (name.str()) {
                "void" -> return AliasedType(DTVoid)
                "i8" -> return AliasedType(DTI8)
                "i16" -> return AliasedType(DTI16)
                "i32" -> return AliasedType(DTI32)
            }
            if (name[0].c == '&') return AliasedType(DTOwnerPointer(get(name.drop(1))))
            if(name.startsWith("const *")) return AliasedType(DTSharedPointer(get(name.substringAfter('*')), true))
            if (name[0].c == '*') return AliasedType(DTSharedPointer(get(name.drop(1)), false))

            name.beforeBrackets()?.let {
                when(it.second[0].c) {
                    '[' -> {
                        if(it.second.length == 2) {
                            if (it.first.startsWith("const "))
                                return AliasedType(DTPointerArray(get(it.first.substringAfter(' ')), true))
                            return AliasedType(DTPointerArray(get(it.first), false))
                        }
                        val sizeObj = parseObject(it.second.drop(1).dropLast(1)).obj(DTI64)
                        val size = (sizeObj.constant?:illegal("Expected constant", sizeObj.line)) as Long

                        if (it.first.startsWith("const "))
                            return AliasedType(DTSizedArray(get(it.first.substringAfter(' ')), size, true))
                        return AliasedType(DTSizedArray(get(it.first), size, false))
                    }
                    '(' -> if(it.first.isEmpty()) return Type[name.drop(1).dropLast(1)]
                    else -> {}
                }
            }

            if (name.startsWith("const ") && name.endsWith("[]")) return AliasedType(DTPointerArray(get(name.substringAfter(' ').dropLast(2)), true))
            if (name.endsWith("[]")) return AliasedType(DTPointerArray(get(name.dropLast(2)), false))

            if("::" in name.str()) {
                val pckg = DPackage[name.substringBefore("::").str()]?: illegal("Package doesn't exist", name.substringBefore("::"))
                val v = pckg.structs.find { name.substringAfter("::").str() == it.name.str() }
                    ?: return AliasedType((pckg.aliases.find { name.substringAfter("::").str() == it.first })
                        ?: illegal("Type doesn't exist", name))

                return AliasedType(v)
            }
            name.file.pckg.structs.forEach { if(it.name.str() == name.str()) return AliasedType(it) }
            name.file.pckg.aliases.forEach { if(it.first == name.str()) return AliasedType(it) }

            name.file.imports.forEach {
                it.structs.forEach {
                    if(it.name.str() == name.str()) return AliasedType(it)
                }
                it.aliases.forEach {
                    if(it.first == name.str()) return AliasedType(it)
                }
            }

            illegal("Expected type", name)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Type) return false
        return isType(other)
    }

    open fun isType(other: Type) = toString() == other.toString()

    abstract fun size(): Long
}
abstract class DTInt(val size: Int) : Type(true) {
    override fun toString() = "i$size"
    override fun size() = size / 8L
}
object DTI64 : DTInt(64) {
    override fun ir() = tI64
}
object DTI32 : DTInt(32) {
    override fun ir() = tI32
}
object DTI16 : DTInt(16) {
    override fun ir() = tI16
}
object DTI8 : DTInt(8) {
    override fun ir() = tI8
}
object DTVoid : Type(false) {
    override fun ir() = TVoid
    override fun toString() = "void"
    override fun size(): Long {
        TODO("Not yet implemented")
    }
}

class DTFunc(val args: List<AliasedType>, val isVararg: Boolean, val returns: Type) : Type(true) {
    override fun toString() = "(${args.addArgs(isVararg)}): $returns"
    override fun ir() = TPtr

    val signature = FuncSignature(returns.ir(), *args.map { it.ir() }.toTypedArray(), isVararg = isVararg)
    override fun size() = 8L
}
abstract class DTPointer(val to: AliasedType, copyable: Boolean, constant: Boolean) : Type(copyable, constant) {
    override fun ir() = TPtr
    override fun size() = 8L
}
class DTSharedPointer(to: AliasedType, constant: Boolean) : DTPointer(to, true, constant) {
    override fun toString() = "${if(constant) "const " else ""}*$to"
}
class DTOwnerPointer(to: AliasedType) : DTPointer(to, false, false) {
    override fun toString() = "&$to"
}
abstract class DTArray(val of: AliasedType, constant: Boolean) : Type(true, constant)
class DTPointerArray(of: AliasedType, constant: Boolean) : DTArray(of, constant) {
    override fun toString() = "${if(constant) "const " else ""}$of[]"
    override fun ir() = TPtr
    override fun size() = 8L
}
class DTSizedArray(of: AliasedType, val size: Long, constant: Boolean) : DTArray(of, constant) {
    override fun toString() = "${if(constant) "const " else ""}$of[$size]"
    override fun ir() = TArray(of.ir(), size)
    override fun size() = size * of.size()
}
class DTStruct(val pckg: DPackage, val name: Line) : Type(true, false) {
    val vars = mutableListOf<Triple<Line, Pair<Boolean, Type>, TreeObject?>>()
    val statics = mutableListOf<DGlobal>()
    var complete = false
    override fun ir() = TStruct(*vars.map { it.second.second.ir() }.toTypedArray())
    override fun size() = vars.sumOf { it.second.second.size() }
    override fun toString() = "$pckg::$name"
}