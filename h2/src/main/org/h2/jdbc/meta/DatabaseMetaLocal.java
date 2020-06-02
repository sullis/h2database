/*
 * Copyright 2004-2020 H2 Group. Multiple-Licensed under the MPL 2.0,
 * and the EPL 1.0 (https://h2database.com/html/license.html).
 * Initial Developer: H2 Group
 */
package org.h2.jdbc.meta;

import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;

import org.h2.api.ErrorCode;
import org.h2.command.CommandInterface;
import org.h2.command.dml.Help;
import org.h2.constraint.Constraint;
import org.h2.constraint.ConstraintActionType;
import org.h2.constraint.ConstraintReferential;
import org.h2.constraint.ConstraintUnique;
import org.h2.engine.Constants;
import org.h2.engine.Database;
import org.h2.engine.Session;
import org.h2.expression.ParameterInterface;
import org.h2.expression.condition.CompareLike;
import org.h2.index.Index;
import org.h2.index.IndexType;
import org.h2.message.DbException;
import org.h2.result.ResultInterface;
import org.h2.result.SimpleResult;
import org.h2.result.SortOrder;
import org.h2.schema.Schema;
import org.h2.schema.SchemaObjectBase;
import org.h2.table.Column;
import org.h2.table.IndexColumn;
import org.h2.table.Table;
import org.h2.table.TableSynonym;
import org.h2.util.MathUtils;
import org.h2.util.StringUtils;
import org.h2.util.Utils;
import org.h2.value.DataType;
import org.h2.value.TypeInfo;
import org.h2.value.Value;
import org.h2.value.ValueBigint;
import org.h2.value.ValueBoolean;
import org.h2.value.ValueInteger;
import org.h2.value.ValueNull;
import org.h2.value.ValueSmallint;
import org.h2.value.ValueVarchar;

/**
 * Local implementation of database meta information.
 */
public final class DatabaseMetaLocal extends DatabaseMetaLocalBase {

    private static final Value PERCENT = ValueVarchar.get("%");

    private static final Value BACKSLASH = ValueVarchar.get("\\");

    private static final Value YES = ValueVarchar.get("YES");

    private static final Value NO = ValueVarchar.get("NO");

    private static final Value SCHEMA_MAIN = ValueVarchar.get(Constants.SCHEMA_MAIN);

    private static final ValueSmallint BEST_ROW_SESSION = ValueSmallint.get((short) DatabaseMetaData.bestRowSession);

    private static final ValueSmallint BEST_ROW_NOT_PSEUDO = ValueSmallint
            .get((short) DatabaseMetaData.bestRowNotPseudo);

    private static final ValueInteger COLUMN_NO_NULLS = ValueInteger.get(DatabaseMetaData.columnNoNulls);

    private static final ValueInteger COLUMN_NULLABLE = ValueInteger.get(DatabaseMetaData.columnNullable);

    private static final ValueSmallint IMPORTED_KEY_CASCADE = ValueSmallint
            .get((short) DatabaseMetaData.importedKeyCascade);

    private static final ValueSmallint IMPORTED_KEY_RESTRICT = ValueSmallint
            .get((short) DatabaseMetaData.importedKeyRestrict);

    private static final ValueSmallint IMPORTED_KEY_DEFAULT = ValueSmallint
            .get((short) DatabaseMetaData.importedKeySetDefault);

    private static final ValueSmallint IMPORTED_KEY_SET_NULL = ValueSmallint
            .get((short) DatabaseMetaData.importedKeySetNull);

    private static final ValueSmallint IMPORTED_KEY_NOT_DEFERRABLE = ValueSmallint
            .get((short) DatabaseMetaData.importedKeyNotDeferrable);

    private static final ValueSmallint TABLE_INDEX_STATISTIC = ValueSmallint.get(DatabaseMetaData.tableIndexStatistic);

    private static final ValueSmallint TABLE_INDEX_HASHED = ValueSmallint.get(DatabaseMetaData.tableIndexHashed);

    private static final ValueSmallint TABLE_INDEX_OTHER = ValueSmallint.get(DatabaseMetaData.tableIndexOther);

    // This list must be ordered
    private static final String[] TABLE_TYPES = { "BASE TABLE", "GLOBAL TEMPORARY", "LOCAL TEMPORARY", "SYNONYM",
            "VIEW" };

    private static final ValueSmallint TYPE_NULLABLE = ValueSmallint.get((short) DatabaseMetaData.typeNullable);

    private static final ValueSmallint TYPE_SEARCHABLE = ValueSmallint.get((short) DatabaseMetaData.typeSearchable);

    private final Session session;

    private Comparator<String> comparator;

    public DatabaseMetaLocal(Session session) {
        this.session = session;
    }

    @Override
    public String getSQLKeywords() {
        return "CURRENT_CATALOG," //
                + "CURRENT_SCHEMA," //
                + "GROUPS," //
                + "IF,ILIKE,INTERSECTS," //
                + "LIMIT," //
                + "MINUS," //
                + "OFFSET," //
                + "QUALIFY," //
                + "REGEXP,ROWNUM," //
                + "SYSDATE,SYSTIME,SYSTIMESTAMP," //
                + "TODAY,TOP,"//
                + "_ROWID_";
    }

    @Override
    public String getNumericFunctions() {
        return getFunctions("Functions (Numeric)");
    }

    @Override
    public String getStringFunctions() {
        return getFunctions("Functions (String)");
    }

