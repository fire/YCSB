package com.yahoo.ycsb.db;

/*
 * Copyright (c) 2010-2015 Pivotal Software, Inc. All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you
 * may not use this file except in compliance with the License. You
 * may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied. See the License for the specific language governing
 * permissions and limitations under the License. See accompanying
 * LICENSE file.
 */

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.jdbc.ClientAttribute;


import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.Properties;

public class GFXDUtil {

    public static final String PEER_DRIVER = "com.pivotal.gemfirexd.jdbc.EmbeddedDriver";
    public static final String THIN_DRIVER = "com.pivotal.gemfirexd.jdbc.ClientDriver";
    public static final String PROTOCOL = "jdbc:snappydata:";

    public static Connection openConnection(GFXDPrms.ConnectionType type)
            throws SQLException {
        switch (type) {
            case peer:
                return openPeerClientConnection();
            case thin:
                return openThinClientConnection();
            default:
                String s = "Should not happen";
                throw new RuntimeException(s);
        }
    }

    /**
     * Opens an embedded connection, making this thread a peer client. Uses the
     * configured transaction isolation, autocommit, and statement statistics
     * settings.
     */
    public static Connection openPeerClientConnection() throws SQLException {
        Properties p = new Properties();
        //p.setProperty(Attribute.ENABLE_STATS,
        //        String.valueOf(GFXDPrms.enableStats()));
        //p.setProperty(Attribute.ENABLE_TIMESTATS,
        //        String.valueOf(GFXDPrms.enableTimeStats()));
        return openConnection(PEER_DRIVER, PROTOCOL + ";locators=localhost[4423];host-data=false;persist-dd=false",
                0, false, p);
    }

    /**
     * Opens a connection using network locators, making this thread a thin
     * client. Uses the configured transaction isolation and autocommit settings.
     */
    public static  Connection openThinClientConnection() throws SQLException {
        //List<Endpoint> endpoints = NetworkServerHelper.getNetworkLocatorEndpoints();
        //if (endpoints.size() == 0) {
          //  String s = "No network locator endpoints found";
            //throw new PerfTestException(s);
        //}
        String url = PROTOCOL + "//" + "snappydata-locator-0.snappydata-locator.snappydata.svc.cluster.local:1527/;route-query=false";
        Properties p = new Properties();

        /*Properties p = ThinClientHelper.getConnectionProperties();
        List<Endpoint> secondaries = endpoints.subList(1, endpoints.size());
        if (secondaries.size() > 0) {
            String val = "";
            for (int i = 0; i < secondaries.size(); i++) {
                if (i > 0) val += ",";
                val += secondaries.get(i).toString();
            }
            //p.setProperty(ClientAttribute.SECONDARY_LOCATORS, val);
        }*/
        return openConnection(THIN_DRIVER, url, 0,
               false, p);
    }

    public static  Connection openConnection(String driver, String url,
                                             int txIsolation, boolean autocommit) throws SQLException {
        return openConnection(driver, url, txIsolation, autocommit, null);
    }

    public static  Connection openConnection(String driver, String url,
                                             int txIsolation, boolean autocommit, Properties p) throws SQLException {
        //Log.getLogWriter().info("Creating connection using " + driver
         //       + " with " + url + " and properties: " + p);
        loadDriver(driver);
        Connection conn;
        if (p == null) {
            conn = DriverManager.getConnection(url);
        } else {
            conn = DriverManager.getConnection(url, p);
        }
        //conn.setAutoCommit(autocommit);
        //conn.setTransactionIsolation(txIsolation);
        //Log.getLogWriter().info("Created connection using transaction isolation: "
        //        + GFXDPrms.getTxIsolation(conn.getTransactionIsolation())
         //       + " autocommit: " + autocommit);

        return conn;
    }

    /**
     * Opens a basic non-transactional embedded connection, handy for use as a
     * temporary connection.
     */
    public static Connection openBasicEmbeddedConnection() throws SQLException {
        return openConnection(PEER_DRIVER, PROTOCOL,
                GFXDPrms.TRANSACTION_NONE, false);
    }

    /**
     * Loads the given JDBC driver.
     */
    private static void loadDriver(String driver) {
        try {
            Class.forName(driver).newInstance();
        } catch (Exception e) {
            String s = "Problem loading JDBC driver: " + driver;
            throw new RuntimeException(s, e);
        }
    }
}
