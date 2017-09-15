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

import org.jetbrains.kotlin.descriptors.contracts.expressions.BooleanConstantDescriptor
import org.jetbrains.kotlin.descriptors.contracts.expressions.ConstantDescriptor
import org.jetbrains.kotlin.contracts.impls.ESConstant
import org.jetbrains.kotlin.contracts.impls.lift

internal class ConstantValuesInterpreter {
    fun interpretConstant(constantDescriptor: ConstantDescriptor): ESConstant? = when (constantDescriptor) {
        BooleanConstantDescriptor.TRUE -> true.lift()
        BooleanConstantDescriptor.FALSE -> false.lift()
        ConstantDescriptor.NULL-> ESConstant.NULL
        ConstantDescriptor.NOT_NULL -> ESConstant.NOT_NULL
        ConstantDescriptor.WILDCARD -> ESConstant.WILDCARD
        else -> null
    }
}