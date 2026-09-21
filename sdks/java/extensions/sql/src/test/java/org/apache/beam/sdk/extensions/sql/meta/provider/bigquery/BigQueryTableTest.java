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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.google.api.services.bigquery.model.TableRow;
import java.io.Serializable;
import java.util.Arrays;
import java.util.List;
import org.apache.beam.sdk.extensions.sql.TableUtils;
import org.apache.beam.sdk.extensions.sql.meta.Table;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryUtils;
import org.apache.beam.sdk.io.gcp.testing.FakeBigQueryServices;
import org.apache.beam.sdk.io.gcp.testing.FakeDatasetService;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TimestampedValue;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link BigQueryTable} CDC row packaging, schema, and configuration. */
@RunWith(JUnit4.class)
public class BigQueryTableTest implements Serializable {

  @Rule public transient TestPipeline pipeline = TestPipeline.create();

  private static final Schema RECORD_SCHEMA =
      Schema.builder().addInt64Field("id").addStringField("name").build();

  @Test
  public void testBigQueryTableConstructor_cdcProperties() {
    String properties =
        "{\n"
            + "  \"primary_keys\": \"id,name\",\n"
            + "  \"run_epoch\": 42,\n"
            + "  \"cdc\": true\n"
            + "}";

    Table table =
        Table.builder()
            .name("cdc_table")
            .location("project:dataset.cdc_table")
            .schema(RECORD_SCHEMA)
            .type("bigquery")
            .properties(TableUtils.parseProperties(properties))
            .build();

    BigQueryTable bqTable =
        new BigQueryTable(table, BigQueryUtils.ConversionOptions.builder().build());

    assertTrue(bqTable.cdcEnabled);
    assertEquals(Arrays.asList("id", "name"), bqTable.primaryKeys);
    assertEquals(42L, bqTable.runEpoch);
  }

  @Test
  public void testBigQueryTableConstructor_defaultsWhenNonCdc() {
    Table table =
        Table.builder()
            .name("batch_table")
            .location("project:dataset.batch_table")
            .schema(RECORD_SCHEMA)
            .type("bigquery")
            .build();

    BigQueryTable bqTable =
        new BigQueryTable(table, BigQueryUtils.ConversionOptions.builder().build());

    assertFalse(bqTable.cdcEnabled);
    assertTrue(bqTable.primaryKeys.isEmpty());
    assertEquals(1L, bqTable.runEpoch);
  }

  @Test
  public void testCreateWriteConfigRow() {
    Row configRow =
        BigQueryTable.createWriteConfigRow("project:dataset.table", Arrays.asList("id", "name"));

    assertNotNull(configRow);
    assertEquals("project:dataset.table", configRow.getString("table"));
    assertEquals(Boolean.TRUE, configRow.getBoolean("use_cdc_writes"));
    assertEquals(Boolean.TRUE, configRow.getBoolean("use_at_least_once_semantics"));
    assertEquals(Boolean.TRUE, configRow.getBoolean("auto_sharding"));
    assertEquals("WRITE_APPEND", configRow.getString("write_disposition"));

    java.util.Collection<String> primaryKeys = configRow.getArray("primary_key");
    assertEquals(Arrays.asList("id", "name"), primaryKeys);
  }

