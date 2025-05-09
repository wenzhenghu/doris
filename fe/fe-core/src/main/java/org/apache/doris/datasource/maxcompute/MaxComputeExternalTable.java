// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.datasource.maxcompute;

import org.apache.doris.catalog.ArrayType;
import org.apache.doris.catalog.Column;
import org.apache.doris.catalog.Env;
import org.apache.doris.catalog.MapType;
import org.apache.doris.catalog.PartitionItem;
import org.apache.doris.catalog.ScalarType;
import org.apache.doris.catalog.StructField;
import org.apache.doris.catalog.StructType;
import org.apache.doris.catalog.Type;
import org.apache.doris.datasource.ExternalTable;
import org.apache.doris.datasource.SchemaCacheValue;
import org.apache.doris.datasource.TablePartitionValues;
import org.apache.doris.datasource.mvcc.MvccSnapshot;
import org.apache.doris.thrift.TMCTable;
import org.apache.doris.thrift.TTableDescriptor;
import org.apache.doris.thrift.TTableType;

import com.aliyun.odps.OdpsType;
import com.aliyun.odps.Table;
import com.aliyun.odps.type.ArrayTypeInfo;
import com.aliyun.odps.type.CharTypeInfo;
import com.aliyun.odps.type.DecimalTypeInfo;
import com.aliyun.odps.type.MapTypeInfo;
import com.aliyun.odps.type.StructTypeInfo;
import com.aliyun.odps.type.TypeInfo;
import com.aliyun.odps.type.VarcharTypeInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * MaxCompute external table.
 */
public class MaxComputeExternalTable extends ExternalTable {
    public MaxComputeExternalTable(long id, String name, String remoteName, MaxComputeExternalCatalog catalog,
            MaxComputeExternalDatabase db) {
        super(id, name, remoteName, catalog, db, TableType.MAX_COMPUTE_EXTERNAL_TABLE);
    }

    private Map<String, com.aliyun.odps.Column> columnNameToOdpsColumn = new HashMap();

    @Override
    protected synchronized void makeSureInitialized() {
        super.makeSureInitialized();
        if (!objectCreated) {
            objectCreated = true;
        }
    }

    @Override
    public boolean supportInternalPartitionPruned() {
        return true;
    }

    @Override
    public List<Column> getPartitionColumns(Optional<MvccSnapshot> snapshot) {
        return getPartitionColumns();
    }

    public List<Column> getPartitionColumns() {
        makeSureInitialized();
        Optional<SchemaCacheValue> schemaCacheValue = getSchemaCacheValue();
        return schemaCacheValue.map(value -> ((MaxComputeSchemaCacheValue) value).getPartitionColumns())
                .orElse(Collections.emptyList());
    }

    @Override
    public Map<String, PartitionItem> getNameToPartitionItems(Optional<MvccSnapshot> snapshot) {
        if (getPartitionColumns().isEmpty()) {
            return Collections.emptyMap();
        }

        TablePartitionValues tablePartitionValues = getPartitionValues();
        Map<Long, PartitionItem> idToPartitionItem = tablePartitionValues.getIdToPartitionItem();
        Map<Long, String> idToNameMap = tablePartitionValues.getPartitionIdToNameMap();

        Map<String, PartitionItem> nameToPartitionItem = Maps.newHashMapWithExpectedSize(idToPartitionItem.size());
        for (Entry<Long, PartitionItem> entry : idToPartitionItem.entrySet()) {
            nameToPartitionItem.put(idToNameMap.get(entry.getKey()), entry.getValue());
        }
        return nameToPartitionItem;
    }

    private TablePartitionValues getPartitionValues() {
        makeSureInitialized();
        Optional<SchemaCacheValue> schemaCacheValue = getSchemaCacheValue();
        if (!schemaCacheValue.isPresent()) {
            return new TablePartitionValues();
        }
        Table odpsTable = ((MaxComputeSchemaCacheValue) schemaCacheValue.get()).getOdpsTable();
        String projectName = odpsTable.getProject();
        String tableName = odpsTable.getName();
        MaxComputeMetadataCache metadataCache = Env.getCurrentEnv().getExtMetaCacheMgr()
                .getMaxComputeMetadataCache(catalog.getId());
        return metadataCache.getCachedPartitionValues(
                new MaxComputeCacheKey(projectName, tableName),
                key -> loadPartitionValues((MaxComputeSchemaCacheValue) schemaCacheValue.get()));
    }

