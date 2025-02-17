/*
 * Copyright 2020 Google Inc.
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
package com.google.j2cl.transpiler.backend.wasm;

import static com.google.common.base.Predicates.not;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.lang.String.format;
import static java.util.Arrays.stream;

import com.google.common.collect.ImmutableList;
import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.OutputUtils.Output;
import com.google.j2cl.common.Problems;
import com.google.j2cl.common.SourcePosition;
import com.google.j2cl.common.StringUtils;
import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.ArrayLiteral;
import com.google.j2cl.transpiler.ast.ArrayTypeDescriptor;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Expression;
import com.google.j2cl.transpiler.ast.Field;
import com.google.j2cl.transpiler.ast.FieldDescriptor;
import com.google.j2cl.transpiler.ast.Library;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.NumberLiteral;
import com.google.j2cl.transpiler.ast.PrimitiveTypeDescriptor;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDeclaration;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.transpiler.backend.common.SourceBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Generates all the outputs for Wasm compilation. */
public class WasmOutputsGenerator {

  private final Problems problems;
  private final Output output;
  private SourceBuilder builder = new SourceBuilder();
  private final Path libraryInfoOutputPath;
  private WasmGenerationEnvironment environment;

  public WasmOutputsGenerator(Output output, Path libraryInfoOutputPath, Problems problems) {
    this.output = output;
    this.libraryInfoOutputPath = libraryInfoOutputPath;
    this.problems = problems;
  }

  public void generateModularOutput(Library library) {
    // TODO(b/283154838): Add the modular summaries and the library info output.
    if (libraryInfoOutputPath != null) {
      OutputUtils.writeToFile(libraryInfoOutputPath, new byte[0], problems);
    }
    environment =
        new WasmGenerationEnvironment(
            library, JsImportsGenerator.collectImports(library, problems));
    List<ArrayTypeDescriptor> usedNativeArrayTypes = collectUsedNativeArrayTypes(library);

    copyJavaSources(library);

    builder = new SourceBuilder();
    emitTypeDefinitions(library, usedNativeArrayTypes);
    outputIfNotEmpty("types.wat");

    builder = new SourceBuilder();
    emitForEachType(library, this::renderTypeMethods, "methods");
    outputIfNotEmpty("functions.wat");

    builder = new SourceBuilder();
    emitGlobals(library);
    outputIfNotEmpty("globals.wat");

    builder = new SourceBuilder();
    emitDataSegments(library);
    outputIfNotEmpty("data.wat");

    generateJsImportsFile();
  }

  private void outputIfNotEmpty(String path) {

    String content = builder.build();
    if (content.isEmpty()) {
      return;
    }

    output.write(path, content);
  }

  public void generateMonolithicOutput(Library library) {
    copyJavaSources(library);
    generateWasmModule(library);
    generateJsImportsFile();
  }

  private void copyJavaSources(Library library) {
    library.getCompilationUnits().stream()
        .filter(not(CompilationUnit::isSynthetic))
        .forEach(
            compilationUnit ->
                output.copyFile(
                    compilationUnit.getFilePath(), compilationUnit.getPackageRelativePath()));
  }

  private void generateWasmModule(Library library) {
    environment =
        new WasmGenerationEnvironment(
            library, JsImportsGenerator.collectImports(library, problems));
    List<ArrayTypeDescriptor> usedNativeArrayTypes = collectUsedNativeArrayTypes(library);

    builder.appendln(";;; Code generated by J2WASM");
    builder.append("(module");
    // Emit all types at the beginning of the module.
    emitLibraryRecGroup(library, usedNativeArrayTypes);

    // Declare a tag that will be used for Java exceptions. The tag has a single parameter that is
    // the Throwable object being thrown by the throw instruction.
    // The throw instruction will refer to this tag and will expect a single element in the stack
    // with the type $java.lang.Throwable.
    // TODO(b/277970998): Decide how to handle this hard coded import w.r.t. import generation.
    builder.newLine();
    builder.append(
        "(import \"imports\" \"j2wasm.ExceptionUtils.tag\" (tag $exception.event (param"
            + " externref)))");
    // Add an export that uses the tag to workarund binaryen assuming the tag is never instantiated.
    builder.append(
        "(func $keep_tag_alive_hack (export \"_tag_hack_\") (param $param externref)  "
            + "(throw $exception.event (local.get $param)))");

    // Emit all the globals, e.g. vtable instances, etc.
    emitDataSegments(library);
    emitDispatchTablesInitialization(library);
    emitEmptyArraySingletons(usedNativeArrayTypes);
    emitGlobals(library);

    // Emit intrinsics imports
    emitImportsForBinaryenIntrinsics(library);

    // Last, emit all methods at the very end so that the synthetic code generated above does
    // not inherit an incorrect source position.
    emitForEachType(library, this::renderTypeMethods, "methods");

    builder.newLine();
    builder.append(")");
    output.write("module.wat", builder.build());
  }