  @Test
  public void testPackageCdcRows_correctlyWrapsMutationInfoAndRecord() {
    Instant baseTime = Instant.ofEpochMilli(0);

    TestStream<Row> stream =
        TestStream.create(RECORD_SCHEMA)
            .advanceWatermarkTo(baseTime)
            .addElements(
                TimestampedValue.of(
                    Row.withSchema(RECORD_SCHEMA).addValues(1L, "alice").build(),
                    baseTime.plus(Duration.standardSeconds(10))))
            .advanceWatermarkTo(baseTime.plus(Duration.standardSeconds(70)))
            .advanceWatermarkToInfinity();

    PCollection<Row> windowedInput =
        pipeline.apply(stream).apply(Window.into(FixedWindows.of(Duration.standardMinutes(1))));

    long epoch = 1L;
    PCollection<Row> cdcRows = BigQueryTable.packageCdcRows(windowedInput, epoch);

    // Schema verification
    Schema cdcSchema = cdcRows.getSchema();
    assertTrue(cdcSchema.hasField(BigQueryTable.CDC_MUTATION_INFO));
    assertTrue(cdcSchema.hasField(BigQueryTable.CDC_RECORD));
    assertEquals(
        BigQueryTable.CDC_MUTATION_SCHEMA,
        cdcSchema.getField(BigQueryTable.CDC_MUTATION_INFO).getType().getRowSchema());
    assertEquals(
        RECORD_SCHEMA, cdcSchema.getField(BigQueryTable.CDC_RECORD).getType().getRowSchema());

    // Row payload and sequence number verification
    PAssert.that(cdcRows).satisfies(new VerifyCdcRowPayload());

    pipeline.run().waitUntilFinish();
  }

  private static class VerifyCdcRowPayload implements SerializableFunction<Iterable<Row>, Void> {
    @Override
    public Void apply(Iterable<Row> rows) {
      int count = 0;
      for (Row cdcRow : rows) {
        count++;
        Row mutationInfo = cdcRow.getRow(BigQueryTable.CDC_MUTATION_INFO);
        assertNotNull(mutationInfo);
        assertEquals("UPSERT", mutationInfo.getString(BigQueryTable.CDC_MUTATION_TYPE));

        // Window [0s, 60s) has maxTimestamp = 59,999 ms -> 0x000000000000ea5f
        // epoch = 1 -> 0x00000001, pane = 0 -> 0x00000000
        String expectedSqn = "00000001/000000000000ea5f/00000000";
        assertEquals(expectedSqn, mutationInfo.getString(BigQueryTable.CDC_MUTATION_SQN));

        Row record = cdcRow.getRow(BigQueryTable.CDC_RECORD);
        assertNotNull(record);
        assertEquals(Long.valueOf(1L), record.getInt64("id"));
        assertEquals("alice", record.getString("name"));
      }
      assertEquals(1, count);
      return null;
    }
  }

  @Test
  public void testBuildIOWriter_cdc_writesToFakeBigQueryServices() throws Exception {
    FakeDatasetService.setUp();
    FakeDatasetService fakeDatasetService = new FakeDatasetService();
    FakeBigQueryServices fakeBqServices =
        new FakeBigQueryServices().withDatasetService(fakeDatasetService);

    fakeDatasetService.createDataset("project", "dataset", "", "", null);

    String properties =
        "{\n"
            + "  \"primary_keys\": \"id,name\",\n"
            + "  \"run_epoch\": 42,\n"
            + "  \"cdc\": true\n"
            + "}";

    Table table =
        Table.builder()
            .name("cdc_write_table")
            .location("project:dataset.cdc_write_table")
            .schema(RECORD_SCHEMA)
            .type("bigquery")
            .properties(TableUtils.parseProperties(properties))
            .build();

    BigQueryTable bqTable =
        new BigQueryTable(table, BigQueryUtils.ConversionOptions.builder().build());
    bqTable.setTestBigQueryServices(fakeBqServices);

    Instant baseTime = Instant.ofEpochMilli(10000L);
    Row row1 = Row.withSchema(RECORD_SCHEMA).addValues(1L, "alice").build();
    Row row2 = Row.withSchema(RECORD_SCHEMA).addValues(2L, "bob").build();

    PCollection<Row> windowedInput =
        pipeline
            .apply(
                Create.timestamped(
                    TimestampedValue.of(row1, baseTime),
                    TimestampedValue.of(row2, baseTime.plus(Duration.standardSeconds(5)))))
            .setRowSchema(RECORD_SCHEMA)
            .apply(Window.into(FixedWindows.of(Duration.standardMinutes(1))))
            .setRowSchema(RECORD_SCHEMA);

    bqTable.buildIOWriter(windowedInput);

    pipeline.run().waitUntilFinish();

    List<TableRow> writtenRows =
        fakeDatasetService.getAllRows("project", "dataset", "cdc_write_table");
    assertEquals(2, writtenRows.size());
  }
}
