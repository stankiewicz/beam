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
package org.apache.beam.sdk.io.kafka;

import org.apache.beam.sdk.schemas.transforms.ExternalRawSchemaLocator;
import org.apache.beam.sdk.schemas.transforms.ExternalRawSchemaProvider;
import org.apache.beam.sdk.schemas.transforms.RawSchemaWithFormat;
import org.checkerframework.checker.initialization.qual.Initialized;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.checkerframework.checker.nullness.qual.Nullable;
import org.checkerframework.checker.nullness.qual.UnknownKeyFor;

public class ConfluentSchemaRegistryProvider implements ExternalRawSchemaProvider {

  private static final String IDENTIFIER = "ConfluentSchemaRegistry";

  @Override
  public @UnknownKeyFor @Nullable @Initialized RawSchemaWithFormat getRawSchema(
      @UnknownKeyFor @NonNull @Initialized ExternalRawSchemaLocator locator) {
    return null;
  }

  @Override
  public String getProviderIdentifier() {
    return IDENTIFIER;
  }

  public static class ConfluentSchemaRegistryLocator implements ExternalRawSchemaLocator {

    ConfluentSchemaRegistryLocator(
        String confluentSchemaRegistryUrl, String confluentSchemaRegistrySubject) {
      this.confluentSchemaRegistrySubject = confluentSchemaRegistrySubject;
      this.confluentSchemaRegistryUrl = confluentSchemaRegistryUrl;
    }

    String confluentSchemaRegistryUrl;

    String confluentSchemaRegistrySubject;

    @Override
    public String getProviderIdentifier() {
      return IDENTIFIER;
    }

    public static ConfluentSchemaRegistryLocator of(
        String confluentSchemaRegistryUrl, String confluentSchemaRegistrySubject) {
      return new ConfluentSchemaRegistryLocator(
          confluentSchemaRegistryUrl, confluentSchemaRegistrySubject);
    }
  }
}
