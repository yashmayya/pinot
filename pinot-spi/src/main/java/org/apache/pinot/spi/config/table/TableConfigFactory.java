/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pinot.spi.config.table;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.helix.zookeeper.datamodel.ZNRecord;
import org.apache.pinot.spi.config.TableConfigs;
import org.apache.pinot.spi.data.Schema;
import org.apache.pinot.spi.utils.JsonUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class TableConfigFactory {

  private TableConfigFactory() {
  }

  private static final Logger LOGGER = LoggerFactory.getLogger(TableConfigFactory.class);
  private static Class<? extends TableConfig> _tableConfigClass = DefaultTableConfig.class;

  public static void registerTableConfigClass(Class<? extends TableConfig> tableConfigClass) {
    _tableConfigClass = tableConfigClass;
  }

  public static Class<? extends TableConfig> getTableConfigClass() {
    return _tableConfigClass;
  }

  public static Pair<? extends TableConfig, Map<String, Object>> tableConfigAndUnrecognizedProperties(
      String tableConfigStr)
      throws IOException {
    return JsonUtils.stringToObjectAndUnrecognizedProperties(tableConfigStr, _tableConfigClass);
  }

  public static Pair<TableConfigs, Map<String, Object>> tableConfigsAndUnrecognizedProperties(
      String tableConfigsStr)
      throws IOException {
    JsonNode jsonNode = JsonUtils.stringToJsonNode(tableConfigsStr);
    String tableName = jsonNode.get("tableName").asText();
    Schema schema = JsonUtils.jsonNodeToObject(jsonNode.get("schema"), Schema.class);
    TableConfig offline = null;
    Map<String, Object> unrecognizedProperties = new HashMap<>();
    if (jsonNode.has("offline")) {
      Pair<? extends TableConfig, Map<String, Object>> pair =
          tableConfigAndUnrecognizedProperties(jsonNode.get("offline").toString());
      offline = pair.getLeft();
      unrecognizedProperties.putAll(pair.getRight());
    }
    TableConfig realtime = null;
    if (jsonNode.has("realtime")) {
      Pair<? extends TableConfig, Map<String, Object>> pair =
          tableConfigAndUnrecognizedProperties(jsonNode.get("realtime").toString());
      realtime = pair.getLeft();
      unrecognizedProperties.putAll(pair.getRight());
    }
    TableConfigs tableConfigs = new TableConfigs(tableName, schema, offline, realtime);
    return Pair.of(tableConfigs, unrecognizedProperties);
  }

  public static TableConfigs tableConfigsFromString(String tableConfigsStr)
      throws IOException {
    return tableConfigsAndUnrecognizedProperties(tableConfigsStr).getLeft();
  }

  public static TableConfig fromZNRecord(ZNRecord znRecord) {
    TableConfig tableConfig;
    try {
      tableConfig = _tableConfigClass.getConstructor().newInstance();
      tableConfig.deserializeFromZNRecord(znRecord);
    } catch (Exception e) {
      LOGGER.error("Caught exception while creating table config from ZNRecord: {}, table config class: {}", znRecord,
          _tableConfigClass.getName(), e);
      throw new RuntimeException(e);
    }
    return tableConfig;
  }
}
