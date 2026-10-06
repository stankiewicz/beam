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
package org.apache.beam.sdk.extensions.sql.impl.parser;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;


import com.google.api.services.bigquery.model.TableRow;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.beam.sdk.extensions.sql.impl.BeamSqlEnv;
import org.apache.beam.sdk.extensions.sql.impl.MaterializedViewOptions;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamAggregationRel;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamIOSinkRel;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamRelNode;
import org.apache.beam.sdk.extensions.sql.impl.rel.BeamSqlRelUtils;
import org.apache.beam.sdk.extensions.sql.meta.BaseBeamTable;
import org.apache.beam.sdk.extensions.sql.meta.BeamSqlTable;
import org.apache.beam.sdk.extensions.sql.meta.Table;
import org.apache.beam.sdk.extensions.sql.meta.catalog.InMemoryCatalogManager;
import org.apache.beam.sdk.extensions.sql.meta.provider.bigquery.BigQueryTable;
import org.apache.beam.sdk.extensions.sql.meta.provider.bigquery.BigQueryTableProvider;
import org.apache.beam.sdk.extensions.sql.meta.provider.test.TestTableProvider;
import org.apache.beam.sdk.io.gcp.bigquery.RowMutationInformation;
import org.apache.beam.sdk.io.gcp.testing.FakeBigQueryServices;
import org.apache.beam.sdk.io.gcp.testing.FakeDatasetService;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.testing.PAssert;
import org.apache.beam.sdk.testing.TestPipeline;
import org.apache.beam.sdk.testing.TestStream;
import org.apache.beam.sdk.transforms.Create;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PBegin;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.POutput;
import org.apache.beam.sdk.values.Row;
import org.apache.beam.sdk.values.TimestampedValue;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlKind;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlNode;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.pretty.SqlPrettyWriter;
import org.joda.time.Duration;

import org.joda.time.Instant;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Comprehensive unit tests for {@code CREATE MATERIALIZED VIEW} DDL and lowering. */
@RunWith(JUnit4.class)
public class BeamSqlMaterializedViewTest {

  @Rule public transient TestPipeline pipeline = TestPipeline.create();

  private StreamableTestTableProvider tableProvider;
  private BeamSqlEnv env;

  public static class StreamableTestTableProvider extends TestTableProvider {
    private final Map<String, BeamSqlTable> customTables = new ConcurrentHashMap<>();

    public void registerTable(String name, BeamSqlTable table) {
      customTables.put(name, table);
    }

    @Override
    public synchronized BeamSqlTable buildBeamSqlTable(Table table) {
      if (customTables.containsKey(table.getName())) {
        return customTables.get(table.getName());
      }
      return super.buildBeamSqlTable(table);
    }
  }

  public static class TestStreamTable extends BaseBeamTable {
    private final Schema schema;
    private final TestStream<Row> testStream;

    public TestStreamTable(Schema schema, TestStream<Row> testStream) {
      this.schema = schema;
      this.testStream = testStream;
    }

    @Override
    public Schema getSchema() {
      return schema;
    }

    @Override
    public PCollection.IsBounded isBounded() {
      return PCollection.IsBounded.UNBOUNDED;
    }

    @Override
    public PCollection<Row> buildIOReader(PBegin begin) {
      return begin
          .apply("TestStreamReader_" + System.nanoTime(), testStream)
          .setRowSchema(schema);
    }

    @Override
    public POutput buildIOWriter(PCollection<Row> input) {
      throw new UnsupportedOperationException();
    }
  }

  public static class BoundedTimestampedTable extends BaseBeamTable {
    private final Schema schema;
    private final List<TimestampedValue<Row>> rows;

    public BoundedTimestampedTable(Schema schema, List<TimestampedValue<Row>> rows) {
      this.schema = schema;
      this.rows = rows;
    }

    @Override
    public Schema getSchema() {
      return schema;
    }

    @Override
    public PCollection.IsBounded isBounded() {
      return PCollection.IsBounded.BOUNDED;
    }

    @Override
    public PCollection<Row> buildIOReader(PBegin begin) {
      return begin
          .apply("CreateTimestamped_" + System.nanoTime(), Create.timestamped(rows))
          .setRowSchema(schema);
    }

    @Override
    public POutput buildIOWriter(PCollection<Row> input) {
      throw new UnsupportedOperationException();
    }
  }

