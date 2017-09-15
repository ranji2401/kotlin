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

package org.jetbrains.kotlin.descriptors.contracts

import org.jetbrains.kotlin.descriptors.FunctionDescriptor

/**
 * Essentially, this is a composition of two fields: value of type 'ContractDescriptor' and
 * 'computation', which guarantees to initialize this field.
 *
 * However, we do some extra bit of work to detect errors and report them properly.
 */
class LazyContractProvider(private val ownerFunction: FunctionDescriptor, private val computation: () -> Any?) {
    private enum class ComputationState { NOT_PROCESSED, IN_PROCESS, PROCESSED }
    private var contractDescriptor: ContractDescriptor? = null
    private var state: ComputationState = ComputationState.NOT_PROCESSED


    fun getContractDescriptor(): ContractDescriptor? = when (state) {
        LazyContractProvider.ComputationState.NOT_PROCESSED -> {
            state = ComputationState.IN_PROCESS
            computation.invoke() // should initialize contractDescriptor
            assert(state == ComputationState.PROCESSED) { "Computation of contract for function $ownerFunction hasn't initialized contract properly" }
            contractDescriptor
        }

        LazyContractProvider.ComputationState.IN_PROCESS -> {
            throw IllegalStateException("Recursive evaluation during resolving of contract for function $ownerFunction")
        }

        LazyContractProvider.ComputationState.PROCESSED -> contractDescriptor
    }

    fun setContractDescriptor(contractDescriptor: ContractDescriptor?) {
        this.contractDescriptor = contractDescriptor
        state = ComputationState.PROCESSED
    }

    companion object {
        fun createInitialized(ownerFunction: FunctionDescriptor, contract: ContractDescriptor?): LazyContractProvider =
                LazyContractProvider(ownerFunction, {}).apply { setContractDescriptor(contract) }
    }
}

// For storing into UserDataMap of FunctionDescriptor
object ContractProviderKey : FunctionDescriptor.UserDataKey<LazyContractProvider?>