    @Override
    public String getSystemFunctions() {
        return getFunctions("Functions (System)");
    }

    @Override
    public String getTimeDateFunctions() {
        return getFunctions("Functions (Time and Date)");
    }

    private String getFunctions(String section) {
        checkClosed();
        StringBuilder builder = new StringBuilder();
        try {
            ResultSet rs = Help.getTable();
            while (rs.next()) {
                if (rs.getString(1).trim().equals(section)) {
                    if (builder.length() != 0) {
                        builder.append(',');
                    }
                    String f = rs.getString(2).trim();
                    int spaceIndex = f.indexOf(' ');
                    if (spaceIndex >= 0) {
                        // remove 'Function' from 'INSERT Function'
                        StringUtils.trimSubstring(builder, f, 0, spaceIndex);
                    } else {
                        builder.append(f);
                    }
                }
            }
        } catch (Exception e) {
            throw DbException.convert(e);
        }
        return builder.toString();
    }

    @Override
    public String getSearchStringEscape() {
        return session.getDatabase().getSettings().defaultEscape;
    }

    @Override
    public ResultInterface getProcedures(String catalog, String schemaPattern, String procedureNamePattern) {
        return executeQuery("SELECT " //
                + "ALIAS_CATALOG PROCEDURE_CAT, " //
                + "ALIAS_SCHEMA PROCEDURE_SCHEM, " //
                + "ALIAS_NAME PROCEDURE_NAME, " //
                + "COLUMN_COUNT NUM_INPUT_PARAMS, " //
                + "ZERO() NUM_OUTPUT_PARAMS, " //
                + "ZERO() NUM_RESULT_SETS, " //
                + "REMARKS, " //
                + "RETURNS_RESULT PROCEDURE_TYPE, " //
                + "ALIAS_NAME SPECIFIC_NAME " //
                + "FROM INFORMATION_SCHEMA.FUNCTION_ALIASES " //
                + "WHERE ALIAS_CATALOG LIKE ?1 ESCAPE ?4 " //
                + "AND ALIAS_SCHEMA LIKE ?2 ESCAPE ?4 " //
                + "AND ALIAS_NAME LIKE ?3 ESCAPE ?4 " //
                + "ORDER BY PROCEDURE_SCHEM, PROCEDURE_NAME, NUM_INPUT_PARAMS", //
                getCatalogPattern(catalog), //
                getSchemaPattern(schemaPattern), //
                getPattern(procedureNamePattern), //
                BACKSLASH);
    }

    @Override
    public ResultInterface getProcedureColumns(String catalog, String schemaPattern, String procedureNamePattern,
            String columnNamePattern) {
        return executeQuery("SELECT " //
                + "ALIAS_CATALOG PROCEDURE_CAT, " //
                + "ALIAS_SCHEMA PROCEDURE_SCHEM, " //
                + "ALIAS_NAME PROCEDURE_NAME, " //
                + "COLUMN_NAME, " //
                + "COLUMN_TYPE, " //
                + "DATA_TYPE, " //
                + "TYPE_NAME, " //
                + "PRECISION, " //
                + "PRECISION LENGTH, " //
                + "SCALE, " //
                + "RADIX, " //
                + "NULLABLE, " //
                + "REMARKS, " //
                + "COLUMN_DEFAULT COLUMN_DEF, " //
                + "ZERO() SQL_DATA_TYPE, " //
                + "ZERO() SQL_DATETIME_SUB, " //
                + "ZERO() CHAR_OCTET_LENGTH, " //
                + "POS ORDINAL_POSITION, " //
                + "?1 IS_NULLABLE, " //
                + "ALIAS_NAME SPECIFIC_NAME " //
                + "FROM INFORMATION_SCHEMA.FUNCTION_COLUMNS " //
                + "WHERE ALIAS_CATALOG LIKE ?2 ESCAPE ?6 " //
                + "AND ALIAS_SCHEMA LIKE ?3 ESCAPE ?6 " //
                + "AND ALIAS_NAME LIKE ?4 ESCAPE ?6 " //
                + "AND COLUMN_NAME LIKE ?5 ESCAPE ?6 " //
                + "ORDER BY PROCEDURE_SCHEM, PROCEDURE_NAME, ORDINAL_POSITION", //
                YES, //
                getCatalogPattern(catalog), //
                getSchemaPattern(schemaPattern), //
                getPattern(procedureNamePattern), //
                getPattern(columnNamePattern), //
                BACKSLASH);
    }

