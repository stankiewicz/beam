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

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.auto.service.AutoService;
import org.apache.beam.sdk.extensions.sql.meta.BeamSqlTable;
import org.apache.beam.sdk.extensions.sql.meta.Table;
import org.apache.beam.sdk.extensions.sql.meta.provider.InMemoryMetaTableProvider;
import org.apache.beam.sdk.extensions.sql.meta.provider.TableProvider;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryServices;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryUtils.ConversionOptions;
import org.apache.beam.sdk.io.gcp.bigquery.BigQueryUtils.ConversionOptions.TruncateTimestamps;
import org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.annotations.VisibleForTesting;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * BigQuery table provider.
 *
 * <p>A sample of text table is:
 *
 * <pre>{@code
 * CREATE TABLE ORDERS(
 *   ID INT COMMENT 'this is the primary key',
 *   NAME VARCHAR(127) COMMENT 'this is the name'
 * )
 * TYPE 'bigquery'
 * COMMENT 'this is the table orders'
 * LOCATION '[PROJECT_ID]:[DATASET].[TABLE]'
 * }</pre>
 */
@AutoService(TableProvider.class)
public class BigQueryTableProvider extends InMemoryMetaTableProvider {
  private @Nullable BigQueryServices testBigQueryServices = null;

  @VisibleForTesting
  public void setTestBigQueryServices(BigQueryServices testBigQueryServices) {
    this.testBigQueryServices = testBigQueryServices;
  }

  @Override
  public String getTableType() {
    return "bigquery";
  }

  @Override
  public BeamSqlTable buildBeamSqlTable(Table table) {
    BigQueryTable bqTable = new BigQueryTable(table, getConversionOptions(table.getProperties()));
    if (testBigQueryServices != null) {
      bqTable.setTestBigQueryServices(testBigQueryServices);
    }
    return bqTable;
  }

  protected static ConversionOptions getConversionOptions(ObjectNode properties) {
    return ConversionOptions.builder()
        .setTruncateTimestamps(
            properties.path("truncateTimestamps").asBoolean(false)
                ? TruncateTimestamps.TRUNCATE
                : TruncateTimestamps.REJECT)
        .build();
  }
}
