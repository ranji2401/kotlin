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

package org.jetbrains.kotlin.contracts.interpretation

import org.jetbrains.kotlin.contracts.functors.SubstitutingFunctor
import org.jetbrains.kotlin.contracts.impls.ESConstant
import org.jetbrains.kotlin.contracts.impls.ESVariable
import org.jetbrains.kotlin.contracts.interpretation.effects.CallsEffectInterpreter
import org.jetbrains.kotlin.contracts.interpretation.effects.ConditionalEffectInterpreter
import org.jetbrains.kotlin.contracts.interpretation.effects.ReturnsEffectInterpreter
import org.jetbrains.kotlin.contracts.model.ESEffect
import org.jetbrains.kotlin.contracts.model.ESExpression
import org.jetbrains.kotlin.contracts.model.Functor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.contracts.BooleanExpression
import org.jetbrains.kotlin.descriptors.contracts.ContractDescriptor
import org.jetbrains.kotlin.descriptors.contracts.ContractProviderKey
import org.jetbrains.kotlin.descriptors.contracts.EffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.effects.ConditionalEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.expressions.ConstantDescriptor
import org.jetbrains.kotlin.descriptors.contracts.expressions.VariableReference

/**
 * This class manages conversion of [ContractDescriptor] to [Functor]
 */
class ContractInterpretationDispatcher {
    private val constantsInterpreter = ConstantValuesInterpreter()
    private val conditionInterpreter = ConditionInterpreter(this)
    private val conditionalEffectInterpreter = ConditionalEffectInterpreter(this)
    private val effectsInterpreters: List<EffectDeclarationInterpreter> = listOf(
        ReturnsEffectInterpreter(this),
        CallsEffectInterpreter(this)
    )
    fun resolveFunctor(functionDescriptor: FunctionDescriptor): Functor? {
        val contractDescriptor = functionDescriptor.getUserData(ContractProviderKey)?.getContractDescriptor() ?: return null
        return convertContractDescriptorToFunctor(contractDescriptor)
    }

    private fun convertContractDescriptorToFunctor(contractDescriptor: ContractDescriptor): Functor? {
        val resultingClauses = contractDescriptor.effects.map { effect ->
            if (effect is ConditionalEffectDeclaration) {
                conditionalEffectInterpreter.interpret(effect) ?: return null
            } else {
                effectsInterpreters.mapNotNull { it.tryInterpret(effect) }.singleOrNull() ?: return null
            }
        }

        return SubstitutingFunctor(resultingClauses, contractDescriptor.ownerFunction)
    }

    internal fun interpretEffect(effectDeclaration: EffectDeclaration): ESEffect? {
        val convertedFunctors = effectsInterpreters.mapNotNull { it.tryInterpret(effectDeclaration) }
        return convertedFunctors.singleOrNull()
    }

    internal fun interpretConstant(constantDescriptor: ConstantDescriptor): ESConstant? =
            constantsInterpreter.interpretConstant(constantDescriptor)

    internal fun interpretCondition(booleanExpression: BooleanExpression): ESExpression? =
            booleanExpression.accept(conditionInterpreter, Unit)

    internal fun interpretVariable(variableReference: VariableReference): ESVariable? = ESVariable(variableReference.descriptor)
}