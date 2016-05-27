/**
 * Copyright 2015 Palantir Technologies
 *
 * Licensed under the BSD-3 License (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.palantir.nexus.db.pool;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.TimeZone;
import java.util.concurrent.TimeUnit;

import javax.management.JMX;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.sql.DataSource;

import org.apache.commons.io.IOUtils;
import org.apache.commons.lang.exception.ExceptionUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.codahale.metrics.SharedMetricRegistries;
import com.google.common.base.Preconditions;
import com.google.common.base.Strings;
import com.palantir.common.visitor.Visitor;
import com.palantir.nexus.db.DBType;
import com.palantir.nexus.db.manager.DatabaseConstants;
import com.palantir.nexus.db.pool.config.ConnectionConfig;
import com.palantir.nexus.db.pool.config.ConnectionProtocol;
import com.palantir.nexus.db.pool.config.ImmutableConnectionConfig;
import com.palantir.nexus.db.sql.ExceptionCheck;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import com.zaxxer.hikari.pool.HikariPool.PoolInitializationException;
import com.zaxxer.hikari.util.DriverDataSource;

/**
 * HikariCP Connection Manager.
 *
 * @author dstipp
 */
class HikariCPConnectionManager extends BaseConnectionManager {
    private static final Logger log = LoggerFactory.getLogger(HikariCPConnectionManager.class);

    // TODO: Make this delay configurable?
    private static final long COOLDOWN_MILLISECONDS = 30000;

    private ConnectionConfig dbConfig;
    private final Visitor<Connection> onAcquireVisitor;

    private enum StateType {
        // Base state at construction.  Nothing is set.
        ZERO,

        // "Normal" state.  dataSourceProxy and poolProxy are set.
        NORMAL,

        // Elevated state.  elevatedStateTimeMillis, dataSourceProxy, poolProxy are set.
        ELEVATED,

        // Closed state.  closeTrace is set.
        CLOSED;
    }

    private static class State {
        public final StateType type;
        public final long elevatedStateTimeMillis;
        public final HikariDataSource dataSourcePool;
        public final HikariPoolMXBean poolProxy;
        public final Throwable closeTrace;

        public State(StateType type, long elevatedStateTimeMillis, HikariDataSource dataSourcePool, HikariPoolMXBean poolProxy, Throwable closeTrace) {
            this.type = type;
            this.elevatedStateTimeMillis = elevatedStateTimeMillis;
            this.dataSourcePool = dataSourcePool;
            this.poolProxy = poolProxy;
            this.closeTrace = closeTrace;
        }
    }

    private volatile State state = new State(StateType.ZERO, 0, null, null, null);

    public HikariCPConnectionManager(ConnectionConfig dbConfig, Visitor<Connection> onAcquireVisitor) {
        this.dbConfig = Preconditions.checkNotNull(dbConfig);
        this.onAcquireVisitor = onAcquireVisitor;
    }

    @Override
    public Connection getConnection() throws SQLException {
        long start = System.currentTimeMillis();
        try {
            return getConnectionInternal();
        } finally {
            long now = System.currentTimeMillis();
            long elapsed = now - start;
            if (elapsed > 1000) {
                log.warn("Waited {}ms for connection", elapsed);
                logPoolStats();
            } else {
                log.debug("Waited {}ms for connection", elapsed);
            }
        }
    }

    private void logPoolStats() {
        if (log.isTraceEnabled()) {
            State stateLocal = state;
            HikariPoolMXBean poolProxy = stateLocal.poolProxy;
            if (poolProxy != null) {
                try {
                    log.trace(
                            "HikariCP: "
                                    + "numBusyConnections = " + poolProxy.getActiveConnections() + ", "
                                    + "numIdleConnections = " + poolProxy.getIdleConnections() + ", "
                                    + "numConnections = " + poolProxy.getTotalConnections() + ", "
                                    + "numThreadsAwaitingCheckout = " + poolProxy.getThreadsAwaitingConnection());
                } catch (Exception e) {
                    log.error("Unable to log pool statistics.", e);
                }
            }
        }
    }

