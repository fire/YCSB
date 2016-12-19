/**
 * Copyright (c) 2013 - 2014 YCSB Contributors. All rights reserved.
 * <p/>
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

package com.yahoo.ycsb.db;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheFactory;
import com.gemstone.gemfire.cache.GemFireCache;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionExistsException;
import com.gemstone.gemfire.cache.RegionFactory;
import com.gemstone.gemfire.cache.RegionShortcut;
import com.gemstone.gemfire.cache.client.ClientCache;
import com.gemstone.gemfire.cache.client.ClientCacheFactory;
import com.gemstone.gemfire.cache.client.ClientRegionFactory;
import com.gemstone.gemfire.cache.client.ClientRegionShortcut;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.internal.admin.remote.DistributionLocatorId;
import com.pivotal.gemfirexd.FabricServer;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.yahoo.ycsb.*;
import com.yahoo.ycsb.workloads.CoreWorkload;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;

/**
 * VMware vFabric GemFire client for the YCSB benchmark.<br />
 * <p>By default acts as a GemFire client and tries to connect
 * to GemFire cache server running on localhost with default
 * cache server port. Hostname and port of a GemFire cacheServer
 * can be provided using <code>gemfire.serverport=port</code> and <code>
 * gemfire.serverhost=host</code> properties on YCSB command line.
 * A locator may also be used for discovering a cacheServer
 * by using the property <code>gemfire.locator=host[port]</code></p>
 * <p/>
 * <p>To run this client in a peer-to-peer topology with other GemFire
 * nodes, use the property <code>gemfire.topology=p2p</code>. Running
 * in p2p mode will enable embedded caching in this client.</p>
 * <p/>
 * <p>YCSB by default does its operations against "usertable". When running
 * as a client this is a <code>ClientRegionShortcut.PROXY</code> region,
 * when running in p2p mode it is a <code>RegionShortcut.PARTITION</code>
 * region. A cache.xml defining "usertable" region can be placed in the
 * working directory to override these region definitions.</p>
 *
 * @author Swapnil Bawaskar (sbawaska at vmware)
 */
public class GFXDDB extends DB {

    public static final String PEER_DRIVER = "com.pivotal.gemfirexd.jdbc.EmbeddedDriver";
    public static final String THIN_DRIVER = "com.pivotal.gemfirexd.jdbc.ClientDriver";
    public static final String PROTOCOL = "jdbc:snappydata:";

    static final Object instanceLock = new Object();

    /**
     * Prefix for each column in the table
     */
    public static String COLUMN_PREFIX = "field";

    /**
     * Primary key column in the table
     */
    public static final String PRIMARY_KEY = "YCSB_KEY";

    protected ConcurrentMap<StatementType, PreparedStatement> cachedStatements =
            new ConcurrentHashMap();
    protected Connection connection;
    //private DistributedSystem distributedSystem;
    protected long lastQueryPlanTime;

    /**
     * Imported from original GemFireXDClient
     */
    private static final String FIELD2 = COLUMN_PREFIX + 2;
    private static final String FIELD3 = COLUMN_PREFIX + 3;
    private boolean generateQueryData = false;

    public Status scan(String table, String startkey, int recordcount, Set<String> fields,
                       Vector<HashMap<String, ByteIterator>> result) {
        throw new RuntimeException("scan not allowed");
    }

    /**
     * The statement type for the prepared statements.
     */
    private static class StatementType {

        enum Type {
            INSERT(1),
            DELETE(2),
            READ(3),
            UPDATE(4),
            SCAN(5),
            QUERY_WITH_FILTER(6),
            QUERY_WITH_AGGREGATE(7),
            QUERY_WITH_JOIN(8);
            int internalType;

            private Type(int type) {
                internalType = type;
            }

            int getHashCode() {
                final int prime = 31;
                int result = 1;
                result = prime * result + internalType;
                return result;
            }
        }

        Type type;
        int numFields;
        String tableName;
        String table2;

        StatementType(Type type, String tableName, int numFields) {
            this.type = type;
            this.tableName = tableName;
            this.numFields = numFields;
        }

        StatementType(Type type, String tableName, String table2) {
            this.type = type;
            this.tableName = tableName;
            this.table2 = table2;
        }

