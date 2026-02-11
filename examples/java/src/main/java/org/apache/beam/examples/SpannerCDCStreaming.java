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
package org.apache.beam.examples;

import com.google.cloud.Timestamp;
import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.gcp.spanner.SpannerConfig;
import org.apache.beam.sdk.io.gcp.spanner.SpannerIO;
import org.apache.beam.sdk.io.gcp.spanner.changestreams.model.DataChangeRecord;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.Redistribute;
import org.apache.beam.sdk.values.PCollection;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

public class SpannerCDCStreaming {

  private static final DateTimeFormatter dateTimeFormatter = DateTimeFormat.forPattern("HH:mm:ss");

  public interface SpannerCDCStreamingOptions extends PipelineOptions {}

  public static void main(String[] args) {

    SpannerCDCStreamingOptions options =
        PipelineOptionsFactory.fromArgs(args).withValidation().as(SpannerCDCStreamingOptions.class);
    Pipeline pipeline = Pipeline.create(options);
    PCollection<DataChangeRecord> apply =
        pipeline.apply(
            SpannerIO.readChangeStream()
                .withSpannerConfig(
                    SpannerConfig.create()
                        .withProjectId("radoslaws-playground-pso")
                        .withInstanceId("fooo")
                        .withDatabaseId("my-db"))
                .withChangeStreamName("taxis_cdc")
                .withMetadataInstance("fooo")
                .withMetadataDatabase("my-db")
                .withInclusiveStartAt(Timestamp.now()));

    apply.apply("redistribute", Redistribute.arbitrarily());
    pipeline.run().waitUntilFinish();
  }
}