    private Connection getConnectionInternal() throws SQLException {
        while (true) {
            // Volatile read state to see if we can get through here without
            // locking.
            State stateLocal = state;

            switch (stateLocal.type) {
                case ZERO:
                    // Not yet initialized, no such luck.
                    synchronized (this) {
                        if (state == stateLocal) {
                            // The state hasn't changed on us, we can perform
                            // the initialization and start
                            // getConnectionInternal() over again.
                            state = initialState();
                        } else {
                            // Someone else changed the state on us, just start
                            // over.
                        }
                    }
                    break;

                case NORMAL:
                    // Normal state, we try to get a connection.
                    try {
                        return stateLocal.dataSourcePool.getConnection();
                    } catch (SQLException e) {
                        // No luck, consider this a timeout, worthy of turning
                        // it to eleven.  If we "timed out" getting connections
                        // we probably deadlocked.
                        log.error("\"Timed out\" getting connection from pool.", e);
                        // Ah, let's take it to eleven...
                    }
                    // But first we need to lock and make sure it hasn't moved
                    // on us.
                    synchronized (this) {
                        if (state == stateLocal) {
                            // The state hasn't changed on us, we can perform
                            // the elevation and start getConnectionInternal()
                            // over again.
                            state = elevatedState(state);
                        } else {
                            // Someone else changed the state on us, just start
                            // over.
                        }
                    }
                    break;

                case ELEVATED:
                    // Elevated state, but is it expired?
                    if (System.currentTimeMillis() < stateLocal.elevatedStateTimeMillis + COOLDOWN_MILLISECONDS) {
                        // Nope, the purest case -- delegate and hope for the
                        // best (there is no twelve to which to turn it).
                        return stateLocal.dataSourcePool.getConnection();
                    } else {
                        // Ah, but the elevated state is expired, we should amp
                        // it back down.  But first, blah blah blah locks.
                        synchronized (this) {
                            if (state == stateLocal) {
                                // The state hasn't changed on us, we can
                                // perform the unelevation and start
                                // getConnectionInternal() over again.
                                state = normalState(state);
                            } else {
                                // Someone else changed the state on us, just
                                // start over.
                            }
                        }
                    }
                    break;

                case CLOSED:
                    throw new SQLException("Hikari connection pool already closed!", stateLocal.closeTrace);
            }

            // fall throughs are spins
        }
    }

    private void logConnectionFailure() {
        log.error("Failed to get connection from the datasource "
                + "db-pool-" + dbConfig.getConnId() + "-" + dbConfig.getDbLogin()
                + ". Please check the jdbc url (" + (dbConfig.getUrl()) + ")"
                + ", the password, and that the secure server key is correct for the hashed password.");
    }

    /**
     * Test an initialized dataSourcePool by running a simple query.
     */
    private void testDataSource(HikariDataSource dataSourcePool) throws SQLException {
        try {
            Connection conn = null;

            try {
                conn = dataSourcePool.getConnection();
                testConnection(conn);
            } finally {
                if (conn != null) {
                    conn.close();
                }
            }
        } catch (SQLException e) {
            logConnectionFailure();
            throw e;
        }
    }

    private boolean testConnection(Connection conn) throws SQLException {
        boolean isValid = false;
        Statement stmt = conn.createStatement();
        try {
            ResultSet rs = stmt.executeQuery(dbConfig.getDbType().getTestQuery());
            isValid = rs.next();
            rs.close();
        } finally {
            stmt.close();
        }
        isValid &= true;
        return isValid;
    }

    @Override
    public synchronized void close() {
        try {
            switch (state.type) {
                case ZERO:
                    break;

                case NORMAL:
                case ELEVATED:
                    if (log.isDebugEnabled()) {
                        log.debug(
                                "Closing connection pool: {}",
                                dbConfig,
                                new RuntimeException("Closing connection pool"));
                    }

                    state.dataSourcePool.close();
                    break;

                case CLOSED:
                    break;
            }
        } finally {
            state = new State(StateType.CLOSED, 0, null, null, new Throwable("Hikari pool closed here"));
        }
    }

