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

import com.google.j2cl.transpiler.ast.FieldDescriptor
import com.google.j2cl.transpiler.ast.JsMemberType
import com.google.j2cl.transpiler.ast.JsUtils
import com.google.j2cl.transpiler.ast.MemberDescriptor
import com.google.j2cl.transpiler.ast.MethodDescriptor
import com.google.j2cl.transpiler.ast.TypeDeclaration
import com.google.j2cl.transpiler.backend.kotlin.KotlinSource.annotation
import com.google.j2cl.transpiler.backend.kotlin.KotlinSource.assignment
import com.google.j2cl.transpiler.backend.kotlin.KotlinSource.literal
import com.google.j2cl.transpiler.backend.kotlin.ast.Visibility as KtVisibility
import com.google.j2cl.transpiler.backend.kotlin.source.Source
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.dotSeparated
import com.google.j2cl.transpiler.backend.kotlin.source.Source.Companion.source
import com.google.j2cl.transpiler.backend.kotlin.source.orEmpty

internal fun Renderer.jsInteropAnnotationsSource(fieldDescriptor: FieldDescriptor): Source =
  jsPropertyAnnotationSource(fieldDescriptor)
    .ifEmpty { jsIgnoreAnnotationSource(fieldDescriptor) }
    .ifEmpty { jsOverlayAnnotationSource(fieldDescriptor) }

internal fun Renderer.jsInteropAnnotationsSource(methodDescriptor: MethodDescriptor): Source =
  jsPropertyAnnotationSource(methodDescriptor)
    .ifEmpty { jsMethodAnnotationSource(methodDescriptor) }
    .ifEmpty { jsConstructorAnnotationSource(methodDescriptor) }
    .ifEmpty { jsIgnoreAnnotationSource(methodDescriptor) }
    .ifEmpty { jsOverlayAnnotationSource(methodDescriptor) }

private fun Renderer.jsPropertyAnnotationSource(fieldDescriptor: FieldDescriptor): Source =
  fieldDescriptor
    .takeIf { it.hasJsPropertyAnnotation }
    ?.let { jsPropertyAnnotationSource(it.jsInfo.jsName, it.jsInfo.jsNamespace) }
    .orEmpty()

private fun Renderer.jsPropertyAnnotationSource(methodDescriptor: MethodDescriptor): Source =
  // Emit an annotation only if the original java method had a `JsProperty` annotation defined on
  // it.
  methodDescriptor
    .takeIf { it.hasJsPropertyAnnotation }
    ?.let { jsPropertyAnnotationSource(it.originalJsInfo.jsName, it.originalJsInfo.jsNamespace) }
    .orEmpty()

private fun Renderer.jsPropertyAnnotationSource(jsName: String?, jsNamespace: String?): Source =
  annotation(
    topLevelQualifiedNameSource("jsinterop.annotations.JsProperty"),
    nameParameterSource(jsName),
    namespaceParameterSource(jsNamespace)
  )

private fun Renderer.jsIgnoreAnnotationSource(memberDescriptor: MemberDescriptor): Source =
  memberDescriptor
    .takeIf { it.hasJsIgnoreAnnotation }
    ?.let { annotation(topLevelQualifiedNameSource("jsinterop.annotations.JsIgnore")) }
    .orEmpty()

private fun Renderer.jsOverlayAnnotationSource(memberDescriptor: MemberDescriptor): Source =
  memberDescriptor
    .takeIf { it.isJsOverlay }
    ?.let { annotation(topLevelQualifiedNameSource("jsinterop.annotations.JsOverlay")) }
    .orEmpty()

private fun Renderer.jsConstructorAnnotationSource(methodDescriptor: MethodDescriptor): Source =
  methodDescriptor
    .takeIf { it.hasJsConstructorAnnotation }
    ?.let { annotation(topLevelQualifiedNameSource("jsinterop.annotations.JsConstructor")) }
    .orEmpty()

private fun Renderer.jsMethodAnnotationSource(methodDescriptor: MethodDescriptor): Source =
  // Emit an annotation only if the original java method had a `JsMethod` annotation defined on it.
  methodDescriptor
    .takeIf { it.hasJsMethodAnnotation }
    ?.let {
      annotation(
        topLevelQualifiedNameSource("jsinterop.annotations.JsMethod"),
        nameParameterSource(it.jsInfo.jsName),
        namespaceParameterSource(it.jsInfo.jsNamespace)
      )
    }
    .orEmpty()

