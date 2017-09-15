/*
 * Copyright 2010-2016 JetBrains s.r.o.
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

package org.jetbrains.kotlin.serialization

import org.jetbrains.kotlin.builtins.KotlinBuiltIns
import org.jetbrains.kotlin.builtins.isSuspendFunctionType
import org.jetbrains.kotlin.builtins.transformSuspendFunctionToRuntimeFunctionType
import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.descriptors.*
import org.jetbrains.kotlin.descriptors.annotations.Annotated
import org.jetbrains.kotlin.descriptors.contracts.*
import org.jetbrains.kotlin.descriptors.contracts.effects.CallsEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.effects.ConditionalEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.effects.InvocationKind
import org.jetbrains.kotlin.descriptors.contracts.effects.ReturnsEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.expressions.*
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.protobuf.MessageLite
import org.jetbrains.kotlin.resolve.DescriptorUtils
import org.jetbrains.kotlin.resolve.DescriptorUtils.isEnumEntry
import org.jetbrains.kotlin.resolve.MemberComparator
import org.jetbrains.kotlin.resolve.constants.NullValue
import org.jetbrains.kotlin.serialization.deserialization.descriptors.SinceKotlinInfo
import org.jetbrains.kotlin.types.*
import org.jetbrains.kotlin.types.typeUtil.contains
import org.jetbrains.kotlin.utils.Interner
import java.io.ByteArrayOutputStream
import java.util.*

class DescriptorSerializer private constructor(
        private val containingDeclaration: DeclarationDescriptor?,
        private val typeParameters: Interner<TypeParameterDescriptor>,
        private val extension: SerializerExtension,
        private val typeTable: MutableTypeTable,
        private val sinceKotlinInfoTable: MutableSinceKotlinInfoTable,
        private val serializeTypeTableToFunction: Boolean
) {
    fun serialize(message: MessageLite): ByteArray {
        return ByteArrayOutputStream().apply {
            stringTable.serializeTo(this)
            message.writeTo(this)
        }.toByteArray()
    }

    private fun createChildSerializer(descriptor: DeclarationDescriptor): DescriptorSerializer =
            DescriptorSerializer(descriptor, Interner(typeParameters), extension, typeTable, sinceKotlinInfoTable,
                                 serializeTypeTableToFunction = false)

    val stringTable: StringTable
        get() = extension.stringTable

    private fun useTypeTable(): Boolean = extension.shouldUseTypeTable()

    fun classProto(classDescriptor: ClassDescriptor): ProtoBuf.Class.Builder {
        val builder = ProtoBuf.Class.newBuilder()

        val flags = Flags.getClassFlags(
                hasAnnotations(classDescriptor), classDescriptor.visibility, classDescriptor.modality, classDescriptor.kind,
                classDescriptor.isInner, classDescriptor.isCompanionObject, classDescriptor.isData, classDescriptor.isExternal,
                classDescriptor.isExpect
        )
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.fqName = getClassifierId(classDescriptor)

        for (typeParameterDescriptor in classDescriptor.declaredTypeParameters) {
            builder.addTypeParameter(typeParameter(typeParameterDescriptor))
        }

        if (!KotlinBuiltIns.isSpecialClassWithNoSupertypes(classDescriptor)) {
            // Special classes (Any, Nothing) have no supertypes
            for (supertype in classDescriptor.typeConstructor.supertypes) {
                if (useTypeTable()) {
                    builder.addSupertypeId(typeId(supertype))
                }
                else {
                    builder.addSupertype(type(supertype))
                }
            }
        }

        for (descriptor in classDescriptor.constructors) {
            builder.addConstructor(constructorProto(descriptor))
        }

        for (descriptor in sort(DescriptorUtils.getAllDescriptors(classDescriptor.defaultType.memberScope))) {
            if (descriptor is CallableMemberDescriptor) {
                if (descriptor.kind == CallableMemberDescriptor.Kind.FAKE_OVERRIDE) continue

                when (descriptor) {
                    is PropertyDescriptor -> builder.addProperty(propertyProto(descriptor))
                    is FunctionDescriptor -> builder.addFunction(functionProto(descriptor))
                }
            }
        }

        for (descriptor in sort(DescriptorUtils.getAllDescriptors(classDescriptor.unsubstitutedInnerClassesScope))) {
            if (descriptor is TypeAliasDescriptor) {
                builder.addTypeAlias(typeAliasProto(descriptor))
            }
            else {
                val name = getSimpleNameIndex(descriptor.name)
                if (isEnumEntry(descriptor)) {
                    builder.addEnumEntry(enumEntryProto(descriptor as ClassDescriptor))
                }
                else {
                    builder.addNestedClassName(name)
                }
            }
        }

        for (sealedSubclass in classDescriptor.sealedSubclasses) {
            builder.addSealedSubclassFqName(getClassifierId(sealedSubclass))
        }

        val companionObjectDescriptor = classDescriptor.companionObjectDescriptor
        if (companionObjectDescriptor != null) {
            builder.companionObjectName = getSimpleNameIndex(companionObjectDescriptor.name)
        }

        val typeTableProto = typeTable.serialize()
        if (typeTableProto != null) {
            builder.typeTable = typeTableProto
        }

        val sinceKotlinInfoProto = sinceKotlinInfoTable.serialize()
        if (sinceKotlinInfoProto != null) {
            builder.sinceKotlinInfoTable = sinceKotlinInfoProto
        }

        extension.serializeClass(classDescriptor, builder)

        return builder
    }

    fun propertyProto(descriptor: PropertyDescriptor): ProtoBuf.Property.Builder {
        val builder = ProtoBuf.Property.newBuilder()

        val local = createChildSerializer(descriptor)

        var hasGetter = false
        var hasSetter = false

        val compileTimeConstant = descriptor.compileTimeInitializer
        val hasConstant = compileTimeConstant != null && compileTimeConstant !is NullValue

        val hasAnnotations = descriptor.annotations.getAllAnnotations().isNotEmpty()

        val propertyFlags = Flags.getAccessorFlags(hasAnnotations, descriptor.visibility, descriptor.modality, false, false, false)

        val getter = descriptor.getter
        if (getter != null) {
            hasGetter = true
            val accessorFlags = getAccessorFlags(getter)
            if (accessorFlags != propertyFlags) {
                builder.getterFlags = accessorFlags
            }
        }

        val setter = descriptor.setter
        if (setter != null) {
            hasSetter = true
            val accessorFlags = getAccessorFlags(setter)
            if (accessorFlags != propertyFlags) {
                builder.setterFlags = accessorFlags
            }

            if (!setter.isDefault) {
                val setterLocal = local.createChildSerializer(setter)
                for (valueParameterDescriptor in setter.valueParameters) {
                    builder.setSetterValueParameter(setterLocal.valueParameter(valueParameterDescriptor))
                }
            }
        }

        val flags = Flags.getPropertyFlags(
                hasAnnotations, descriptor.visibility, descriptor.modality, descriptor.kind, descriptor.isVar,
                hasGetter, hasSetter, hasConstant, descriptor.isConst, descriptor.isLateInit, descriptor.isExternal,
                @Suppress("DEPRECATION") descriptor.isDelegated, descriptor.isExpect
        )
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.name = getSimpleNameIndex(descriptor.name)

        if (useTypeTable()) {
            builder.returnTypeId = local.typeId(descriptor.type)
        }
        else {
            builder.setReturnType(local.type(descriptor.type))
        }

        for (typeParameterDescriptor in descriptor.typeParameters) {
            builder.addTypeParameter(local.typeParameter(typeParameterDescriptor))
        }

        val receiverParameter = descriptor.extensionReceiverParameter
        if (receiverParameter != null) {
            if (useTypeTable()) {
                builder.receiverTypeId = local.typeId(receiverParameter.type)
            }
            else {
                builder.setReceiverType(local.type(receiverParameter.type))
            }
        }

        if (descriptor.isSuspendOrHasSuspendTypesInSignature()) {
            builder.sinceKotlinInfo = writeSinceKotlinInfo(LanguageFeature.Coroutines)
        }

        extension.serializeProperty(descriptor, builder)

        return builder
    }

    fun functionProto(descriptor: FunctionDescriptor): ProtoBuf.Function.Builder {
        val builder = ProtoBuf.Function.newBuilder()

        val local = createChildSerializer(descriptor)

        val flags = Flags.getFunctionFlags(
                hasAnnotations(descriptor), descriptor.visibility, descriptor.modality, descriptor.kind, descriptor.isOperator,
                descriptor.isInfix, descriptor.isInline, descriptor.isTailrec, descriptor.isExternal, descriptor.isSuspend,
                descriptor.isExpect
        )
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.name = getSimpleNameIndex(descriptor.name)

        if (useTypeTable()) {
            builder.returnTypeId = local.typeId(descriptor.returnType!!)
        }
        else {
            builder.setReturnType(local.type(descriptor.returnType!!))
        }

        for (typeParameterDescriptor in descriptor.typeParameters) {
            builder.addTypeParameter(local.typeParameter(typeParameterDescriptor))
        }

        val receiverParameter = descriptor.extensionReceiverParameter
        if (receiverParameter != null) {
            if (useTypeTable()) {
                builder.receiverTypeId = local.typeId(receiverParameter.type)
            }
            else {
                builder.setReceiverType(local.type(receiverParameter.type))
            }
        }

        for (valueParameterDescriptor in descriptor.valueParameters) {
            builder.addValueParameter(local.valueParameter(valueParameterDescriptor))
        }

        if (serializeTypeTableToFunction) {
            val typeTableProto = typeTable.serialize()
            if (typeTableProto != null) {
                builder.typeTable = typeTableProto
            }
        }

        if (descriptor.isSuspendOrHasSuspendTypesInSignature()) {
            builder.sinceKotlinInfo = writeSinceKotlinInfo(LanguageFeature.Coroutines)
        }

        val contractDescriptor = descriptor.getUserData(ContractProviderKey)?.getContractDescriptor()
        if (contractDescriptor != null) {
            val serializedContract = contractProto(contractDescriptor)
            builder.setContract(serializedContract)
        }

        extension.serializeFunction(descriptor, builder)

        return builder
    }

    fun constructorProto(descriptor: ConstructorDescriptor): ProtoBuf.Constructor.Builder {
        val builder = ProtoBuf.Constructor.newBuilder()

        val local = createChildSerializer(descriptor)

        val flags = Flags.getConstructorFlags(hasAnnotations(descriptor), descriptor.visibility, !descriptor.isPrimary)
        if (flags != builder.flags) {
            builder.flags = flags
        }

        for (valueParameterDescriptor in descriptor.valueParameters) {
            builder.addValueParameter(local.valueParameter(valueParameterDescriptor))
        }

        if (descriptor.isSuspendOrHasSuspendTypesInSignature()) {
            builder.sinceKotlinInfo = writeSinceKotlinInfo(LanguageFeature.Coroutines)
        }

        extension.serializeConstructor(descriptor, builder)

        return builder
    }

    private fun CallableMemberDescriptor.isSuspendOrHasSuspendTypesInSignature(): Boolean {
        if (this is FunctionDescriptor && isSuspend) return true

        return listOfNotNull(
                extensionReceiverParameter?.type,
                returnType,
                *valueParameters.map(ValueParameterDescriptor::getType).toTypedArray()
        ).any { type -> type.contains(UnwrappedType::isSuspendFunctionType) }
    }

    fun typeAliasProto(descriptor: TypeAliasDescriptor): ProtoBuf.TypeAlias.Builder {
        val builder = ProtoBuf.TypeAlias.newBuilder()
        val local = createChildSerializer(descriptor)

        val flags = Flags.getTypeAliasFlags(hasAnnotations(descriptor), descriptor.visibility)
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.name = getSimpleNameIndex(descriptor.name)

        for (typeParameterDescriptor in descriptor.declaredTypeParameters) {
            builder.addTypeParameter(local.typeParameter(typeParameterDescriptor))
        }

        val underlyingType = descriptor.underlyingType
        if (useTypeTable()) {
            builder.underlyingTypeId = local.typeId(underlyingType)
        }
        else {
            builder.setUnderlyingType(local.type(underlyingType))
        }

        val expandedType = descriptor.expandedType
        if (useTypeTable()) {
            builder.expandedTypeId = local.typeId(expandedType)
        }
        else {
            builder.setExpandedType(local.type(expandedType))
        }

        builder.addAllAnnotation(descriptor.annotations.map { extension.annotationSerializer.serializeAnnotation(it) })

        return builder
    }

    fun enumEntryProto(descriptor: ClassDescriptor): ProtoBuf.EnumEntry.Builder {
        val builder = ProtoBuf.EnumEntry.newBuilder()
        builder.name = getSimpleNameIndex(descriptor.name)
        extension.serializeEnumEntry(descriptor, builder)
        return builder
    }

    private fun valueParameter(descriptor: ValueParameterDescriptor): ProtoBuf.ValueParameter.Builder {
        val builder = ProtoBuf.ValueParameter.newBuilder()

        val flags = Flags.getValueParameterFlags(
                hasAnnotations(descriptor), descriptor.declaresDefaultValue(),
                descriptor.isCrossinline, descriptor.isNoinline
        )
        if (flags != builder.flags) {
            builder.flags = flags
        }

        builder.name = getSimpleNameIndex(descriptor.name)

        if (useTypeTable()) {
            builder.typeId = typeId(descriptor.type)
        }
        else {
            builder.setType(type(descriptor.type))
        }

        val varargElementType = descriptor.varargElementType
        if (varargElementType != null) {
            if (useTypeTable()) {
                builder.varargElementTypeId = typeId(varargElementType)
            }
            else {
                builder.setVarargElementType(type(varargElementType))
            }
        }

        extension.serializeValueParameter(descriptor, builder)

        return builder
    }

    private fun typeParameter(typeParameter: TypeParameterDescriptor): ProtoBuf.TypeParameter.Builder {
        val builder = ProtoBuf.TypeParameter.newBuilder()

        builder.id = getTypeParameterId(typeParameter)

        builder.name = getSimpleNameIndex(typeParameter.name)

        if (typeParameter.isReified != builder.reified) {
            builder.reified = typeParameter.isReified
        }

        val variance = variance(typeParameter.variance)
        if (variance != builder.variance) {
            builder.variance = variance
        }
        extension.serializeTypeParameter(typeParameter, builder)

        val upperBounds = typeParameter.upperBounds
        if (upperBounds.size == 1 && KotlinBuiltIns.isDefaultBound(upperBounds.single())) return builder

        for (upperBound in upperBounds) {
            if (useTypeTable()) {
                builder.addUpperBoundId(typeId(upperBound))
            }
            else {
                builder.addUpperBound(type(upperBound))
            }
        }

        return builder
    }

    private fun typeId(type: KotlinType): Int = typeTable[type(type)]

    private fun type(type: KotlinType): ProtoBuf.Type.Builder {
        val builder = ProtoBuf.Type.newBuilder()

        if (type.isError) {
            extension.serializeErrorType(type, builder)
            return builder
        }

        if (type.isFlexible()) {
            val flexibleType = type.asFlexibleType()

            val lowerBound = type(flexibleType.lowerBound)
            val upperBound = type(flexibleType.upperBound)
            extension.serializeFlexibleType(flexibleType, lowerBound, upperBound)
            if (useTypeTable()) {
                lowerBound.flexibleUpperBoundId = typeTable[upperBound]
            }
            else {
                lowerBound.setFlexibleUpperBound(upperBound)
            }
            return lowerBound
        }

        if (type.isSuspendFunctionType) {
            val functionType = type(transformSuspendFunctionToRuntimeFunctionType(type))
            functionType.flags = Flags.getTypeFlags(true)
            return functionType
        }

        val descriptor = type.constructor.declarationDescriptor
        when (descriptor) {
            is ClassDescriptor, is TypeAliasDescriptor -> {
                val possiblyInnerType = type.buildPossiblyInnerType() ?: error("possiblyInnerType should not be null: $type")
                fillFromPossiblyInnerType(builder, possiblyInnerType)
            }
            is TypeParameterDescriptor -> {
                if (descriptor.containingDeclaration === containingDeclaration) {
                    builder.typeParameterName = getSimpleNameIndex(descriptor.name)
                }
                else {
                    builder.typeParameter = getTypeParameterId(descriptor)
                }

                assert(type.arguments.isEmpty()) { "Found arguments for type constructor build on type parameter: $descriptor" }
            }
        }

        if (type.isMarkedNullable != builder.nullable) {
            builder.nullable = type.isMarkedNullable
        }

        val abbreviation = type.getAbbreviatedType()?.abbreviation
        if (abbreviation != null) {
            if (useTypeTable()) {
                builder.abbreviatedTypeId = typeId(abbreviation)
            }
            else {
                builder.setAbbreviatedType(type(abbreviation))
            }
        }

        extension.serializeType(type, builder)

        return builder
    }

    private fun fillFromPossiblyInnerType(builder: ProtoBuf.Type.Builder, type: PossiblyInnerType) {
        val classifierDescriptor = type.classifierDescriptor
        val classifierId = getClassifierId(classifierDescriptor)
        when (classifierDescriptor) {
            is ClassDescriptor -> builder.className = classifierId
            is TypeAliasDescriptor -> builder.typeAliasName = classifierId
        }

        for (projection in type.arguments) {
            builder.addArgument(typeArgument(projection))
        }

        if (type.outerType != null) {
            val outerBuilder = ProtoBuf.Type.newBuilder()
            fillFromPossiblyInnerType(outerBuilder, type.outerType!!)
            if (useTypeTable()) {
                builder.outerTypeId = typeTable[outerBuilder]
            }
            else {
                builder.setOuterType(outerBuilder)
            }
        }
    }

    private fun typeArgument(typeProjection: TypeProjection): ProtoBuf.Type.Argument.Builder {
        val builder = ProtoBuf.Type.Argument.newBuilder()

        if (typeProjection.isStarProjection) {
            builder.projection = ProtoBuf.Type.Argument.Projection.STAR
        }
        else {
            val projection = projection(typeProjection.projectionKind)

            if (projection != builder.projection) {
                builder.projection = projection
            }

            if (useTypeTable()) {
                builder.typeId = typeId(typeProjection.type)
            }
            else {
                builder.setType(type(typeProjection.type))
            }
        }

        return builder
    }

    fun packagePartProto(packageFqName: FqName, members: Collection<DeclarationDescriptor>): ProtoBuf.Package.Builder {
        val builder = ProtoBuf.Package.newBuilder()

        for (declaration in sort(members)) {
            when (declaration) {
                is PropertyDescriptor -> builder.addProperty(propertyProto(declaration))
                is FunctionDescriptor -> builder.addFunction(functionProto(declaration))
                is TypeAliasDescriptor -> builder.addTypeAlias(typeAliasProto(declaration))
            }
        }

        val typeTableProto = typeTable.serialize()
        if (typeTableProto != null) {
            builder.typeTable = typeTableProto
        }

        val sinceKotlinInfoProto = sinceKotlinInfoTable.serialize()
        if (sinceKotlinInfoProto != null) {
            builder.sinceKotlinInfoTable = sinceKotlinInfoProto
        }

        extension.serializePackage(packageFqName, builder)

        return builder
    }

    private fun writeSinceKotlinInfo(languageFeature: LanguageFeature): Int {
        val languageVersion = languageFeature.sinceVersion!!
        val sinceKotlinInfo = ProtoBuf.SinceKotlinInfo.newBuilder().apply {
            SinceKotlinInfo.Version(languageVersion.major, languageVersion.minor).encode(
                    writeVersion = { version = it },
                    writeVersionFull = { versionFull = it }
            )
        }
        return sinceKotlinInfoTable[sinceKotlinInfo]
    }

    private fun getClassifierId(descriptor: ClassifierDescriptorWithTypeParameters): Int =
            stringTable.getFqNameIndex(descriptor)

    private fun getSimpleNameIndex(name: Name): Int =
            stringTable.getStringIndex(name.asString())

    private fun getTypeParameterId(descriptor: TypeParameterDescriptor): Int =
            typeParameters.intern(descriptor)

    private fun contractProto(contractDescriptor: ContractDescriptor): ProtoBuf.Contract.Builder? {
        return ProtoBuf.Contract.newBuilder().apply {
            contractDescriptor.effects.forEach { addEffect(effectProto(it, contractDescriptor)) }
        }
    }

    private fun effectProto(effectDeclaration: EffectDeclaration, contractDescriptor: ContractDescriptor): ProtoBuf.Effect.Builder? {
        return ProtoBuf.Effect.newBuilder().apply {
            fillEffectProto(this, effectDeclaration, contractDescriptor)
        }
    }

    private fun fillEffectProto(builder: ProtoBuf.Effect.Builder, effectDeclaration: EffectDeclaration, contractDescriptor: ContractDescriptor) {
        when (effectDeclaration) {
            is ConditionalEffectDeclaration -> {
                builder.setConclusionOfConditionalEffect(contractExpressionProto(effectDeclaration.condition, contractDescriptor))
                fillEffectProto(builder, effectDeclaration.effect, contractDescriptor)
            }

            is ReturnsEffectDeclaration -> {
                when {
                    effectDeclaration.value == ConstantDescriptor.NOT_NULL -> builder.effectType = ProtoBuf.Effect.EffectType.RETURNS_NOT_NULL
                    effectDeclaration.value == ConstantDescriptor.WILDCARD -> builder.effectType = ProtoBuf.Effect.EffectType.RETURNS_CONSTANT
                    else -> {
                        builder.effectType = ProtoBuf.Effect.EffectType.RETURNS_CONSTANT
                        builder.addEffectConstructorArguments(contractExpressionProto(effectDeclaration.value, contractDescriptor))
                    }
                }
            }

            is CallsEffectDeclaration -> {
                builder.effectType = ProtoBuf.Effect.EffectType.CALLS
                builder.addEffectConstructorArguments(contractExpressionProto(effectDeclaration.variableReference, contractDescriptor))
                val invocationKindProtobufEnum = invocationKindProtobufEnum(effectDeclaration.kind)
                if (invocationKindProtobufEnum != null) {
                    builder.kind = invocationKindProtobufEnum
                }
            }

            // TODO: Add else and do something like reporting issue?
        }
    }

    private fun contractExpressionProto(contractDescriptionElement: ContractDescriptionElement, contractDescriptor: ContractDescriptor): ProtoBuf.Expression.Builder {
        return contractDescriptionElement.accept(object: ContractDescriptorVisitor<ProtoBuf.Expression.Builder, Unit> {
            override fun visitLogicalOr(logicalOr: LogicalOr, data: Unit): ProtoBuf.Expression.Builder {
                val leftBuilder = logicalOr.left.accept(this, data)

                return if (leftBuilder.andArgumentsCount != 0) {
                    // can't flatten and re-use left builder
                    ProtoBuf.Expression.newBuilder().apply {
                        addOrArguments(leftBuilder)
                        addOrArguments(contractExpressionProto(logicalOr.right, contractDescriptor))
                    }
                }
                else {
                    // we can save some space by re-using left builder instead of nesting new one
                    leftBuilder.apply { addOrArguments(contractExpressionProto(logicalOr.right, contractDescriptor)) }
                }
            }

            override fun visitLogicalAnd(logicalAnd: LogicalAnd, data: Unit): ProtoBuf.Expression.Builder {
                val leftBuilder = logicalAnd.left.accept(this, data)

                return if (leftBuilder.orArgumentsCount != 0) {
                    // leftBuilder is already a sequence of Or-operators, so we can't re-use it
                    ProtoBuf.Expression.newBuilder().apply {
                        addAndArguments(leftBuilder)
                        addAndArguments(contractExpressionProto(logicalAnd.right, contractDescriptor))
                    }
                }
                else {
                    // we can save some space by re-using left builder instead of nesting new one
                    leftBuilder.apply { addAndArguments(contractExpressionProto(logicalAnd.right, contractDescriptor)) }
                }
            }

            override fun visitLogicalNot(logicalNot: LogicalNot, data: Unit): ProtoBuf.Expression.Builder {
                val argBuilder = logicalNot.arg.accept(this, data)
                argBuilder.flags = Flags.IS_NEGATED.invert(argBuilder.flags)
                return argBuilder
            }

            override fun visitIsInstancePredicate(isInstancePredicate: IsInstancePredicate, data: Unit): ProtoBuf.Expression.Builder {
                // write variable
                val builder = visitVariableReference(isInstancePredicate.arg, data)

                // write rhs type
                builder.setIsInstanceType(type(isInstancePredicate.type))
                builder.isInstanceTypeId = typeId(isInstancePredicate.type)

                // set flags
                builder.flags = Flags.getContractExpressionFlags(isInstancePredicate.isNegated, false)

                return builder
            }

            override fun visitIsNullPredicate(isNullPredicate: IsNullPredicate, data: Unit): ProtoBuf.Expression.Builder {
                // get builder with variable embeded into it
                val builder = visitVariableReference(isNullPredicate.arg, data)

                // set flags
                builder.flags = builder.flags or Flags.getContractExpressionFlags(isNullPredicate.isNegated, true)

                return builder
            }

            override fun visitConstantDescriptor(constantDescriptor: ConstantDescriptor, data: Unit): ProtoBuf.Expression.Builder {
                val builder = ProtoBuf.Expression.newBuilder()

                // write constant value
                val constantValueProtobufEnum = constantValueProtobufEnum(constantDescriptor)
                if (constantValueProtobufEnum != null) {
                    builder.constantValue = constantValueProtobufEnum
                }

                return builder
            }

            override fun visitVariableReference(variableReference: VariableReference, data: Unit): ProtoBuf.Expression.Builder {
                val builder = ProtoBuf.Expression.newBuilder()

                val indexOfParameter = when (variableReference.descriptor) {
                    is ReceiverParameterDescriptor -> 0

                    is ValueParameterDescriptor -> {
                        val indexInParametersList = contractDescriptor.ownerFunction.valueParameters.withIndex()
                                .find { it.value == variableReference.descriptor }?.index ?: return builder
                        indexInParametersList + 1
                    }

                    else -> return builder
                }

                builder.valueParameterReference = indexOfParameter

                return builder
            }
        }, Unit)
    }

    private fun invocationKindProtobufEnum(kind: InvocationKind): ProtoBuf.Effect.InvocationKind? = when (kind) {
        InvocationKind.AT_MOST_ONCE -> ProtoBuf.Effect.InvocationKind.AT_MOST_ONCE
        InvocationKind.EXACTLY_ONCE -> ProtoBuf.Effect.InvocationKind.EXACTLY_ONCE
        InvocationKind.AT_LEAST_ONCE -> ProtoBuf.Effect.InvocationKind.AT_LEAST_ONCE
        InvocationKind.UNKNOWN -> null
    }

    private fun constantValueProtobufEnum(constantDescriptor: ConstantDescriptor): ProtoBuf.Expression.ConstantValue? = when (constantDescriptor) {
        BooleanConstantDescriptor.TRUE -> ProtoBuf.Expression.ConstantValue.TRUE
        BooleanConstantDescriptor.FALSE -> ProtoBuf.Expression.ConstantValue.FALSE
        ConstantDescriptor.NULL -> ProtoBuf.Expression.ConstantValue.NULL
        ConstantDescriptor.NOT_NULL -> throw IllegalStateException(
                "Internal error during serialization of function contract: NOT_NULL constant isn't denotable in protobuf format. " +
                "Its serialization should be handled at higher level"
        )
        ConstantDescriptor.WILDCARD -> null
        else -> throw IllegalArgumentException("Unknown constant: $constantDescriptor")
    }

    companion object {
        @JvmStatic
        fun createTopLevel(extension: SerializerExtension): DescriptorSerializer {
            return DescriptorSerializer(null, Interner(), extension, MutableTypeTable(), MutableSinceKotlinInfoTable(),
                                        serializeTypeTableToFunction = false)
        }

        @JvmStatic
        fun createForLambda(extension: SerializerExtension): DescriptorSerializer {
            return DescriptorSerializer(null, Interner(), extension, MutableTypeTable(), MutableSinceKotlinInfoTable(),
                                        serializeTypeTableToFunction = true)
        }

        @JvmStatic
        fun create(descriptor: ClassDescriptor, extension: SerializerExtension): DescriptorSerializer {
            val container = descriptor.containingDeclaration
            val parentSerializer = if (container is ClassDescriptor)
                create(container, extension)
            else
                createTopLevel(extension)

            // Calculate type parameter ids for the outer class beforehand, as it would've had happened if we were always
            // serializing outer classes before nested classes.
            // Otherwise our interner can get wrong ids because we may serialize classes in any order.
            val serializer = DescriptorSerializer(
                    descriptor,
                    Interner(parentSerializer.typeParameters),
                    parentSerializer.extension,
                    MutableTypeTable(),
                    MutableSinceKotlinInfoTable(),
                    serializeTypeTableToFunction = false
            )
            for (typeParameter in descriptor.declaredTypeParameters) {
                serializer.typeParameters.intern(typeParameter)
            }
            return serializer
        }

        private fun getAccessorFlags(accessor: PropertyAccessorDescriptor): Int {
            return Flags.getAccessorFlags(
                    hasAnnotations(accessor),
                    accessor.visibility,
                    accessor.modality,
                    !accessor.isDefault,
                    accessor.isExternal,
                    accessor.isInline
            )
        }

        private fun variance(variance: Variance): ProtoBuf.TypeParameter.Variance = when (variance) {
            Variance.INVARIANT -> ProtoBuf.TypeParameter.Variance.INV
            Variance.IN_VARIANCE -> ProtoBuf.TypeParameter.Variance.IN
            Variance.OUT_VARIANCE -> ProtoBuf.TypeParameter.Variance.OUT
        }

        private fun projection(projectionKind: Variance): ProtoBuf.Type.Argument.Projection = when (projectionKind) {
            Variance.INVARIANT -> ProtoBuf.Type.Argument.Projection.INV
            Variance.IN_VARIANCE -> ProtoBuf.Type.Argument.Projection.IN
            Variance.OUT_VARIANCE -> ProtoBuf.Type.Argument.Projection.OUT
        }

        private fun hasAnnotations(descriptor: Annotated): Boolean = !descriptor.annotations.isEmpty()

        fun <T : DeclarationDescriptor> sort(descriptors: Collection<T>): List<T> =
                ArrayList(descriptors).apply {
                    //NOTE: the exact comparator does matter here
                    Collections.sort(this, MemberComparator.INSTANCE)
                }
    }
}
