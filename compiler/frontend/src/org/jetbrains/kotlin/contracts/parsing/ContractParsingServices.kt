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

package org.jetbrains.kotlin.contracts.parsing

import org.jetbrains.kotlin.config.LanguageFeature
import org.jetbrains.kotlin.config.LanguageVersionSettings
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.descriptors.contracts.ContractDescriptor
import org.jetbrains.kotlin.descriptors.contracts.ContractProviderKey
import org.jetbrains.kotlin.diagnostics.Errors
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.resolve.BindingTrace
import org.jetbrains.kotlin.resolve.calls.callUtil.getResolvedCall
import org.jetbrains.kotlin.resolve.scopes.LexicalScope
import org.jetbrains.kotlin.resolve.scopes.LexicalScopeKind

class ContractParsingServices(val languageVersionSettings: LanguageVersionSettings) {
    fun fastCheckIfContractPresent(element: KtElement): Boolean {
        if (!isContractAllowedHere(element)) return false
        val firstExpression = ((element as? KtFunction)?.bodyExpression as? KtBlockExpression)?.statements?.firstOrNull() ?: return false
        return isContractDescriptionCallFastCheck(firstExpression)
    }

    fun checkContractAndRecordIfPresent(expression: KtExpression, trace: BindingTrace, scope: LexicalScope, isFirstStatement: Boolean) {
        val ownerDescriptor = scope.ownerDescriptor
        if (!isContractDescriptionCallFastCheck(expression) || ownerDescriptor !is FunctionDescriptor) return
        val contractProvider = ownerDescriptor.getUserData(ContractProviderKey) ?: return

        val isFeatureTurnedOn = languageVersionSettings.supportsFeature(LanguageFeature.CallsInPlaceEffect) ||
                                languageVersionSettings.supportsFeature(LanguageFeature.ReturnsEffect)

        if (!isContractDescriptionCallPreciseCheck(expression, trace.bindingContext)) {
            contractProvider.setContractDescriptor(null)
            return
        }

        if (!isContractAllowedHere(scope) || !isFirstStatement) {
            if (isFeatureTurnedOn) trace.report(Errors.CONTRACT_NOT_ALLOWED.on(expression))
            contractProvider.setContractDescriptor(null)
            return
        }

        contractProvider.setContractDescriptor(parseContract(expression, trace, ownerDescriptor))
    }

    private fun parseContract(expression: KtExpression?, trace: BindingTrace, ownerDescriptor: FunctionDescriptor): ContractDescriptor? {
        val dispatcher = PSIContractParserDispatcher(trace, this)
        return dispatcher.parseContract(expression, ownerDescriptor)
    }

    internal fun isContractDescriptionCall(expression: KtExpression, context: BindingContext): Boolean =
            isContractDescriptionCallFastCheck(expression) && isContractDescriptionCallPreciseCheck(expression, context)

    private fun isContractAllowedHere(element: KtElement): Boolean =
            element is KtNamedFunction && element.isTopLevel && element.hasBlockBody() && !element.hasModifier(KtTokens.OPERATOR_KEYWORD)

    private fun isContractAllowedHere(scope: LexicalScope): Boolean =
            scope.kind == LexicalScopeKind.CODE_BLOCK && (scope.parent as? LexicalScope)?.kind == LexicalScopeKind.FUNCTION_INNER_SCOPE

    private fun isContractDescriptionCallFastCheck(expression: KtExpression): Boolean {
        if (expression !is KtCallExpression) return false
        return expression.calleeExpression?.text == "contract"
    }

    private fun isContractDescriptionCallPreciseCheck(expression: KtExpression, context: BindingContext): Boolean {
        val resolvedCall = expression.getResolvedCall(context) ?: return false
        return resolvedCall.resultingDescriptor.isContractCallDescriptor()
    }
}