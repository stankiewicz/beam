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
package org.apache.beam.sdk.extensions.sql.meta.provider.bigquery;

import java.io.IOException;
import java.io.Serializable;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import org.apache.beam.sdk.extensions.sql.impl.BeamTableStatistics;
import org.apache.beam.sdk.extensions.sql.meta.BeamSqlTableFilter;
import org.apache.beam.sdk.extensions.sql.meta.DefaultTableFilter;
import org.apache.beam.sdk.extensions.sql.meta.ProjectSupport;
import org.apache.beam.sdk.extensions.sql.meta.SchemaBaseBeamTable;
import org.apache.beam.sdk.extensions.sql.meta.Table;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryHelpers;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.TypedRead;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.TypedRead.Method;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO.Write.WriteDisposition;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryOptions;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryUtils;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryUtils.ConversionOptions;
import org.apache.beam.sdk.io.gcp.bigquery.providers.BigQueryStorageWriteApiSchemaTransformProvider;
import org.apache.beam.sdk.io.gcp.bigquery.providers.BigQueryStorageWriteApiSchemaTransformProvider.BigQueryWriteConfiguration;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.schemas.FieldAccessDescriptor;
import org.apache.beam.sdk.schemas.NoSuchSchemaException;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.SchemaCoder;
import org.apache.beam.sdk.schemas.SchemaRegistry;
import org.apache.beam.sdk.schemas.utils.SelectHelpers;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.windowing.BoundedWindow;
import org.apache.beam.sdk.transforms.windowing.PaneInfo;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionRowTuple;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rel.rel2sql.SqlImplementor;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rex.RexNode;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlIdentifier;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlNode;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Splitter;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.collect.ImmutableList;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code BigQueryTable} represent a BigQuery table as a target. This provider does not currently
 * support being a source.
 */
