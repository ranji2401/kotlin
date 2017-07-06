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

package org.jetbrains.kotlin.codegen.range

import org.jetbrains.kotlin.codegen.ExpressionCodegen
import org.jetbrains.kotlin.codegen.StackValue
import org.jetbrains.kotlin.codegen.generateCallReceiver
import org.jetbrains.kotlin.codegen.range.comparison.getComparisonGeneratorForPrimitiveType
import org.jetbrains.kotlin.codegen.range.forLoop.ForInCollectionIndicesRangeLoopGenerator
import org.jetbrains.kotlin.codegen.range.inExpression.InContinuousRangeExpressionGenerator
import org.jetbrains.kotlin.descriptors.CallableDescriptor
import org.jetbrains.kotlin.psi.KtForExpression
import org.jetbrains.kotlin.psi.KtSimpleNameExpression
import org.jetbrains.kotlin.resolve.calls.model.ResolvedCall
import org.jetbrains.kotlin.types.KotlinType
import org.jetbrains.org.objectweb.asm.Type

class CollectionIndicesRangeValue(
        codegen: ExpressionCodegen,
        rangeCall: ResolvedCall<out CallableDescriptor>
): PrimitiveNumberRangeIntrinsicRangeValue(rangeCall) {
    private val comparisonGenerator = getComparisonGeneratorForPrimitiveType(asmElementType)
    private val expectedReceiverType: KotlinType = ExpressionCodegen.getExpectedReceiverType(rangeCall)

    private val boundedValue =
            SimpleBoundedValue(
                    codegen, rangeCall,
                    lowBound = StackValue.constant(0, asmElementType),
                    highBound = StackValue.operation(Type.INT_TYPE) { v ->
                        codegen.generateCallReceiver(rangeCall).put(codegen.asmType(expectedReceiverType), v)
                        v.invokeinterface("java/util/Collection", "size", "()I")
                    }
            )

    override fun createForLoopGenerator(codegen: ExpressionCodegen, forExpression: KtForExpression) =
            ForInCollectionIndicesRangeLoopGenerator(codegen, forExpression, rangeCall)

    override fun createIntrinsicInExpressionGenerator(codegen: ExpressionCodegen, operatorReference: KtSimpleNameExpression) =
            InContinuousRangeExpressionGenerator(operatorReference, boundedValue, comparisonGenerator)
}