    /**
     * Initializes a connection to the provided database.
     */
    @Override
    public synchronized void init() throws SQLException {
        if (state.type == StateType.ZERO) {
            state = initialState();
        }
    }

    private State initialState() throws SQLException {
        if (dbConfig.getDbType() == null) {
            throw new DBMgrConfigurationException(
                    "Missing required configuration parameter specifying database type.");
        } else if (dbConfig.getDbLogin() == null) {
            throw new DBMgrConfigurationException(
                    "Missing required configuration parameter specifying database login.");
        }

        // Print a stack trace whenever we initialize a pool
        log.info("Initializing connection pool: {}", dbConfig, new RuntimeException("Initializing connection pool"));

        // Initialize the Hikari configuration
        HikariConfig config = new HikariConfig();

        // additional connection properties will go in here
        Properties props = JdbcConfig.getPropertiesFromDbConfig(
                dbConfig.getDbLogin(),
                dbConfig.getDbPassword(),
                dbConfig.getSocketTimeoutSeconds(),
                dbConfig.getConnectionTimeoutSeconds());

        /*
         * This /has/ to be done before initDataSource(Properties) as it updates the JDBC URL.
         */
        initPoolSsl(props);

        config.setPoolName("db-pool-" + dbConfig.getConnId() + "-" + dbConfig.getDbLogin());
        config.setRegisterMbeans(true);
        config.setMetricRegistry(SharedMetricRegistries.getOrCreate("com.palantir.metrics"));

        config.setMinimumIdle(dbConfig.getMinConnections());
        config.setMaximumPoolSize(dbConfig.getMaxConnections());

        config.setMaxLifetime(TimeUnit.SECONDS.toMillis(dbConfig.getMaxConnectionAge()));
        config.setIdleTimeout(TimeUnit.SECONDS.toMillis(dbConfig.getMaxIdleTime()));
        config.setLeakDetectionThreshold(dbConfig.getUnreturnedConnectionTimeout());
        config.setConnectionTimeout(dbConfig.getCheckoutTimeout());

        // Set light-weight test query to run on connections checked out from pool.
        // TODO: See if driver supports JDBC4 (isValid()) and use it.
        config.setConnectionTestQuery(dbConfig.getDbType().getTestQuery());

        initPoolProperties(config, props);

        initDataSource(config, props);

        HikariDataSource dataSourcePool;
        try {
            dataSourcePool = new HikariDataSource(config);
        } catch (PoolInitializationException e) {
            log.error("Failed to initialize hikari data source: {}", dbConfig.getUrl(), e);

            if (ExceptionCheck.isTimezoneInvalid(e)) {
                String tzname = TimeZone.getDefault().getID();

                final String errorPreamble = "Invalid system timezone " + tzname + " detected.";
                final String errorAfterward = "This can be corrected at the system level or overridden in the JVM startup flags by appending -Duser.timezone=UTC. Contact support for more information.";

                if (tzname.equals("Etc/Universal")) {
                    // Really common failure case. UTC is both a name AND an abbreviation.
                    log.error(
                            "{} The timezone *name* should be UTC. {}",
                            errorPreamble,
                            errorAfterward);
                } else {
                    log.error(
                            "{} This is caused by using non-standard or unsupported timezone names. {}",
                            errorPreamble,
                            errorAfterward);
                }
            }

            // This exception gets thrown by HikariCP and is useless outside of
            // ConnectionManagers.
            RuntimeException e2 = new RuntimeException(ExceptionUtils.getMessage(e), e.getCause());
            e2.setStackTrace(e.getStackTrace());
            throw e2;
        }

        boolean keep = false;

        try {
            // Setup monitoring
            HikariPoolMXBean poolProxy = initPoolMbeans();

            testDataSource(dataSourcePool);

            keep = true;
            return new State(StateType.NORMAL, 0, dataSourcePool, poolProxy, null);
        } finally {
            if (!keep) {
                IOUtils.closeQuietly(dataSourcePool);
            }
        }
    }