        @Override
        public int hashCode() {
            final int prime = 31;
            int result = 1;
            result = prime * result + numFields;
            result = prime * result
                    + ((tableName == null) ? 0 : tableName.hashCode());
            result = prime * result + ((type == null) ? 0 : type.getHashCode());
            return result;
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }
            if (obj == null) {
                return false;
            }
            if (getClass() != obj.getClass()) {
                return false;
            }
            StatementType other = (StatementType) obj;
            if (type != other.type) {
                return false;
            }
            if (numFields != other.numFields) {
                return false;
            }
            if (tableName == null) {
                if (other.tableName != null)
                    return false;
            } else if (!tableName.equals(other.tableName)) {
                return false;
            }
            return true;
        }
    }


    static volatile boolean initialized = false;

    @Override
    public void init() throws DBException {

        synchronized (instanceLock) {
            GFXDPrms.ConnectionType type = GFXDPrms.ConnectionType.thin;
            if (type == GFXDPrms.ConnectionType.thin) {
                this.connection = initConnection(type);

            } else {
                Properties bootProperties = new Properties();
                FabricServer fs = FabricServiceManager.getFabricServerInstance();
                FabricService.State status = fs.status();
                try {
                    fs.start(bootProperties);
                } catch (SQLException e) {
                    String s = "Unable to start fabric server";
                    throw new RuntimeException(s, e);
                }
                fs = FabricServiceManager.getFabricServerInstance();
                FabricService.State statusNow = fs.status();
                if (statusNow != FabricService.State.RUNNING) {
                    String s = "Expected fabric server to be RUNNING, but it is: "
                            + statusNow;
                    throw new RuntimeException(s);
                }
                this.connection = initConnection(type);
                //this.distributedSystem = DistributedSystemHelper.getDistributedSystem();
            }

            if (initialized) {
                return;
            }

            int fieldcount = getIntFromProp(getProperties(), CoreWorkload.FIELD_COUNT_PROPERTY,
                    Integer.parseInt(CoreWorkload.FIELD_COUNT_PROPERTY_DEFAULT));
            int buckets = 113;
            int redundancy = 1;


        /*
        StringBuilder sql = new StringBuilder("CREATE TABLE IF NOT EXISTS ");
        sql.append("usertable").append(" (").append(PRIMARY_KEY)
                .append(" VARCHAR(100) PRIMARY KEY");
        for (int i = 0; i < fieldcount; i++) {
          sql.append(", FIELD").append(i).append(" VARCHAR(100)");
        }
        String primaryKey = PRIMARY_KEY;
        sql.append(")  ")
                .append(" partition_by '").append(primaryKey).append("' )");
        */

            try {
                ResultSet rs = null;

                try {
                    rs = connection.getMetaData().getTables(null, null, "USERTABLE%", null);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

                if (rs.next()) {
                    System.out.println("Skipping table creation");
                    initialized = true;
                    return;
                }

                StringBuilder sql = new StringBuilder("CREATE TABLE ");
                sql.append("usertable").append(" (").append(PRIMARY_KEY)
                        .append(" VARCHAR(100) PRIMARY KEY");
                for (int i = 0; i < fieldcount; i++) {
                    sql.append(", FIELD").append(i).append(" VARCHAR(100)");
                }
                String primaryKey = PRIMARY_KEY;
                sql.append(")  ")
                        .append(" partition by (").append(primaryKey).append(")");
                //connection.prepareStatement("drop table usertable").execute();
                connection.createStatement().execute(sql.toString());

                initialized = true;

            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }


    }

    private Connection initConnection(GFXDPrms.ConnectionType type) throws DBException {
        try {
            return GFXDUtil.openConnection(type);
        } catch (SQLException e) {
            String s = "Unable to create " + type + " client connection";
            throw new DBException(s, e);
        }
    }

    public Connection getConnection() {
        return this.connection;
    }

    @Override
    public void cleanup() throws DBException {
        //Log.getLogWriter().info("Closing connection " + this.connection);
        if (this.connection == null) {
            //Log.getLogWriter().info("Connection already closed");
        } else {
            try {
                this.connection.close();
                this.connection = null;
                //Log.getLogWriter().info("Closed connection");
            } catch (SQLException e) {
                //if (e.getSQLState().equalsIgnoreCase("X0Z01") && GFXDPrms.isHA()) {
                //Log.getLogWriter().info("Connection already closed at server end");
                //} else {
                throw new DBException("Problem closing connection", e);
                //}
            }
        }

        GFXDPrms.ConnectionType type = GFXDPrms.getConnectionType();
        if (type == GFXDPrms.ConnectionType.thin) {
            Properties shutdownProperties = new Properties();
            FabricService fs = FabricServiceManager.currentFabricServiceInstance();
            if (fs == null) {
                //  log.info("Fabric server already stopped.");
                return;
            }
            FabricService.State status = fs.status();
            switch (status) {
                case UNINITIALIZED:
                case STOPPED:
                    //log.info("Fabric server already stopped.");
                    break;
                case RUNNING:
                    try {
                        fs.stop(shutdownProperties);
                    } catch (SQLException e) {
                        String s = "Unable to stop fabric server";
                        throw new RuntimeException(s, e);
                    }
                    FabricService.State statusNow = fs.status();
                    if (statusNow != FabricService.State.STOPPED) {
                        String s = "Expected fabric server to be STOPPED, but it is: "
                                + statusNow;
                        throw new RuntimeException(s);
                    }
                    //log.info("Stopped the fabric server");
                    // allow the next start to have a different config
                    //TheFabricServerDescription = null;
                    // TheFabricServerProperties = null;
                    break;
                case STARTING:
                case STOPPING:
                case WAITING:
                default:
                    throw new RuntimeException("Unexpected state: " + status);
            }
        }
        //if (this.distributedSystem != null) {
        //  this.distributedSystem.disconnect();
        //  this.distributedSystem = null;
        //}
    }

//------------------------------------------------------------------------------
// DB

    private static int getIntFromProp(Properties prop, String propName,
                                      int defaultValue) {
        String intStr = prop.getProperty(propName);
        if (intStr == null) {
            return defaultValue;
        } else {
            try {
                return Integer.valueOf(intStr);
            } catch (NumberFormatException ex) {
                throw new RuntimeException(
                        "Provided number for " + propName + " isn't a valid integer");
            }
        }
    }

    @Override
    public Status read(String tableName, String key, Set<String> fields,
                       HashMap<String, ByteIterator> result) {
        //if (logDML) {
        //  Log.getLogWriter().info("Reading key=" + key);
        //}
        //long start = this.statistics.startRead();
        try {
            StatementType type = new StatementType(StatementType.Type.READ, tableName, 1);
            PreparedStatement readStatement = this.cachedStatements.get(type);
            if (readStatement == null) {
                readStatement = createAndCacheReadStatement(type, key);
            }
            readStatement.setString(1, key);
            ResultSet resultSet = readStatement.executeQuery();
            if (!resultSet.next()) {
                resultSet.close();
                resultSet = null;
                String s = "No results reading key=" + key + " from " + tableName;
                //throw new PerfTestException(s);
                throw new RuntimeException(s);
            }
            if (fields == null) {
                // read all fields
                //int fieldcount = CoreWorkloadPrms.getFieldCount();
                int fieldCount = getIntFromProp(getProperties(), CoreWorkload.FIELD_COUNT_PROPERTY,
                        Integer.parseInt(CoreWorkload.FIELD_COUNT_PROPERTY_DEFAULT));
                String value = resultSet.getString(PRIMARY_KEY);
                result.put(PRIMARY_KEY, new StringByteIterator(value));
                for (int i = 0; i < fieldCount; i++) {
                    String field = "FIELD" + i;
                    value = resultSet.getString(field);
                    result.put(field, new StringByteIterator(value));
                }
            } else {
                for (String field : fields) {
                    String value = resultSet.getString(field);
                    result.put(field, new StringByteIterator(value));
                }
            }
            resultSet.close();
            resultSet = null;
        } catch (SQLException e) {
            String s = "Error reading key=" + key + " from " + tableName;
            throw new RuntimeException(s, e);
        }
        return Status.OK;
    }

    @Override
    public Status update(String tableName, String key, HashMap<String, ByteIterator> values) {
        //if (logDML) {
        //  Log.getLogWriter().info("Updating key=" + key);
        //}
        //long start = this.statistics.startUpdate();
        try {
            int numFields = values.size();
            StatementType type = new StatementType(StatementType.Type.UPDATE, tableName, numFields);
            PreparedStatement updateStatement = this.cachedStatements.get(type);
            if (updateStatement == null) {
                updateStatement = createAndCacheUpdateStatement(type, key, values.keySet());
            }
            int i = 1;
            for (String field : values.keySet()) {
                ByteIterator bytes = values.get(field);
                if (generateQueryData && (field.equals(FIELD2) || field.equals(FIELD3))) {
                    updateStatement.setLong(i, ((LongByteIterator) bytes).toLong());
                } else {
                    updateStatement.setString(i, bytes.toString());
                }
                ++i;
            }
            updateStatement.setString(i, key);
            int result = updateStatement.executeUpdate();
            if (result != 1) {
                String s = "Unexpected result updating key=" + key + " in " + tableName + ": " + result;
                //throw new PerfTestException(s);
                throw new RuntimeException(s);
            }
        } catch (SQLException e) {
            String s = "Error updating key=" + key + " in " + tableName;
            throw new RuntimeException(s, e);
        }
        //this.statistics.endUpdate(start, 1);
        //if (logDML) {
        //Log.getLogWriter().info("Updated key=" + key);
        //}
        return Status.OK;
    }

    @Override
    public Status insert(String tableName, String key, HashMap<String, ByteIterator> values) {
        //if (logDML) {
        //Log.getLogWriter().info("Inserting key=" + key);
        //}
        //long start = this.statistics.startInsert();
        try {
            int numFields = values.size();
            StatementType type = new StatementType(StatementType.Type.INSERT, tableName, numFields);
            PreparedStatement insertStatement = this.cachedStatements.get(type);
            if (insertStatement == null) {
                insertStatement = createAndCacheInsertStatement(type, key);
            }
            insertStatement.setString(1, key);
            for (int i = 0; i < numFields; i++) {
                ByteIterator bytes = values.get(COLUMN_PREFIX + i);
                if (generateQueryData && (i == 2 || i == 3)) {
                    insertStatement.setLong(i + 2, ((LongByteIterator) bytes).toLong());
                } else {
                    insertStatement.setString(i + 2, bytes.toString());
                }
            }
            int result = insertStatement.executeUpdate();
            if (result != 1) {
                String s = "Failed inserting key=" + key + " in " + tableName;
                throw new RuntimeException(s);
            }
        } catch (SQLException e) {
            String s = "Error inserting key=" + key + " in " + tableName;
            throw new RuntimeException(s, e);
        }
        //this.statistics.endInsert(start, 1);
        //if (logDML) {
        //  Log.getLogWriter().info("Inserted key=" + key);
        // }
        return Status.OK;
    }

    @Override
    public Status delete(String tableName, String key) {
        //if (logDML) {
        //  Log.getLogWriter().info("Deleting key=" + key);
        // }
        //long start = this.statistics.startDelete();
        try {
            StatementType type = new StatementType(StatementType.Type.DELETE, tableName, 1);
            PreparedStatement deleteStatement = this.cachedStatements.get(type);
            if (deleteStatement == null) {
                deleteStatement = createAndCacheDeleteStatement(type, key);
            }
            deleteStatement.setString(1, key);
            int result = deleteStatement.executeUpdate();
            if (result != 1) {
                String s = "Failed deleting key=" + key + " from " + tableName;
                throw new RuntimeException(s);
            }
        } catch (SQLException e) {
            String s = "Error deleting key=" + key + " from " + tableName;
            throw new RuntimeException(s, e);
        }
        //this.statistics.endDelete(start, 1);
        //if (logDML) {
        // Log.getLogWriter().info("Deleted key=" + key);
        //}
        return Status.OK;
    }

    private PreparedStatement createAndCacheInsertStatement(StatementType insertType, String key)
            throws SQLException {
        StringBuilder insert;
        //if (GFXDPrms.usePutDML()) {
        //  insert = new StringBuilder("PUT INTO ");
        //} else {
        insert = new StringBuilder("INSERT INTO ");
        //}
        insert.append(insertType.tableName);
        insert.append(" VALUES(?");
        for (int i = 0; i < insertType.numFields; i++) {
            insert.append(",?");
        }
        insert.append(");");
        PreparedStatement insertStatement = this.connection.prepareStatement(insert.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(insertType, insertStatement);
        if (stmt == null) return insertStatement;
        else return stmt;
    }

    private PreparedStatement createAndCacheReadStatement(StatementType readType, String key)
            throws SQLException {
        StringBuilder read = new StringBuilder("SELECT * FROM ");
        read.append(readType.tableName);
        //if (GFXDPrms.queryHDFS()) {
        //  read.append(" --GEMFIREXD-PROPERTIES queryHDFS=true \n");
        // }
        read.append(" WHERE ");
        read.append(PRIMARY_KEY);
        read.append(" = ?;");
        PreparedStatement readStatement = this.connection.prepareStatement(read.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(readType, readStatement);
        if (stmt == null) return readStatement;
        else return stmt;
    }

    private PreparedStatement createAndCacheDeleteStatement(StatementType deleteType, String key)
            throws SQLException {
        StringBuilder delete = new StringBuilder("DELETE FROM ");
        delete.append(deleteType.tableName);
        delete.append(" WHERE ");
        delete.append(PRIMARY_KEY);
        delete.append(" = ?;");
        PreparedStatement deleteStatement = this.connection.prepareStatement(delete.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(deleteType, deleteStatement);
        if (stmt == null) return deleteStatement;
        else return stmt;
    }

    private PreparedStatement createAndCacheUpdateStatement(StatementType updateType, String key, Set<String> fields)
            throws SQLException {
        StringBuilder update = new StringBuilder("UPDATE ");
        update.append(updateType.tableName);
        update.append(" SET ");
        int i = 1;
        for (String field : fields) {
            update.append(field);
            update.append("=?");
            if (i < updateType.numFields) update.append(", ");
            ++i;
        }
        update.append(" WHERE ");
        update.append(PRIMARY_KEY);
        update.append(" = ?;");
        PreparedStatement updateStatement = this.connection.prepareStatement(update.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(updateType, updateStatement);
        if (stmt == null) return updateStatement;
        else return stmt;
    }

    private PreparedStatement createAndCacheQueryWithFilterStatement(StatementType query)
            throws SQLException {
        StringBuilder select = new StringBuilder("SELECT * FROM ");
        select.append(query.tableName);
        select.append(" u WHERE u.field0 = ? AND u.field2 > ? AND u.field2 < ?");
        select.append(" FETCH FIRST ? ROWS ONLY;");
        PreparedStatement queryStatement = this.connection.prepareStatement(select.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(query, queryStatement);
        if (stmt == null) return queryStatement;
        else return stmt;
    }

    private PreparedStatement createAndCacheQueryWithAggregateStatement(StatementType query)
            throws SQLException {
        StringBuilder select = new StringBuilder("SELECT u.field1, sum(u.field3) FROM ");
        select.append(query.tableName);
        select.append(" u WHERE u.field0 = ? AND u.field2 > ? AND u.field2 < ?");
        select.append(" GROUP BY u.field1 ORDER BY u.field1");
        select.append(" FETCH FIRST ? ROWS ONLY;");
        PreparedStatement queryStatement = this.connection.prepareStatement(select.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(query, queryStatement);
        if (stmt == null) return queryStatement;
        else return stmt;
    }

    private PreparedStatement createAndCacheQueryWithJoinStatement(StatementType query)
            throws SQLException {
        StringBuilder select = new StringBuilder("SELECT u.ycsb_key, v.ycsb_key, u.field1, v.field1 FROM ");
        select.append(query.tableName);
        select.append(" u JOIN ");
        select.append(query.table2);
        select.append(" v ON u.ycsb_key = v.field4");
        select.append(" WHERE u.field0 = ? AND u.field2 > ? AND u.field2 < ?");
        select.append(" ORDER BY u.field1, v.field0");
        select.append(" FETCH FIRST ? ROWS ONLY;");
        PreparedStatement queryStatement = this.connection.prepareStatement(select.toString());
        PreparedStatement stmt = this.cachedStatements.putIfAbsent(query, queryStatement);
        if (stmt == null) return queryStatement;
        else return stmt;
    }
}