  private void emitDataSegments(Library library) {
    library.accept(
        new AbstractVisitor() {
          @Override
          public void exitArrayLiteral(ArrayLiteral arrayLiteral) {
            if (canBeMovedToDataSegment(arrayLiteral)
                && environment.registerDataSegmentLiteral(
                    arrayLiteral, getCurrentType().getDeclaration().getQualifiedBinaryName())) {
              var dataElementName = environment.getDataElementNameForLiteral(arrayLiteral);
              builder.newLine();
              builder.append(
                  format("(data %s \"%s\")", dataElementName, toDataString(arrayLiteral)));
            }
          }
        });
  }

  private boolean canBeMovedToDataSegment(ArrayLiteral arrayLiteral) {
    return TypeDescriptors.isNonVoidPrimitiveType(
            arrayLiteral.getTypeDescriptor().getComponentTypeDescriptor())
        && arrayLiteral.getValueExpressions().stream().allMatch(NumberLiteral.class::isInstance);
  }

  /**
   * Encodes an array literal of primitive values as a sequence of bytes, in UTF8 encoding for
   * readability.
   */
  private String toDataString(ArrayLiteral arrayLiteral) {
    PrimitiveTypeDescriptor componentTypeDescriptor =
        (PrimitiveTypeDescriptor) arrayLiteral.getTypeDescriptor().getComponentTypeDescriptor();
    int sizeInBits = componentTypeDescriptor.getWidth();
    List<Expression> valueExpressions = arrayLiteral.getValueExpressions();

    // Preallocate the stringbuilder to hold the encoded data since its size its already known.
    StringBuilder sb = new StringBuilder(valueExpressions.size() * (sizeInBits / 8));
    for (Expression expression : valueExpressions) {
      NumberLiteral literal = (NumberLiteral) expression;
      long value;
      PrimitiveTypeDescriptor typeDescriptor = literal.getTypeDescriptor();
      if (TypeDescriptors.isPrimitiveFloat(typeDescriptor)) {
        value = Float.floatToRawIntBits(literal.getValue().floatValue());
      } else if (TypeDescriptors.isPrimitiveDouble(typeDescriptor)) {
        value = Double.doubleToRawLongBits(literal.getValue().doubleValue());
      } else {
        value = literal.getValue().longValue();
      }

      for (int s = sizeInBits; s > 0; s -= 8, value >>>= 8) {
        sb.append(StringUtils.escapeAsUtf8((int) (value & 0xFF)));
      }
    }
    return sb.toString();
  }