    private TablePartitionValues loadPartitionValues(MaxComputeSchemaCacheValue schemaCacheValue) {
        List<String> partitionSpecs = schemaCacheValue.getPartitionSpecs();
        List<Type> partitionTypes = schemaCacheValue.getPartitionTypes();
        List<String> partitionColumnNames = schemaCacheValue.getPartitionColumnNames();
        TablePartitionValues partitionValues = new TablePartitionValues();
        partitionValues.addPartitions(partitionSpecs,
                partitionSpecs.stream()
                        .map(p -> parsePartitionValues(partitionColumnNames, p))
                        .collect(Collectors.toList()),
                partitionTypes, Collections.nCopies(partitionSpecs.size(), 0L));
        return partitionValues;
    }

    /**
     * parse all values from partitionPath to a single list.
     * In MaxCompute : Support special characters : _$#.!@
     * Ref : MaxCompute Error Code: ODPS-0130071  Invalid partition value.
     *
     * @param partitionColumns partitionColumns can contain the part1,part2,part3...
     * @param partitionPath partitionPath format is like the 'part1=123/part2=abc/part3=1bc'
     * @return all values of partitionPath
     */
    private static List<String> parsePartitionValues(List<String> partitionColumns, String partitionPath) {
        String[] partitionFragments = partitionPath.split("/");
        if (partitionFragments.length != partitionColumns.size()) {
            throw new RuntimeException("Failed to parse partition values of path: " + partitionPath);
        }
        List<String> partitionValues = new ArrayList<>(partitionFragments.length);
        for (int i = 0; i < partitionFragments.length; i++) {
            String prefix = partitionColumns.get(i) + "=";
            if (partitionFragments[i].startsWith(prefix)) {
                partitionValues.add(partitionFragments[i].substring(prefix.length()));
            } else {
                partitionValues.add(partitionFragments[i]);
            }
        }
        return partitionValues;
    }

    public Map<String, com.aliyun.odps.Column> getColumnNameToOdpsColumn() {
        return columnNameToOdpsColumn;
    }

    @Override
    public Optional<SchemaCacheValue> initSchema() {
        // this method will be called at semantic parsing.
        makeSureInitialized();
        Table odpsTable = ((MaxComputeExternalCatalog) catalog).getClient().tables().get(dbName, name);
        List<com.aliyun.odps.Column> columns = odpsTable.getSchema().getColumns();


        for (com.aliyun.odps.Column column : columns) {
            columnNameToOdpsColumn.put(column.getName(), column);
        }

        List<Column> schema = Lists.newArrayListWithCapacity(columns.size());
        for (com.aliyun.odps.Column field : columns) {
            schema.add(new Column(field.getName(), mcTypeToDorisType(field.getTypeInfo()), true, null,
                    field.isNullable(), field.getComment(), true, -1));
        }

        List<com.aliyun.odps.Column> partitionColumns = odpsTable.getSchema().getPartitionColumns();
        List<String> partitionColumnNames = new ArrayList<>(partitionColumns.size());
        List<Type> partitionTypes = new ArrayList<>(partitionColumns.size());

        // sort partition columns to align partitionTypes and partitionName.
        List<Column> partitionDorisColumns = new ArrayList<>();
        for (com.aliyun.odps.Column partColumn : partitionColumns) {
            Type partitionType = mcTypeToDorisType(partColumn.getTypeInfo());
            Column dorisCol = new Column(partColumn.getName(), partitionType, true, null,
                    true, partColumn.getComment(), true, -1);

            columnNameToOdpsColumn.put(partColumn.getName(), partColumn);
            partitionColumnNames.add(partColumn.getName());
            partitionDorisColumns.add(dorisCol);
            partitionTypes.add(partitionType);
            schema.add(dorisCol);
        }

        List<String> partitionSpecs;
        if (!partitionColumns.isEmpty()) {
            partitionSpecs = odpsTable.getPartitions().stream()
                    .map(e -> e.getPartitionSpec().toString(false, true))
                    .collect(Collectors.toList());
        } else {
            partitionSpecs = ImmutableList.of();
        }

        return Optional.of(new MaxComputeSchemaCacheValue(schema, odpsTable, partitionColumnNames,
                partitionSpecs, partitionDorisColumns, partitionTypes));
    }

