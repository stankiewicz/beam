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
package org.apache.beam.sdk.schemas.transforms;

import java.util.ServiceLoader;
import org.apache.beam.sdk.schemas.Schema;
import org.apache.beam.sdk.schemas.transforms.RawSchemaWithFormat.Format;
import org.apache.beam.sdk.transforms.SerializableFunction;
import org.apache.beam.sdk.values.Row;
import org.checkerframework.checker.nullness.qual.Nullable;

/** Utilities for finding external raw schema providers based on locator. */
public class ExternalRawSchemaHelper {

  public interface ExternalSchemaConverter {
    Schema convert(String from);

    Format supportedFormat();
  }

  public interface ExternalSchemaToRowMapper {
    SerializableFunction<byte[], Row> lambda(String rawSchema, Schema beamSchema);

    Format supportedFormat();
  }

  private static class Providers {
    private static final ServiceLoader<ExternalRawSchemaProvider> INSTANCE =
        ServiceLoader.load(ExternalRawSchemaProvider.class);

    private static final ServiceLoader<ExternalSchemaConverter> CONVERTERS =
        ServiceLoader.load(ExternalSchemaConverter.class);

    private static final ServiceLoader<ExternalSchemaToRowMapper> MAPPERS =
        ServiceLoader.load(ExternalSchemaToRowMapper.class);
  }

  public static @Nullable Schema getSchema(ExternalRawSchemaLocator locator) {
    for (ExternalRawSchemaProvider provider : Providers.INSTANCE) {
      if (provider.getProviderIdentifier().equals(locator.getProviderIdentifier())) {
        RawSchemaWithFormat schema = provider.getRawSchema(locator);
        if (schema == null) {
          return null;
        }
        Format format = schema.getFormat();

        for (ExternalSchemaConverter converter : Providers.CONVERTERS) {
          if (converter.supportedFormat() == format) {
            return converter.convert(schema.getRawSchema());
          }
        }
      }
    }
    return null;
  }

  public static @Nullable SerializableFunction<byte[], Row> getMapper(
      ExternalRawSchemaLocator locator) {
    for (ExternalRawSchemaProvider provider : Providers.INSTANCE) {
      if (provider.getProviderIdentifier().equals(locator.getProviderIdentifier())) {
        RawSchemaWithFormat schema = provider.getRawSchema(locator);
        if (schema == null) {
          return null;
        }
        Format format = schema.getFormat();

        Schema beamSchema = null;
        for (ExternalSchemaConverter converter : Providers.CONVERTERS) {
          if (converter.supportedFormat() == format) {
            beamSchema = converter.convert(schema.getRawSchema());
          }
        }
        if (beamSchema == null) {
          return null;
        }
        for (ExternalSchemaToRowMapper mappers : Providers.MAPPERS) {
          if (mappers.supportedFormat() == format) {
            return mappers.lambda(schema.getRawSchema(), beamSchema);
          }
        }
      }
    }
    return null;
  }
}
