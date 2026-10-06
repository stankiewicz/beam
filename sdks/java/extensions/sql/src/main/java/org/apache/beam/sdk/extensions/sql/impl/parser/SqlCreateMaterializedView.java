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

import static org.apache.beam.sdk.extensions.sql.impl.parser.SqlDdlNodes.name;
import static org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.util.Static.RESOURCE;
import static org.apache.beam.vendor.guava.v32_1_2_jre.com.google.common.base.Preconditions.checkNotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.beam.sdk.extensions.sql.TableUtils;
import org.apache.beam.sdk.extensions.sql.impl.BeamCalciteSchema;
import org.apache.beam.sdk.extensions.sql.impl.CatalogManagerSchema;
import org.apache.beam.sdk.extensions.sql.impl.CatalogSchema;
import org.apache.beam.sdk.extensions.sql.impl.TableName;
import org.apache.beam.sdk.extensions.sql.meta.Table;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.jdbc.CalcitePrepare;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.jdbc.CalciteSchema;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlCreate;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlIdentifier;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlKind;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlNode;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlNodeList;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlOperator;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlSpecialOperator;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlUtil;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.SqlWriter;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.sql.parser.SqlParserPos;
import org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.util.Pair;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Parse tree for {@code CREATE MATERIALIZED VIEW} statement. */
@SuppressWarnings({
  "nullness" // TODO(https://github.com/apache/beam/issues/20497)
})
public class SqlCreateMaterializedView extends SqlCreate
    implements BeamSqlParser.ExecutableStatement {
  private final SqlIdentifier viewName;
  private final @Nullable SqlNodeList optionList;
  private final SqlNode query;

  private static final SqlOperator OPERATOR =
      new SqlSpecialOperator("CREATE MATERIALIZED VIEW", SqlKind.OTHER_DDL);

  public SqlCreateMaterializedView(
      SqlParserPos pos,
      boolean replace,
      boolean ifNotExists,
      SqlIdentifier viewName,
      @Nullable SqlNodeList optionList,
      SqlNode query) {
    super(OPERATOR, pos, replace, ifNotExists);
    this.viewName = checkNotNull(viewName, "viewName");
    this.optionList = optionList;
    this.query = checkNotNull(query, "query");
  }

  public SqlIdentifier getViewName() {
    return viewName;
  }

  public @Nullable SqlNodeList getOptionList() {
    return optionList;
  }

  public SqlNode getQuery() {
    return query;
  }

  @Override
  public SqlOperator getOperator() {
    return OPERATOR;
  }

  @Override
  public List<SqlNode> getOperandList() {
    List<SqlNode> operands = new ArrayList<>();
    operands.add(viewName);
    if (optionList != null) {
      operands.add(optionList);
    }
    operands.add(query);
    return operands;
  }

  @Override
  public void unparse(SqlWriter writer, int leftPrec, int rightPrec) {
    writer.keyword("CREATE");
    writer.keyword("MATERIALIZED");
    writer.keyword("VIEW");
    if (ifNotExists) {
      writer.keyword("IF NOT EXISTS");
    }
    viewName.unparse(writer, 0, 0);
    if (optionList != null) {
      writer.keyword("OPTIONS");
      optionList.unparse(writer, 0, 0);
    }
    writer.keyword("AS");
    query.unparse(writer, 0, 0);
  }

  public Map<String, String> parseOptions() {
    Map<String, String> options = new HashMap<>();
    if (optionList == null) {
      return options;
    }
    List<SqlNode> list = optionList.getList();
    for (int i = 0; i < list.size(); i += 2) {
      SqlNode keyNode = list.get(i);
      SqlNode valueNode = list.get(i + 1);
      String key =
          keyNode instanceof SqlIdentifier
              ? ((SqlIdentifier) keyNode).getSimple().toLowerCase()
              : SqlDdlNodes.getString(keyNode) != null
                  ? SqlDdlNodes.getString(keyNode).toLowerCase()
                  : keyNode.toString().toLowerCase();
      String value =
          SqlDdlNodes.getString(valueNode) != null
              ? SqlDdlNodes.getString(valueNode)
              : valueNode.toString();
      options.put(key, value);
    }
    return options;
  }

  @Override
  public void execute(CalcitePrepare.Context context) {
    final Pair<CalciteSchema, String> pair = SqlDdlNodes.schema(context, true, viewName);
    if (pair.left.plus().getTable(pair.right) != null) {
      if (!ifNotExists) {
        throw SqlUtil.newContextException(
            viewName.getParserPosition(), RESOURCE.tableExists(pair.right));
      }
      return;
    }

    org.apache.beam.vendor.calcite.v1_40_0.org.apache.calcite.schema.Schema schema =
        pair.left.schema;
    BeamCalciteSchema beamCalciteSchema;
    Map<String, String> options = parseOptions();
    String targetType = options.getOrDefault("target_type", "bigquery");

    if (schema instanceof CatalogManagerSchema) {
      TableName pathOverride = TableName.create(viewName.toString());
      CatalogManagerSchema catalogManagerSchema = (CatalogManagerSchema) schema;
      catalogManagerSchema.maybeRegisterProvider(pathOverride, targetType);

      CatalogSchema catalogSchema = catalogManagerSchema.getCatalogSchema(pathOverride);
      beamCalciteSchema = catalogSchema.getDatabaseSchema(pathOverride);
    } else if (schema instanceof BeamCalciteSchema) {
      beamCalciteSchema = (BeamCalciteSchema) schema;
    } else {
      throw SqlUtil.newContextException(
          viewName.getParserPosition(),
          RESOURCE.internal(
              "Attempting to create a materialized view with unexpected Calcite Schema of type "
                  + schema.getClass()));
    }

    Table table = toTable(options);
    beamCalciteSchema.getTableProvider().createTable(table);
  }

  private Table toTable(Map<String, String> options) {
    String targetType = options.getOrDefault("target_type", "bigquery");
    String location =
        options.getOrDefault(
            "destination_table", options.getOrDefault("table", options.get("location")));
    return Table.builder()
        .type(targetType)
        .name(name(viewName))
        .schema(Schema.builder().build()) // Derived dynamically at query planning time
        .location(location)
        .properties(TableUtils.parseProperties(options))
        .build();
  }
}