  @Before
  public void setUp() {
    tableProvider = new StreamableTestTableProvider();
    env = BeamSqlEnv.withTableProvider(tableProvider);

    // Register source stream table with event time
    env.executeDdl(
        "CREATE EXTERNAL TABLE clickstream (\n"
            + "  user_id VARCHAR,\n"
            + "  event_type VARCHAR,\n"
            + "  event_time TIMESTAMP\n"
            + ") TYPE 'test'\n"
            + "LOCATION '/tmp/clickstream'");
  }

  @Test
  public void testParseCreateMaterializedView_ddlAndOptions() throws Exception {
    String ddl =
        "CREATE MATERIALIZED VIEW mv_click_counts\n"
            + "OPTIONS (\n"
            + "  freshness = '10s',\n"
            + "  trigger_debounce = '2s',\n"
            + "  allowed_lateness = '1h',\n"
            + "  run_epoch = '42',\n"
            + "  primary_keys = 'window_end,user_id',\n"
            + "  target_type = 'test',\n"
            + "  destination_table = 'project:dataset.mv_click_counts'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  user_id,\n"
            + "  COUNT(*) AS click_count\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE),\n"
            + "  user_id";

    // 1. Verify DDL detection
    assertTrue(env.isDdl(ddl));

    // 2. Parse into AST
    SqlCreateMaterializedView mvNode = (SqlCreateMaterializedView) env.parse(ddl);
    assertEquals("mv_click_counts", mvNode.getViewName().getSimple());

    Map<String, String> options = mvNode.parseOptions();
    assertEquals("10s", options.get("freshness"));
    assertEquals("2s", options.get("trigger_debounce"));
    assertEquals("1h", options.get("allowed_lateness"));
    assertEquals("42", options.get("run_epoch"));
    assertEquals("window_end,user_id", options.get("primary_keys"));
    assertEquals("test", options.get("target_type"));

    // 3. Verify MaterializedViewOptions extraction
    MaterializedViewOptions mvOptions = MaterializedViewOptions.fromMap(options);
    assertEquals(Duration.standardSeconds(10), mvOptions.getFreshness());
    assertEquals(Duration.standardSeconds(2), mvOptions.getTriggerDebounce());
    assertEquals(Duration.standardHours(1), mvOptions.getAllowedLateness());
    assertEquals(42L, mvOptions.getRunEpoch());
    assertEquals(Arrays.asList("window_end", "user_id"), mvOptions.getPrimaryKeys());

    // 4. Verify compilation into pipeline graph
    BeamRelNode relNode = env.parseMaterializedView(ddl);
    assertNotNull(relNode);
    assertTrue(
        "Expected BeamIOSinkRel as root node, got: " + relNode.getClass(),
        relNode instanceof BeamIOSinkRel);

    // Verify table is registered in tableProvider
    Table registeredTable = tableProvider.getTables().get("mv_click_counts");
    assertNotNull(registeredTable);
    assertEquals("test", registeredTable.getType());
  }

  @Test
  public void testParseMaterializedView_propagatesOptionsToAggregationRel() throws Exception {
    String ddl =
        "CREATE MATERIALIZED VIEW mv_aggregated\n"
            + "OPTIONS (\n"
            + "  freshness = '5s',\n"
            + "  trigger_debounce = '1s',\n"
            + "  allowed_lateness = '30m',\n"
            + "  primary_keys = 'window_end',\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '5' MINUTE) AS window_end,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '5' MINUTE)";

    BeamRelNode rootRel = env.parseMaterializedView(ddl);
    BeamIOSinkRel sinkRel = (BeamIOSinkRel) rootRel;

    // Search input DAG for BeamAggregationRel
    BeamAggregationRel aggRel = findAggregationRel(sinkRel.getInput(0));
    assertNotNull("BeamAggregationRel must be present in compiled DAG", aggRel);

    assertEquals(Duration.standardSeconds(5), aggRel.getFreshness());
    assertEquals(Duration.standardSeconds(1), aggRel.getTriggerDebounce());
    assertEquals(Duration.standardMinutes(30), aggRel.getAllowedLateness());
  }

