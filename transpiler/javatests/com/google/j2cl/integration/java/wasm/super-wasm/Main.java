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
package wasm;

import static com.google.j2cl.integration.testing.Asserts.assertEquals;
import static com.google.j2cl.integration.testing.Asserts.assertFalse;
import static com.google.j2cl.integration.testing.Asserts.assertTrue;

import javaemul.internal.annotations.Wasm;

/**
 * Incrementally tests wasm features as they are being added.
 *
 * <p>This test will be removed when all Wasm features are implemented and all integration tests are
 * enabled for Wasm.
 */
public class Main {

  public static void main(String... args) throws Exception {
    testWasmAnnotation();
    testClassLiterals();
    testArrayInstanceOf();
    testArrayGetClass();
  }

  private static void testWasmAnnotation() {
    assertTrue(42 == multiply(6, 7));
  }

  @Wasm("i32.mul")
  private static native int multiply(int x, int y);

  private static class SomeClass {}

  private interface SomeInterface {}

  private enum SomeEnum {
    A
  }

  private static void testClassLiterals() {
    assertEquals("wasm.Main$SomeClass", SomeClass.class.getName());
    assertEquals(Object.class, SomeClass.class.getSuperclass());
    assertFalse(SomeClass.class.isEnum());
    assertFalse(SomeClass.class.isInterface());
    assertFalse(SomeClass.class.isArray());
    assertFalse(SomeClass.class.isPrimitive());
    assertEquals(
        "wasm.Main$SomeInterface", SomeInterface.class.getName());
    assertEquals(null, SomeInterface.class.getSuperclass());
    assertFalse(SomeInterface.class.isEnum());
    assertTrue(SomeInterface.class.isInterface());
    assertFalse(SomeInterface.class.isArray());
    assertFalse(SomeInterface.class.isPrimitive());
    assertEquals("wasm.Main$SomeEnum", SomeEnum.class.getName());
    assertEquals(Enum.class, SomeEnum.class.getSuperclass());
    assertTrue(SomeEnum.class.isEnum());
    assertFalse(SomeEnum.class.isInterface());
    assertFalse(SomeEnum.class.isArray());
    assertFalse(SomeEnum.class.isPrimitive());
    assertEquals("int", int.class.getName());
    assertEquals(null, int.class.getSuperclass());
    assertFalse(int.class.isEnum());
    assertFalse(int.class.isInterface());
    assertFalse(int.class.isArray());
    assertTrue(int.class.isPrimitive());
    assertEquals(int.class, int[].class.getComponentType());
    assertFalse(int[].class.isEnum());
    assertFalse(int[].class.isInterface());
    assertTrue(int[].class.isArray());
    assertFalse(int[].class.isPrimitive());
  }

  private static void testArrayInstanceOf() {
    Object intArray = new int[0];
    assertTrue(intArray instanceof int[]);
    assertFalse(intArray instanceof long[]);
    assertFalse(intArray instanceof Object[]);
    assertFalse(intArray instanceof SomeInterface[]);

    Object multiDimIntArray = new int[0][0];
    assertFalse(multiDimIntArray instanceof int[]);
    assertFalse(multiDimIntArray instanceof long[]);
    assertTrue(multiDimIntArray instanceof Object[]);
    assertTrue(multiDimIntArray instanceof int[][]);
    // TODO(b/184675805): enable when this is fixed.
    // assertFalse(multiDimIntArray instanceof long[][]);
    // assertFalse(multiDimIntArray instanceof SomeInterface[]);

    Object objectArray = new Object[0];
    assertFalse(objectArray instanceof int[]);
    assertFalse(objectArray instanceof long[]);
    assertTrue(objectArray instanceof Object[]);
    // TODO(b/184675805): enable when this is fixed.
    // assertFalse(objectArray instanceof int[][]);
    // assertFalse(objectArray instanceof long[][]);
    // assertFalse(objectArray instanceof SomeInterface[]);

    Object multiDimObjectArray = new Object[0][0];
    assertFalse(multiDimObjectArray instanceof int[]);
    assertFalse(multiDimObjectArray instanceof long[]);
    assertTrue(multiDimObjectArray instanceof Object[]);
    assertTrue(multiDimObjectArray instanceof Object[][]);
    // TODO(b/184675805): enable when this is fixed.
    // assertFalse(multiDimObjectArray instanceof int[][]);
    // assertFalse(multiDimObjectArray instanceof long[][]);
    // assertFalse(multiDimObjectArray instanceof SomeInterface[]);

    Object referencetArray = new SomeInterface[0];
    assertFalse(referencetArray instanceof int[]);
    assertFalse(referencetArray instanceof long[]);
    assertTrue(referencetArray instanceof Object[]);
    assertTrue(referencetArray instanceof SomeInterface[]);
    // TODO(b/184675805): enable when this is fixed.
    // assertFalse(referencetArray instanceof Object[][]);
    // assertFalse(referencetArray instanceof int[][]);
    // assertFalse(referencetArray instanceof long[][]);
  }

  private static void testArrayGetClass() {
    Object intArray = new int[0];
    assertEquals(int[].class, intArray.getClass());
    assertEquals(int.class, intArray.getClass().getComponentType());

    Object objectArray = new Object[0];
    assertEquals(Object[].class, objectArray.getClass());
    assertEquals(Object.class, objectArray.getClass().getComponentType());
  }
}