    private State elevatedState(State oldState) {
        HikariDataSource dataSourcePool = oldState.dataSourcePool;
        HikariPoolMXBean poolProxy = oldState.poolProxy;

        /**
         *  Nigel Tufnel: The numbers all go to eleven. Look, right across the board, eleven,
         *                eleven, eleven and...
         * Marty DiBergi: Oh, I see. And most amps go up to ten?
         *  Nigel Tufnel: Exactly.
         * Marty DiBergi: Does that mean it's louder? Is it any louder?
         *  Nigel Tufnel: Well, it's one louder, isn't it? It's not ten. You see, most
         *                blokes, you know, will be playing at ten. You're on ten here,
         *                all the way up, all the way up, all the way up, you're on ten
         *                on your guitar. Where can you go from there? Where?
         * Marty DiBergi: I don't know.
         *  Nigel Tufnel: Nowhere. Exactly. What we do is, if we need that extra push
         *                over the cliff, you know what we do?
         * Marty DiBergi: Put it up to eleven.
         *  Nigel Tufnel: Eleven. Exactly. One louder.
         * Marty DiBergi: Why don't you just make ten louder and make ten be the top
         *                number and make that a little louder?
         *  Nigel Tufnel: [pause] These go to eleven.
         */

        int normalSize = dbConfig.getMaxConnections();
        int elevatedSize = normalSize + 11;
        log.info("Elevating connection pool: {} -> {}", normalSize, elevatedSize);
        dataSourcePool.setMaximumPoolSize(elevatedSize);

        return new State(StateType.ELEVATED, System.currentTimeMillis(), dataSourcePool, poolProxy, null);
    }

    private State normalState(State oldState) {
        HikariDataSource dataSourcePool = oldState.dataSourcePool;
        HikariPoolMXBean poolProxy = oldState.poolProxy;

        int normalSize = dbConfig.getMaxConnections();
        log.info("De-elevating connection pool: {}", normalSize);
        dataSourcePool.setMaximumPoolSize(normalSize);

        return new State(StateType.NORMAL, 0, dataSourcePool, poolProxy, null);
    }

    /**
     * Setup SSL on the pool connection.
     *
     * This is currently Oracle-specific and updates the "urlSuffix" on Oracle connections
     * regardless if SSL is used or not.
     *
     * @param props
     */
    private void initPoolSsl(Properties props) {
        if (dbConfig.getDbType() == DBType.ORACLE) {
            try {
                initPoolSslOracle(props);
            } catch (Exception e) {
                String why = "Cannot setup Oracle SSL connections";
                log.error(why, e);
                throw new DBMgrConfigurationException(why, e);
            }
        }
    }

    /**
     * Set and log the pool properties.
     *
     * @param props
     */
    private void initPoolProperties(HikariConfig config, Properties props) {
        if (!props.isEmpty()) {
            config.setDataSourceProperties(props);

            if (log.isInfoEnabled()) {
                for (Entry<Object, Object> entry : props.entrySet()) {
                    String key = entry.getKey().toString();
                    String value = null;

                    // Censor password values.
                    if (key.toLowerCase().contains("passw")) {
                        value = "******";
                    } else {
                        value = entry.getValue().toString();
                    }
                    log.info("DBConnection property '" + key + "' is set to '" + value + "'");
                }
            }
        }
    }

    /**
     * Setup JMX client.  This is only used for trace logging.
     */
    private HikariPoolMXBean initPoolMbeans() {
        MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();

        ObjectName poolName = null;
        try {
            poolName = new ObjectName("com.zaxxer.hikari:type=Pool (" + "db-pool-" + dbConfig.getConnId() + "-" + dbConfig.getDbLogin() + ")");
        } catch (MalformedObjectNameException e) {
            log.error("Unable to setup mBean monitoring for pool.", e);
        }
        return JMX.newMBeanProxy(mBeanServer, poolName, HikariPoolMXBean.class);
    }