    private Type mcTypeToDorisType(TypeInfo typeInfo) {
        OdpsType odpsType = typeInfo.getOdpsType();
        switch (odpsType) {
            case VOID: {
                return Type.NULL;
            }
            case BOOLEAN: {
                return Type.BOOLEAN;
            }
            case TINYINT: {
                return Type.TINYINT;
            }
            case SMALLINT: {
                return Type.SMALLINT;
            }
            case INT: {
                return Type.INT;
            }
            case BIGINT: {
                return Type.BIGINT;
            }
            case CHAR: {
                CharTypeInfo charType = (CharTypeInfo) typeInfo;
                return ScalarType.createChar(charType.getLength());
            }
            case STRING: {
                return ScalarType.createStringType();
            }
            case VARCHAR: {
                VarcharTypeInfo varcharType = (VarcharTypeInfo) typeInfo;
                return ScalarType.createVarchar(varcharType.getLength());
            }
            case JSON: {
                return Type.UNSUPPORTED;
                // return Type.JSONB;
            }
            case FLOAT: {
                return Type.FLOAT;
            }
            case DOUBLE: {
                return Type.DOUBLE;
            }
            case DECIMAL: {
                DecimalTypeInfo decimal = (DecimalTypeInfo) typeInfo;
                return ScalarType.createDecimalV3Type(decimal.getPrecision(), decimal.getScale());
            }
            case DATE: {
                return ScalarType.createDateV2Type();
            }
            case DATETIME: {
                return ScalarType.createDatetimeV2Type(3);
            }
            case TIMESTAMP:
            case TIMESTAMP_NTZ: {
                return ScalarType.createDatetimeV2Type(6);
            }
            case ARRAY: {
                ArrayTypeInfo arrayType = (ArrayTypeInfo) typeInfo;
                Type innerType = mcTypeToDorisType(arrayType.getElementTypeInfo());
                return ArrayType.create(innerType, true);
            }
            case MAP: {
                MapTypeInfo mapType = (MapTypeInfo) typeInfo;
                return new MapType(mcTypeToDorisType(mapType.getKeyTypeInfo()),
                        mcTypeToDorisType(mapType.getValueTypeInfo()));
            }
            case STRUCT: {
                ArrayList<StructField> fields = new ArrayList<>();
                StructTypeInfo structType = (StructTypeInfo) typeInfo;
                List<String> fieldNames = structType.getFieldNames();
                List<TypeInfo> fieldTypeInfos = structType.getFieldTypeInfos();
                for (int i = 0; i < structType.getFieldCount(); i++) {
                    Type innerType = mcTypeToDorisType(fieldTypeInfos.get(i));
                    fields.add(new StructField(fieldNames.get(i), innerType));
                }
                return new StructType(fields);
            }
            case BINARY:
            case INTERVAL_DAY_TIME:
            case INTERVAL_YEAR_MONTH:
                return Type.UNSUPPORTED;
            default:
                throw new IllegalArgumentException("Cannot transform unknown type: " + odpsType);
        }
    }

    @Override
    public TTableDescriptor toThrift() {
        // ak sk endpoint project  quota
        List<Column> schema = getFullSchema();
        TMCTable tMcTable = new TMCTable();
        MaxComputeExternalCatalog mcCatalog = ((MaxComputeExternalCatalog) catalog);

        tMcTable.setAccessKey(mcCatalog.getAccessKey());
        tMcTable.setSecretKey(mcCatalog.getSecretKey());
        tMcTable.setOdpsUrl("deprecated");
        tMcTable.setRegion("deprecated");
        tMcTable.setEndpoint(mcCatalog.getEndpoint());
        // use mc project as dbName
        tMcTable.setProject(dbName);
        tMcTable.setQuota(mcCatalog.getQuota());

        tMcTable.setTunnelUrl("deprecated");
        tMcTable.setProject("deprecated");
        tMcTable.setTable(name);
        TTableDescriptor tTableDescriptor = new TTableDescriptor(getId(), TTableType.MAX_COMPUTE_TABLE,
                schema.size(), 0, getName(), dbName);
        tTableDescriptor.setMcTable(tMcTable);
        return tTableDescriptor;
    }

    public Table getOdpsTable() {
        makeSureInitialized();
        Optional<SchemaCacheValue> schemaCacheValue = getSchemaCacheValue();
        return schemaCacheValue.map(value -> ((MaxComputeSchemaCacheValue) value).getOdpsTable())
                .orElse(null);
    }
}