  @Test
  public void testParseMaterializedView_rejectsMissingTemporalWindowPrimaryKey() {
    // Missing window boundary in primary_keys
    String ddl =
        "CREATE MATERIALIZED VIEW mv_invalid_pk\n"
            + "OPTIONS (\n"
            + "  primary_keys = 'user_id',\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  user_id,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE),\n"
            + "  user_id";

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> env.parseMaterializedView(ddl));
    assertTrue(
        thrown.getMessage().contains("must include at least one column tracing to a temporal window boundary operator"));
  }

  @Test
  public void testParseMaterializedView_rejectsNonExistentPrimaryKey() {
    String ddl =
        "CREATE MATERIALIZED VIEW mv_missing_col\n"
            + "OPTIONS (\n"
            + "  primary_keys = 'non_existent_column,window_end',\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE)";

    IllegalArgumentException thrown =
        assertThrows(IllegalArgumentException.class, () -> env.parseMaterializedView(ddl));
    assertTrue(
        thrown.getMessage().contains("does not exist in query output schema"));
  }

  @Test
  public void testCdcSequenceNumberFormat() {
    long runEpoch = 1L;
    Instant windowEnd = Instant.ofEpochMilli(1700000000000L);
    long paneIndex = 3L;

    String sqn =
        String.format(
            "%08x/%016x/%08x", runEpoch, windowEnd.getMillis(), paneIndex);

    assertEquals("00000001/0000018bcfe56800/00000003", sqn);

    // Verify regex strictly conforms to StorageApiCDC.EXPECTED_SQN_PATTERN: ^([0-9A-Fa-f]{1,16})(/([0-9A-Fa-f]{1,16})){0,3}$
    java.util.regex.Pattern pattern =
        java.util.regex.Pattern.compile("^([0-9A-Fa-f]{1,16})(/([0-9A-Fa-f]{1,16})){0,3}$");
    assertTrue(pattern.matcher(sqn).matches());
  }

  @Test
  public void testPipeline_withTestStream_windowedMaterializedView() throws Exception {
    Schema streamSchema =
        Schema.builder()
            .addStringField("user_id")
            .addStringField("event_type")
            .addDateTimeField("event_time")
            .build();

    Instant baseTime = Instant.ofEpochMilli(0);

    TestStream<Row> stream =
        TestStream.create(streamSchema)
            .advanceWatermarkTo(baseTime)
            .addElements(
                TimestampedValue.of(
                    Row.withSchema(streamSchema)
                        .addValues("user1", "click", baseTime.plus(Duration.standardSeconds(10)))
                        .build(),
                    baseTime.plus(Duration.standardSeconds(10))),
                TimestampedValue.of(
                    Row.withSchema(streamSchema)
                        .addValues("user1", "click", baseTime.plus(Duration.standardSeconds(20)))
                        .build(),
                    baseTime.plus(Duration.standardSeconds(20))),
                TimestampedValue.of(
                    Row.withSchema(streamSchema)
                        .addValues("user2", "click", baseTime.plus(Duration.standardSeconds(30)))
                        .build(),
                    baseTime.plus(Duration.standardSeconds(30))))
            // Advance watermark past 1-minute window boundary (baseTime + 60s)
            .advanceWatermarkTo(baseTime.plus(Duration.standardSeconds(70)))
            .addElements(
                // Add an element in the second window [60s, 120s)
                TimestampedValue.of(
                    Row.withSchema(streamSchema)
                        .addValues("user1", "click", baseTime.plus(Duration.standardSeconds(75)))
                        .build(),
                    baseTime.plus(Duration.standardSeconds(75))))
            .advanceWatermarkToInfinity();

    tableProvider.registerTable("clickstream", new TestStreamTable(streamSchema, stream));

    String ddl =
        "CREATE MATERIALIZED VIEW mv_stream_clicks\n"
            + "OPTIONS (\n"
            + "  freshness = '5s',\n"
            + "  trigger_debounce = '1s',\n"
            + "  allowed_lateness = '10m',\n"
            + "  run_epoch = '1',\n"
            + "  primary_keys = 'window_end,user_id',\n"
            + "  target_type = 'test',\n"
            + "  destination_table = 'mv_stream_clicks'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  user_id,\n"
            + "  COUNT(*) AS click_count\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE),\n"
            + "  user_id";

    BeamRelNode relNode = env.parseMaterializedView(ddl);
    PCollection<Row> output = BeamSqlRelUtils.toPCollection(pipeline, relNode);

    PAssert.that(output).satisfies(new VerifyStreamResults());

    pipeline.run().waitUntilFinish();

    assertEquals(3, tableProvider.tableRows("mv_stream_clicks").size());
  }

  private static class VerifyStreamResults implements SerializableFunction<Iterable<Row>, Void> {
    @Override
    public Void apply(Iterable<Row> rows) {
      Map<String, Long> counts = new HashMap<>();
      for (Row row : rows) {
        Instant windowEnd = row.getDateTime("window_end").toInstant();
        String userId = row.getString("user_id");
        Long clickCount = row.getInt64("click_count");
        counts.put(windowEnd.getMillis() + ":" + userId, clickCount);
      }
      assertEquals(Long.valueOf(2), counts.get("60000:user1"));
      assertEquals(Long.valueOf(1), counts.get("60000:user2"));
      assertEquals(Long.valueOf(1), counts.get("120000:user1"));
      assertEquals(3, counts.size());
      return null;
    }
  }

  @Test
  public void testCreateMaterializedView_bigQueryCdcSink_endToEndExecution() throws Exception {
    FakeDatasetService.setUp();
    FakeDatasetService fakeDatasetService = new FakeDatasetService();
    FakeBigQueryServices fakeBqServices =
        new FakeBigQueryServices().withDatasetService(fakeDatasetService);

    fakeDatasetService.createDataset("project", "dataset", "", "", null);

    BigQueryTableProvider bqProvider = new BigQueryTableProvider();
    bqProvider.setTestBigQueryServices(fakeBqServices);

    BeamSqlEnv customEnv = BeamSqlEnv.inMemory(tableProvider, bqProvider);

    // Register source stream table in customEnv
    customEnv.executeDdl(
        "CREATE EXTERNAL TABLE clickstream_bq (\n"
            + "  user_id VARCHAR,\n"
            + "  event_type VARCHAR,\n"
            + "  event_time TIMESTAMP\n"
            + ") TYPE 'test'\n"
            + "LOCATION '/tmp/clickstream_bq'");

    Schema streamSchema =
        Schema.builder()
            .addStringField("user_id")
            .addStringField("event_type")
            .addDateTimeField("event_time")
            .build();

    Instant baseTime = Instant.ofEpochMilli(0);
    List<TimestampedValue<Row>> timestampedRows =
        Arrays.asList(
            TimestampedValue.of(
                Row.withSchema(streamSchema)
                    .addValues("user1", "click", baseTime.plus(Duration.standardSeconds(10)))
                    .build(),
                baseTime.plus(Duration.standardSeconds(10))),
            TimestampedValue.of(
                Row.withSchema(streamSchema)
                    .addValues("user2", "click", baseTime.plus(Duration.standardSeconds(20)))
                    .build(),
                baseTime.plus(Duration.standardSeconds(20))),
            TimestampedValue.of(
                Row.withSchema(streamSchema)
                    .addValues("user1", "click", baseTime.plus(Duration.standardSeconds(30)))
                    .build(),
                baseTime.plus(Duration.standardSeconds(30))),
            TimestampedValue.of(
                Row.withSchema(streamSchema)
                    .addValues("user1", "click", baseTime.plus(Duration.standardSeconds(75)))
                    .build(),
                baseTime.plus(Duration.standardSeconds(75))));

    tableProvider.registerTable(
        "clickstream_bq", new BoundedTimestampedTable(streamSchema, timestampedRows));

    String ddl =
        "CREATE MATERIALIZED VIEW mv_bq_clicks\n"
            + "OPTIONS (\n"
            + "  freshness = '5s',\n"
            + "  trigger_debounce = '1s',\n"
            + "  allowed_lateness = '10m',\n"
            + "  run_epoch = '42',\n"
            + "  primary_keys = 'window_end,user_id',\n"
            + "  target_type = 'bigquery',\n"
            + "  destination_table = 'project:dataset.mv_bq_clicks'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  user_id,\n"
            + "  COUNT(*) AS click_count\n"
            + "FROM clickstream_bq\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE),\n"
            + "  user_id";

    BeamRelNode relNode = customEnv.parseMaterializedView(ddl);
    assertNotNull(relNode);
    assertTrue(relNode instanceof BeamIOSinkRel);

    // Expand DAG and run on DirectRunner
    BeamSqlRelUtils.toPCollection(pipeline, relNode);
    pipeline.run().waitUntilFinish();

    List<KV<TableRow, RowMutationInformation>> rowsWithMutations =
        fakeDatasetService.getRowsWithMutationInformation("project", "dataset", "mv_bq_clicks");
    assertEquals(3, rowsWithMutations.size());
    Map<String, Long> aggregatedCounts = new HashMap<>();
    Map<String, String> sequenceNumbers = new HashMap<>();
    Map<String, RowMutationInformation.MutationType> mutationTypes = new HashMap<>();
    for (KV<TableRow, RowMutationInformation> kv : rowsWithMutations) {
      TableRow r = kv.getKey();
      RowMutationInformation mutationInfo = kv.getValue();
      assertNotNull(mutationInfo);

      String key = r.get("user_id") + "@" + r.get("window_end");
      Long count = Long.valueOf(String.valueOf(r.get("click_count")));
      aggregatedCounts.put(key, count);
      mutationTypes.put(key, mutationInfo.getMutationType());
      sequenceNumbers.put(key, mutationInfo.getChangeSequenceNumber());
    }

    assertEquals(Long.valueOf(2), aggregatedCounts.get("user1@1970-01-01 T00:01:00"));
    assertEquals(Long.valueOf(1), aggregatedCounts.get("user2@1970-01-01 T00:01:00"));
    assertEquals(Long.valueOf(1), aggregatedCounts.get("user1@1970-01-01 T00:02:00"));

    // Assert UPSERT mutation type for all rows
    assertEquals(
        RowMutationInformation.MutationType.UPSERT,
        mutationTypes.get("user1@1970-01-01 T00:01:00"));
    assertEquals(
        RowMutationInformation.MutationType.UPSERT,
        mutationTypes.get("user2@1970-01-01 T00:01:00"));
    assertEquals(
        RowMutationInformation.MutationType.UPSERT,
        mutationTypes.get("user1@1970-01-01 T00:02:00"));

    // Assert sequence numbers matching format (epoch=42 / window_max_timestamp / pane_index):
    // Window [0, 60s): maxTimestamp = 59,999 ms -> 0x000000000000ea5f
    assertEquals(
        "0000002a/000000000000ea5f/00000000", sequenceNumbers.get("user1@1970-01-01 T00:01:00"));
    assertEquals(
        "0000002a/000000000000ea5f/00000000", sequenceNumbers.get("user2@1970-01-01 T00:01:00"));
    // Window [60s, 120s): maxTimestamp = 119,999 ms -> 0x000000000001d4bf
    assertEquals(
        "0000002a/000000000001d4bf/00000000", sequenceNumbers.get("user1@1970-01-01 T00:02:00"));
  }

  @Test
  public void testSqlCreateMaterializedView_unparseAndAccessors() throws Exception {
    String ddl =
        "CREATE MATERIALIZED VIEW mv_unparse\n"
            + "OPTIONS (\n"
            + "  freshness = '10s'\n"
            + ")\n"
            + "AS\n"
            + "SELECT 1 FROM clickstream";

    SqlCreateMaterializedView mv = (SqlCreateMaterializedView) env.parse(ddl);
    assertNotNull(mv.getViewName());
    assertNotNull(mv.getOptionList());
    assertNotNull(mv.getQuery());
    assertNotNull(mv.getOperandList());
    assertEquals(SqlKind.OTHER_DDL, mv.getOperator().getKind());

    SqlPrettyWriter writer = new SqlPrettyWriter();
    mv.unparse(writer, 0, 0);
    String unparsed = writer.toString();
    assertTrue(unparsed.contains("CREATE"));
    assertTrue(unparsed.contains("MATERIALIZED"));
    assertTrue(unparsed.contains("VIEW"));
    assertTrue(unparsed.contains("OPTIONS"));
    assertTrue(unparsed.contains("AS"));
  }

  @Test
  public void testCreateMaterializedView_ifNotExistsAndDuplicate() throws Exception {
    String ddlIfNotExists =
        "CREATE MATERIALIZED VIEW IF NOT EXISTS mv_dup_test\n"
            + "OPTIONS (\n"
            + "  primary_keys = 'window_end',\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE)";

    // First execution succeeds
    env.executeDdl(ddlIfNotExists);
    // Second execution with IF NOT EXISTS also succeeds (no-op)
    env.executeDdl(ddlIfNotExists);

    String ddlDuplicate =
        "CREATE MATERIALIZED VIEW mv_dup_err\n"
            + "OPTIONS (\n"
            + "  primary_keys = 'window_end',\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE)";

    env.executeDdl(ddlDuplicate);
    // Duplicate without IF NOT EXISTS must throw an exception
    assertThrows(Exception.class, () -> env.executeDdl(ddlDuplicate));
  }

  @Test
  public void testMaterializedViewOptions_durationParsingAndValidation() {
    assertEquals(Duration.millis(500), MaterializedViewOptions.parseDuration("500ms"));
    assertEquals(Duration.standardSeconds(10), MaterializedViewOptions.parseDuration("10s"));
    assertEquals(Duration.standardMinutes(15), MaterializedViewOptions.parseDuration("15m"));
    assertEquals(Duration.standardHours(2), MaterializedViewOptions.parseDuration("2h"));
    assertEquals(Duration.standardDays(3), MaterializedViewOptions.parseDuration("3d"));
    assertEquals(Duration.standardSeconds(45), MaterializedViewOptions.parseDuration("45"));
    assertNull(MaterializedViewOptions.parseDuration(null));
    assertNull(MaterializedViewOptions.parseDuration(""));

    assertThrows(
        IllegalArgumentException.class,
        () -> MaterializedViewOptions.parseDuration("unparseable_duration"));

    Map<String, String> invalidEpoch = new HashMap<>();
    invalidEpoch.put("run_epoch", "non_numeric_epoch");
    assertThrows(
        IllegalArgumentException.class,
        () -> MaterializedViewOptions.fromMap(invalidEpoch));
  }

  @Test
  public void testBeamAggregationRel_copyPreservesMaterializedViewOptions() throws Exception {
    String ddl =
        "CREATE MATERIALIZED VIEW mv_copy_test\n"
            + "OPTIONS (\n"
            + "  freshness = '10s',\n"
            + "  trigger_debounce = '2s',\n"
            + "  allowed_lateness = '5m',\n"
            + "  primary_keys = 'window_end',\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  TUMBLE_END(event_time, INTERVAL '1' MINUTE) AS window_end,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  TUMBLE(event_time, INTERVAL '1' MINUTE)";

    BeamRelNode rootRel = env.parseMaterializedView(ddl);
    BeamIOSinkRel sinkRel = (BeamIOSinkRel) rootRel;
    BeamAggregationRel aggRel = findAggregationRel(sinkRel.getInput(0));
    assertNotNull(aggRel);

    BeamAggregationRel copied =
        (BeamAggregationRel)
            aggRel.copy(
                aggRel.getTraitSet(),
                aggRel.getInput(),
                aggRel.getGroupSet(),
                aggRel.getGroupSets(),
                aggRel.getAggCallList());

    assertEquals(Duration.standardSeconds(10), copied.getFreshness());
    assertEquals(Duration.standardSeconds(2), copied.getTriggerDebounce());
    assertEquals(Duration.standardMinutes(5), copied.getAllowedLateness());
    assertEquals(aggRel.getWindowFieldIndex(), copied.getWindowFieldIndex());
  }

  @Test
  public void testParseMaterializedView_withoutPrimaryKeys_allowed() throws Exception {
    String ddl =
        "CREATE MATERIALIZED VIEW mv_no_pk\n"
            + "OPTIONS (\n"
            + "  target_type = 'test'\n"
            + ")\n"
            + "AS\n"
            + "SELECT\n"
            + "  user_id,\n"
            + "  COUNT(*) AS cnt\n"
            + "FROM clickstream\n"
            + "GROUP BY\n"
            + "  user_id";

    BeamRelNode relNode = env.parseMaterializedView(ddl);
    assertNotNull(relNode);
  }

  private static BeamAggregationRel findAggregationRel(org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rel.RelNode node) {
    if (node instanceof BeamAggregationRel) {
      return (BeamAggregationRel) node;
    }
    for (org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.rel.RelNode input : node.getInputs()) {
      BeamAggregationRel found = findAggregationRel(input);
      if (found != null) {
        return found;
      }
    }
    return null;
  }
}

