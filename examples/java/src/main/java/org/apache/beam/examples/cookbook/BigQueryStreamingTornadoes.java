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
package org.apache.beam.examples.cookbook;

import com.google.api.services.bigquery.model.TableFieldSchema;
import com.google.api.services.bigquery.model.TableRow;
import com.google.api.services.bigquery.model.TableSchema;
import java.util.ArrayList;
import java.util.List;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryDynamicReadDescriptor;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryIO;
import org.apache.beam.sdk.options.Default;
import org.apache.beam.sdk.options.Description;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.options.Validation;
import org.apache.beam.sdk.transforms.Count;
import org.apache.beam.sdk.transforms.DoFn;
import org.apache.beam.sdk.transforms.MapElements;
import org.apache.beam.sdk.transforms.PTransform;
import org.apache.beam.sdk.transforms.ParDo;
import org.apache.beam.sdk.transforms.PeriodicImpulse;
import org.apache.beam.sdk.transforms.windowing.FixedWindows;
import org.apache.beam.sdk.transforms.windowing.Window;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.TypeDescriptor;
import org.joda.time.Duration;
import org.joda.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An example that reads periodically the public samples of weather data from BigQuery, counts the
 * number of tornadoes that occur in each month, and writes the results to BigQuery.
 *
 * <p>Concepts: Reading/writing BigQuery; counting a PCollection; user-defined PTransforms
 *
 * <p>Note: Before running this example, you must create a BigQuery dataset to contain your output
 * table.
 *
 * <p>To execute this pipeline locally, specify the BigQuery table for the output with the form:
 *
 * <pre>{@code
 * --output=YOUR_PROJECT_ID:DATASET_ID.TABLE_ID
 * }</pre>
 *
 * <p>To change the runner, specify:
 *
 * <pre>{@code
 * --runner=YOUR_SELECTED_RUNNER
 * }</pre>
 *
 * See examples/java/README.md for instructions about how to configure different runners.
 *
 * <p>The BigQuery input table defaults to {@code apache-beam-testing.samples.weather_stations} and
 * can be overridden with {@code --input}.
 */
public class BigQueryStreamingTornadoes {
  private static final Logger LOG = LoggerFactory.getLogger(BigQueryStreamingTornadoes.class);

  // Default to using a 1000 row subset of the public weather station table publicdata:samples.gsod.
  private static final String WEATHER_SAMPLES_TABLE =
      "apache-beam-testing.samples.weather_stations";

  /**
   * Examines each row in the input table. If a tornado was recorded in that sample, the month in
   * which it occurred is output.
   */
  static class ExtractTornadoesFn extends DoFn<TableRow, Integer> {
    @ProcessElement
    public void processElement(ProcessContext c) {
      TableRow row = c.element();
      if ((Boolean) row.get("tornado")) {
        c.output(Integer.parseInt((String) row.get("month")));
      }
    }
  }

  /**
   * Prepares the data for writing to BigQuery by building a TableRow object containing an integer
   * representation of month and the number of tornadoes that occurred in each month.
   */
  static class FormatCountsFn extends DoFn<KV<Integer, Long>, TableRow> {
    @ProcessElement
    public void processElement(ProcessContext c) {
      TableRow row =
          new TableRow()
              .set("ts", c.timestamp().toString())
              .set("month", c.element().getKey())
              .set("tornado_count", c.element().getValue());
      c.output(row);
    }
  }

  /**
   * Takes rows from a table and generates a table of counts.
   *
   * <p>The input schema is described by https://developers.google.com/bigquery/docs/dataset-gsod .
   * The output contains the total number of tornadoes found in each month in the following schema:
   *
   * <ul>
   *   <li>month: integer
   *   <li>tornado_count: integer
   * </ul>
   */
  static class CountTornadoes extends PTransform<PCollection<TableRow>, PCollection<TableRow>> {
    @Override
    public PCollection<TableRow> expand(PCollection<TableRow> rows) {

      // row... => month...
      PCollection<Integer> tornadoes = rows.apply(ParDo.of(new ExtractTornadoesFn()));

      // month... => <month,count>...
      PCollection<KV<Integer, Long>> tornadoCounts = tornadoes.apply(Count.perElement());

      // <month,count>... => row...
      PCollection<TableRow> results = tornadoCounts.apply(ParDo.of(new FormatCountsFn()));

      return results;
    }
  }

  /**
   * Options supported by {@link BigQueryStreamingTornadoes}.
   *
   * <p>Inherits standard configuration options.
   */
  public interface Options extends PipelineOptions {
    @Description("Table to read from, specified as <project_id>:<dataset_id>.<table_id>")
    @Default.String(WEATHER_SAMPLES_TABLE)
    String getInput();

    void setInput(String value);

    @Description("Write method to use to write to BigQuery")
    @Default.Enum("DEFAULT")
    BigQueryIO.Write.Method getWriteMethod();

    void setWriteMethod(BigQueryIO.Write.Method value);

    @Description(
        "BigQuery table to write to, specified as "
            + "<project_id>:<dataset_id>.<table_id>. The dataset must already exist.")
    @Validation.Required
    String getOutput();

    void setOutput(String value);
  }

  public static void applyBigQueryStreamingTornadoes(Pipeline p, Options options) {
    List<TableFieldSchema> fields = new ArrayList<>();
    fields.add(new TableFieldSchema().setName("ts").setType("STRING"));
    fields.add(new TableFieldSchema().setName("month").setType("INTEGER"));
    fields.add(new TableFieldSchema().setName("tornado_count").setType("INTEGER"));
    TableSchema schema = new TableSchema().setFields(fields);

    PCollection<BigQueryDynamicReadDescriptor> descriptors =
        p.apply("Impulse", PeriodicImpulse.create().withInterval(Duration.standardSeconds(60)))
            .apply(
                "Create query",
                MapElements.into(TypeDescriptor.of(BigQueryDynamicReadDescriptor.class))
                    .via(
                        (Instant t) ->
                            BigQueryDynamicReadDescriptor.create(
                                null, WEATHER_SAMPLES_TABLE, null, null, null, null)));

    PCollection<TableRow> readDynamically =
        descriptors.apply("Read dynamically", BigQueryIO.readDynamicallyTableRows());
    readDynamically
        .apply(Window.into(FixedWindows.of(Duration.standardMinutes(1))))
        .apply(new CountTornadoes())
        .apply(
            BigQueryIO.writeTableRows()
                .to(options.getOutput())
                .withSchema(schema)
                .withCreateDisposition(BigQueryIO.Write.CreateDisposition.CREATE_NEVER)
                .withWriteDisposition(BigQueryIO.Write.WriteDisposition.WRITE_APPEND)
                .withMethod(options.getWriteMethod()));
  }

  public static void runBigQueryTornadoes(Options options) {
    LOG.info("Running BigQuery Tornadoes with options " + options.toString());
    Pipeline p = Pipeline.create(options);
    applyBigQueryStreamingTornadoes(p, options);
    p.run().waitUntilFinish();
  }

  public static void main(String[] args) {
    Options options = PipelineOptionsFactory.fromArgs(args).as(Options.class);
    runBigQueryTornadoes(options);
  }
}