@SuppressWarnings({
  "rawtypes", // TODO(https://github.com/apache/beam/issues/20447)
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class BigQueryTable extends SchemaBaseBeamTable implements Serializable {
  @VisibleForTesting static final String METHOD_PROPERTY = "method";
  @VisibleForTesting static final String WRITE_DISPOSITION_PROPERTY = "writeDisposition";
  @VisibleForTesting final String bqLocation;
  private final ConversionOptions conversionOptions;
  private BeamTableStatistics rowCountStatistics = null;
  private static final Logger LOG = LoggerFactory.getLogger(BigQueryTable.class);
  @VisibleForTesting final Method method;
  @VisibleForTesting final WriteDisposition writeDisposition;

  @VisibleForTesting public final boolean cdcEnabled;
  @VisibleForTesting public final List<String> primaryKeys;
  @VisibleForTesting public final long runEpoch;
  private @Nullable BigQueryServices testBigQueryServices = null;

  @VisibleForTesting
  public void setTestBigQueryServices(BigQueryServices testBigQueryServices) {
    this.testBigQueryServices = testBigQueryServices;
  }

  BigQueryTable(Table table, BigQueryUtils.ConversionOptions options) {
    super(table.getSchema());
    this.conversionOptions = options;
    this.bqLocation = table.getLocation();

    com.fasterxml.jackson.databind.node.ObjectNode props = table.getProperties();
    this.cdcEnabled =
        props.has("cdc") && props.get("cdc").asBoolean(false)
            || props.has("primary_keys")
            || props.has("run_epoch");

    if (props.has("primary_keys")) {
      com.fasterxml.jackson.databind.JsonNode pkNode = props.get("primary_keys");
      if (pkNode.isArray()) {
        List<String> pks = new ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode item : pkNode) {
          pks.add(item.asText());
        }
        this.primaryKeys = pks;
      } else {
        this.primaryKeys =
            Splitter.on(',').trimResults().omitEmptyStrings().splitToList(pkNode.asText());
      }
    } else {
      this.primaryKeys = Collections.emptyList();
    }

    if (props.has("run_epoch")) {
      this.runEpoch = props.get("run_epoch").asLong(1L);
    } else {
      this.runEpoch = 1L;
    }

    if (table.getProperties().has(METHOD_PROPERTY)) {
      List<String> validMethods =
          Arrays.stream(Method.values()).map(Enum::toString).collect(Collectors.toList());
      // toUpperCase should make it case-insensitive
      String selectedMethod = table.getProperties().get(METHOD_PROPERTY).asText().toUpperCase();

      if (validMethods.contains(selectedMethod)) {
        method = Method.valueOf(selectedMethod);
      } else {
        throw new InvalidPropertyException(
            "Invalid method "
                + "'"
                + selectedMethod
                + "'. "
                + "Supported methods are: "
                + validMethods.toString()
                + ".");
      }
    } else {
      method = Method.DIRECT_READ;
    }

    LOG.info("BigQuery method is set to: {}", method);

    if (table.getProperties().has(WRITE_DISPOSITION_PROPERTY)) {
      List<String> validWriteDispositions =
          Arrays.stream(WriteDisposition.values()).map(Enum::toString).collect(Collectors.toList());
      // toUpperCase should make it case-insensitive
      String selectedWriteDisposition =
          table.getProperties().get(WRITE_DISPOSITION_PROPERTY).asText().toUpperCase();

      if (validWriteDispositions.contains(selectedWriteDisposition)) {
        writeDisposition = WriteDisposition.valueOf(selectedWriteDisposition);
      } else {
        throw new InvalidPropertyException(
            "Invalid write disposition "
                + "'"
                + selectedWriteDisposition
                + "'. "
                + "Supported write dispositions are: "
                + validWriteDispositions.toString()
                + ".");
      }
    } else {
      writeDisposition = WriteDisposition.WRITE_EMPTY;
    }

    LOG.info("BigQuery writeDisposition is set to: {}", writeDisposition);
  }

  public boolean isCdcEnabled() {
    return cdcEnabled;
  }

  public List<String> getPrimaryKeys() {
    return primaryKeys;
  }

  public long getRunEpoch() {
    return runEpoch;
  }

  @Override
  public BeamTableStatistics getTableStatistics(PipelineOptions options) {
    if (testBigQueryServices != null) {
      return BeamTableStatistics.BOUNDED_UNKNOWN;
    }

    if (rowCountStatistics == null) {
      rowCountStatistics = getRowCountFromBQ(options, bqLocation);
    }

    return rowCountStatistics;
  }

  @Override
  public PCollection.IsBounded isBounded() {
    return PCollection.IsBounded.BOUNDED;
  }

  @Override
  public PCollection<Row> buildIOReader(PBegin begin) {
    return begin.apply("Read Input BQ Rows", getBigQueryTypedRead(getSchema()));
  }

  @Override
  public PCollection<Row> buildIOReader(
      PBegin begin, BeamSqlTableFilter filters, List<String> fieldNames) {
    if (!method.equals(Method.DIRECT_READ)) {
      LOG.info("Predicate/project push-down only available for `DIRECT_READ` method, skipping.");
      return buildIOReader(begin);
    }

    final FieldAccessDescriptor resolved =
        FieldAccessDescriptor.withFieldNames(fieldNames).resolve(getSchema());
    final Schema newSchema = SelectHelpers.getOutputSchema(getSchema(), resolved);

    TypedRead<Row> typedRead = getBigQueryTypedRead(newSchema);

    if (!(filters instanceof DefaultTableFilter)) {
      BigQueryFilter bigQueryFilter = (BigQueryFilter) filters;
      if (!bigQueryFilter.getSupported().isEmpty()) {
        String rowRestriction = generateRowRestrictions(getSchema(), bigQueryFilter.getSupported());
        if (!rowRestriction.isEmpty()) {
          LOG.info("Pushing down the following filter: {}", rowRestriction);
          typedRead = typedRead.withRowRestriction(rowRestriction);
        }
      }
    }

    if (!fieldNames.isEmpty()) {
      typedRead = typedRead.withSelectedFields(fieldNames);
    }

    return begin.apply("Read Input BQ Rows with push-down", typedRead);
  }

  @VisibleForTesting static final String CDC_MUTATION_INFO = "row_mutation_info";
  @VisibleForTesting static final String CDC_MUTATION_TYPE = "mutation_type";
  @VisibleForTesting static final String CDC_MUTATION_SQN = "change_sequence_number";
  @VisibleForTesting static final String CDC_RECORD = "record";

  @VisibleForTesting
  static final Schema CDC_MUTATION_SCHEMA =
      Schema.builder().addStringField(CDC_MUTATION_TYPE).addStringField(CDC_MUTATION_SQN).build();

  @VisibleForTesting
  static PCollection<Row> packageCdcRows(PCollection<Row> input, long epoch) {
    Schema inputSchema = input.getSchema();
    Schema cdcRowSchema =
        Schema.builder()
            .addRowField(CDC_MUTATION_INFO, CDC_MUTATION_SCHEMA)
            .addRowField(CDC_RECORD, inputSchema)
            .build();

    return input
        .apply("PackageCdcRows", ParDo.of(new CdcRowPackagingFn(epoch, cdcRowSchema)))
        .setRowSchema(cdcRowSchema);
  }

  @VisibleForTesting
  static class CdcRowPackagingFn extends DoFn<Row, Row> {
    private final long epoch;
    private final Schema cdcRowSchema;

    CdcRowPackagingFn(long epoch, Schema cdcRowSchema) {
      this.epoch = epoch;
      this.cdcRowSchema = cdcRowSchema;
    }

    @ProcessElement
    public void processElement(
        @Element Row record, OutputReceiver<Row> out, BoundedWindow window, PaneInfo paneInfo) {
      long windowEndMillis = window.maxTimestamp().getMillis();
      long paneIndex = paneInfo.getIndex();
      String sqn = String.format("%08x/%016x/%08x", epoch, windowEndMillis, paneIndex);

      Row mutationInfo = Row.withSchema(CDC_MUTATION_SCHEMA).addValues("UPSERT", sqn).build();

      Row cdcRow = Row.withSchema(cdcRowSchema).addValues(mutationInfo, record).build();

      out.output(cdcRow);
    }
  }

  @VisibleForTesting
  static Row createWriteConfigRow(String bqLocation, List<String> primaryKeys) {
    BigQueryWriteConfiguration.Builder configBuilder =
        BigQueryWriteConfiguration.builder()
            .setTable(bqLocation)
            .setUseCdcWrites(true)
            .setUseAtLeastOnceSemantics(true)
            .setAutoSharding(true)
            .setWriteDisposition("WRITE_APPEND");

    if (!primaryKeys.isEmpty()) {
      configBuilder.setPrimaryKey(primaryKeys);
    }

    BigQueryWriteConfiguration config = configBuilder.build();
    try {
      return SchemaRegistry.createDefault()
          .getToRowFunction(BigQueryWriteConfiguration.class)
          .apply(config)
          .sorted()
          .toSnakeCase();
    } catch (NoSuchSchemaException e) {
      throw new RuntimeException("Unable to find schema for BigQueryWriteConfiguration", e);
    }
  }

  @Override
  public POutput buildIOWriter(PCollection<Row> input) {
    if (!cdcEnabled) {
      BigQueryIO.Write<Row> write =
          BigQueryIO.<Row>write()
              .withSchema(BigQueryUtils.toTableSchema(getSchema()))
              .withFormatFunction(BigQueryUtils.toTableRow())
              .withWriteDisposition(writeDisposition)
              .to(bqLocation);
      if (testBigQueryServices != null) {
        write = write.withTestServices(testBigQueryServices);
      }
      return input.apply(write);
    }

    PCollection<Row> cdcRows = packageCdcRows(input, this.runEpoch);
    Row configRow = createWriteConfigRow(bqLocation, primaryKeys);
    BigQueryStorageWriteApiSchemaTransformProvider provider =
        new BigQueryStorageWriteApiSchemaTransformProvider();

    org.apache.beam.sdk.transforms.PTransform<PCollectionRowTuple, PCollectionRowTuple> transform =
        provider.from(configRow);
    if (testBigQueryServices != null
        && transform
            instanceof
            org.apache.beam.sdk.io.gcp.bigquery.providers
                .BigQueryStorageWriteApiSchemaTransformProvider
                .BigQueryStorageWriteApiSchemaTransform) {
      ((org.apache.beam.sdk.io.gcp.bigquery.providers.BigQueryStorageWriteApiSchemaTransformProvider
                  .BigQueryStorageWriteApiSchemaTransform)
              transform)
          .setBigQueryServices(testBigQueryServices);
    }

    PCollectionRowTuple inputTuple = PCollectionRowTuple.of("input", cdcRows);
    return transform.expand(inputTuple);
  }

  @Override
  public ProjectSupport supportsProjects() {
    return method.equals(Method.DIRECT_READ)
        ? ProjectSupport.WITHOUT_FIELD_REORDERING
        : ProjectSupport.NONE;
  }

  @Override
  public BeamSqlTableFilter constructFilter(List<RexNode> filter) {
    if (method.equals(Method.DIRECT_READ)) {
      return new BigQueryFilter(filter);
    }

    return super.constructFilter(filter);
  }

  private String generateRowRestrictions(Schema schema, List<RexNode> supported) {
    assert !supported.isEmpty();
    final IntFunction<SqlNode> field =
        i -> new SqlIdentifier(schema.getField(i).getName(), SqlParserPos.ZERO);

    // TODO: BigQuerySqlDialectWithTypeTranslation can be replaced with BigQuerySqlDialect after
    // updating vendor Calcite version.
    SqlImplementor.Context context = new BeamSqlUnparseContext(field);

    // Create a single SqlNode from a list of RexNodes
    SqlNode andSqlNode = null;
    for (RexNode node : supported) {
      SqlNode sqlNode = context.toSql(null, node);
      if (andSqlNode == null) {
        andSqlNode = sqlNode;
        continue;
      }
      // AND operator must have exactly 2 operands.
      andSqlNode =
          SqlStdOperatorTable.AND.createCall(
              SqlParserPos.ZERO, ImmutableList.of(andSqlNode, sqlNode));
    }

    return andSqlNode.toSqlString(BeamBigQuerySqlDialect.DEFAULT).getSql();
  }

  private TypedRead<Row> getBigQueryTypedRead(Schema schema) {
    return BigQueryIO.read(
            record -> BigQueryUtils.toBeamRow(record.getRecord(), schema, conversionOptions))
        .withMethod(method)
        .from(bqLocation)
        .withCoder(SchemaCoder.of(schema));
  }

  private static BeamTableStatistics getRowCountFromBQ(PipelineOptions o, String bqLocation) {
    try {
      BigInteger rowCount =
          BigQueryHelpers.getNumRows(
              o.as(BigQueryOptions.class), BigQueryHelpers.parseTableSpec(bqLocation));

      if (rowCount == null) {
        return BeamTableStatistics.BOUNDED_UNKNOWN;
      }

      return BeamTableStatistics.createBoundedTableStatistics(rowCount.doubleValue());

    } catch (IOException | InterruptedException e) {
      LOG.warn("Could not get the row count for the table {}", bqLocation, e);
    }

    return BeamTableStatistics.BOUNDED_UNKNOWN;
  }

  public static class InvalidPropertyException extends UnsupportedOperationException {
    private InvalidPropertyException(String s) {
      super(s);
    }
  }
}
