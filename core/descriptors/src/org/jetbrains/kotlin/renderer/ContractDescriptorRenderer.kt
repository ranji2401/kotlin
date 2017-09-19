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

package org.jetbrains.kotlin.renderer

import org.jetbrains.kotlin.descriptors.contracts.ContractDescriptionElement
import org.jetbrains.kotlin.descriptors.contracts.ContractDescriptorVisitor
import org.jetbrains.kotlin.descriptors.contracts.effects.CallsEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.effects.ConditionalEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.effects.ReturnsEffectDeclaration
import org.jetbrains.kotlin.descriptors.contracts.expressions.*

class ContractDescriptorRenderer(private val builder: StringBuilder) : ContractDescriptorVisitor<Unit, Unit> {
    override fun visitConditionalEffectDeclaration(conditionalEffect: ConditionalEffectDeclaration, data: Unit) {
        conditionalEffect.effect.accept(this, data)
        builder.append(" -> ")
        conditionalEffect.condition.accept(this, data)
    }

    override fun visitReturnsEffectDeclaration(returnsEffect: ReturnsEffectDeclaration, data: Unit) {
        builder.append("Returns(")
        returnsEffect.value.accept(this, data)
        builder.append(")")
    }

    override fun visitCallsEffectDeclaration(callsEffect: CallsEffectDeclaration, data: Unit) {
        builder.append("CallsInPlace(")
        callsEffect.variableReference.accept(this, data)
        builder.append(", ${callsEffect.kind})")

    }

    override fun visitLogicalOr(logicalOr: LogicalOr, data: Unit) {
        inBracketsIfNecessary(logicalOr, logicalOr.left) { logicalOr.left.accept(this, data) }
        builder.append(" || ")
        inBracketsIfNecessary(logicalOr, logicalOr.right) { logicalOr.right.accept(this, data) }
    }

    override fun visitLogicalAnd(logicalAnd: LogicalAnd, data: Unit) {
        inBracketsIfNecessary(logicalAnd, logicalAnd.left) { logicalAnd.left.accept(this, data) }
        builder.append(" && ")
        inBracketsIfNecessary(logicalAnd, logicalAnd.right) { logicalAnd.right.accept(this, data) }
    }

    override fun visitLogicalNot(logicalNot: LogicalNot, data: Unit) {
        inBracketsIfNecessary(logicalNot, logicalNot.arg) { builder.append("!") }
        logicalNot.arg.accept(this, data)
    }

    override fun visitIsInstancePredicate(isInstancePredicate: IsInstancePredicate, data: Unit) {
        isInstancePredicate.arg.accept(this, data)
        builder.append(" ${if (isInstancePredicate.isNegated) "!" else ""}is ${isInstancePredicate.type}")
    }

    override fun visitIsNullPredicate(isNullPredicate: IsNullPredicate, data: Unit) {
        isNullPredicate.arg.accept(this, data)
        builder.append(" ${if (isNullPredicate.isNegated) "!=" else "=="} null")
    }

    override fun visitConstantDescriptor(constantDescriptor: ConstantDescriptor, data: Unit) {
        builder.append(constantDescriptor.name)
    }

    override fun visitVariableReference(variableReference: VariableReference, data: Unit) {
        builder.append(variableReference.descriptor.name)
    }

    private fun ContractDescriptionElement.isAtom(): Boolean =
            this is VariableReference || this is ConstantDescriptor || this is IsNullPredicate || this is IsInstancePredicate

    private fun needsBrackets(parent: ContractDescriptionElement, child: ContractDescriptionElement): Boolean {
        if (child.isAtom()) return false
        if (parent is LogicalNot) return true
        return parent::class != child::class
    }

    private fun inBracketsIfNecessary(parent: ContractDescriptionElement, child: ContractDescriptionElement, block: () -> Unit) {
        if (needsBrackets(parent, child)) {
            builder.append("(")
            block()
            builder.append(")")
        } else {
            block()
        }
    }

}