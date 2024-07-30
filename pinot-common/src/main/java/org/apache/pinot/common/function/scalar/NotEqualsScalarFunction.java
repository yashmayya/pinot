/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.common.function.scalar;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.pinot.common.function.FunctionInfo;
import org.apache.pinot.common.utils.DataSchema;
import org.apache.pinot.spi.annotations.ScalarFunction;

/**
 * Polymorphic notEquals scalar function implementation
 */
@ScalarFunction
public class NotEqualsScalarFunction extends PolymorphicComparisonScalarFunction {

  private static final Map<DataSchema.ColumnDataType, FunctionInfo> TYPE_FUNCTION_INFO_MAP = new HashMap<>();

  static {
    try {
      // Set nullable parameters to false for each function because the return value should be null if any argument
      // is null
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.INT, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("intNotEquals", int.class, int.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.LONG, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("longNotEquals", long.class, long.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.FLOAT, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("floatNotEquals", float.class, float.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.DOUBLE, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("doubleNotEquals", double.class, double.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.BIG_DECIMAL, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("bigDecimalNotEquals", BigDecimal.class, BigDecimal.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.STRING, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("stringNotEquals", String.class, String.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.BYTES, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("bytesNotEquals", byte[].class, byte[].class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.OBJECT, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("objectNotEquals", Object.class, Object.class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.INT_ARRAY, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("intArrayNotEquals", int[].class, int[].class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.LONG_ARRAY, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("longArrayNotEquals", long[].class, long[].class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.FLOAT_ARRAY, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("floatArrayNotEquals", float[].class, float[].class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.DOUBLE_ARRAY, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("doubleArrayNotEquals", double[].class, double[].class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.STRING_ARRAY, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("stringArrayNotEquals", String[].class, String[].class),
          NotEqualsScalarFunction.class, false));
      TYPE_FUNCTION_INFO_MAP.put(DataSchema.ColumnDataType.BYTES_ARRAY, new FunctionInfo(
          NotEqualsScalarFunction.class.getMethod("bytesArrayNotEquals", byte[][].class, byte[][].class),
          NotEqualsScalarFunction.class, false));
    } catch (NoSuchMethodException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  protected FunctionInfo functionInfoForType(DataSchema.ColumnDataType argumentType) {
    return TYPE_FUNCTION_INFO_MAP.get(argumentType);
  }

  @Override
  public String getName() {
    return "notEquals";
  }

  public static boolean intNotEquals(int a, int b) {
    return a != b;
  }

  public static boolean longNotEquals(long a, long b) {
    return a != b;
  }

  public static boolean floatNotEquals(float a, float b) {
    return Math.abs(a - b) >= DOUBLE_COMPARISON_TOLERANCE;
  }

  public static boolean doubleNotEquals(double a, double b) {
    return Math.abs(a - b) >= DOUBLE_COMPARISON_TOLERANCE;
  }

  public static boolean bigDecimalNotEquals(BigDecimal a, BigDecimal b) {
    return a.compareTo(b) != 0;
  }

  public static boolean stringNotEquals(String a, String b) {
    return !a.equals(b);
  }

  public static boolean bytesNotEquals(byte[] a, byte[] b) {
    return !Arrays.equals(a, b);
  }

  public static boolean objectNotEquals(Object a, Object b) {
    return !Objects.equals(a, b);
  }

  public static boolean intArrayNotEquals(int[] a, int[] b) {
    return !Arrays.equals(a, b);
  }

  public static boolean longArrayNotEquals(long[] a, long[] b) {
    return !Arrays.equals(a, b);
  }

  public static boolean floatArrayNotEquals(float[] a, float[] b) {
    return !Arrays.equals(a, b);
  }

  public static boolean doubleArrayNotEquals(double[] a, double[] b) {
    return !Arrays.equals(a, b);
  }

  public static boolean stringArrayNotEquals(String[] a, String[] b) {
    return !Arrays.equals(a, b);
  }

  public static boolean bytesArrayNotEquals(byte[][] a, byte[][]b) {
    return !Arrays.deepEquals(a, b);
  }
}