    /**
     * Initialize and wrap the Datasource.  Sets the visitor on connections pulled from the underlying .
     *
     * @param props
     *
     */
    private void initDataSource(HikariConfig config, Properties props) {
        DataSource dataSourceRaw;
        DataSource dataSourceWrapped;

        final String url = dbConfig.getUrl();
        config.setJdbcUrl(url);

        log.info("DBConnection url is: {}", url);

        try {
            dataSourceRaw = new DriverDataSource(dbConfig.getUrl(), dbConfig.getDriverClass(), props, null, null);

            dataSourceWrapped = InterceptorDataSource.wrapInterceptor(new InterceptorDataSource(dataSourceRaw) {
                @Override
                protected void onAcquire(Connection c) {
                    onAcquireVisitor.visit(c);
                }
            });

            config.setDataSource(dataSourceWrapped);
        } catch (Exception e) {
            throw new DBMgrConfigurationException(
                    "The connection pool was unable to wrap the jdbc driver, "
                            + dbConfig.getDriverClass(),
                    e);
        }

    }

    // TODO: This will be pulled out to a common class for C3P0 & Hikari if it winds up we don't
    // need any specific changes to the driver.
    /**
     * Sets up Oracle-specific SSL settings, as well as sets the urlSuffix to make the connection
     * string usable.
     *
     * @param props
     * @throws Exception
     */
    private void initPoolSslOracle(Properties props) throws Exception {
        /*
         * If the protocol has been set to "tcps," setup a trust store.
         */
        ConnectionProtocol sProtocol = dbConfig.getProtocol();
        log.info("DB Protocol is set to " + sProtocol);

        if (sProtocol == ConnectionProtocol.TCPS) {
            // Create the truststore
            Preconditions.checkArgument(dbConfig.getTruststorePath().isPresent());
            File clientTrustore = new File(dbConfig.getTruststorePath().get());

            if (clientTrustore.exists()) {
                props.setProperty("javax.net.ssl.trustStore", clientTrustore.getAbsolutePath());
                props.setProperty("javax.net.ssl.trustStorePassword", "ptclient");
            } else {
                log.error("TrustStore does not exist at expected location, init DB may fail!  Location: " + clientTrustore.getAbsolutePath());
            }

            // server_dn_matching
            String sMatchServerDN = dbConfig.getMatchServerDn();
            if (!Strings.isNullOrEmpty(sMatchServerDN)) {
                props.setProperty("oracle.net.ssl_server_dn_match", "true");
                log.info("Will require the server certificate DB to match: " + sMatchServerDN);

                // modify the config to have a URL suffix
                dbConfig = ImmutableConnectionConfig.builder().from(dbConfig)
                        .urlSuffix(DatabaseConstants.DB_ORACLE_SECURITY_SUFFIX)
                        .build();
            } else {
                // set the closer if we don't have a dn to match
                dbConfig = ImmutableConnectionConfig.builder().from(dbConfig)
                        .urlSuffix(DatabaseConstants.DB_ORACLE_NO_SECURITY_SUFFIX)
                        .build();
            }

            /*
             * Enable client SSL certificate support. "two-way" SSL in Oracle parlance.
             */
            if (dbConfig.getTwoWaySsl()) {
                Preconditions.checkArgument(dbConfig.getKeystorePath().isPresent());
                Preconditions.checkArgument(dbConfig.getKeystorePassword().isPresent());
                props.setProperty("javax.net.ssl.keyStore", dbConfig.getKeystorePath().get());
                props.setProperty("javax.net.ssl.keyStorePassword", dbConfig.getKeystorePassword().get());
            }
        } else {
            /*
             * Set no security suffix if security isn't enabled.
             */
            dbConfig = ImmutableConnectionConfig.builder().from(dbConfig)
                    .urlSuffix(DatabaseConstants.DB_ORACLE_NO_SECURITY_SUFFIX)
                    .build();

            /*
             * Ensure a PROTOCOL is set.  Default to "tcp" if none is set.
             */
            if (sProtocol == null) {
                dbConfig = ImmutableConnectionConfig.builder().from(dbConfig)
                        .protocol(ConnectionProtocol.TCP)
                        .build();
            }
        }
    }

    @Override
    public DBType getDbType() {
        return dbConfig.getDbType();
    }
}
