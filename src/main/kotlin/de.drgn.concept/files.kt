package de.drgn.concept

import java.io.File

class DPackage(val name: String) {
    companion object {
        val packages = mutableListOf<DPackage>()
        operator fun get(name: String) = packages.find { it.name == name }
    }
    init {
        packages += this
    }
}
val noFile = DFile(File(""))
class DFile(val f: File) {
    var pckg = DPackage["std"] ?: DPackage("std")

    val lines = mutableListOf<Line>()
}