/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.fir.resolve.dfa

import com.google.common.collect.LinkedHashMultimap
import com.google.common.collect.Multimap
import org.jetbrains.kotlin.fir.FirElement
import org.jetbrains.kotlin.fir.declarations.FirTypedDeclaration
import org.jetbrains.kotlin.fir.expressions.FirExpression
import org.jetbrains.kotlin.fir.resolve.transformers.resultType
import org.jetbrains.kotlin.fir.symbols.FirBasedSymbol
import org.jetbrains.kotlin.fir.types.FirTypeRef

/*
 * isSynthetic = false for variables that represents actual variables in fir
 * isSynthetic = true for complex expressions (like when expression)
 */
data class DataFlowVariable(
    val name: String,
    val type: FirTypeRef,
    val isSynthetic: Boolean
) {
    override fun toString(): String {
        return name
    }
}

class DataFlowVariableStorage {
    private val dfi2FirMap: Multimap<DataFlowVariable, FirElement> = LinkedHashMultimap.create()
    private val fir2DfiMap: MutableMap<FirElement, DataFlowVariable> = mutableMapOf()
    private var counter: Int = 1

    private fun getVarName(): String = "d${counter++}"

    fun getOrCreateNewRealVariable(symbol: FirBasedSymbol<*>): DataFlowVariable {
        val fir = symbol.fir
        get(fir)?.let { return it }
        return DataFlowVariable(getVarName(), fir.type, false).also { storeVariable(it, fir) }
    }

    fun getOrCreateNewSyntheticVariable(fir: FirElement): DataFlowVariable {
        get(fir)?.let { return it }
        return DataFlowVariable(getVarName(), fir.type, true).also { storeVariable(it, fir) }
    }

    @Deprecated("only for debug")
    fun getByName(name: String): DataFlowVariable? = dfi2FirMap.keySet().firstOrNull { it.name == name }

    fun removeVariable(variable: DataFlowVariable) {
        val firExpressions = dfi2FirMap.removeAll(variable)
        firExpressions.forEach(fir2DfiMap::remove)
    }

    private fun storeVariable(variable: DataFlowVariable, fir: FirElement) {
        dfi2FirMap.put(variable, fir)
        fir2DfiMap.put(fir, variable)
    }

    operator fun get(variable: DataFlowVariable): Collection<FirElement> {
        return dfi2FirMap[variable]
    }

    operator fun get(firElement: FirElement): DataFlowVariable? {
        return fir2DfiMap[firElement]
    }

    operator fun get(symbol: FirBasedSymbol<*>): DataFlowVariable? {
        return fir2DfiMap[symbol.fir]
    }

    fun reset() {
        dfi2FirMap.clear()
        fir2DfiMap.clear()
        counter = 1
    }
}

private val FirElement.type: FirTypeRef
    get() = when (this) {
        is FirExpression -> this.resultType
        is FirTypedDeclaration -> this.returnTypeRef
        else -> TODO()
    }