  /** Emits all wasm type definitions into a single rec group. */
  private void emitLibraryRecGroup(
      Library library, List<ArrayTypeDescriptor> usedNativeArrayTypes) {
    builder.newLine();
    builder.append("(rec");
    builder.indent();

    emitTypeDefinitions(library, usedNativeArrayTypes);

    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  private void emitTypeDefinitions(
      Library library, List<ArrayTypeDescriptor> usedNativeArrayTypes) {
    emitDynamicDispatchMethodTypes(library);
    emitItableSupportTypes();
    emitNativeArrayTypes(usedNativeArrayTypes);
    emitForEachType(library, this::renderTypeStructs, "type definition");
  }

  private void emitItableSupportTypes() {
    builder.newLine();
    // The itable is a struct that contains only interface vtables. Interfaces are assigned a slot
    // on this struct based on the classes that implement them.
    builder.append("(type $itable (sub (struct ");
    for (int slot = 0; slot < environment.getNumberOfInterfaceSlots(); slot++) {
      builder.newLine();
      builder.append(
          format("(field %s (ref null struct))", environment.getInterfaceSlotFieldName(slot)));
    }
    builder.newLine();
    builder.append(")))");
  }

  private void emitGlobals(Library library) {
    emitStaticFieldGlobals(library);
  }

  /** Emit the type for all function signatures that will be needed to reference vtable methods. */
  private void emitDynamicDispatchMethodTypes(Library library) {
    Set<String> emittedFunctionTypeNames = new HashSet<>();
    library
        .streamTypes()
        .flatMap(t -> t.getMethods().stream())
        .map(Method::getDescriptor)
        .filter(MethodDescriptor::isPolymorphic)
        .forEach(m -> emitFunctionType(emittedFunctionTypeNames, m));
  }

  private void emitFunctionType(Set<String> emittedFunctionTypeNames, MethodDescriptor m) {
    String typeName = environment.getFunctionTypeName(m);
    if (!emittedFunctionTypeNames.add(typeName)) {
      return;
    }
    builder.newLine();
    builder.append(format("(type %s (func", typeName));
    emitFunctionParameterTypes(m);
    emitFunctionResultType(m);
    builder.append("))");
  }

  /**
   * Emit the necessary imports of binaryen intrinsics.
   *
   * <p>In order to communicate information to binaryen, binaryen provides intrinsic methods that
   * need to be imported.
   */
  private void emitImportsForBinaryenIntrinsics(Library library) {

    // Emit the intrinsic for calls with no side effects. The intrinsic method exported name is
    // "call.without.effects" and can be used to convey to binaryen that a certain function call
    // does not have side effects.
    // Since the mechanism itself is a call, it needs to be correctly typed. As a result for each
    // different function type that appears in the AST as part of no-side-effect call, an import
    // with the right function type definition needs to be created.
    Set<String> emittedFunctionTypeNames = new HashSet<>();
    library
        .streamTypes()
        .flatMap(t -> t.getMethods().stream())
        .map(Method::getDescriptor)
        .filter(MethodDescriptor::isSideEffectFree)
        .forEach(m -> emitBinaryenIntrinsicImport(emittedFunctionTypeNames, m));
  }

  private void emitBinaryenIntrinsicImport(
      Set<String> emittedFunctionTypeNames, MethodDescriptor m) {
    String typeName = environment.getNoSideEffectWrapperFunctionName(m);
    if (!emittedFunctionTypeNames.add(typeName)) {
      return;
    }
    builder.newLine();
    builder.append(
        format(
            "(import \"binaryen-intrinsics\" \"call.without.effects\" " + "(func %s ", typeName));
    emitFunctionParameterTypes(m);
    builder.append(" (param funcref)");
    emitFunctionResultType(m);
    builder.append("))");
  }

  private void emitFunctionParameterTypes(MethodDescriptor methodDescriptor) {
    if (!methodDescriptor.isStatic()) {
      // Add the implicit parameter
      builder.append(
          format(
              " (param (ref %s))",
              environment.getWasmTypeName(TypeDescriptors.get().javaLangObject)));
    }
    methodDescriptor
        .getDispatchParameterTypeDescriptors()
        .forEach(t -> builder.append(format(" (param %s)", environment.getWasmType(t))));
  }

  private void emitFunctionResultType(MethodDescriptor methodDescriptor) {
    TypeDescriptor returnTypeDescriptor = methodDescriptor.getDispatchReturnTypeDescriptor();
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.append(format(" (result %s)", environment.getWasmType(returnTypeDescriptor)));
    }
  }

  private void renderTypeStructs(Type type) {
    if (type.isNative() || type.getDeclaration().getWasmInfo() != null) {
      return;
    }
    if (type.isInterface()) {
      // Interfaces at runtime are treated as java.lang.Object.
      renderInterfaceVtableStruct(type);
    } else {
      renderTypeStruct(type);
      renderClassVtableStruct(type);
      renderClassItableStruct(type);
    }
  }

