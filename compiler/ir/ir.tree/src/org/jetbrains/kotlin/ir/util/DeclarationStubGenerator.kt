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

import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.ir.UNDEFINED_OFFSET
import org.jetbrains.kotlin.ir.declarations.*
import org.jetbrains.kotlin.ir.declarations.impl.IrValueParameterImpl
import org.jetbrains.kotlin.ir.declarations.lazy.*
import org.jetbrains.kotlin.ir.expressions.impl.IrConstImpl
import org.jetbrains.kotlin.ir.expressions.impl.IrExpressionBodyImpl
import org.jetbrains.kotlin.ir.symbols.IrFieldSymbol
import org.jetbrains.kotlin.ir.symbols.IrSymbol
import org.jetbrains.kotlin.ir.symbols.IrTypeParameterSymbol
import org.jetbrains.kotlin.ir.types.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.types.KotlinType

class DeclarationStubGenerator(
    moduleDescriptor: ModuleDescriptor,
    val symbolTable: SymbolTable,
    languageVersionSettings: LanguageVersionSettings,
    private val externalDeclarationOrigin: ((DeclarationDescriptor) -> IrDeclarationOrigin)? = null,
    private val deserializer: IrDeserializer
) {
    private val lazyTable = symbolTable.lazyWrapper

    internal var unboundSymbolGeneration: Boolean
        get() = lazyTable.stubGenerator != null
        set(value) {
            lazyTable.stubGenerator = if (value) this else null
        }


    private val typeTranslator =
        TypeTranslator(lazyTable, languageVersionSettings, moduleDescriptor.builtIns, LazyScopedTypeParametersResolver(lazyTable), true)
    private val constantValueGenerator = ConstantValueGenerator(moduleDescriptor, lazyTable)

    init {
        typeTranslator.constantValueGenerator = constantValueGenerator
        constantValueGenerator.typeTranslator = typeTranslator
    }

    fun generateOrGetEmptyExternalPackageFragmentStub(descriptor: PackageFragmentDescriptor): IrExternalPackageFragment {
        val referenced = symbolTable.referenceExternalPackageFragment(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }
        return symbolTable.declareExternalPackageFragment(descriptor)
    }

    fun generateMemberStub(descriptor: DeclarationDescriptor): IrDeclaration = when (descriptor) {
        is ClassDescriptor ->
            if (DescriptorUtils.isEnumEntry(descriptor))
                generateEnumEntryStub(descriptor)
            else
                generateClassStub(descriptor)
        is ClassConstructorDescriptor ->
            generateConstructorStub(descriptor)
        is FunctionDescriptor ->
            generateFunctionStub(descriptor)
        is PropertyDescriptor ->
            generatePropertyStub(descriptor)
        else ->
            throw AssertionError("Unexpected member descriptor: $descriptor")
    }

    fun generateStubBySymbol(symbol: IrSymbol): IrDeclaration = when (symbol) {
        is IrFieldSymbol ->
            generateFieldStub(symbol.descriptor)
        is IrTypeParameterSymbol ->
            generateOrGetTypeParameterStub(symbol.descriptor)
        else ->
            generateMemberStub(symbol.descriptor)
    }

    private fun computeOrigin(descriptor: DeclarationDescriptor): IrDeclarationOrigin =
        externalDeclarationOrigin?.invoke(descriptor) ?: IrDeclarationOrigin.IR_EXTERNAL_DECLARATION_STUB

    internal fun generatePropertyStub(
        descriptor: PropertyDescriptor,
        bindingContext: BindingContext? = null
    ): IrProperty {
        val referenced = symbolTable.referenceProperty(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }

        val origin = computeOrigin(descriptor)
        return symbolTable.declareProperty(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor.original,
            isDelegated = @Suppress("DEPRECATION") descriptor.isDelegated
        ) {
            IrLazyProperty(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator, bindingContext)
        }
    }

    fun generateFieldStub(descriptor: PropertyDescriptor): IrField {
        val referenced = symbolTable.referenceField(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }

        val origin =
            if (descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                IrDeclarationOrigin.FAKE_OVERRIDE
            else computeOrigin(descriptor)

        return symbolTable.declareField(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor.original, descriptor.type.toIrType()) {
            IrLazyField(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    fun generateFunctionStub(descriptor: FunctionDescriptor, createPropertyIfNeeded: Boolean = true): IrSimpleFunction {
        val referenced = symbolTable.referenceSimpleFunction(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }

        if (createPropertyIfNeeded && descriptor is PropertyGetterDescriptor) {
            val propertySymbol = symbolTable.referenceProperty(descriptor.correspondingProperty)
            val property = deserializer.findDeserializedDeclaration(propertySymbol, ::generateStubBySymbol) as IrProperty
            return property.getter!!
        }
        if (createPropertyIfNeeded && descriptor is PropertySetterDescriptor) {
            val propertySymbol = symbolTable.referenceProperty(descriptor.correspondingProperty)
            val property = deserializer.findDeserializedDeclaration(propertySymbol, ::generateStubBySymbol) as IrProperty
            return property.setter!!
        }

        val origin =
            if (descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE)
                IrDeclarationOrigin.FAKE_OVERRIDE
            else computeOrigin(descriptor)
        return symbolTable.declareSimpleFunction(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET,
            origin,
            descriptor.original
        ) {
            IrLazyFunction(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    internal fun generateConstructorStub(descriptor: ClassConstructorDescriptor): IrConstructor {
        val referenced = symbolTable.referenceConstructor(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }

        val origin = computeOrigin(descriptor)
        return symbolTable.declareConstructor(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor.original
        ) {
            IrLazyConstructor(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    private fun KotlinType.toIrType() = typeTranslator.translateType(this)

    internal fun generateValueParameterStub(descriptor: ValueParameterDescriptor): IrValueParameter {
        return IrValueParameterImpl(
            UNDEFINED_OFFSET, UNDEFINED_OFFSET, computeOrigin(descriptor),
            descriptor, descriptor.type.toIrType(), descriptor.varargElementType?.toIrType()
        ).also { irValueParameter ->
            if (descriptor.declaresDefaultValue()) {
                irValueParameter.defaultValue =
                    IrExpressionBodyImpl(nullConst(descriptor.type.toIrType()))
            }
        }
    }

    internal fun generateClassStub(descriptor: ClassDescriptor): IrClass {
        val referenceClass = symbolTable.referenceClass(descriptor)
        if (referenceClass.isBound) {
            return referenceClass.owner
        }
        val origin = computeOrigin(descriptor)
        return symbolTable.declareClass(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor) {
            IrLazyClass(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    internal fun generateEnumEntryStub(descriptor: ClassDescriptor): IrEnumEntry {
        val referenced = symbolTable.referenceEnumEntry(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }
        val origin = computeOrigin(descriptor)
        return symbolTable.declareEnumEntry(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor) {
            IrLazyEnumEntryImpl(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    internal fun generateOrGetTypeParameterStub(descriptor: TypeParameterDescriptor): IrTypeParameter {
        val referenced = symbolTable.referenceTypeParameter(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }
        val origin = computeOrigin(descriptor)
        return symbolTable.declareGlobalTypeParameter(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor) {
            IrLazyTypeParameter(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    internal fun generateOrGetScopedTypeParameterStub(descriptor: TypeParameterDescriptor): IrTypeParameter {
        val referenced = symbolTable.referenceTypeParameter(descriptor)
        if (referenced.isBound) {
            return referenced.owner
        }
        val origin = computeOrigin(descriptor)
        return symbolTable.declareScopedTypeParameter(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, descriptor) {
            IrLazyTypeParameter(UNDEFINED_OFFSET, UNDEFINED_OFFSET, origin, it, this, typeTranslator)
        }
    }

    private fun nullConst(type: IrType) = when {
        type.isFloat() -> IrConstImpl.float(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.0F)
        type.isDouble() -> IrConstImpl.double(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.0)
        type.isBoolean() -> IrConstImpl.boolean(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, false)
        type.isByte() -> IrConstImpl.byte(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
        type.isChar() -> IrConstImpl.char(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0.toChar())
        type.isShort() -> IrConstImpl.short(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
        type.isInt() -> IrConstImpl.int(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
        type.isLong() -> IrConstImpl.long(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type, 0)
        else -> IrConstImpl.constNull(UNDEFINED_OFFSET, UNDEFINED_OFFSET, type)
    }
}