internal fun Renderer.jsInteropAnnotationsSource(typeDeclaration: TypeDeclaration): Source =
  jsFunctionAnnotationSource(typeDeclaration)
    .ifEmpty { jsTypeAnnotationSource(typeDeclaration) }
    .ifEmpty { jsEnumAnnotationSource(typeDeclaration) }

private fun Renderer.jsFunctionAnnotationSource(typeDeclaration: TypeDeclaration): Source =
  typeDeclaration
    .takeIf { it.isJsFunctionInterface }
    ?.let { annotation(topLevelQualifiedNameSource("jsinterop.annotations.JsFunction")) }
    .orEmpty()

private fun Renderer.jsEnumAnnotationSource(typeDeclaration: TypeDeclaration): Source =
  typeDeclaration
    .takeIf { it.isJsEnum }
    ?.let {
      annotation(
        topLevelQualifiedNameSource("jsinterop.annotations.JsEnum"),
        nameParameterSource(it),
        namespaceParameterSource(it),
        isNativeParameterSource(it.isNative),
        booleanParameterSource(
          "hasCustomValue",
          checkNotNull(it.jsEnumInfo).hasCustomValue(),
          false
        )
      )
    }
    .orEmpty()

private fun Renderer.jsTypeAnnotationSource(typeDeclaration: TypeDeclaration): Source =
  typeDeclaration
    .takeIf { it.isJsType }
    ?.let {
      annotation(
        topLevelQualifiedNameSource("jsinterop.annotations.JsType"),
        nameParameterSource(it),
        namespaceParameterSource(it),
        isNativeParameterSource(it.isNative)
      )
    }
    .orEmpty()

private fun nameParameterSource(typeDeclaration: TypeDeclaration): Source =
  typeDeclaration
    .takeIf { it.simpleJsName != it.simpleSourceName }
    ?.let { nameParameterSource(it.simpleJsName) }
    .orEmpty()

private fun nameParameterSource(value: String?): Source =
  value?.let { assignment(source("name"), literal(it)) }.orEmpty()

private fun Renderer.namespaceParameterSource(typeDeclaration: TypeDeclaration): Source =
  typeDeclaration
    .takeIf { it.hasCustomizedJsNamespace() }
    ?.let { namespaceParameterSource(it.jsNamespace) }
    .orEmpty()

private fun Renderer.namespaceParameterSource(namespace: String?): Source =
  namespace?.let { assignment(source("namespace"), namespaceSource(it)) }.orEmpty()

private fun Renderer.namespaceSource(namespace: String): Source =
  if (JsUtils.isGlobal(namespace)) {
    globalNamespaceSource()
  } else {
    literal(namespace)
  }

private fun Renderer.globalNamespaceSource(): Source =
  dotSeparated(
    topLevelQualifiedNameSource("jsinterop.annotations.JsPackage"),
    identifierSource("GLOBAL")
  )

private fun isNativeParameterSource(value: Boolean): Source =
  booleanParameterSource("isNative", value, false)

private fun booleanParameterSource(name: String, value: Boolean, defaultValue: Boolean): Source =
  value.takeIf { it != defaultValue }?.let { assignment(source(name), literal(it)) }.orEmpty()

private val MethodDescriptor.hasJsPropertyAnnotation
  get() =
    originalJsInfo.hasJsMemberAnnotation &&
      (originalJsInfo.jsMemberType == JsMemberType.GETTER ||
        originalJsInfo.jsMemberType == JsMemberType.SETTER)

private val MethodDescriptor.hasJsMethodAnnotation
  get() = originalJsInfo.hasJsMemberAnnotation && originalJsInfo.jsMemberType == JsMemberType.METHOD

private val MethodDescriptor.hasJsConstructorAnnotation
  get() =
    originalJsInfo.hasJsMemberAnnotation && originalJsInfo.jsMemberType == JsMemberType.CONSTRUCTOR

private val MemberDescriptor.hasJsIgnoreAnnotation
  get() =
    // We need to reverse-engineering  here because JsInfo does not carry over the information about
    // JsIgnore annotation.
    // TODO(b/266614719): Rely on annotation presence instead when JsInterop annotations are part
    //  of the J2CL ast.
    enclosingTypeDescriptor.isJsType &&
      !enclosingTypeDescriptor.isNative &&
      ktVisibility == KtVisibility.PUBLIC &&
      originalJsInfo.jsMemberType == JsMemberType.NONE

private val FieldDescriptor.hasJsPropertyAnnotation
  get() = originalJsInfo.hasJsMemberAnnotation && isJsProperty
