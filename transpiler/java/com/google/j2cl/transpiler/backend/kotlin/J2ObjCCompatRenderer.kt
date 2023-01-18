/*
 * Copyright 2023 Google Inc.
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

import com.google.common.base.CaseFormat
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor
import com.google.j2cl.transpiler.ast.CompilationUnit
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor
import com.google.j2cl.transpiler.ast.FieldDescriptor
import com.google.j2cl.transpiler.ast.Method
import com.google.j2cl.transpiler.ast.MethodDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor
import com.google.j2cl.transpiler.ast.PrimitiveTypes
import com.google.j2cl.transpiler.ast.Type
import com.google.j2cl.transpiler.ast.TypeDeclaration
import com.google.j2cl.transpiler.ast.TypeDescriptor
import com.google.j2cl.transpiler.ast.TypeDescriptors
import com.google.j2cl.transpiler.ast.TypeDescriptors.isJavaLangObject
import com.google.j2cl.transpiler.ast.TypeDescriptors.isPrimitiveVoid
import com.google.j2cl.transpiler.ast.Variable
import com.google.j2cl.transpiler.backend.kotlin.common.buildList
import com.google.j2cl.transpiler.backend.kotlin.common.letIf
import com.google.j2cl.transpiler.backend.kotlin.objc.Import
import com.google.j2cl.transpiler.backend.kotlin.objc.Renderer
import com.google.j2cl.transpiler.backend.kotlin.objc.className
import com.google.j2cl.transpiler.backend.kotlin.objc.comment
import com.google.j2cl.transpiler.backend.kotlin.objc.dependency
import com.google.j2cl.transpiler.backend.kotlin.objc.empty
import com.google.j2cl.transpiler.backend.kotlin.objc.flatten
import com.google.j2cl.transpiler.backend.kotlin.objc.functionDeclaration
import com.google.j2cl.transpiler.backend.kotlin.objc.id
import com.google.j2cl.transpiler.backend.kotlin.objc.localImport
import com.google.j2cl.transpiler.backend.kotlin.objc.map
import com.google.j2cl.transpiler.backend.kotlin.objc.map2
import com.google.j2cl.transpiler.backend.kotlin.objc.methodCall
import com.google.j2cl.transpiler.backend.kotlin.objc.nsCopying
import com.google.j2cl.transpiler.backend.kotlin.objc.nsEnumTypedef
import com.google.j2cl.transpiler.backend.kotlin.objc.nsInline
import com.google.j2cl.transpiler.backend.kotlin.objc.nsMutableArray
import com.google.j2cl.transpiler.backend.kotlin.objc.nsMutableDictionary
import com.google.j2cl.transpiler.backend.kotlin.objc.nsMutableSet
import com.google.j2cl.transpiler.backend.kotlin.objc.nsNumber
import com.google.j2cl.transpiler.backend.kotlin.objc.nsObject
import com.google.j2cl.transpiler.backend.kotlin.objc.nsString
import com.google.j2cl.transpiler.backend.kotlin.objc.nsUInteger
import com.google.j2cl.transpiler.backend.kotlin.objc.protocolName
import com.google.j2cl.transpiler.backend.kotlin.objc.rendererOf
import com.google.j2cl.transpiler.backend.kotlin.objc.rendererWith
import com.google.j2cl.transpiler.backend.kotlin.objc.returnStatement
import com.google.j2cl.transpiler.backend.kotlin.objc.sourceWithDependencies
import com.google.j2cl.transpiler.backend.kotlin.source.Source
import com.google.j2cl.transpiler.backend.kotlin.source.dotSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.emptyLineSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.ifNotEmpty
import com.google.j2cl.transpiler.backend.kotlin.source.inAngleBrackets
import com.google.j2cl.transpiler.backend.kotlin.source.join
import com.google.j2cl.transpiler.backend.kotlin.source.plus
import com.google.j2cl.transpiler.backend.kotlin.source.plusNewLine
import com.google.j2cl.transpiler.backend.kotlin.source.source
import com.google.j2cl.transpiler.backend.kotlin.source.spaceSeparated
import java.util.stream.Collectors.toList

internal val CompilationUnit.j2ObjCCompatHeaderSource: Source
  get() =
    dependenciesAndDeclarationsSource.ifNotEmpty {
      emptyLineSeparated(fileCommentSource, it).plusNewLine
    }

private val CompilationUnit.fileCommentSource: Source
  get() = comment(source("Generated by J2KT from \"${packageRelativePath}\""))

private val CompilationUnit.dependenciesAndDeclarationsSource: Source
  get() = declarationsRenderer.sourceWithDependencies

private val CompilationUnit.declarationsRenderer: Renderer<Source>
  get() = declarationsRenderers.flatten.map(::emptyLineSeparated)

private val CompilationUnit.declarationsRenderers: List<Renderer<Source>>
  get() = allTypes.filter { it.shouldRender }.flatMap { it.declarationsRenderers }

private val CompilationUnit.allTypes: List<Type>
  get() = streamTypes().collect(toList())

private val Type.shouldRender: Boolean
  get() = !visibility.isPrivate && !declaration.isKtNative

private val Type.declarationsRenderers: List<Renderer<Source>>
  get() =
    buildList<Renderer<Source>> {
      if (isEnum) {
        add(nsEnumTypedefRenderer)
        addAll(enumGetFunctionRenderers)
      }

      addAll(methods.map { it.functionRenderer })
    }

private val Type.nsEnumTypedefRenderer: Renderer<Source>
  get() =
    nsEnumTypedef(
      name = declaration.objCEnumName,
      type = nsUInteger,
      values = enumFields.map { it.descriptor.objCEnumName }
    )

private val Type.enumGetFunctionRenderers: List<Renderer<Source>>
  get() = enumFields.map { it.descriptor.enumGetFunctionRenderer }

private val FieldDescriptor.enumGetFunctionRenderer: Renderer<Source>
  get() =
    functionDeclaration(
      modifiers = listOf(nsInline),
      returnType = enclosingTypeDescriptor.objCRenderer,
      name = enumGetFunctionName,
      statements = listOf(returnStatement(enumGetExpressionRenderer))
    )

private val FieldDescriptor.enumGetFunctionName: String
  get() = enclosingTypeDescriptor.typeDeclaration.objCName(forMember = true) + "_get_" + name!!

private val FieldDescriptor.enumObjCName: String
  get() = CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.LOWER_CAMEL, name!!)

private val FieldDescriptor.enumGetExpressionRenderer: Renderer<Source>
  get() =
    enclosingTypeDescriptor.typeDeclaration.objCNameRenderer.map {
      dotSeparated(it, source(enumObjCName))
    }

private val Method.functionRenderer: Renderer<Source>
  get() =
    takeIf { it.descriptor.shouldRender }?.toObjCNames()?.let { functionRenderer(it) } ?: empty

private val MethodDescriptor.shouldRender: Boolean
  get() =
    enclosingTypeDescriptor.isClass &&
      isStatic &&
      !isConstructor &&
      returnTypeDescriptor.existsInObjC &&
      parameterTypeDescriptors.all { it.existsInObjC }

private val TypeDescriptor.existsInObjC: Boolean
  get() =
    when (this) {
      is DeclaredTypeDescriptor ->
        !typeDeclaration.isKtNative && !isAssignableTo(TypeDescriptors.get().javaUtilCollection)
      is ArrayTypeDescriptor -> false
      else -> true
    }

private fun Method.functionRenderer(objCNames: MethodObjCNames): Renderer<Source> =
  functionDeclaration(
    modifiers = listOf(nsInline),
    returnType = descriptor.returnTypeDescriptor.objCRenderer,
    name = descriptor.functionName(objCNames),
    parameters = parameters.map { it.renderer },
    statements = statementRenderers(objCNames)
  )

private fun MethodDescriptor.functionName(objCNames: MethodObjCNames): String =
  enclosingTypeDescriptor
    .objCName(useId = true, forMember = true)
    .plus("_")
    .plus(objCNames.methodName ?: ktName)
    .letIf(objCNames.parameterNames.isNotEmpty()) { parameterName ->
      parameterName.plus(
        objCNames.parameterNames
          .mapIndexed { index, name -> name.letIf(index == 0) { it.titleCase } + "_" }
          .joinToString("")
      )
    }

private fun Method.statementRenderers(objCNames: MethodObjCNames): List<Renderer<Source>> =
  if (isPrimitiveVoid(descriptor.returnTypeDescriptor)) listOf()
  else listOf(returnStatement(methodCallRenderer(objCNames)))

private fun Method.methodCallRenderer(objCNames: MethodObjCNames): Renderer<Source> =
  methodCall(
    target = descriptor.enclosingTypeDescriptor.typeDeclaration.objCNameRenderer,
    name = objCNames.objCName(descriptor.ktName),
    arguments = parameters.map { it.nameRenderer }
  )

private fun MethodObjCNames.objCName(defaultMethodName: String) =
  (methodName
    ?: defaultMethodName) +
    parameterNames
      .mapIndexed { index, name -> name.letIf(index == 0) { it.titleCase } + ":" }
      .joinToString("")

private val Variable.renderer: Renderer<Source>
  get() =
    map2(typeDescriptor.objCRenderer, nameRenderer) { typeSource, nameSource ->
      spaceSeparated(typeSource, nameSource)
    }

private val Variable.nameRenderer: Renderer<Source>
  get() = rendererOf(source(name.objCName))

private val TypeDeclaration.objCNameRenderer: Renderer<Source>
  get() = objectiveCNameRenderer ?: mappedObjCNameRenderer ?: defaultObjCNameRenderer

private val TypeDeclaration.objectiveCNameRenderer: Renderer<Source>?
  get() = objectiveCName?.let { if (isInterface) protocolName(it) else className(it) }

private val TypeDeclaration.mappedObjCNameRenderer: Renderer<Source>?
  get() =
    when (qualifiedBinaryName) {
      "java.lang.Object" -> nsObject
      "java.lang.String" -> nsString
      "java.lang.Number" -> nsNumber
      "java.lang.Cloneable" -> nsCopying
      "java.util.List" -> nsMutableArray
      "java.util.Set" -> nsMutableSet
      "java.util.Map" -> nsMutableDictionary
      else -> null
    }

private val TypeDeclaration.defaultObjCNameRenderer: Renderer<Source>
  get() =
    defaultObjCName(forMember = false).let { if (isInterface) protocolName(it) else className(it) }

private val TypeDescriptor.objCRenderer: Renderer<Source>
  get() =
    when (this) {
      is PrimitiveTypeDescriptor -> primitiveObjCRenderer
      is DeclaredTypeDescriptor -> declaredObjCRenderer
      // TODO: Handle TypeVariable and Array
      else -> id
    }

private val PrimitiveTypeDescriptor.primitiveObjCRenderer: Renderer<Source>
  get() =
    when (this) {
      PrimitiveTypes.VOID -> rendererOf(source("void"))
      else -> source("j$simpleSourceName") rendererWith dependency(j2ObjCTypesImport)
    }

private val DeclaredTypeDescriptor.declaredObjCRenderer: Renderer<Source>
  get() =
    when {
      isJavaLangObject(this) -> id
      isInterface ->
        typeDeclaration.objCNameRenderer.map { join(source("id"), inAngleBrackets(it)) }
      else -> typeDeclaration.objCNameRenderer.map { it + source("*") }
    }

private val j2ObjCTypesImport: Import
  get() = localImport("third_party/java_src/j2objc/jre_emul/Classes/J2ObjC_types.h")

private val TypeDeclaration.objCEnumName: String
  get() = "${objCName}_Enum"

private val FieldDescriptor.objCEnumName: String
  get() = "${enclosingTypeDescriptor.typeDeclaration.objCEnumName}_$name"
