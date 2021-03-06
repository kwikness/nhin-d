/* 
Copyright (c) 2010, NHIN Direct Project
All rights reserved.

Authors:
   Greg Meyer      gm2552@cerner.com
 
Redistribution and use in source and binary forms, with or without modification, are permitted provided that the following conditions are met:

Redistributions of source code must retain the above copyright notice, this list of conditions and the following disclaimer.
Redistributions in binary form must reproduce the above copyright notice, this list of conditions and the following disclaimer 
in the documentation and/or other materials provided with the distribution.  Neither the name of the The NHIN Direct Project (nhindirect.org). 
nor the names of its contributors may be used to endorse or promote products derived from this software without specific prior written permission.
THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, 
THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS 
BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE 
GOODS OR SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, 
STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF 
THE POSSIBILITY OF SUCH DAMAGE.
*/

package org.nhindirect.policy;

import java.io.Serializable;

/**
 * Expressions are the building blocks of building policy statements.  Expressions can be simple literals or a complex series of operations that take other expressions as parameters.
 * <br>
 * PolicyExpression objects are generally the resulting intermediate state of a parsed {@link PolicyLexicon} before being further processed by the policy engine
 * {@link Compiler}.  
 * <br>
 * Expressions are categorized into three types.
 * <ul>
 *    <li>Literals: Literals are simply primitive types or objects that have a static value.  In the policy engine, literals are represented by {@link PolicyValue} objects.
 *    <li>References: References are objects whose values are evaluated at runtime similar to variables.  Reference may be simple structures or specific structure types 
 *    such as X509 certificates.
 *    <li>Operations: Operations are a combination of a {@link PolicyOperator} and one or more parameters.  Operator parameters are themselves expressions
 *    allowing parameters to be either literals, references, or the result of another operation.
 * <ul>
 * Because operator parameters are expressions, complex expressions can be built by nesting other operations as parameters resulting in a tree like construct.
 * Expressions are built using the either the {@link LiteralPolicyExpressionFactory}, the {@link OperationPolicyExpressionFactory}, or by instantiating 
 * one of the defined reference structures in the org.nhindirect.policy.x509 package.
 * @author Greg Meyer
 * @since 1.0
 *
 */
public interface PolicyExpression extends Serializable
{
	/**
	 * Gets the expression type.
	 * @return The expression type.
	 */
	public PolicyExpressionType getExpressionType();
}
