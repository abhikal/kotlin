/*
 * Copyright 2010-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license
 * that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.backend.jvm

import org.jetbrains.kotlin.backend.jvm.serialization.JvmIrSerializer
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrFile

fun serializeIrFile(context: JvmBackendContext, irFile: IrFile): ByteArray {
    return JvmIrSerializer(
        context,
        context.declarationTable,
        context.psiSourceManager,
        externallyVisibleOnly = true
    ).serializeJvmIrFile(irFile).toByteArray()
}

fun serializeToplevelIrClass(context: JvmBackendContext, irClass: IrClass): ByteArray {
    assert(irClass.parent is IrFile)
    return JvmIrSerializer(
        context,
        context.declarationTable,
        context.psiSourceManager,
        externallyVisibleOnly = true
    ).serializeJvmToplevelClass(irClass)
        .toByteArray()
}