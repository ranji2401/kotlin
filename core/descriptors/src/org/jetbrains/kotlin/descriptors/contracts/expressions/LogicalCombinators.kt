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

package org.jetbrains.kotlin.descriptors.contracts.expressions

import org.jetbrains.kotlin.descriptors.contracts.BooleanExpression
import org.jetbrains.kotlin.descriptors.contracts.ContractDescriptorVisitor

class LogicalOr(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
    override fun <R, D> accept(contractDescriptorVisitor: ContractDescriptorVisitor<R, D>, data: D): R =
            contractDescriptorVisitor.visitLogicalOr(this, data)
}

class LogicalAnd(val left: BooleanExpression, val right: BooleanExpression) : BooleanExpression {
    override fun <R, D> accept(contractDescriptorVisitor: ContractDescriptorVisitor<R, D>, data: D): R =
            contractDescriptorVisitor.visitLogicalAnd(this, data)
}

class LogicalNot(val arg: BooleanExpression) : BooleanExpression {
    override fun <R, D> accept(contractDescriptorVisitor: ContractDescriptorVisitor<R, D>, data: D): R =
            contractDescriptorVisitor.visitLogicalNot(this, data)
}