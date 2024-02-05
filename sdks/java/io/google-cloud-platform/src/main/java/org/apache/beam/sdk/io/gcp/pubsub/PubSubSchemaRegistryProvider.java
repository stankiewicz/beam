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
package org.apache.beam.sdk.io.gcp.pubsub;

import com.google.api.gax.rpc.NotFoundException;
import com.google.cloud.pubsub.v1.SchemaServiceClient;
import com.google.pubsub.v1.Schema;
import com.google.pubsub.v1.SchemaName;
import java.io.IOException;
import java.util.Optional;
import org.apache.beam.sdk.schemas.transforms.ExternalRawSchemaLocator;
import org.apache.beam.sdk.schemas.transforms.ExternalRawSchemaProvider;
import org.checkerframework.checker.nullness.qual.Nullable;

public class PubSubSchemaRegistryProvider implements ExternalRawSchemaProvider {

  private static final String IDENTIFIER = "PubSubSchemaRegistry";

  @Override
  @Nullable
  public Format getFormat(ExternalRawSchemaLocator locator) {
    PubSubSchemaRegistryLocator pubSubSchemaRegistryLocator = (PubSubSchemaRegistryLocator) locator;
    Optional<Schema> schema = findSchema(pubSubSchemaRegistryLocator);
    if(schema.isPresent()){
      switch(schema.get().getType()){

        case PROTOCOL_BUFFER:
          return Format.PROTOCOL_BUFFER;
        case AVRO:
          return Format.AVRO;
        case TYPE_UNSPECIFIED:
        case UNRECOGNIZED:
        default:
          return Format.OTHER;
      }
    }else{
      return null;
    }
  }

  Optional<Schema> findSchema(PubSubSchemaRegistryLocator locator){
    try (SchemaServiceClient schemaServiceClient = SchemaServiceClient.create()) {
      return Optional.of(schemaServiceClient.getSchema(locator.schemaName));
    } catch (NotFoundException e) {
      throw new RuntimeException(e);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  @Override
  @Nullable
  public String getRawSchema(ExternalRawSchemaLocator locator) {
    PubSubSchemaRegistryLocator pubSubSchemaRegistryLocator = (PubSubSchemaRegistryLocator) locator;
    Optional<Schema> schema = findSchema(pubSubSchemaRegistryLocator);
    if(schema.isPresent()){
      return schema.get().getDefinition();
    }else{
      return null;
    }
  }

  @Override
  public String getProviderIdentifier() {
    return IDENTIFIER;
  }

  public static class PubSubSchemaRegistryLocator implements ExternalRawSchemaLocator {

    private final SchemaName schemaName;

    PubSubSchemaRegistryLocator(String projectId, String schemaId) {
      schemaName = SchemaName.of(projectId, schemaId);
    }

    @Override
    public String getProviderIdentifier() {
      return IDENTIFIER;
    }

    public static PubSubSchemaRegistryLocator of(String projectId, String schemaId) {
      return new PubSubSchemaRegistryLocator(projectId, schemaId);
    }
  }
}