  private void renderClassItableStruct(Type type) {
    TypeDeclaration typeDeclaration = type.getDeclaration();
    if (!typeDeclaration.implementsInterfaces()) {
      return;
    }
    emitItableType(typeDeclaration, getItableSlots(typeDeclaration));
  }

  /** Renders the struct for the vtable of a class. */
  private void renderClassVtableStruct(Type type) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(type.getDeclaration());
    renderVtableStruct(type, wasmTypeLayout.getAllPolymorphicMethods());
  }

  /**
   * Renders the struct for the vtable of an interface.
   *
   * <p>There is a vtable for each interface, and it consists of fields only for the methods
   * declared in that interface (not including methods declared in their supers). Calls to interface
   * methods will always point to an interface that declared them.
   */
  private void renderInterfaceVtableStruct(Type type) {
    // TODO(b/186472671): centralize all concepts related to layout in WasmTypeLayout, including
    // interface vtables and slot assignments.
    renderVtableStruct(
        type,
        type.getDeclaration().getDeclaredMethodDescriptors().stream()
            .filter(MethodDescriptor::isPolymorphic)
            .collect(Collectors.toList()));
  }

  private void renderVtableStruct(Type type, Collection<MethodDescriptor> methods) {
    emitWasmStruct(type, environment::getWasmVtableTypeName, () -> renderVtableEntries(methods));
  }

  private void renderVtableEntries(Collection<MethodDescriptor> methodDescriptors) {
    methodDescriptors.forEach(
        m -> {
          builder.newLine();
          builder.append(
              format(
                  "(field $%s (ref %s))", m.getMangledName(), environment.getFunctionTypeName(m)));
        });
  }

  private void emitStaticFieldGlobals(Library library) {
    library.streamTypes().forEach(this::emitStaticFieldGlobals);
  }

  private void emitStaticFieldGlobals(Type type) {
    emitBeginCodeComment(type, "static fields");
    for (Field field : type.getStaticFields()) {
      builder.newLine();
      builder.append("(global " + environment.getFieldName(field));

      if (field.isCompileTimeConstant()) {
        builder.append(
            format(" %s ", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        ExpressionTranspiler.render(field.getInitializer(), builder, environment);
      } else {
        builder.append(
            format(
                " (mut %s) ", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        ExpressionTranspiler.render(
            field.getDescriptor().getTypeDescriptor().getDefaultValue(), builder, environment);
      }

      builder.append(")");
    }
    emitEndCodeComment(type, "static fields");
  }

  private void renderTypeMethods(Type type) {
    type.getMethods().stream()
        .filter(method -> !method.isAbstract() || method.isNative())
        .filter(m -> m.getDescriptor().getWasmInfo() == null)
        .forEach(this::renderMethod);
  }

  public void generateMethods(List<Method> methods) {
    if (methods.isEmpty()) {
      return;
    }
    // Create the type objects and add all the exported methods to the corresponding type to
    // initialize the WasmGenerationEnvironment.
    CompilationUnit cu = CompilationUnit.createSynthetic("wasm.exports");
    Map<TypeDeclaration, Type> typesByDeclaration = new LinkedHashMap<>();
    methods.forEach(
        m -> {
          TypeDeclaration typeDeclaration =
              m.getDescriptor().getEnclosingTypeDescriptor().getTypeDeclaration();
          Type type =
              typesByDeclaration.computeIfAbsent(
                  typeDeclaration, t -> new Type(SourcePosition.NONE, t));
          type.addMember(m);
        });
    typesByDeclaration.values().forEach(cu::addType);
    Library library = Library.newBuilder().setCompilationUnits(ImmutableList.of(cu)).build();
    environment =
        new WasmGenerationEnvironment(
            library, JsImportsGenerator.collectImports(library, problems));

    methods.stream().forEach(this::renderMethod);
    output.write("functions.wat", builder.build());
  }

  private void renderMethod(Method method) {
    MethodDescriptor methodDescriptor = method.getDescriptor();
    // TODO(b/264676817): Consider refactoring to have MethodDescriptor.isNative return true for
    // native constructors, or exposing isNativeConstructor from MethodDescriptor.
    boolean isNativeConstructor =
        methodDescriptor.getEnclosingTypeDescriptor().isNative()
            && methodDescriptor.isConstructor();
    JsMethodImport jsMethodImport = environment.getJsMethodImport(methodDescriptor);
    if (jsMethodImport == null && isNativeConstructor) {
      // TODO(b/279187295): These are implicit constructors of native types that don't really exist,
      // remove this check once they are removed from the AST.
      return;
    }
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + method.getReadableDescription());
    builder.newLine();
    builder.append("(func " + environment.getMethodImplementationName(methodDescriptor));

    // Generate an import if the method is native. We don't use the normal qualified js name,
    // because it doesn't differentiate between js property getters and setters.
    if (jsMethodImport != null) {
      builder.append(
          format(
              " (import \"%s\" \"%s\") ",
              JsImportsGenerator.MODULE, jsMethodImport.getImportKey()));
    }

    if (method.isWasmEntryPoint()) {
      builder.append(" (export \"" + method.getWasmExportName() + "\")");
    }

    DeclaredTypeDescriptor enclosingTypeDescriptor = methodDescriptor.getEnclosingTypeDescriptor();

    // Emit parameters
    builder.indent();
    // Add the implicit "this" parameter to instance methods and constructors.
    // Note that constructors and private methods can declare the parameter type to be the
    // enclosing type because they are not overridden but normal instance methods have to
    // declare the parameter more generically as java.lang.Object, since all the overrides need
    // to have matching signatures.
    if (methodDescriptor.isClassDynamicDispatch()
        && !methodDescriptor.isNative()
        && !methodDescriptor.isJsOverlay()) {
      builder.newLine();
      builder.append(format("(type %s)", environment.getFunctionTypeName(methodDescriptor)));
      builder.newLine();
      builder.append(
          format(
              "(param $this.untyped (ref %s))",
              environment.getWasmTypeName(TypeDescriptors.get().javaLangObject)));
    } else if (!method.isStatic() && !isNativeConstructor) {
      // Private methods and constructors receive the instance with the actual type.
      // Native constructors do not receive the instance.
      builder.newLine();
      builder.append(format("(param $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
    }

    for (Variable parameter : method.getParameters()) {
      builder.newLine();
      builder.append(
          "(param "
              + environment.getDeclarationName(parameter)
              + " "
              + environment.getWasmType(parameter.getTypeDescriptor())
              + ")");
    }

    TypeDescriptor returnTypeDescriptor = methodDescriptor.getDispatchReturnTypeDescriptor();

    // Emit return type.
    if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.newLine();
      builder.append("(result " + environment.getWasmType(returnTypeDescriptor) + ")");
    }

    if (jsMethodImport != null) {
      // Imports don't define locals nor body.
      builder.unindent();
      builder.newLine();
      builder.append(")");
      return;
    }

    // Emit a source mapping at the entry of a method so that when stepping into a method
    // the debugger shows the right source line.
    StatementTranspiler.renderSourceMappingComment(method.getSourcePosition(), builder);

    // Emit locals.
    for (Variable variable : collectLocals(method)) {
      builder.newLine();
      builder.append(
          "(local "
              + environment.getDeclarationName(variable)
              + " "
              + environment.getWasmType(variable.getTypeDescriptor())
              + ")");
    }
    // Introduce the actual $this variable for polymorphic methods and cast the parameter to
    // the right type.
    if (methodDescriptor.isClassDynamicDispatch() && !methodDescriptor.isJsOverlay()) {
      builder.newLine();
      builder.append(format("(local $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
      builder.newLine();
      // Use non-nullable `ref.cast` since the receiver of an instance method should
      // not be null, and it is ok to fail for devirtualized methods if it is.
      builder.append(
          format(
              "(local.set $this (ref.cast (ref %s) (local.get $this.untyped)))",
              environment.getWasmTypeName(enclosingTypeDescriptor)));
    }

    StatementTranspiler.render(method.getBody(), builder, environment);
    builder.unindent();
    builder.newLine();
    builder.append(")");

    // Declare a function that will be target of dynamic dispatch.
    if (methodDescriptor.isPolymorphic()) {
      builder.newLine();
      builder.append(
          format(
              "(elem declare func %s)",
              environment.getMethodImplementationName(method.getDescriptor())));
    }
  }

  private static List<Variable> collectLocals(Method method) {
    List<Variable> locals = new ArrayList<>();
    method
        .getBody()
        .accept(
            new AbstractVisitor() {
              @Override
              public void exitVariable(Variable variable) {
                locals.add(variable);
              }
            });
    return locals;
  }

  private void renderTypeStruct(Type type) {
    emitWasmStruct(type, environment::getWasmTypeName, () -> renderTypeFields(type));
  }

  private void renderTypeFields(Type type) {
    // The first field is always the vtable for class dynamic dispatch.
    builder.newLine();
    builder.append(
        format(
            "(field $vtable (ref %s))",
            environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    // The second field is always the itable for interface method dispatch.
    builder.newLine();
    builder.append(
        format(
            "(field $itable (ref %s))", environment.getWasmItableTypeName(type.getDeclaration())));

    WasmTypeLayout wasmType = environment.getWasmTypeLayout(type.getDeclaration());
    for (FieldDescriptor fieldDescriptor : wasmType.getAllInstanceFields()) {
      builder.newLine();
      String fieldType = environment.getWasmFieldType(fieldDescriptor.getTypeDescriptor());

      // TODO(b/296475021): Cleanup the handling of the arrays elements field.
      if (!environment.isWasmArrayElementsField(fieldDescriptor)) {
        fieldType = format("(mut %s)", fieldType);
      }
      builder.append(format("(field %s %s)", environment.getFieldName(fieldDescriptor), fieldType));
    }
  }

  /**
   * Emit a function that will be used to initialize the runtime at module instantiation time;
   * together with the required type definitions.
   */
  private void emitDispatchTablesInitialization(Library library) {
    builder.newLine();
    // TODO(b/183994530): Initialize dynamic dispatch tables lazily.
    builder.append(";;; Initialize dynamic dispatch tables.");

    // Emit an empty itable that will be used for types that don't implement any interface.
    builder.newLine();
    builder.append("(global $itable.empty (ref $itable) (struct.new_default $itable))");

    // Populate all vtables.
    library
        .streamTypes()
        .filter(not(Type::isInterface))
        .filter(not(Type::isNative))
        .map(Type::getDeclaration)
        .filter(not(TypeDeclaration::isAbstract))
        .filter(type -> type.getWasmInfo() == null)
        .forEach(this::emitDispatchTablesInitialization);
    builder.newLine();
  }

  private void emitDispatchTablesInitialization(TypeDeclaration typeDeclaration) {
    emitClassVtableInitialization(typeDeclaration);
    emitItableInitialization(typeDeclaration);
  }

  /** Emits the code to initialize the class vtable structure for {@code typeDeclaration}. */
  private void emitClassVtableInitialization(TypeDeclaration typeDeclaration) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);

    emitBeginCodeComment(typeDeclaration, "vtable.init");
    builder.newLine();
    //  Create the class vtable for this type (which is either a class or an enum) and store it
    // in a global variable to be able to use it to initialize instance of this class.
    builder.append(
        format(
            "(global %s (ref %s)",
            environment.getWasmVtableGlobalName(typeDeclaration),
            environment.getWasmVtableTypeName(typeDeclaration)));
    builder.indent();
    emitVtableInitialization(typeDeclaration, wasmTypeLayout.getAllPolymorphicMethods());
    builder.unindent();
    builder.newLine();
    builder.append(")");
    emitEndCodeComment(typeDeclaration, "vtable.init");
  }

  /** Emits the code to initialize the Itable array for {@code typeDeclaration}. */
  private void emitItableInitialization(TypeDeclaration typeDeclaration) {
    if (!typeDeclaration.implementsInterfaces()) {
      return;
    }
    emitBeginCodeComment(typeDeclaration, "itable.init");

    // Create the struct of interface vtables of the required size and store it in a global variable
    // to be able to use it when objects of this class are instantiated.
    builder.newLine();
    builder.append(
        format(
            "(global %s (ref %s) (struct.new %s",
            environment.getWasmItableGlobalName(typeDeclaration),
            environment.getWasmItableTypeName(typeDeclaration),
            environment.getWasmItableTypeName(typeDeclaration)));
    builder.indent();
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);
    stream(getItableSlots(typeDeclaration))
        .forEach(i -> initializeInterfaceVtable(wasmTypeLayout, i));
    builder.newLine();
    builder.append("))");
    builder.unindent();
    emitEndCodeComment(typeDeclaration, "itable.init");
  }

  private TypeDeclaration[] getItableSlots(TypeDeclaration typeDeclaration) {
    ImmutableList<TypeDeclaration> superInterfaces =
        typeDeclaration.getAllSuperTypesIncludingSelf().stream()
            .filter(TypeDeclaration::isInterface)
            .collect(toImmutableList());

    // Compute the itable for this type.
    int numSlots = environment.getNumberOfInterfaceSlots();
    TypeDeclaration[] itableSlots = new TypeDeclaration[numSlots];
    superInterfaces.forEach(
        superInterface ->
            itableSlots[environment.getInterfaceSlot(superInterface)] = superInterface);
    return itableSlots;
  }

  /** Emits a specialized itable type for this type to allow for better optimizations. */
  private void emitItableType(TypeDeclaration typeDeclaration, TypeDeclaration[] itableSlots) {
    // Create the specialized struct for the itable for this type. A specialized itable type will
    // be a subtype of the specialized itable type for its superclass. Note that the struct fields
    // get incrementally specialized in this struct in the subclasses as the interfaces are
    // implemented by them.
    builder.newLine();
    builder.append(
        format(
            "(type %s (sub %s (struct",
            environment.getWasmItableTypeName(typeDeclaration),
            environment.getWasmItableTypeName(typeDeclaration.getSuperTypeDeclaration())));
    for (int slot = 0; slot < environment.getNumberOfInterfaceSlots(); slot++) {
      builder.newLine();
      builder.append(format("(field %s ", environment.getInterfaceSlotFieldName(slot)));
      if (itableSlots[slot] == null) {
        // This type does not use the struct, so it is kept at the generic struct type.
        builder.append("(ref null struct))");
      } else {
        builder.append(format("(ref %s))", environment.getWasmVtableTypeName(itableSlots[slot])));
      }
    }
    builder.newLine();
    builder.append(")))");
  }

  private void initializeInterfaceVtable(
      WasmTypeLayout wasmTypeLayout, TypeDeclaration interfaceDeclaration) {
    if (interfaceDeclaration == null) {
      builder.newLine();
      builder.append(" (ref.null struct)");
      return;
    }
    ImmutableList<MethodDescriptor> interfaceMethodImplementations =
        interfaceDeclaration.getDeclaredMethodDescriptors().stream()
            .filter(MethodDescriptor::isPolymorphic)
            .map(wasmTypeLayout::getImplementationMethod)
            .collect(toImmutableList());
    emitVtableInitialization(interfaceDeclaration, interfaceMethodImplementations);
  }

  /**
   * Creates and initializes the vtable for {@code implementedType} with the methods in {@code
   * methodDescriptors}.
   *
   * <p>This is used to initialize both class vtables and interface vtables. Each concrete class
   * will have a class vtable to implement the dynamic class method dispatch and one vtable for each
   * interface it implements to implement interface dispatch.
   */
  private void emitVtableInitialization(
      TypeDeclaration implementedType, Collection<MethodDescriptor> methodDescriptors) {
    builder.newLine();
    // Create an instance of the vtable for the type initializing it with the methods that are
    // passed in methodDescriptors.
    builder.append(format("(struct.new %s", environment.getWasmVtableTypeName(implementedType)));

    builder.indent();
    methodDescriptors.forEach(
        m -> {
          builder.newLine();
          builder.append(format("(ref.func %s)", environment.getMethodImplementationName(m)));
        });
    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  /** Emits a Wasm struct using nominal inheritance. */
  private void emitWasmStruct(
      Type type, Function<DeclaredTypeDescriptor, String> structNamer, Runnable fieldsRenderer) {
    boolean hasSuperType = type.getSuperTypeDescriptor() != null;
    builder.newLine();
    builder.append(String.format("(type %s (sub ", structNamer.apply(type.getTypeDescriptor())));
    if (hasSuperType) {
      builder.append(format("%s ", structNamer.apply(type.getSuperTypeDescriptor())));
    }
    builder.append("(struct");
    builder.indent();
    fieldsRenderer.run();

    builder.newLine();
    builder.append(")");
    builder.append(")");
    builder.unindent();
    builder.newLine();
    builder.append(")");
  }

  private List<ArrayTypeDescriptor> collectUsedNativeArrayTypes(Library library) {
    Set<ArrayTypeDescriptor> usedArrayTypes = new LinkedHashSet<>();
    library.accept(
        new AbstractVisitor() {
          @Override
          public void exitField(Field field) {
            TypeDescriptor typeDescriptor = field.getDescriptor().getTypeDescriptor();
            if (!typeDescriptor.isArray()) {
              return;
            }

            ArrayTypeDescriptor arrayTypeDescriptor = (ArrayTypeDescriptor) typeDescriptor;
            if (arrayTypeDescriptor.isNativeWasmArray()) {
              usedArrayTypes.add(arrayTypeDescriptor);
            }
          }
        });

    return new ArrayList<>(usedArrayTypes);
  }

  private void emitNativeArrayTypes(List<ArrayTypeDescriptor> arrayTypes) {
    emitBeginCodeComment("Native Array types");
    arrayTypes.forEach(this::emitNativeArrayType);
    emitEndCodeComment("Native Array types");
  }

  private void emitNativeArrayType(ArrayTypeDescriptor arrayTypeDescriptor) {
    String wasmArrayTypeName = environment.getWasmTypeName(arrayTypeDescriptor);
    builder.newLine();
    builder.append(
        format(
            "(type %s (array (mut %s)))",
            wasmArrayTypeName,
            environment.getWasmFieldType(arrayTypeDescriptor.getComponentTypeDescriptor())));
  }

  private void emitEmptyArraySingletons(List<ArrayTypeDescriptor> arrayTypes) {
    emitBeginCodeComment("Empty array singletons");
    arrayTypes.forEach(this::emitEmptyArraySingleton);
    emitEndCodeComment("Empty array singletons");
  }

  private void emitEmptyArraySingleton(ArrayTypeDescriptor arrayTypeDescriptor) {
    String wasmArrayTypeName = environment.getWasmTypeName(arrayTypeDescriptor);
    // Emit a global empty array singleton to avoid allocating empty arrays. */
    builder.newLine();
    builder.append(
        format(
            "(global %s (ref %s) (array.new_default %s (i32.const 0)))",
            environment.getWasmEmptyArrayGlobalName(arrayTypeDescriptor),
            wasmArrayTypeName,
            wasmArrayTypeName));
    builder.newLine();
  }

  /**
   * Iterate through all the types in the library, supertypes first, calling the emitter for each of
   * them.
   */
  private void emitForEachType(Library library, Consumer<Type> emitter, String comment) {
    library
        .streamTypes()
        // Emit the types supertypes first.
        .sorted(Comparator.comparing(t -> t.getDeclaration().getClassHierarchyDepth()))
        .forEach(
            type -> {
              emitBeginCodeComment(type, comment);
              emitter.accept(type);
              emitEndCodeComment(type, comment);
            });
  }

  private void emitBeginCodeComment(Type type, String section) {
    emitBeginCodeComment(type.getDeclaration(), section);
  }

  private void emitBeginCodeComment(TypeDeclaration typeDeclaration, String section) {
    emitBeginCodeComment(format("%s [%s]", typeDeclaration.getQualifiedSourceName(), section));
  }

  private void emitBeginCodeComment(String commentId) {
    builder.newLine();
    builder.append(";;; Code for " + commentId);
  }

  private void emitEndCodeComment(Type type, String section) {
    emitEndCodeComment(type.getDeclaration(), section);
  }

  private void emitEndCodeComment(TypeDeclaration typeDeclaration, String section) {
    emitEndCodeComment(format("%s [%s]", typeDeclaration.getQualifiedSourceName(), section));
  }

  private void emitEndCodeComment(String commentId) {
    builder.newLine();
    builder.append(";;; End of code for " + commentId);
  }

  private void generateJsImportsFile() {
    JsImportsGenerator.generateOutputs(output, environment.getJsImports());
  }
}