    @Override
    public ResultInterface getTables(String catalog, String schemaPattern, String tableNamePattern, String[] types) {
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_TYPE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SELF_REFERENCING_COL_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("REF_GENERATION", TypeInfo.TYPE_VARCHAR);
        if (!checkCatalogName(catalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        HashSet<String> typesSet;
        if (types != null) {
            typesSet = new HashSet<>(8);
            for (String type : types) {
                int idx = Arrays.binarySearch(TABLE_TYPES, type);
                if (idx >= 0) {
                    typesSet.add(TABLE_TYPES[idx]);
                } else if (type.equals("TABLE")) {
                    typesSet.add("BASE TABLE");
                }
            }
            if (typesSet.isEmpty()) {
                return result;
            }
        } else {
            typesSet = null;
        }
        for (Schema schema : getSchemasForPattern(schemaPattern)) {
            Value schemaValue = getString(schema.getName());
            for (SchemaObjectBase object : getTablesForPattern(schema, tableNamePattern)) {
                Value tableName = getString(object.getName());
                if (object instanceof Table) {
                    Table t = (Table) object;
                    if (!t.isHidden()) {
                        getTablesAdd(result, catalogValue, schemaValue, tableName, t, false, typesSet);
                    }
                } else {
                    getTablesAdd(result, catalogValue, schemaValue, tableName, ((TableSynonym) object).getSynonymFor(),
                            true, typesSet);
                }
            }
        }
        result.sortRows(new SortOrder(session, new int[] { 3, 0, 1, 2 }, new int[4], null));
        return result;
    }

    private void getTablesAdd(SimpleResult result, Value catalogValue, Value schemaValue, Value tableName, Table t,
            boolean synonym, HashSet<String> typesSet) {
        String type = synonym ? "SYNONYM" : t.getSQLTableType();
        if (typesSet != null && !typesSet.contains(type)) {
            return;
        }
        result.addRow(
                // TABLE_CAT
                catalogValue,
                // TABLE_SCHEM
                schemaValue,
                // TABLE_NAME
                tableName,
                // TABLE_TYPE
                getString(type),
                // REMARKS
                getString(t.getComment()),
                // TYPE_CAT
                ValueNull.INSTANCE,
                // TYPE_SCHEM
                ValueNull.INSTANCE,
                // TYPE_NAME
                ValueNull.INSTANCE,
                // SELF_REFERENCING_COL_NAME
                ValueNull.INSTANCE,
                // REF_GENERATION
                ValueNull.INSTANCE);
    }

    @Override
    public ResultInterface getSchemas() {
        return getSchemas(null, null);
    }

    @Override
    public ResultInterface getCatalogs() {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addRow(getString(session.getDatabase().getShortName()));
        return result;
    }

    @Override
    public ResultInterface getTableTypes() {
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_TYPE", TypeInfo.TYPE_VARCHAR);
        result.addRow(getString("BASE TABLE"));
        result.addRow(getString("GLOBAL TEMPORARY"));
        result.addRow(getString("LOCAL TEMPORARY"));
        result.addRow(getString("SYNONYM"));
        result.addRow(getString("VIEW"));
        return result;
    }

    @Override
    public ResultInterface getColumns(String catalog, String schemaPattern, String tableNamePattern,
            String columnNamePattern) {
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_SIZE", TypeInfo.TYPE_INTEGER);
        result.addColumn("BUFFER_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("DECIMAL_DIGITS", TypeInfo.TYPE_INTEGER);
        result.addColumn("NUM_PREC_RADIX", TypeInfo.TYPE_INTEGER);
        result.addColumn("NULLABLE", TypeInfo.TYPE_INTEGER);
        result.addColumn("REMARKS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_DEF", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SQL_DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("SQL_DATETIME_SUB", TypeInfo.TYPE_INTEGER);
        result.addColumn("CHAR_OCTET_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("ORDINAL_POSITION", TypeInfo.TYPE_INTEGER);
        result.addColumn("IS_NULLABLE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SCOPE_CATALOG", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SCOPE_SCHEMA", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SCOPE_TABLE", TypeInfo.TYPE_VARCHAR);
        result.addColumn("SOURCE_DATA_TYPE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("IS_AUTOINCREMENT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("IS_GENERATEDCOLUMN", TypeInfo.TYPE_VARCHAR);
        if (!checkCatalogName(catalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        CompareLike columnLike = getLike(columnNamePattern);
        for (Schema schema : getSchemasForPattern(schemaPattern)) {
            Value schemaValue = getString(schema.getName());
            for (SchemaObjectBase object : getTablesForPattern(schema, tableNamePattern)) {
                Value tableName = getString(object.getName());
                if (object instanceof Table) {
                    Table t = (Table) object;
                    if (!t.isHidden()) {
                        getColumnsAdd(result, catalogValue, schemaValue, tableName, t, columnLike);
                    }
                } else {
                    TableSynonym s = (TableSynonym) object;
                    Table t = s.getSynonymFor();
                    getColumnsAdd(result, catalogValue, schemaValue, tableName, t, columnLike);
                }
            }
        }
        result.sortRows(new SortOrder(session, new int[] { 0, 1, 2, 16 }, new int[4], null));
        return result;
    }

    private void getColumnsAdd(SimpleResult result, Value catalogValue, Value schemaValue, Value tableName, Table t,
            CompareLike columnLike) {
        Column[] columns = t.getColumns();
        for (int i = 0; i < columns.length; i++) {
            Column c = columns[i];
            String name = c.getName();
            if (columnLike != null && !columnLike.test(name)) {
                continue;
            }
            TypeInfo type = c.getType();
            DataType dt = DataType.getDataType(type.getValueType());
            ValueInteger precision = ValueInteger.get(MathUtils.convertLongToInt(type.getPrecision()));
            boolean nullable = c.isNullable(), isGenerated = c.getGenerated();
            result.addRow(
                    // TABLE_CAT
                    catalogValue,
                    // TABLE_SCHEM
                    schemaValue,
                    // TABLE_NAME
                    tableName,
                    // COLUMN_NAME
                    getString(name),
                    // DATA_TYPE
                    ValueInteger.get(dt.sqlType),
                    // TYPE_NAME
                    getString(dt.name),
                    // COLUMN_SIZE
                    precision,
                    // BUFFER_LENGTH
                    ValueNull.INSTANCE,
                    // DECIMAL_DIGITS
                    ValueInteger.get(type.getScale()),
                    // NUM_PREC_RADIX
                    DataType.isNumericType(type.getValueType()) ? ValueInteger.get(10) : ValueNull.INSTANCE,
                    // NULLABLE
                    nullable ? COLUMN_NULLABLE : COLUMN_NO_NULLS,
                    // REMARKS
                    getString(c.getComment()),
                    // COLUMN_DEF
                    isGenerated ? ValueNull.INSTANCE : getString(c.getDefaultSQL()),
                    // SQL_DATA_TYPE (unused)
                    ValueNull.INSTANCE,
                    // SQL_DATETIME_SUB (unused)
                    ValueNull.INSTANCE,
                    // CHAR_OCTET_LENGTH
                    precision,
                    // ORDINAL_POSITION
                    ValueInteger.get(i + 1),
                    // IS_NULLABLE
                    nullable ? YES : NO,
                    // SCOPE_CATALOG
                    ValueNull.INSTANCE,
                    // SCOPE_SCHEMA
                    ValueNull.INSTANCE,
                    // SCOPE_TABLE
                    ValueNull.INSTANCE,
                    // SOURCE_DATA_TYPE
                    ValueNull.INSTANCE,
                    // IS_AUTOINCREMENT
                    c.isAutoIncrement() ? YES : NO,
                    // IS_GENERATEDCOLUMN
                    isGenerated ? YES : NO);
        }
    }

    @Override
    public ResultInterface getColumnPrivileges(String catalog, String schema, String table, String columnNamePattern) {
        return executeQuery("SELECT " //
                + "TABLE_CATALOG TABLE_CAT, " //
                + "TABLE_SCHEMA TABLE_SCHEM, " //
                + "TABLE_NAME, " //
                + "COLUMN_NAME, " //
                + "GRANTOR, " //
                + "GRANTEE, " //
                + "PRIVILEGE_TYPE PRIVILEGE, " //
                + "IS_GRANTABLE " //
                + "FROM INFORMATION_SCHEMA.COLUMN_PRIVILEGES " //
                + "WHERE TABLE_CATALOG LIKE ?1 ESCAPE ?5 " //
                + "AND TABLE_SCHEMA LIKE ?2 ESCAPE ?5 " //
                + "AND TABLE_NAME = ?3 " //
                + "AND COLUMN_NAME LIKE ?4 ESCAPE ?5 " //
                + "ORDER BY COLUMN_NAME, PRIVILEGE", //
                getCatalogPattern(catalog), //
                getSchemaPattern(schema), //
                getString(table), //
                getPattern(columnNamePattern), //
                BACKSLASH);
    }

    @Override
    public ResultInterface getTablePrivileges(String catalog, String schemaPattern, String tableNamePattern) {
        return executeQuery("SELECT " //
                + "TABLE_CATALOG TABLE_CAT, " //
                + "TABLE_SCHEMA TABLE_SCHEM, " //
                + "TABLE_NAME, " //
                + "GRANTOR, " //
                + "GRANTEE, " //
                + "PRIVILEGE_TYPE PRIVILEGE, " //
                + "IS_GRANTABLE " //
                + "FROM INFORMATION_SCHEMA.TABLE_PRIVILEGES " //
                + "WHERE TABLE_CATALOG LIKE ?1 ESCAPE ?4 " //
                + "AND TABLE_SCHEMA LIKE ?2 ESCAPE ?4 " //
                + "AND TABLE_NAME LIKE ?3 ESCAPE ?4 " //
                + "ORDER BY TABLE_SCHEM, TABLE_NAME, PRIVILEGE", //
                getCatalogPattern(catalog), //
                getSchemaPattern(schemaPattern), //
                getPattern(tableNamePattern), //
                BACKSLASH);
    }

    @Override
    public ResultInterface getBestRowIdentifier(String catalog, String schema, String table, int scope,
            boolean nullable) {
        if (table == null) {
            throw DbException.getInvalidValueException("table", null);
        }
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("SCOPE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_SIZE", TypeInfo.TYPE_INTEGER);
        result.addColumn("BUFFER_LENGTH", TypeInfo.TYPE_INTEGER);
        result.addColumn("DECIMAL_DIGITS", TypeInfo.TYPE_SMALLINT);
        result.addColumn("PSEUDO_COLUMN", TypeInfo.TYPE_SMALLINT);
        if (!checkCatalogName(catalog)) {
            return result;
        }
        for (Schema s : getSchemas(schema)) {
            Table t = s.findTableOrView(session, table);
            if (t == null || t.isHidden()) {
                continue;
            }
            ArrayList<Constraint> constraints = t.getConstraints();
            if (constraints == null) {
                continue;
            }
            for (Constraint constraint : constraints) {
                if (constraint.getConstraintType() != Constraint.Type.PRIMARY_KEY) {
                    continue;
                }
                IndexColumn[] columns = ((ConstraintUnique) constraint).getColumns();
                for (int i = 0, l = columns.length; i < l; i++) {
                    IndexColumn ic = columns[i];
                    Column c = ic.column;
                    TypeInfo type = c.getType();
                    DataType dt = DataType.getDataType(type.getValueType());
                    result.addRow(
                            // SCOPE
                            BEST_ROW_SESSION,
                            // COLUMN_NAME
                            getString(c.getName()),
                            // DATA_TYPE
                            ValueInteger.get(dt.sqlType),
                            // TYPE_NAME
                            getString(dt.name), ValueInteger.get(MathUtils.convertLongToInt(type.getPrecision())),
                            // BUFFER_LENGTH
                            ValueNull.INSTANCE,
                            // DECIMAL_DIGITS
                            dt.supportsScale ? ValueSmallint.get(MathUtils.convertIntToShort(type.getScale()))
                                    : ValueNull.INSTANCE,
                            // PSEUDO_COLUMN
                            BEST_ROW_NOT_PSEUDO);
                }
            }
        }
        return result;
    }

    @Override
    public ResultInterface getPrimaryKeys(String catalog, String schema, String table) {
        if (table == null) {
            throw DbException.getInvalidValueException("table", null);
        }
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("KEY_SEQ", TypeInfo.TYPE_SMALLINT);
        result.addColumn("PK_NAME", TypeInfo.TYPE_VARCHAR);
        if (!checkCatalogName(catalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        for (Schema s : getSchemas(schema)) {
            Table t = s.findTableOrView(session, table);
            if (t == null || t.isHidden()) {
                continue;
            }
            ArrayList<Constraint> constraints = t.getConstraints();
            if (constraints == null) {
                continue;
            }
            for (Constraint constraint : constraints) {
                if (constraint.getConstraintType() != Constraint.Type.PRIMARY_KEY) {
                    continue;
                }
                Value schemaValue = getString(s.getName());
                Value tableValue = getString(t.getName());
                Value pkValue = getString(constraint.getName());
                IndexColumn[] columns = ((ConstraintUnique) constraint).getColumns();
                for (int i = 0, l = columns.length; i < l;) {
                    result.addRow(
                            // TABLE_CAT
                            catalogValue,
                            // TABLE_SCHEM
                            schemaValue,
                            // TABLE_NAME
                            tableValue,
                            // COLUMN_NAME
                            getString(columns[i].column.getName()),
                            // KEY_SEQ
                            ValueSmallint.get((short) ++i),
                            // PK_NAME
                            pkValue);
                }
            }
        }
        result.sortRows(new SortOrder(session, new int[] { 3 }, new int[1], null));
        return result;
    }

    @Override
    public ResultInterface getImportedKeys(String catalog, String schema, String table) {
        if (table == null) {
            throw DbException.getInvalidValueException("table", null);
        }
        SimpleResult result = initCrossReferenceResult();
        if (!checkCatalogName(catalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        for (Schema s : getSchemas(schema)) {
            Table t = s.findTableOrView(session, table);
            if (t == null || t.isHidden()) {
                continue;
            }
            ArrayList<Constraint> constraints = t.getConstraints();
            if (constraints == null) {
                continue;
            }
            for (Constraint constraint : constraints) {
                if (constraint.getConstraintType() != Constraint.Type.REFERENTIAL) {
                    continue;
                }
                ConstraintReferential fk = (ConstraintReferential) constraint;
                Table fkTable = fk.getTable();
                if (fkTable != t) {
                    continue;
                }
                Table pkTable = fk.getRefTable();
                addCrossReferenceResult(result, catalogValue, pkTable.getSchema().getName(), pkTable,
                        fkTable.getSchema().getName(), fkTable, fk);
            }
        }
        return sortCrossReferenceResult(result);
    }

    @Override
    public ResultInterface getExportedKeys(String catalog, String schema, String table) {
        if (table == null) {
            throw DbException.getInvalidValueException("table", null);
        }
        SimpleResult result = initCrossReferenceResult();
        if (!checkCatalogName(catalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        for (Schema s : getSchemas(schema)) {
            Table t = s.findTableOrView(session, table);
            if (t == null || t.isHidden()) {
                continue;
            }
            ArrayList<Constraint> constraints = t.getConstraints();
            if (constraints == null) {
                continue;
            }
            for (Constraint constraint : constraints) {
                if (constraint.getConstraintType() != Constraint.Type.REFERENTIAL) {
                    continue;
                }
                ConstraintReferential fk = (ConstraintReferential) constraint;
                Table pkTable = fk.getRefTable();
                if (pkTable != t) {
                    continue;
                }
                Table fkTable = fk.getTable();
                addCrossReferenceResult(result, catalogValue, pkTable.getSchema().getName(), pkTable,
                        fkTable.getSchema().getName(), fkTable, fk);
            }
        }
        return sortCrossReferenceResult(result);
    }

    @Override
    public ResultInterface getCrossReference(String primaryCatalog, String primarySchema, String primaryTable,
            String foreignCatalog, String foreignSchema, String foreignTable) {
        if (primaryTable == null) {
            throw DbException.getInvalidValueException("primaryTable", null);
        }
        if (foreignTable == null) {
            throw DbException.getInvalidValueException("foreignTable", null);
        }
        SimpleResult result = initCrossReferenceResult();
        if (!checkCatalogName(primaryCatalog) || !checkCatalogName(foreignCatalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        for (Schema s : getSchemas(foreignSchema)) {
            Table t = s.findTableOrView(session, foreignTable);
            if (t == null || t.isHidden()) {
                continue;
            }
            ArrayList<Constraint> constraints = t.getConstraints();
            if (constraints == null) {
                continue;
            }
            for (Constraint constraint : constraints) {
                if (constraint.getConstraintType() != Constraint.Type.REFERENTIAL) {
                    continue;
                }
                ConstraintReferential fk = (ConstraintReferential) constraint;
                Table fkTable = fk.getTable();
                if (fkTable != t) {
                    continue;
                }
                Table pkTable = fk.getRefTable();
                if (!db.equalsIdentifiers(pkTable.getName(), primaryTable)) {
                    continue;
                }
                Schema pkSchema = pkTable.getSchema();
                if (!checkSchema(primarySchema, pkSchema)) {
                    continue;
                }
                addCrossReferenceResult(result, catalogValue, pkSchema.getName(), pkTable,
                        fkTable.getSchema().getName(), fkTable, fk);
            }
        }
        return sortCrossReferenceResult(result);
    }

    private SimpleResult initCrossReferenceResult() {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("PKTABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("PKTABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("PKTABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("PKCOLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FKTABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FKTABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FKTABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("FKCOLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("KEY_SEQ", TypeInfo.TYPE_SMALLINT);
        result.addColumn("UPDATE_RULE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("DELETE_RULE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("FK_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("PK_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DEFERRABILITY", TypeInfo.TYPE_SMALLINT);
        return result;
    }

    private void addCrossReferenceResult(SimpleResult result, Value catalog, String pkSchema, Table pkTable,
            String fkSchema, Table fkTable, ConstraintReferential fk) {
        Value pkSchemaValue = getString(pkSchema);
        Value pkTableValue = getString(pkTable.getName());
        Value fkSchemaValue = getString(fkSchema);
        Value fkTableValue = getString(fkTable.getName());
        IndexColumn[] pkCols = fk.getRefColumns();
        IndexColumn[] fkCols = fk.getColumns();
        Value update = getRefAction(fk.getUpdateAction());
        Value delete = getRefAction(fk.getDeleteAction());
        Value fkNameValue = getString(fk.getName());
        Value pkNameValue = getString(fk.getReferencedConstraint().getName());
        for (int j = 0, len = fkCols.length; j < len; j++) {
            result.addRow(
                    // PKTABLE_CAT
                    catalog,
                    // PKTABLE_SCHEM
                    pkSchemaValue,
                    // PKTABLE_NAME
                    pkTableValue,
                    // PKCOLUMN_NAME
                    getString(pkCols[j].column.getName()),
                    // FKTABLE_CAT
                    catalog,
                    // FKTABLE_SCHEM
                    fkSchemaValue,
                    // FKTABLE_NAME
                    fkTableValue,
                    // FKCOLUMN_NAME
                    getString(fkCols[j].column.getName()),
                    // KEY_SEQ
                    ValueSmallint.get((short) (j + 1)),
                    // UPDATE_RULE
                    update,
                    // DELETE_RULE
                    delete,
                    // FK_NAME
                    fkNameValue,
                    // PK_NAME
                    pkNameValue,
                    // DEFERRABILITY
                    IMPORTED_KEY_NOT_DEFERRABLE);
        }
    }

    private static ValueSmallint getRefAction(ConstraintActionType action) {
        switch (action) {
        case CASCADE:
            return IMPORTED_KEY_CASCADE;
        case RESTRICT:
            return IMPORTED_KEY_RESTRICT;
        case SET_DEFAULT:
            return IMPORTED_KEY_DEFAULT;
        case SET_NULL:
            return IMPORTED_KEY_SET_NULL;
        default:
            throw DbException.throwInternalError("action=" + action);
        }
    }

    private ResultInterface sortCrossReferenceResult(SimpleResult result) {
        result.sortRows(new SortOrder(session, new int[] { 4, 5, 6, 8 }, new int[4], null));
        return result;
    }

    @Override
    public ResultInterface getTypeInfo() {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("PRECISION", TypeInfo.TYPE_INTEGER);
        result.addColumn("LITERAL_PREFIX", TypeInfo.TYPE_VARCHAR);
        result.addColumn("LITERAL_SUFFIX", TypeInfo.TYPE_VARCHAR);
        result.addColumn("CREATE_PARAMS", TypeInfo.TYPE_VARCHAR);
        result.addColumn("NULLABLE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("CASE_SENSITIVE", TypeInfo.TYPE_BOOLEAN);
        result.addColumn("SEARCHABLE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("UNSIGNED_ATTRIBUTE", TypeInfo.TYPE_BOOLEAN);
        result.addColumn("FIXED_PREC_SCALE", TypeInfo.TYPE_BOOLEAN);
        result.addColumn("AUTO_INCREMENT", TypeInfo.TYPE_BOOLEAN);
        result.addColumn("LOCAL_TYPE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("MINIMUM_SCALE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("MAXIMUM_SCALE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("SQL_DATA_TYPE", TypeInfo.TYPE_INTEGER);
        result.addColumn("SQL_DATETIME_SUB", TypeInfo.TYPE_INTEGER);
        result.addColumn("NUM_PREC_RADIX", TypeInfo.TYPE_INTEGER);
        for (DataType t : DataType.getTypes()) {
            if (t.hidden) {
                continue;
            }
            Value name = getString(t.name);
            result.addRow(
                    // TYPE_NAME
                    name,
                    // DATA_TYPE
                    ValueInteger.get(t.sqlType),
                    // PRECISION
                    ValueInteger.get(MathUtils.convertLongToInt(t.maxPrecision)),
                    // LITERAL_PREFIX
                    getString(t.prefix),
                    // LITERAL_SUFFIX
                    getString(t.suffix),
                    // CREATE_PARAMS
                    getString(t.params),
                    // NULLABLE
                    TYPE_NULLABLE,
                    // CASE_SENSITIVE
                    ValueBoolean.get(t.caseSensitive),
                    // SEARCHABLE
                    TYPE_SEARCHABLE,
                    // UNSIGNED_ATTRIBUTE
                    ValueBoolean.FALSE,
                    // FIXED_PREC_SCALE
                    ValueBoolean.get(t.type == Value.NUMERIC),
                    // AUTO_INCREMENT
                    ValueBoolean.get(t.autoIncrement),
                    // LOCAL_TYPE_NAME
                    name,
                    // MINIMUM_SCALE
                    ValueSmallint.get(MathUtils.convertIntToShort(t.minScale)),
                    // MAXIMUM_SCALE
                    ValueSmallint.get(MathUtils.convertIntToShort(t.maxScale)),
                    // SQL_DATA_TYPE (unused)
                    ValueNull.INSTANCE,
                    // SQL_DATETIME_SUB (unused)
                    ValueNull.INSTANCE,
                    // NUM_PREC_RADIX
                    t.decimal ? ValueInteger.get(10) : ValueNull.INSTANCE);
        }
        return result;
    }

    @Override
    public ResultInterface getIndexInfo(String catalog, String schema, String table, boolean unique,
            boolean approximate) {
        if (table == null) {
            throw DbException.getInvalidValueException("table", null);
        }
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_CAT", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("NON_UNIQUE", TypeInfo.TYPE_BOOLEAN);
        result.addColumn("INDEX_QUALIFIER", TypeInfo.TYPE_VARCHAR);
        result.addColumn("INDEX_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TYPE", TypeInfo.TYPE_SMALLINT);
        result.addColumn("ORDINAL_POSITION", TypeInfo.TYPE_SMALLINT);
        result.addColumn("COLUMN_NAME", TypeInfo.TYPE_VARCHAR);
        result.addColumn("ASC_OR_DESC", TypeInfo.TYPE_VARCHAR);
        result.addColumn("CARDINALITY", TypeInfo.TYPE_BIGINT);
        result.addColumn("PAGES", TypeInfo.TYPE_BIGINT);
        result.addColumn("FILTER_CONDITION", TypeInfo.TYPE_VARCHAR);
        if (!checkCatalogName(catalog)) {
            return result;
        }
        Database db = session.getDatabase();
        Value catalogValue = getString(db.getShortName());
        for (Schema s : getSchemas(schema)) {
            Table t = s.findTableOrView(session, table);
            if (t == null || t.isHidden()) {
                continue;
            }
            getIndexInfo(catalogValue, getString(s.getName()), t, unique, approximate, result, db);
        }
        result.sortRows(new SortOrder(session, new int[] { 3, 6, 5, 7 }, new int[4], null));
        return result;
    }

    private void getIndexInfo(Value catalogValue, Value schemaValue, Table table, boolean unique, boolean approximate,
            SimpleResult result, Database db) {
        for (Index index : table.getIndexes()) {
            if (index.getCreateSQL() == null) {
                continue;
            }
            IndexType indexType = index.getIndexType();
            boolean isUnique = indexType.isUnique();
            if (unique && !isUnique) {
                continue;
            }
            Value tableValue = getString(table.getName());
            Value indexValue = getString(index.getName());
            ValueBoolean nonUnique = ValueBoolean.get(!isUnique);
            IndexColumn[] cols = index.getIndexColumns();
            ValueSmallint type = TABLE_INDEX_STATISTIC;
            type: if (isUnique) {
                for (IndexColumn c : cols) {
                    if (c.column.isNullable()) {
                        break type;
                    }
                }
                type = indexType.isHash() ? TABLE_INDEX_HASHED : TABLE_INDEX_OTHER;
            }
            for (int i = 0, l = cols.length; i < l; i++) {
                IndexColumn c = cols[i];
                result.addRow(
                        // TABLE_CAT
                        catalogValue,
                        // TABLE_SCHEM
                        schemaValue,
                        // TABLE_NAME
                        tableValue,
                        // NON_UNIQUE
                        nonUnique,
                        // INDEX_QUALIFIER
                        catalogValue,
                        // INDEX_NAME
                        indexValue,
                        // TYPE
                        type,
                        // ORDINAL_POSITION
                        ValueSmallint.get((short) (i + 1)),
                        // COLUMN_NAME
                        getString(c.column.getName()),
                        // ASC_OR_DESC
                        getString((c.sortType & SortOrder.DESCENDING) != 0 ? "D" : "A"),
                        // CARDINALITY
                        ValueBigint.get(approximate ? index.getRowCountApproximation() : index.getRowCount(session)),
                        // PAGES
                        ValueBigint.get(index.getDiskSpaceUsed() / db.getPageSize()),
                        // FILTER_CONDITION
                        ValueNull.INSTANCE);
            }
        }
    }

    @Override
    public ResultInterface getSchemas(String catalog, String schemaPattern) {
        checkClosed();
        SimpleResult result = new SimpleResult();
        result.addColumn("TABLE_SCHEM", TypeInfo.TYPE_VARCHAR);
        result.addColumn("TABLE_CATALOG", TypeInfo.TYPE_VARCHAR);
        if (!checkCatalogName(catalog)) {
            return result;
        }
        CompareLike schemaLike = getLike(schemaPattern);
        Collection<Schema> allSchemas = session.getDatabase().getAllSchemas();
        ArrayList<String> list;
        if (schemaLike == null) {
            list = new ArrayList<>(allSchemas.size());
            for (Schema s : allSchemas) {
                list.add(s.getName());
            }
        } else {
            list = Utils.newSmallArrayList();
            for (Schema s : allSchemas) {
                String name = s.getName();
                if (schemaLike.test(name)) {
                    list.add(name);
                }
            }
        }
        list.sort(getComparator());
        Value c = getString(session.getDatabase().getShortName());
        for (String s : list) {
            result.addRow(getString(s), c);
        }
        return result;
    }

    private ResultInterface executeQuery(String sql, Value... args) {
        checkClosed();
        synchronized (session) {
            CommandInterface command = session.prepareCommand(sql, Integer.MAX_VALUE);
            int l = args.length;
            if (l > 0) {
                ArrayList<? extends ParameterInterface> parameters = command.getParameters();
                for (int i = 0; i < l; i++) {
                    parameters.get(i).setValue(args[i], true);
                }
            }
            boolean lazy = session.isLazyQueryExecution();
            ResultInterface result;
            try {
                session.setLazyQueryExecution(false);
                result = command.executeQuery(0, false);
                command.close();
            } finally {
                session.setLazyQueryExecution(lazy);
            }
            return result;
        }
    }

    @Override
    void checkClosed() {
        if (session.isClosed()) {
            throw DbException.get(ErrorCode.DATABASE_CALLED_AT_SHUTDOWN);
        }
    }

    private Comparator<String> getComparator() {
        Comparator<String> comparator = this.comparator;
        if (comparator == null) {
            Database db = session.getDatabase();
            this.comparator = comparator = (o1, o2) -> db.getCompareMode().compareString(o1, o2, false);
        }
        return comparator;
    }

    Value getString(String string) {
        return string != null ? ValueVarchar.get(string, session) : ValueNull.INSTANCE;
    }

    private Value getPattern(String pattern) {
        return pattern == null ? PERCENT : getString(pattern);
    }

    private Value getSchemaPattern(String pattern) {
        return pattern == null ? PERCENT : pattern.isEmpty() ? SCHEMA_MAIN : getString(pattern);
    }

    private boolean checkCatalogName(String catalog) {
        if (catalog != null && !catalog.isEmpty()) {
            Database db = session.getDatabase();
            return db.equalsIdentifiers(catalog, db.getShortName());
        }
        return true;
    }

    private Collection<Schema> getSchemas(String schema) {
        Database db = session.getDatabase();
        if (schema == null) {
            return db.getAllSchemas();
        } else if (schema.isEmpty()) {
            return Collections.singleton(db.getMainSchema());
        } else {
            Schema s = db.findSchema(schema);
            if (s != null) {
                return Collections.singleton(s);
            }
            return Collections.emptySet();
        }
    }

    private Collection<Schema> getSchemasForPattern(String schemaPattern) {
        Database db = session.getDatabase();
        if (schemaPattern == null) {
            return db.getAllSchemas();
        } else if (schemaPattern.isEmpty()) {
            return Collections.singleton(db.getMainSchema());
        } else {
            ArrayList<Schema> list = Utils.newSmallArrayList();
            CompareLike like = getLike(schemaPattern);
            for (Schema s : db.getAllSchemas()) {
                if (like.test(s.getName())) {
                    list.add(s);
                }
            }
            return list;
        }
    }

    private Collection<? extends SchemaObjectBase> getTablesForPattern(Schema schema, String tablePattern) {
        Collection<Table> tables = schema.getAllTablesAndViews();
        Collection<TableSynonym> synonyms = schema.getAllSynonyms();
        if (tablePattern == null) {
            if (tables.isEmpty()) {
                return synonyms;
            } else if (synonyms.isEmpty()) {
                return tables;
            }
            ArrayList<SchemaObjectBase> list = new ArrayList<>(tables.size() + synonyms.size());
            list.addAll(tables);
            list.addAll(synonyms);
            return list;
        } else if (tables.isEmpty() && synonyms.isEmpty()) {
            return Collections.emptySet();
        } else {
            ArrayList<SchemaObjectBase> list = Utils.newSmallArrayList();
            CompareLike like = getLike(tablePattern);
            for (Table t : tables) {
                if (like.test(t.getName())) {
                    list.add(t);
                }
            }
            for (TableSynonym t : synonyms) {
                if (like.test(t.getName())) {
                    list.add(t);
                }
            }
            return list;
        }
    }

    private boolean checkSchema(String schemaName, Schema schema) {
        if (schemaName == null) {
            return true;
        } else if (schemaName.isEmpty()) {
            return schema == session.getDatabase().getMainSchema();
        } else {
            return session.getDatabase().equalsIdentifiers(schemaName, schema.getName());
        }
    }

    private CompareLike getLike(String pattern) {
        if (pattern == null) {
            return null;
        }
        CompareLike like = new CompareLike(session.getDatabase().getCompareMode(), "\\", null, false, false, null, //
                null, CompareLike.LikeType.LIKE);
        like.initPattern(pattern, '\\');
        return like;
    }

    private Value getCatalogPattern(String catalogPattern) {
        return catalogPattern == null || catalogPattern.isEmpty() ? PERCENT : getString(catalogPattern);
    }

}
