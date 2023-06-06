package de.drgn.concept

import java.io.File

val templatePackage = DPackage("template")
class DPackage(val name: String) {
    companion object {
        val packages = mutableListOf<DPackage>()
        operator fun get(name: String) = packages.find { it.name == name }
    }
    val globals = mutableListOf<DGlobal>()
    val structs = mutableListOf<DTStruct>()
    val aliases = mutableListOf<Pair<Pair<DPackage, String>, Type>>()
    val macros = mutableListOf<TreeMacroDec>()
    val templateVars = mutableListOf<ASTGlobalElementVar>()
    val templateStructs = mutableListOf<ASTStructDef>()


    init {
        packages += this
    }

    override fun toString() = name
}
val noFile = DFile(File(""))
class DFile(val f: File) {
    var pckg = DPackage["std"] ?: DPackage("std")
    val imports = mutableListOf(pckg)

    val lines = mutableListOf<Line>()
}