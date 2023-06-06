package de.drgn.concept

import de.drgn.irbuilder.FuncSignature
import de.drgn.irbuilder.types.*

data class AliasedType<T: Type>(val type: T, val alias: Pair<DPackage, String>? = null) {
    constructor(pair: Pair<Pair<DPackage, String>, T>) : this(pair.second, pair.first)

    override fun toString() = if(alias == null) type.toString() else "${alias.first}::${alias.second} (aka $type)"

    fun ir() = type.ir()

    fun size() = type.size()

    val constant = type.constant
    val copyable = type.copyable

    override fun equals(other: Any?) = TODO()
}

abstract class Type(val copyable: Boolean, val constant: Boolean = false) {
    abstract fun ir(): TType
    abstract override fun toString(): String
    open fun string() = toString()
    companion object {
        private var templateNum = 0
        operator fun get(line: Line, templateTypes: List<Pair<Line, Type>>?): AliasedType<*> {
            var line = line.trim()

            templateTypes?.find { it.first.str() == line.str() }?.let { return AliasedType(it.second, templatePackage to it.first.str()) }

            when (line.str()) {
                "void" -> return AliasedType(DTVoid)
                "i8" -> return AliasedType(DTI8)
                "i16" -> return AliasedType(DTI16)
                "i32" -> return AliasedType(DTI32)
                "i64" -> return AliasedType(DTI64)
            }
            val const = line.matches(Regex("const\\W.*"))
            if(const) {
                line = line.drop(5)
            }
            if (line[0].c == '&') return AliasedType(DTOwnerPointer(get(line.drop(1), templateTypes), const))
            if (line[0].c == '*') return AliasedType(DTSharedPointer(get(line.drop(1), templateTypes), const))

            line.template()?.let { (name, templates) ->
                val original = run {
                    if("::" in name.str()) {
                        val pckg = DPackage[name.substringBefore("::").str()]?: illegal("Package doesn't exist", name.substringBefore("::"))
                        val v = pckg.templateStructs.find { name.substringAfter("::").str() == it.name.str() }?: illegal("Macro doesn't exist", name)
                        return@run v
                    }

                    name.file.pckg.templateStructs.forEach { if(it.name.str() == name.str()) return@run it }

                    name.file.imports.forEach {
                        it.templateStructs.forEach {
                            if(it.name.str() == name.str()) return@run it
                        }
                    }

                    illegal("Template doesn't exist", name)
                }
                val types = mutableListOf<Pair<Line, Type>>()

                if(templates.size != original.templates!!.size)
                    illegal("Expected ${original.templates.size} template arguments but found ${templates.size}", name)
                templates.forEachIndexed { i, it ->
                    types += original.templates[i] to Type[it, templateTypes].type
                }
                if(templateTypes != null) types += templateTypes

                original.templateCopies.find {
                    it.second.forEachIndexed { i, type ->
                        if(type != types[i].second) return@find false
                    }
                    true
                }?.let { return AliasedType((it.first as ASTStructDef).struct) }

                val element = original.copy(templateNum++) as ASTStructDef

                element.tree()
                element.typesDone(types)
                element.code(types)

                original.templateCopies += element to types.map { it.second }

                return AliasedType(element.struct)
            }

            line.afterBrackets()?.let {
                when(it.second[0].c) {
                    '[' -> {
                        if(it.second.length == 2) {
                            return AliasedType(DTArray(get(it.first, templateTypes), const))
                        }

                        val sizeObj = if(it.second.str() == "[...]") null
                            else parseObject(it.second.drop(1).dropLast(1)).obj(AliasedType(DTI64), templateTypes)
                        val size = if(sizeObj == null) null
                            else (sizeObj.constant?:illegal("Expected constant", sizeObj.line)) as Long

                        return AliasedType(DTSizedArray(get(it.first, templateTypes), size, const))
                    }
                    '(' -> {
                        if(it.first.isEmpty()) return Type[line.drop(1).dropLast(1), templateTypes]
                        if(it.first[0].c == ':') {
                            return AliasedType(DTFunc(it.second.drop(1).dropLast(1).splitBrackets(',').map {
                                Type[it, templateTypes]
                            }, false, Type[it.first.drop(1), templateTypes]))
                        }
                    }
                    else -> {}
                }
            }
            if("::" in line.str()) {
                val pckg = DPackage[line.substringBefore("::").str()]?: illegal("Package doesn't exist", line.substringBefore("::"))
                val v = pckg.structs.find { line.substringAfter("::").str() == it.name.str() }
                    ?: return AliasedType((pckg.aliases.find { line.substringAfter("::").str() == it.first.second })
                        ?: illegal("Type doesn't exist", line))

                return AliasedType(v)
            }
            line.file.pckg.structs.forEach { if(it.name.str() == line.str()) return AliasedType(it) }
            line.file.pckg.aliases.forEach { if(it.first.second == line.str()) return AliasedType(it) }

            line.file.imports.forEach {
                it.structs.forEach {
                    if(it.name.str() == line.str()) return AliasedType(it)
                }
                it.aliases.forEach {
                    if(it.first.second == line.str()) return AliasedType(it)
                }
            }

            illegal("Expected type", line)
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is Type) return false
        return isType(other)
    }

    open fun isType(other: Type): Boolean {
        return string() == other.string()
    }

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
object DTBool : Type(true) {
    override fun ir() = tI1
    override fun toString() = "bool"
    override fun size() = 1L
}

class DTFunc(val args: List<AliasedType<*>>, val isVararg: Boolean, val returns: AliasedType<*>) : Type(true) {
    override fun toString() = "(${args.addArgs(isVararg)}): $returns"
    override fun string() = "(${args.addArgs(isVararg) { it.type.string() }}): ${returns.type.string()}"

    override fun ir() = TPtr

    val signature = FuncSignature(returns.ir(), *args.map { it.ir() }.toTypedArray(), isVararg = isVararg)
    override fun size() = 8L
}
abstract class DTPointer(val to: AliasedType<*>, copyable: Boolean, constant: Boolean) : Type(copyable, constant) {
    override fun ir() = TPtr
    override fun size() = 8L
}
class DTSharedPointer(to: AliasedType<*>, constant: Boolean) : DTPointer(to, true, constant) {
    override fun toString() = "${if(constant) "const " else ""}*$to"
    override fun string() = "${if(constant) "const " else ""}*${to.type.string()}"
}
class DTOwnerPointer(to: AliasedType<*>, constant: Boolean = false) : DTPointer(to, false, constant) {
    override fun toString() = "${if(constant) "const " else ""}&$to"
    override fun string() = "${if(constant) "const " else ""}&${to.type.string()}"
}
class DTArray(val of: AliasedType<*>, constant: Boolean) : Type(constant) {
    override fun toString() = "${if(constant) "const " else ""}[]$of"
    override fun string() = "${if(constant) "const " else ""}[]${of.type.string()}"
    override fun ir() = TPtr
    override fun size() = 8L
}
class DTStruct(val pckg: DPackage, val name: Line) : Type(true, false) {
    val vars = mutableListOf<Triple<Line, Pair<Boolean, AliasedType<*>>, TreeObject?>>()
    val funcs = mutableListOf<DGlobal>()
    val statics = mutableListOf<DGlobal>()
    var complete = false

    override fun ir() = TStruct(*vars.map { it.second.second.ir() }.toTypedArray())
    override fun size() = vars.sumOf { it.second.second.size() }
    override fun toString() = "$pckg::$name"
}