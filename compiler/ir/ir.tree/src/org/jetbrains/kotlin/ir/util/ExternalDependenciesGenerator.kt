/*
 * Copyright 2010-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.ir.util

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.ModuleDescriptor
import org.jetbrains.kotlin.ir.declarations.IrClass
import org.jetbrains.kotlin.ir.declarations.IrDeclaration
import org.jetbrains.kotlin.ir.declarations.IrDeclarationOrigin
import org.jetbrains.kotlin.ir.descriptors.IrBuiltIns
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.serialization.deserialization.descriptors.DeserializedContainerSource

class ExternalDependenciesGenerator(
    moduleDescriptor: ModuleDescriptor,
    val symbolTable: SymbolTable,
    val irBuiltIns: IrBuiltIns,
    externalDeclarationOrigin: ((DeclarationDescriptor) -> IrDeclarationOrigin)? = null,
    private val deserializer: IrDeserializer = EmptyDeserializer,
    private val irProviders: List<IrProvider> = emptyList(),
    facadeClassGenerator: (DeserializedContainerSource) -> IrClass? = { null }
) {
    private val stubGenerator = DeclarationStubGenerator(
        moduleDescriptor, symbolTable, irBuiltIns.languageVersionSettings, externalDeclarationOrigin,
            listOfNotNull(deserializer) + irProviders, facadeClassGenerator
    )

    fun generateUnboundSymbolsAsDependencies() {
        stubGenerator.unboundSymbolGeneration = true

        /*
            Deserializing a reference may lead to new unbound references, so we loop until none are left.
         */
        var unbound = symbolTable.allUnbound
        while (unbound.isNotEmpty()) {
            for (symbol in unbound) {
                deserializer.getDeclaration(symbol) {
                    irProviders.getDeclaration(it, stubGenerator::generateStubBySymbol)
                }
                assert(symbol.isBound) { "$symbol unbound even after deserialization attempt" }
            }

            unbound = symbolTable.allUnbound
        }

        deserializer.declareForwardDeclarations()
    }
}

private val SymbolTable.allUnbound
    get() =
        unboundClasses + unboundConstructors + unboundEnumEntries + unboundFields +
                unboundSimpleFunctions + unboundProperties + unboundTypeParameters + unboundTypeAliases

fun List<IrProvider>.getDeclaration(symbol: IrSymbol, backoff: (IrSymbol) -> IrDeclaration): IrDeclaration =
    if (isEmpty())
        backoff(symbol)
    else
        this[0].getDeclaration(symbol) {
            drop(1).getDeclaration(it, backoff)
        }


object EmptyDeserializer : IrDeserializer {
    override fun getDeclaration(symbol: IrSymbol, backoff: (IrSymbol) -> IrDeclaration): IrDeclaration = backoff(symbol)

    override fun declareForwardDeclarations() {}
}