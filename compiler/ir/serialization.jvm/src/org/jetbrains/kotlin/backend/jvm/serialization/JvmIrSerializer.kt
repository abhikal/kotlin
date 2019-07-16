/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm.serialization

import org.jetbrains.kotlin.backend.common.LoggingContext
import org.jetbrains.kotlin.backend.common.ir.ir2string
import org.jetbrains.kotlin.backend.common.serialization.*
import org.jetbrains.kotlin.backend.common.serialization.proto.IrSymbolTable as ProtoSymbolTable
import org.jetbrains.kotlin.backend.common.serialization.proto.IrTypeTable as ProtoTypeTable
import org.jetbrains.kotlin.backend.common.serialization.proto.StringTable as ProtoStringTable
import org.jetbrains.kotlin.fileClasses.JvmFileClassUtil
import org.jetbrains.kotlin.ir.IrElement
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.expressions.IrFunctionAccessExpression
import org.jetbrains.kotlin.ir.util.fqNameWhenAvailable
import org.jetbrains.kotlin.ir.visitors.IrElementVisitorVoid
import org.jetbrains.kotlin.ir.visitors.acceptChildrenVoid
import org.jetbrains.kotlin.load.java.descriptors.getImplClassNameForDeserialized
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.psi2ir.PsiSourceManager
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedMemberDescriptor

class JvmIrSerializer(
    logger: LoggingContext,
    declarationTable: DeclarationTable,
    private val psiSourceManager: PsiSourceManager,
    externallyVisibleOnly: Boolean = true
) : IrModuleSerializer(logger, declarationTable, JvmMangler, externallyVisibleOnly) {

    // Usage protocol: construct an instance, call only one of `serializeIrFile()` and `serializeTopLevelClass()` only once.

    fun serializeJvmIrFile(irFile: IrFile): JvmIr.JvmIrFile {
        val proto = JvmIr.JvmIrFile.newBuilder()
        proto.declarationContainer = serializeIrDeclarationContainer(irFile.declarations.filter { it !is IrClass })
        proto.annotations = serializeAnnotations(irFile.annotations)

        val idCollector = UniqIdCollector(irFile.facadeFqName())
        for (declaration in irFile.declarations) {
            if (declaration !is IrClass) {
                idCollector.collectUniqIds(declaration)
            }
        }
        // TODO -- serialize referencesToTopLevelMap
        proto.auxTables = serializeAuxTables(idCollector.map, collectExternalReferences(irFile))

        return proto.build()
    }

    fun serializeJvmToplevelClass(irClass: IrClass): JvmIr.JvmIrClass {
        val proto = JvmIr.JvmIrClass.newBuilder()
        proto.irClass = serializeIrClass(irClass)

        val idMap = UniqIdCollector(irClass.fqNameWhenAvailable!!)
            .apply { collectUniqIds(irClass) }
            .map

        // TODO -- serialize referencesToTopLevelMap
        proto.auxTables = serializeAuxTables(idMap, collectExternalReferences(irClass))

        return proto.build()
    }

    private fun serializeAuxTables(idMap: Map<Long, FqName>, copiedExternalReferences: ExternalReferencesInfo): JvmIr.AuxTables {
        val proto = JvmIr.AuxTables.newBuilder()
        proto.uniqIdTable = JvmIr.UniqIdTable.newBuilder() // This should come before serializing stringTable.
            .addAllInfos(idMap.toList().map { (id, fqName) ->
                JvmIr.UniqIdInfo.newBuilder()
                    .setId(id)
                    .setToplevelFqName(serializeString(fqName.asString()))
                    .build()
            })
            .build()
        proto.externalRefs = serializeExternalReferences(copiedExternalReferences)
        proto.symbolTable = ProtoSymbolTable.newBuilder()
            .addAllSymbols(protoSymbolArray)
            .build()
        proto.typeTable = ProtoTypeTable.newBuilder()
            .addAllTypes(protoTypeArray)
            .build()
        proto.stringTable = ProtoStringTable.newBuilder()
            .addAllStrings(protoStringArray)
            .build()
        return proto.build()
    }

    private fun serializeExternalReferences(externalReferencesInfo: ExternalReferencesInfo): JvmIr.ExternalRefs {
        val proto = JvmIr.ExternalRefs.newBuilder()
        externalReferencesInfo.packageFragments.forEach { packageFragment ->
            proto.addPackages(
                JvmIr.JvmExternalPackage.newBuilder()
                    .setFqName(packageFragment.fqName.asString())
                    .setDeclarationContainer(
                        serializeIrDeclarationContainer(packageFragment.declarations)
                    )
                    .build()
            )
        }
        for ((reference, extPackageIndex) in externalReferencesInfo.references) {
            proto.addReferences(
                JvmIr.ExternalReference.newBuilder()
                    .setId(reference.uniqId)
                    .setIndex(extPackageIndex)
                    .build()
            )
        }
        return proto.build()
    }

    private inner class UniqIdCollector(val ourToplevelFqName: FqName) : IrElementVisitorVoid {

        val map = mutableMapOf<Long, FqName>()

        fun collectUniqIds(declaration: IrDeclaration) {
            declaration.acceptChildrenVoid(this)
        }

        override fun visitElement(element: IrElement) {
            element.acceptChildrenVoid(this)
        }

        override fun visitFunctionAccess(expression: IrFunctionAccessExpression) {
            handleReference(expression.symbol.owner)
            super.visitFunctionAccess(expression)
        }

        private fun handleReference(target: IrDeclaration) {
            val targetToplevelFqName = target.getToplevelFqName()
            if (targetToplevelFqName != ourToplevelFqName) {
                map[target.uniqId] = targetToplevelFqName
            }
        }
    }

    private val IrDeclaration.uniqId get() = with(JvmMangler) { hashedMangle }

    private tailrec fun IrDeclaration.getToplevelFqName(): FqName =
        when {
            parent is IrDeclaration -> (parent as IrDeclaration).getToplevelFqName() // not toplevel
            this is IrClass -> fqNameWhenAvailable!!
            parent is IrFile -> (parent as IrFile).facadeFqName()
            parent is IrExternalPackageFragment -> (parent as IrExternalPackageFragment).fqName
            parent is IrModuleFragment -> (descriptor as DeserializedMemberDescriptor).getImplClassNameForDeserialized()?.fqNameForTopLevelClassMaybeWithDollars
                ?: error("Unknown impl class name for $descriptor")
            else -> error("Unknown element in Ir tree: ${ir2string(this)}")
        }

    private fun IrFile.facadeFqName(): FqName {
        val fileEntry = fileEntry
        val ktFile = psiSourceManager.getKtFile(fileEntry as PsiSourceManager.PsiFileEntry)
            ?: throw AssertionError("Unexpected file entry: $fileEntry")
        val fileClassInfo = JvmFileClassUtil.getFileClassInfoNoResolve(ktFile)
        return fileClassInfo.facadeClassFqName
    }
}

