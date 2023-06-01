package de.drgn.concept

import de.drgn.irbuilder.types.TStruct
import java.io.File

class DPackage(val name: String) {
    companion object {
        val packages = mutableListOf<DPackage>()
        operator fun get(name: String) = packages.find { it.name == name }
    }
    val globals = mutableListOf<DGlobal>()
    val structs = mutableListOf<DTStruct>()
    val aliases = mutableListOf<Pair<String, Type>>()
    val macros = mutableListOf<TreeMacroDec>()
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