/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.beam.sdk.extensions.sql.impl;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rel.RelNode;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rel.core.Aggregate;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rel.core.Project;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rex.RexCall;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rex.RexInputRef;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rex.RexNode;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Splitter;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Strings;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.joda.time.Duration;

/** Options parsed from {@code CREATE MATERIALIZED VIEW} DDL statements. */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class MaterializedViewOptions implements Serializable {
  private static final ThreadLocal<@Nullable MaterializedViewOptions> CURRENT = new ThreadLocal<>();

  private static final Pattern DURATION_PATTERN =
      Pattern.compile(
          "^(\\d+)\\s*(ms|millis|milliseconds|s|sec|seconds|m|min|minutes|h|hr|hours|d|day|days)?$",
          Pattern.CASE_INSENSITIVE);

  private final @Nullable Duration freshness;
  private final @Nullable Duration triggerDebounce;
  private final @Nullable Duration allowedLateness;
  private final long runEpoch;
  private final List<String> primaryKeys;
  private final String targetType;
  private final @Nullable String destinationTable;

  public MaterializedViewOptions(
      @Nullable Duration freshness,
      @Nullable Duration triggerDebounce,
      @Nullable Duration allowedLateness,
      long runEpoch,
      List<String> primaryKeys,
      String targetType,
      @Nullable String destinationTable) {
    this.freshness = freshness;
    this.triggerDebounce = triggerDebounce;
    this.allowedLateness = allowedLateness;
    this.runEpoch = runEpoch;
    this.primaryKeys = ImmutableList.copyOf(primaryKeys);
    this.targetType = targetType;
    this.destinationTable = destinationTable;
  }

  public static void set(@Nullable MaterializedViewOptions options) {
    CURRENT.set(options);
  }

  public static @Nullable MaterializedViewOptions get() {
    return CURRENT.get();
  }

  public static void clear() {
    CURRENT.remove();
  }

  public static MaterializedViewOptions fromMap(Map<String, String> options) {
    Duration freshness = parseDuration(options.get("freshness"));
    Duration triggerDebounce = parseDuration(options.get("trigger_debounce"));
    Duration allowedLateness = parseDuration(options.get("allowed_lateness"));

    long runEpoch = 1L;
    if (options.containsKey("run_epoch")) {
      try {
        runEpoch = Long.parseLong(options.get("run_epoch").trim());
      } catch (NumberFormatException e) {
        throw new IllegalArgumentException(
            "Invalid run_epoch value: " + options.get("run_epoch"), e);
      }
    }

    List<String> primaryKeys = new ArrayList<>();
    if (options.containsKey("primary_keys")) {
      primaryKeys =
          Splitter.on(',').trimResults().omitEmptyStrings().splitToList(options.get("primary_keys"));
    }

    String targetType = options.getOrDefault("target_type", "bigquery");
    String destinationTable = options.getOrDefault("table", options.get("location"));

    return new MaterializedViewOptions(
        freshness,
        triggerDebounce,
        allowedLateness,
        runEpoch,
        primaryKeys,
        targetType,
        destinationTable);
  }

  public static @Nullable Duration parseDuration(@Nullable String str) {
    if (Strings.isNullOrEmpty(str)) {
      return null;
    }
    Matcher matcher = DURATION_PATTERN.matcher(str.trim());
    if (!matcher.matches()) {
      throw new IllegalArgumentException("Unable to parse duration string: '" + str + "'");
    }
    long value = Long.parseLong(matcher.group(1));
    String unit = matcher.group(2);
    if (unit == null || unit.isEmpty() || unit.toLowerCase(Locale.ROOT).startsWith("s")) {
      return Duration.standardSeconds(value);
    }
    String u = unit.toLowerCase(Locale.ROOT);
    if (u.startsWith("ms")) {
      return Duration.millis(value);
    } else if (u.startsWith("m")) {
      return Duration.standardMinutes(value);
    } else if (u.startsWith("h")) {
      return Duration.standardHours(value);
    } else if (u.startsWith("d")) {
      return Duration.standardDays(value);
    }
    return Duration.standardSeconds(value);
  }

  public @Nullable Duration getFreshness() {
    return freshness;
  }

  public @Nullable Duration getTriggerDebounce() {
    return triggerDebounce;
  }

  public @Nullable Duration getAllowedLateness() {
    return allowedLateness;
  }

  public long getRunEpoch() {
    return runEpoch;
  }

  public List<String> getPrimaryKeys() {
    return primaryKeys;
  }

  public String getTargetType() {
    return targetType;
  }

  public @Nullable String getDestinationTable() {
    return destinationTable;
  }

  /**
   * Validates that all primary keys exist in the project row schema, and at least one primary key
   * column traces directly to a temporal window boundary operator (TUMBLE_START, TUMBLE_END,
   * HOP_START, or HOP_END).
   */
  public void validateRexNodeLineage(RelNode relNode) {
    if (primaryKeys.isEmpty()) {
      return;
    }

    List<String> fieldNames = relNode.getRowType().getFieldNames();
    for (String pk : primaryKeys) {
      if (!fieldNames.contains(pk)) {
        throw new IllegalArgumentException(
            String.format(
                "Primary key column '%s' does not exist in query output schema: %s",
                pk, fieldNames));
      }
    }

    boolean hasWindowBoundaryPk = false;
    for (String pk : primaryKeys) {
      int fieldIndex = fieldNames.indexOf(pk);
      if (tracesToWindowBoundary(relNode, fieldIndex)) {
        hasWindowBoundaryPk = true;
        break;
      }
    }

    if (!hasWindowBoundaryPk) {
      throw new IllegalArgumentException(
          String.format(
              "Materialized view primary keys %s must include at least one column tracing to a temporal window boundary operator (TUMBLE_START, TUMBLE_END, HOP_START, or HOP_END).",
              primaryKeys));
    }
  }

  private static boolean tracesToWindowBoundary(RelNode rel, int fieldIndex) {
    if (rel instanceof org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel) {
      org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel beamAgg =
          (org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel) rel;
      if (beamAgg.getWindowFieldIndex() >= 0 && fieldIndex == beamAgg.getWindowFieldIndex()) {
        return true;
      }
    }
    if (rel instanceof Project) {
      Project project = (Project) rel;
      if (fieldIndex >= 0 && fieldIndex < project.getProjects().size()) {
        RexNode expr = project.getProjects().get(fieldIndex);
        if (expr instanceof RexInputRef
            && project.getInput()
                instanceof org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel) {
          org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel beamAgg =
              (org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel) project.getInput();
          if (beamAgg.getWindowFieldIndex() >= 0
              && ((RexInputRef) expr).getIndex() == beamAgg.getWindowFieldIndex()) {
            return true;
          }
        }
        return isWindowBoundaryExpr(expr, project.getInput());
      }
    } else if (rel instanceof Aggregate) {
      Aggregate aggregate = (Aggregate) rel;
      if (fieldIndex >= 0 && fieldIndex < aggregate.getGroupSet().cardinality()) {
        int inputGroupIndex = aggregate.getGroupSet().asList().get(fieldIndex);
        return tracesToWindowBoundary(aggregate.getInput(), inputGroupIndex);
      }
    }
    for (RelNode input : rel.getInputs()) {
      if (fieldIndex < input.getRowType().getFieldCount()
          && tracesToWindowBoundary(input, fieldIndex)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isWindowBoundaryExpr(RexNode expr, @Nullable RelNode inputRel) {
    if (expr instanceof RexCall) {
      RexCall call = (RexCall) expr;
      String opName = call.getOperator().getName().toUpperCase(Locale.ROOT);
      if (opName.startsWith("TUMBLE")
          || opName.startsWith("HOP")
          || opName.startsWith("SESSION")
          || opName.contains("WINDOW")) {
        return true;
      }
      for (RexNode operand : call.getOperands()) {
        if (isWindowBoundaryExpr(operand, inputRel)) {
          return true;
        }
      }
    } else if (expr instanceof RexInputRef && inputRel != null) {
      RexInputRef ref = (RexInputRef) expr;
      return tracesToWindowBoundary(inputRel, ref.getIndex());
    }
    return false;
  }
}
