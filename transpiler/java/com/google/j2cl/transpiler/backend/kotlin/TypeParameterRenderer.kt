/*
 * Copyright 2021 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.kotlin

import com.google.j2cl.transpiler.ast.HasName
import com.google.j2cl.transpiler.ast.IntersectionTypeDescriptor
import com.google.j2cl.transpiler.ast.KtVariance
import com.google.j2cl.transpiler.ast.TypeDescriptor
import com.google.j2cl.transpiler.ast.TypeVariable
import com.google.j2cl.transpiler.backend.kotlin.KotlinSource.IN_KEYWORD
import com.google.j2cl.transpiler.backend.kotlin.KotlinSource.OUT_KEYWORD
import com.google.j2cl.transpiler.backend.kotlin.KotlinSource.WHERE_KEYWORD
import com.google.j2cl.transpiler.backend.kotlin.common.runIf
import com.google.j2cl.transpiler.backend.kotlin.source.Source
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.colonSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.commaSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.inAngleBrackets
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.source
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.spaceSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.orEmpty

internal fun Renderer.typeParametersSource(typeVariables: List<TypeVariable>): Source =
  commaSeparated(typeVariables.map(::typeParameterSource)).ifNotEmpty { inAngleBrackets(it) }

internal fun Renderer.whereClauseSource(typeVariables: List<TypeVariable>): Source =
  whereClauseSource(
    commaSeparated(typeVariables.map { it.whereClauseItems }.flatten().map { source(it) })
  )

fun whereClauseSource(itemsSource: Source): Source =
  itemsSource.ifNotEmpty { spaceSeparated(WHERE_KEYWORD, it) }

internal val TypeVariable.upperBoundTypeDescriptors: List<TypeDescriptor>
  get() =
    upperBoundTypeDescriptor
      .let { if (it is IntersectionTypeDescriptor) it.intersectionTypeDescriptors else listOf(it) }
      .filter { !it.isImplicitUpperBound }
      .map { it.runIf(!it.canBeNullableAsBound) { toNonNullable() } }

private fun Renderer.typeParameterSource(typeVariable: TypeVariable): Source =
  spaceSeparated(
    typeParameterVarianceSource(typeVariable),
    colonSeparated(nameSource(typeVariable), typeParameterBoundSource(typeVariable))
  )

private fun Renderer.typeParameterBoundSource(typeVariable: TypeVariable): Source =
  typeVariable.upperBoundTypeDescriptors
    .singleOrNull()
    ?.let { typeDescriptorSource(it, projectRawToWildcards = true) }
    .orEmpty()

private fun typeParameterVarianceSource(typeVariable: TypeVariable): Source =
  typeVariable.ktVariance?.source.orEmpty()

private val KtVariance.source: Source
  get() =
    when (this) {
      KtVariance.IN -> IN_KEYWORD
      KtVariance.OUT -> OUT_KEYWORD
    }

private data class WhereClauseItem(val hasName: HasName, val boundTypeDescriptor: TypeDescriptor)

private val TypeVariable.whereClauseItems: List<WhereClauseItem>
  get() =
    upperBoundTypeDescriptors.takeIf { it.size > 1 }?.map { WhereClauseItem(this, it) } ?: listOf()

private fun Renderer.source(whereClauseItem: WhereClauseItem): Source =
  colonSeparated(
    nameSource(whereClauseItem.hasName),
    typeDescriptorSource(whereClauseItem.boundTypeDescriptor)
  )
