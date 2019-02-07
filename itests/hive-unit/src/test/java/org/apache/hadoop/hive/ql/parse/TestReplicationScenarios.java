/*
  Licensed to the Apache Software Foundation (ASF) under one
  or more contributor license agreements.  See the NOTICE file
  distributed with this work for additional information
  regarding copyright ownership.  The ASF licenses this file
  to you under the Apache License, Version 2.0 (the
  "License"); you may not use this file except in compliance
  with the License.  You may obtain a copy of the License at

      http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.
 */
package org.apache.hadoop.hive.ql.parse;

import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.PathFilter;
import org.apache.hadoop.hive.cli.CliSessionState;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.HiveMetaStoreClient;
import org.apache.hadoop.hive.metastore.IMetaStoreClient;
import org.apache.hadoop.hive.metastore.InjectableBehaviourObjectStore;
import org.apache.hadoop.hive.metastore.InjectableBehaviourObjectStore.BehaviourInjection;
import org.apache.hadoop.hive.metastore.MetaStoreUtils;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.NoSuchObjectException;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.NotificationEventResponse;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.messaging.MessageFactory;
import org.apache.hadoop.hive.metastore.messaging.event.filters.AndFilter;
import org.apache.hadoop.hive.metastore.messaging.event.filters.DatabaseAndTableFilter;
import org.apache.hadoop.hive.metastore.messaging.event.filters.EventBoundaryFilter;
import org.apache.hadoop.hive.metastore.messaging.event.filters.MessageFormatFilter;
import org.apache.hadoop.hive.ql.CommandNeedRetryException;
import org.apache.hadoop.hive.ql.Context;
import org.apache.hadoop.hive.ql.DriverContext;
import org.apache.hadoop.hive.ql.Driver;
import org.apache.hadoop.hive.ql.plan.AddPartitionDesc;
import org.apache.hadoop.hive.ql.WindowsPathUtil;
import org.apache.hadoop.hive.ql.exec.repl.ReplDumpWork;
import org.apache.hadoop.hive.ql.exec.DDLTask;
import org.apache.hadoop.hive.ql.exec.MoveTask;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.exec.repl.ReplLoadWork;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.repl.load.EventDumpDirComparator;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.hive.hcatalog.api.repl.ReplicationV1CompatRule;
import org.apache.hadoop.hive.shims.ShimLoader;
import org.apache.hadoop.util.Shell;
import org.apache.thrift.TException;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestName;
import org.junit.rules.TestRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.hadoop.hive.ql.ErrorMsg;

import javax.annotation.Nullable;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.io.Serializable;

import org.junit.Assert;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.apache.hadoop.hive.metastore.ReplChangeManager.SOURCE_OF_REPLICATION;

public class TestReplicationScenarios {

  @Rule
  public final TestName testName = new TestName();

  private final static String DBNOTIF_LISTENER_CLASSNAME =
      "org.apache.hive.hcatalog.listener.DbNotificationListener";
      // FIXME : replace with hive copy once that is copied
  private final static String tid =
      TestReplicationScenarios.class.getCanonicalName().replace('.','_') + "_" + System.currentTimeMillis();
  private final static String TEST_PATH =
      System.getProperty("test.warehouse.dir", "/tmp") + Path.SEPARATOR + tid;

  private static HiveConf hconf;
  private static boolean useExternalMS = false;
  private static int msPort;
  private static Driver driver;
  private static HiveMetaStoreClient metaStoreClient;

  @Rule
  public TestRule replV1BackwardCompatibleRule =
      new ReplicationV1CompatRule(metaStoreClient, hconf,
          new ArrayList<>(Arrays.asList("testEventFilters")));
  // Make sure we skip backward-compat checking for those tests that don't generate events

  protected static final Logger LOG = LoggerFactory.getLogger(TestReplicationScenarios.class);
  private ArrayList<String> lastResults;

  private final boolean VERIFY_SETUP_STEPS = true;
  // if verifySetup is set to true, all the test setup we do will perform additional
  // verifications as well, which is useful to verify that our setup occurred
  // correctly when developing and debugging tests. These verifications, however
  // do not test any new functionality for replication, and thus, are not relevant
  // for testing replication itself. For steady state, we want this to be false.

  @BeforeClass
  public static void setUpBeforeClass() throws Exception {
    hconf = new HiveConf(TestReplicationScenarios.class);
    String metastoreUri = System.getProperty("test."+HiveConf.ConfVars.METASTOREURIS.varname);
    if (metastoreUri != null) {
      hconf.setVar(HiveConf.ConfVars.METASTOREURIS, metastoreUri);
      useExternalMS = true;
      return;
    }
    if (Shell.WINDOWS) {
      WindowsPathUtil.convertPathsFromWindowsToHdfs(hconf);
    }

    hconf.set(HiveConf.ConfVars.METASTORE_TRANSACTIONAL_EVENT_LISTENERS.varname,
        DBNOTIF_LISTENER_CLASSNAME); // turn on db notification listener on metastore
    hconf.set(HiveConf.ConfVars.METASTORE_RAW_STORE_IMPL.varname,
        "org.apache.hadoop.hive.metastore.InjectableBehaviourObjectStore");

    hconf.setVar(HiveConf.ConfVars.REPLCMDIR, TEST_PATH + "/cmroot/");
    hconf.setBoolVar(HiveConf.ConfVars.REPLCMENABLED, true);
    hconf.setBoolVar(HiveConf.ConfVars.FIRE_EVENTS_FOR_DML, true);
    msPort = MetaStoreUtils.findFreePort();
    MetaStoreUtils.startMetaStore(msPort, ShimLoader.getHadoopThriftAuthBridge(), hconf);
    hconf.setVar(HiveConf.ConfVars.REPLDIR,TEST_PATH + "/hrepl/");
    hconf.setVar(HiveConf.ConfVars.METASTOREURIS, "thrift://localhost:"
        + msPort);
    hconf.setIntVar(HiveConf.ConfVars.METASTORETHRIFTCONNECTIONRETRIES, 3);
    hconf.set(HiveConf.ConfVars.PREEXECHOOKS.varname, "");
    hconf.set(HiveConf.ConfVars.POSTEXECHOOKS.varname, "");
    hconf.set(HiveConf.ConfVars.HIVE_SUPPORT_CONCURRENCY.varname, "false");
    hconf.setBoolVar(HiveConf.ConfVars.HIVEOPTIMIZEMETADATAQUERIES, true);
    System.setProperty(HiveConf.ConfVars.PREEXECHOOKS.varname, " ");
    System.setProperty(HiveConf.ConfVars.POSTEXECHOOKS.varname, " ");

    Path testPath = new Path(TEST_PATH);
    FileSystem fs = FileSystem.get(testPath.toUri(),hconf);
    fs.mkdirs(testPath);

    driver = new Driver(hconf);
    SessionState.start(new CliSessionState(hconf));
    metaStoreClient = new HiveMetaStoreClient(hconf);
  }

  @AfterClass
  public static void tearDownAfterClass(){
    // FIXME : should clean up TEST_PATH, but not doing it now, for debugging's sake
  }

  @Before
  public void setUp(){
    // before each test
  }

  @After
  public void tearDown(){
    // after each test
  }

  private static  int next = 0;
  private synchronized void advanceDumpDir() {
    next++;
    ReplDumpWork.injectNextDumpDirForTest(String.valueOf(next));
  }

 static class Tuple {
    final String dumpLocation;
    final String lastReplId;

    Tuple(String dumpLocation, String lastReplId) {
      this.dumpLocation = dumpLocation;
      this.lastReplId = lastReplId;
    }
  }

  private Tuple bootstrapLoadAndVerify(String dbName, String replDbName) throws IOException {
    return incrementalLoadAndVerify(dbName, null, replDbName);
  }

  private Tuple incrementalLoadAndVerify(String dbName, String fromReplId, String replDbName) throws IOException {
    Tuple dump = replDumpDb(dbName, fromReplId, null, null);
    loadAndVerify(replDbName, dump.dumpLocation, dump.lastReplId);
    return dump;
  }

  private Tuple dumpDbFromLastDump(String dbName, Tuple lastDump) throws IOException {
    return replDumpDb(dbName, lastDump.lastReplId, null, null);
  }

  private Tuple replDumpDb(String dbName, String fromReplID, String toReplID, String limit) throws IOException {
    advanceDumpDir();
    String dumpCmd = "REPL DUMP " + dbName;
    if (null != fromReplID) {
      dumpCmd = dumpCmd + " FROM " + fromReplID;
    }
    if (null != toReplID) {
      dumpCmd = dumpCmd + " TO " + toReplID;
    }
    if (null != limit) {
      dumpCmd = dumpCmd + " LIMIT " + limit;
    }
    run(dumpCmd);
    String dumpLocation = getResult(0, 0);
    String lastReplId = getResult(0, 1, true);
    LOG.info("Dumped to {} with id {} for command: {}", dumpLocation, lastReplId, dumpCmd);
    return new Tuple(dumpLocation, lastReplId);
  }

  private void loadAndVerify(String replDbName, String dumpLocation, String lastReplId) throws IOException {
    run("REPL LOAD " + replDbName + " FROM '" + dumpLocation + "' with ('hive.exec.parallel' =  'true')");
    verifyRun("REPL STATUS " + replDbName, lastReplId);
    return;
  }

  /**
   * Tests basic operation - creates a db, with 4 tables, 2 ptned and 2 unptned.
   * Inserts data into one of the ptned tables, and one of the unptned tables,
   * and verifies that a REPL DUMP followed by a REPL LOAD is able to load it
   * appropriately. This tests bootstrap behaviour primarily.
   */
  @Test
  public void testBasic() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".unptned_empty(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned_empty(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH, name + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH, name + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, name + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", ptn_data_2);
    verifySetup("SELECT a from " + dbName + ".ptned_empty", empty);
    verifySetup("SELECT * from " + dbName + ".unptned_empty", empty);

    String replicatedDbName = dbName + "_dupe";
    bootstrapLoadAndVerify(dbName, replicatedDbName);

    verifyRun("SELECT * from " + replicatedDbName + ".unptned", unptn_data);
    verifyRun("SELECT a from " + replicatedDbName + ".ptned WHERE b=1", ptn_data_1);
    verifyRun("SELECT a from " + replicatedDbName + ".ptned WHERE b=2", ptn_data_2);
    verifyRun("SELECT a from " + replicatedDbName + ".ptned_empty", empty);
    verifyRun("SELECT * from " + replicatedDbName + ".unptned_empty", empty);
  }

  private abstract class checkTaskPresent {
    public boolean hasTask(Task rootTask) {
      if (rootTask == null) {
        return false;
      }
      if (validate(rootTask)) {
        return true;
      }
      List<Task<? extends Serializable>> childTasks = rootTask.getChildTasks();
      if (childTasks == null) {
        return false;
      }
      for (Task<? extends Serializable> childTask : childTasks) {
        if (hasTask(childTask)) {
          return true;
        }
      }
      return false;
    }

    public abstract boolean validate(Task task);
  }

  private boolean hasMoveTask(Task rootTask) {
    checkTaskPresent validator =  new checkTaskPresent() {
      public boolean validate(Task task) {
        return  (task instanceof MoveTask);
      }
    };
    return validator.hasTask(rootTask);
  }

  private boolean hasPartitionTask(Task rootTask) {
    checkTaskPresent validator =  new checkTaskPresent() {
      public boolean validate(Task task) {
        if (task instanceof DDLTask) {
          DDLTask ddlTask = (DDLTask)task;
          AddPartitionDesc addPartitionDesc = ddlTask.getWork().getAddPartitionDesc();
          if (addPartitionDesc != null && !addPartitionDesc.getReplaceMode()) {
            return true;
          }
        }
        return false;
      }
    };
    return validator.hasTask(rootTask);
  }

  private Task getReplLoadRootTask(String replicadb, boolean isIncrementalDump, Tuple tuple) throws Throwable {
    HiveConf confTemp = new HiveConf(hconf);
    confTemp.set("hive.repl.enable.move.optimization", "true");
    ReplLoadWork replLoadWork = new ReplLoadWork(confTemp, tuple.dumpLocation, replicadb,
            null, null, isIncrementalDump, Long.valueOf(tuple.lastReplId),
        Collections.emptyList());
    Task replLoadTask = TaskFactory.get(replLoadWork, confTemp);
    replLoadTask.initialize(confTemp, null, new DriverContext(new Context(confTemp)));
    replLoadTask.executeTask(null);
    Hive.getThreadLocal().closeCurrent();
    return replLoadWork.getRootTask();
  }

  @Test
  public void testTaskCreationOptimization() throws Throwable {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    String dbNameReplica = dbName + "_replica";
    run("create table " + dbName + ".t2 (place string) partitioned by (country string)");
    run("insert into table " + dbName + ".t2 partition(country='india') values ('bangalore')");

    Tuple dump = replDumpDb(dbName, null, null, null);

    //bootstrap load should not have move task
    Task task = getReplLoadRootTask(dbNameReplica, false, dump);
    assertEquals(false, hasMoveTask(task));
    assertEquals(true, hasPartitionTask(task));

    loadAndVerify(dbNameReplica, dump.dumpLocation, dump.lastReplId);

    run("insert into table " + dbName + ".t2 partition(country='india') values ('delhi')");
    dump = replDumpDb(dbName, dump.lastReplId, null, null);

    // no partition task should be added as the operation is inserting into an existing partition.
    task = getReplLoadRootTask(dbNameReplica, true, dump);
    assertEquals(true, hasMoveTask(task));
    assertEquals(false, hasPartitionTask(task));

    loadAndVerify(dbNameReplica, dump.dumpLocation, dump.lastReplId);

    run("insert into table " + dbName + ".t2 partition(country='us') values ('sf')");
    dump = replDumpDb(dbName, dump.lastReplId, null, null);

    //no move task should be added as the operation is adding a dynamic partition
    task = getReplLoadRootTask(dbNameReplica, true, dump);
    assertEquals(false, hasMoveTask(task));
    assertEquals(true, hasPartitionTask(task));
  }

  @Test
  public void testBasicWithCM() throws Exception {

    String testName = "basic_with_cm";
    LOG.info("Testing "+testName);
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".unptned_empty(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned_empty(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH , testName + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH , testName + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH , testName + "_ptn2").toUri().getPath();
    String ptn_locn_2_later = new Path(TEST_PATH , testName + "_ptn2_later").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    run("SELECT * from " + dbName + ".unptned");
    verifyResults(unptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    run("SELECT a from " + dbName + ".ptned WHERE b=1");
    verifyResults(ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=2)");
    run("SELECT a from " + dbName + ".ptned WHERE b=2");
    verifyResults(ptn_data_2);
    run("SELECT a from " + dbName + ".ptned_empty");
    verifyResults(empty);
    run("SELECT * from " + dbName + ".unptned_empty");
    verifyResults(empty);

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);

    // Table dropped after "repl dump"
    run("DROP TABLE " + dbName + ".unptned");

    // Partition droppped after "repl dump"
    run("ALTER TABLE " + dbName + ".ptned " + "DROP PARTITION(b=1)");

    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    run("REPL STATUS " + dbName + "_dupe");
    verifyResults(new String[] {replDumpId});

    run("SELECT * from " + dbName + "_dupe.unptned");
    verifyResults(unptn_data);
    run("SELECT a from " + dbName + "_dupe.ptned WHERE b=1");
    verifyResults(ptn_data_1);
    run("SELECT a from " + dbName + "_dupe.ptned WHERE b=2");
    verifyResults(ptn_data_2);
    run("SELECT a from " + dbName + ".ptned_empty");
    verifyResults(empty);
    run("SELECT * from " + dbName + ".unptned_empty");
    verifyResults(empty);
  }

  @Test
  public void testBootstrapLoadOnExistingDb() throws IOException {
    String testName = "bootstrapLoadOnExistingDb";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String unptn_locn = new Path(TEST_PATH , testName + "_unptn").toUri().getPath();
    createTestDataFile(unptn_locn, unptn_data);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned ORDER BY a", unptn_data);

    // Create an empty database to load
    String emptyDB = createDB(testName + "_empty");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    // Load to an empty database
    run("REPL LOAD " + emptyDB + " FROM '" + replDumpLocn + "'");

    // REPL STATUS should return same repl ID as dump
    verifyRun("REPL STATUS " + emptyDB, replDumpId);
    verifyRun("SELECT * from " + emptyDB + ".unptned", unptn_data);

    String[] nullReplId = new String[]{ "NULL" };

    // Create a database with a table
    String withTableDb = createDB(testName + "_withtable");
    run("CREATE TABLE " + withTableDb + ".unptned(a string) STORED AS TEXTFILE");
    // Load using same dump to a DB with table. It should fail as DB is not empty.
    verifyFail("REPL LOAD " + withTableDb + " FROM '" + replDumpLocn + "'");

    // REPL STATUS should return NULL
    verifyRun("REPL STATUS " + withTableDb , nullReplId);

    // Create a database with a view
    String withViewDb = createDB(testName + "_withview");
    run("CREATE TABLE " + withViewDb + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE VIEW " + withViewDb + ".view AS SELECT * FROM " + withViewDb + ".unptned");
    // Load using same dump to a DB with view. It should fail as DB is not empty.
    verifyFail("REPL LOAD " + withViewDb + " FROM '" + replDumpLocn + "'");

    // REPL STATUS should return NULL
    verifyRun("REPL STATUS " + withViewDb, nullReplId);
  }

  @Test
  public void testBootstrapWithConcurrentDropTable() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH, name + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH, name + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, name + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", ptn_data_2);

    advanceDumpDir();

    BehaviourInjection<Table,Table> ptnedTableNuller = new BehaviourInjection<Table,Table>(){
      @Nullable
      @Override
      public Table apply(@Nullable Table table) {
        LOG.info("Performing injection on table " + table.getTableName());
        if (table.getTableName().equalsIgnoreCase("ptned")){
          injectionPathCalled = true;
          return null;
        } else {
          nonInjectedPathCalled = true;
          return table;
        }
      }
    };
    InjectableBehaviourObjectStore.setGetTableBehaviour(ptnedTableNuller);

    // The ptned table will not be dumped as getTable will return null
    run("REPL DUMP " + dbName);
    ptnedTableNuller.assertInjectionsPerformed(true,true);
    InjectableBehaviourObjectStore.resetGetTableBehaviour(); // reset the behaviour

    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    // The ptned table should miss in target as the table was marked virtually as dropped
    verifyRun("SELECT * from " + dbName + "_dupe.unptned", unptn_data);
    verifyFail("SELECT a from " + dbName + "_dupe.ptned WHERE b=1");
    verifyIfTableNotExist(dbName + "_dupe", "ptned");

    // Verify if Drop table on a non-existing table is idempotent
    run("DROP TABLE " + dbName + ".ptned");
    verifyIfTableNotExist(dbName, "ptned");

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String postDropReplDumpLocn = getResult(0,0);
    String postDropReplDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}->{}", postDropReplDumpLocn, replDumpId, postDropReplDumpId);
    assert(run("REPL LOAD " + dbName + "_dupe FROM '" + postDropReplDumpLocn + "'", true));

    verifyRun("SELECT * from " + dbName + "_dupe.unptned", unptn_data);
    verifyIfTableNotExist(dbName + "_dupe", "ptned");
    verifyFail("SELECT a from " + dbName + "_dupe.ptned WHERE b=1");
  }

  @Test
  public void testBootstrapWithConcurrentDropPartition() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String ptn_locn_1 = new Path(TEST_PATH, name + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, name + "_ptn2").toUri().getPath();

    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", ptn_data_2);

    advanceDumpDir();

    BehaviourInjection<List<String>, List<String>> listPartitionNamesNuller
            = new BehaviourInjection<List<String>, List<String>>(){
      @Nullable
      @Override
      public List<String> apply(@Nullable List<String> partitions) {
        injectionPathCalled = true;
        return new ArrayList<String>();
      }
    };
    InjectableBehaviourObjectStore.setListPartitionNamesBehaviour(listPartitionNamesNuller);

    // None of the partitions will be dumped as the partitions list was empty
    run("REPL DUMP " + dbName);
    listPartitionNamesNuller.assertInjectionsPerformed(true, false);
    InjectableBehaviourObjectStore.resetListPartitionNamesBehaviour(); // reset the behaviour

    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    // All partitions should miss in target as it was marked virtually as dropped
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=1", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=2", empty);
    verifyIfPartitionNotExist(dbName + "_dupe", "ptned", new ArrayList<>(Arrays.asList("1")));
    verifyIfPartitionNotExist(dbName + "_dupe", "ptned", new ArrayList<>(Arrays.asList("2")));

    // Verify if drop partition on a non-existing partition is idempotent and just a noop.
    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION (b=1)");
    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION (b=2)");
    verifyIfPartitionNotExist(dbName, "ptned", new ArrayList<>(Arrays.asList("1")));
    verifyIfPartitionNotExist(dbName, "ptned", new ArrayList<>(Arrays.asList("2")));
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", empty);
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", empty);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String postDropReplDumpLocn = getResult(0,0);
    String postDropReplDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}->{}", postDropReplDumpLocn, replDumpId, postDropReplDumpId);
    assert(run("REPL LOAD " + dbName + "_dupe FROM '" + postDropReplDumpLocn + "'", true));

    verifyIfPartitionNotExist(dbName + "_dupe", "ptned", new ArrayList<>(Arrays.asList("1")));
    verifyIfPartitionNotExist(dbName + "_dupe", "ptned", new ArrayList<>(Arrays.asList("2")));
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=1", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=2", empty);
  }

  @Test
  public void testBootstrapWithConcurrentRename() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    String replDbName = dbName + "_dupe";
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] ptn_data = new String[]{ "eleven" , "twelve" };
    String[] empty = new String[]{};
    String ptn_locn = new Path(TEST_PATH, name + "_ptn").toUri().getPath();

    createTestDataFile(ptn_locn, ptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");

    BehaviourInjection<Table,Table> ptnedTableRenamer = new BehaviourInjection<Table,Table>(){
      boolean success = false;

      @Nullable
      @Override
      public Table apply(@Nullable Table table) {
        if (injectionPathCalled) {
          nonInjectedPathCalled = true;
        } else {
          // getTable is invoked after fetching the table names
          injectionPathCalled = true;
          Thread t = new Thread(new Runnable() {
            public void run() {
              try {
                LOG.info("Entered new thread");
                Driver driver2 = new Driver(hconf);
                SessionState.start(new CliSessionState(hconf));
                CommandProcessorResponse ret = driver2.run("ALTER TABLE " + dbName + ".ptned PARTITION (b=1) RENAME TO PARTITION (b=10)");
                success = (ret.getException() == null);
                assertFalse(success);
                ret = driver2.run("ALTER TABLE " + dbName + ".ptned RENAME TO " + dbName + ".ptned_renamed");
                success = (ret.getException() == null);
                assertFalse(success);
                LOG.info("Exit new thread success - {}", success);
              } catch (CommandNeedRetryException e) {
                LOG.info("Hit Exception {} from new thread", e.getMessage());
                throw new RuntimeException(e);
              }
            }
          });
          t.start();
          LOG.info("Created new thread {}", t.getName());
          try {
            t.join();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        return table;
      }
    };
    InjectableBehaviourObjectStore.setGetTableBehaviour(ptnedTableRenamer);

    // The intermediate rename would've failed as bootstrap dump in progress
    bootstrapLoadAndVerify(dbName, replDbName);

    ptnedTableRenamer.assertInjectionsPerformed(true,true);
    InjectableBehaviourObjectStore.resetGetTableBehaviour(); // reset the behaviour

    // The ptned table should be there in both source and target as rename was not successful
    verifyRun("SELECT a from " + dbName + ".ptned WHERE (b=1) ORDER BY a", ptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE (b=1) ORDER BY a", ptn_data);

    // Verify if Rename after bootstrap is successful
    run("ALTER TABLE " + dbName + ".ptned PARTITION (b=1) RENAME TO PARTITION (b=10)");
    verifyIfPartitionNotExist(dbName, "ptned", new ArrayList<>(Arrays.asList("1")));
    run("ALTER TABLE " + dbName + ".ptned RENAME TO " + dbName + ".ptned_renamed");
    verifyIfTableNotExist(dbName, "ptned");
    verifyRun("SELECT a from " + dbName + ".ptned_renamed WHERE (b=10) ORDER BY a", ptn_data);
  }

  @Test
  public void testBootstrapWithDropPartitionedTable() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    String replDbName = dbName + "_dupe";
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] ptn_data = new String[]{ "eleven" , "twelve" };
    String[] empty = new String[]{};
    String ptn_locn = new Path(TEST_PATH, name + "_ptn").toUri().getPath();

    createTestDataFile(ptn_locn, ptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");

    BehaviourInjection<Table,Table> ptnedTableRenamer = new BehaviourInjection<Table,Table>(){
      boolean success = false;

      @Nullable
      @Override
      public Table apply(@Nullable Table table) {
        if (injectionPathCalled) {
          nonInjectedPathCalled = true;
        } else {
          // getTable is invoked after fetching the table names
          injectionPathCalled = true;
          Thread t = new Thread(new Runnable() {
            public void run() {
              try {
                LOG.info("Entered new thread");
                Driver driver2 = new Driver(hconf);
                SessionState.start(new CliSessionState(hconf));
                CommandProcessorResponse ret = driver2.run("DROP TABLE " + dbName + ".ptned");
                success = (ret.getException() == null);
                assertTrue(success);
                LOG.info("Exit new thread success - {}", success);
              } catch (CommandNeedRetryException e) {
                LOG.info("Hit Exception {} from new thread", e.getMessage());
                throw new RuntimeException(e);
              }
            }
          });
          t.start();
          LOG.info("Created new thread {}", t.getName());
          try {
            t.join();
          } catch (InterruptedException e) {
            throw new RuntimeException(e);
          }
        }
        return table;
      }
    };
    InjectableBehaviourObjectStore.setGetTableBehaviour(ptnedTableRenamer);

    Tuple bootstrap = bootstrapLoadAndVerify(dbName, replDbName);

    ptnedTableRenamer.assertInjectionsPerformed(true,true);
    InjectableBehaviourObjectStore.resetGetTableBehaviour(); // reset the behaviour

    incrementalLoadAndVerify(dbName, bootstrap.lastReplId, replDbName);
    verifyIfTableNotExist(replDbName, "ptned");

  }

  @Test
  public void testIncrementalAdds() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".unptned_empty(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned_empty(a string) partitioned by (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}",replDumpLocn,replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH, name + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH, name + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, name + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    verifySetup("SELECT a from " + dbName + ".ptned_empty", empty);
    verifySetup("SELECT * from " + dbName + ".unptned_empty", empty);

    // Now, we load data into the tables, and see if an incremental
    // repl drop/load can duplicate it.

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    run("CREATE TABLE " + dbName + ".unptned_late AS SELECT * from " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned_late", unptn_data);

    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", ptn_data_2);

    run("CREATE TABLE " + dbName + ".ptned_late(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned_late PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned_late WHERE b=1",ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned_late PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned_late WHERE b=2", ptn_data_2);

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId );
    String incrementalDumpLocn = getResult(0,0);
    String incrementalDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '"+incrementalDumpLocn+"'");

    run("REPL STATUS " + dbName + "_dupe");
    verifyResults(new String[] {incrementalDumpId});

    // VERIFY tables and partitions on destination for equivalence.

    verifyRun("SELECT * from " + dbName + "_dupe.unptned_empty", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_empty", empty);

//    verifyRun("SELECT * from " + dbName + "_dupe.unptned", unptn_data);
    // TODO :this does not work because LOAD DATA LOCAL INPATH into an unptned table seems
    // to use ALTER_TABLE only - it does not emit an INSERT or CREATE - re-enable after
    // fixing that.
    verifyRun("SELECT * from " + dbName + "_dupe.unptned_late", unptn_data);

    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=1", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=2", ptn_data_2);

    verifyRun("SELECT a from " + dbName + "_dupe.ptned_late WHERE b=1", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_late WHERE b=2", ptn_data_2);
  }

  @Test
  public void testIncrementalLoadWithVariableLengthEventId() throws IOException, TException {
    String testName = "incrementalLoadWithVariableLengthEventId";
    String dbName = createDB(testName);
    String replDbName = dbName + "_dupe";
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("INSERT INTO TABLE " + dbName + ".unptned values('ten')");

    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);
    String replDumpId = bootstrapDump.lastReplId;

    // CREATE_TABLE - TRUNCATE - INSERT - The result is just one record.
    // Creating dummy table to control the event ID of TRUNCATE not to be 10 or 100 or 1000...
    String[] unptn_data = new String[]{ "eleven" };
    run("CREATE TABLE " + dbName + ".dummy(a string) STORED AS TEXTFILE");
    run("TRUNCATE TABLE " + dbName + ".unptned");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");

    Tuple incrementalDump = replDumpDb(dbName, replDumpId, null, null);
    String incrementalDumpLocn = incrementalDump.dumpLocation;
    replDumpId = incrementalDump.lastReplId;

    // Rename the event directories such a way that the length varies.
    // We will encounter create_table, truncate followed by insert.
    // For the insert, set the event ID longer such that old comparator picks insert before truncate
    // Eg: Event IDs CREATE_TABLE - 5, TRUNCATE - 9, INSERT - 12 changed to
    // CREATE_TABLE - 5, TRUNCATE - 9, INSERT - 100
    // But if TRUNCATE have ID-10, then having INSERT-100 won't be sufficient to test the scenario.
    // So, we set any event comes after CREATE_TABLE starts with 20.
    // Eg: Event IDs CREATE_TABLE - 5, TRUNCATE - 10, INSERT - 12 changed to
    // CREATE_TABLE - 5, TRUNCATE - 20(20 <= Id < 100), INSERT - 100
    Path dumpPath = new Path(incrementalDumpLocn);
    FileSystem fs = dumpPath.getFileSystem(hconf);
    FileStatus[] dirsInLoadPath = fs.listStatus(dumpPath, EximUtil.getDirectoryFilter(fs));
    Arrays.sort(dirsInLoadPath, new EventDumpDirComparator());
    long nextEventId = 0;
    for (FileStatus dir : dirsInLoadPath) {
      Path srcPath = dir.getPath();
      if (nextEventId == 0) {
        nextEventId = (long) Math.pow(10.0, (double) srcPath.getName().length()) * 2;
        continue;
      }
      Path destPath = new Path(srcPath.getParent(), String.valueOf(nextEventId));
      fs.rename(srcPath, destPath);
      LOG.info("Renamed eventDir {} to {}", srcPath.getName(), destPath.getName());
      // Once the eventId reaches 5-20-100, then just increment it sequentially. This is to avoid longer values.
      if (String.valueOf(nextEventId).length() - srcPath.getName().length() >= 2) {
        nextEventId++;
      } else {
        nextEventId = (long) Math.pow(10.0, (double) String.valueOf(nextEventId).length());
      }
    }

    // Load from modified dump event directories.
    run("REPL LOAD " + replDbName + " FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + replDbName + ".unptned ORDER BY a", unptn_data);
  }

  @Test
  public void testIncrementalReplWithEventsMissing() throws Exception {
    String testName = "incrementalReplWithEventsMissing";
    String dbName = createDB(testName);
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);
    String replDumpId = bootstrapDump.lastReplId;

    // CREATE_TABLE - INSERT - TRUNCATE - INSERT - The result is just one record.
    String[] unptn_data = new String[]{ "eleven" };
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("INSERT INTO TABLE " + dbName + ".unptned values('ten')");
    run("TRUNCATE TABLE " + dbName + ".unptned");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");

    // Inject a behaviour where some events missing from notification_log table.
    // This ensures the incremental dump doesn't get all events for replication.
    BehaviourInjection<NotificationEventResponse,NotificationEventResponse> eventIdSkipper
            = new BehaviourInjection<NotificationEventResponse,NotificationEventResponse>(){

      @Nullable
      @Override
      public NotificationEventResponse apply(@Nullable NotificationEventResponse eventIdList) {
        if (null != eventIdList) {
          List<NotificationEvent> eventIds = eventIdList.getEvents();
          List<NotificationEvent> outEventIds = new ArrayList<NotificationEvent>();
          for (int i = 0; i < eventIds.size(); i++) {
            NotificationEvent event = eventIds.get(i);

            // Skip all the INSERT events
            if (event.getDbName().equalsIgnoreCase(dbName) && event.getEventType().equalsIgnoreCase("INSERT")) {
              injectionPathCalled = true;
              continue;
            }
            outEventIds.add(event);
          }

          // Return the new list
          return new NotificationEventResponse(outEventIds);
        } else {
          return null;
        }
      }
    };
    InjectableBehaviourObjectStore.setGetNextNotificationBehaviour(eventIdSkipper);

    advanceDumpDir();
    CommandProcessorResponse ret = driver.run("REPL DUMP " + dbName + " FROM " + replDumpId);
    assertTrue(ret.getResponseCode() == ErrorMsg.REPL_EVENTS_MISSING_IN_METASTORE.getErrorCode());
    eventIdSkipper.assertInjectionsPerformed(true,false);
    InjectableBehaviourObjectStore.resetGetNextNotificationBehaviour(); // reset the behaviour
  }

  @Test
  public void testLoadCmPathMissing() throws Exception {
    String dbName = createDB(testName.getMethodName());
    run("CREATE TABLE " + dbName + ".normal(a int)");
    run("INSERT INTO " + dbName + ".normal values (1)");

    advanceDumpDir();
    run("repl dump " + dbName, true);
    String dumpLocation = getResult(0, 0);

    run("DROP TABLE " + dbName + ".normal");

    String cmDir = hconf.getVar(HiveConf.ConfVars.REPLCMDIR);
    Path path = new Path(cmDir);
    FileSystem fs = path.getFileSystem(hconf);
    fs.delete(path);

    CommandProcessorResponse ret = driver.run("REPL LOAD " + dbName + " FROM '" + dumpLocation + "'");
    assertTrue(ret.getResponseCode() == ErrorMsg.REPL_FILE_MISSING_FROM_SRC_AND_CM_PATH.getErrorCode());

    ret = driver.run("REPL LOAD " + dbName + " FROM '" + dumpLocation + "' with ('hive.metastore.uris'='junk')");
    assertTrue(ret.getResponseCode() == ErrorMsg.METASTORE_COULD_NOT_INITIATE.getErrorCode());

    run("drop database " + dbName, true);
    fs.create(path, false);
  }

  @Test
  public void testDrops() throws IOException {

    String name = testName.getMethodName();
    String dbName = createDB(name);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned2(a string) partitioned by (b string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned3(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH, name + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH, name + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, name + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b='1')");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b='1'", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b='2')");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b='2'", ptn_data_2);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b='1')");
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b='1'", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b='2')");
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b='2'", ptn_data_2);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned3 PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned3 PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b=2", ptn_data_2);

    // At this point, we've set up all the tables and ptns we're going to test drops across
    // Replicate it first, and then we'll drop it on the source.

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");
    verifySetup("REPL STATUS " + dbName + "_dupe", new String[]{replDumpId});

    verifySetup("SELECT * from " + dbName + "_dupe.unptned", unptn_data);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned WHERE b='1'", ptn_data_1);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned WHERE b='2'", ptn_data_2);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned2 WHERE b='1'", ptn_data_1);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned2 WHERE b='2'", ptn_data_2);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned3 WHERE b=1", ptn_data_1);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned3 WHERE b=2", ptn_data_2);

    // All tables good on destination, drop on source.

    run("DROP TABLE " + dbName + ".unptned");
    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION (b='2')");
    run("DROP TABLE " + dbName + ".ptned2");
    run("ALTER TABLE " + dbName + ".ptned3 DROP PARTITION (b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b='2'", empty);
    verifySetup("SELECT a from " + dbName + ".ptned", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned3 WHERE b=1",empty);
    verifySetup("SELECT a from " + dbName + ".ptned3", ptn_data_2);

    // replicate the incremental drops

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String postDropReplDumpLocn = getResult(0,0);
    String postDropReplDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}->{}", postDropReplDumpLocn, replDumpId, postDropReplDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + postDropReplDumpLocn + "'");

    // verify that drops were replicated. This can either be from tables or ptns
    // not existing, and thus, throwing a NoSuchObjectException, or returning nulls
    // or select * returning empty, depending on what we're testing.

    verifyIfTableNotExist(dbName + "_dupe", "unptned");

    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b='2'", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned3 WHERE b=1", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned3", ptn_data_2);

    verifyIfTableNotExist(dbName + "_dupe", "ptned2");
  }

  @Test
  public void testDropsWithCM() throws IOException {

    String testName = "drops_with_cm";
    LOG.info("Testing "+testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned2(a string) partitioned by (b string) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH , testName + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH , testName + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH , testName + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    run("SELECT * from " + dbName + ".unptned");
    verifyResults(unptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b='1')");
    run("SELECT a from " + dbName + ".ptned WHERE b='1'");
    verifyResults(ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b='2')");
    run("SELECT a from " + dbName + ".ptned WHERE b='2'");
    verifyResults(ptn_data_2);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b='1')");
    run("SELECT a from " + dbName + ".ptned2 WHERE b='1'");
    verifyResults(ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b='2')");
    run("SELECT a from " + dbName + ".ptned2 WHERE b='2'");
    verifyResults(ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    run("REPL STATUS " + dbName + "_dupe");
    verifyResults(new String[] {replDumpId});

    run("SELECT * from " + dbName + "_dupe.unptned");
    verifyResults(unptn_data);
    run("SELECT a from " + dbName + "_dupe.ptned WHERE b='1'");
    verifyResults(ptn_data_1);
    run("SELECT a from " + dbName + "_dupe.ptned WHERE b='2'");
    verifyResults(ptn_data_2);
    run("SELECT a from " + dbName + "_dupe.ptned2 WHERE b='1'");
    verifyResults(ptn_data_1);
    run("SELECT a from " + dbName + "_dupe.ptned2 WHERE b='2'");
    verifyResults(ptn_data_2);

    run("CREATE TABLE " + dbName + ".unptned_copy" + " AS SELECT a FROM " + dbName + ".unptned");
    run("CREATE TABLE " + dbName + ".ptned_copy" + " LIKE " + dbName + ".ptned");
    run("INSERT INTO TABLE " + dbName + ".ptned_copy" + " PARTITION(b='1') SELECT a FROM " +
        dbName + ".ptned WHERE b='1'");
    run("SELECT a from " + dbName + ".unptned_copy");
    verifyResults(unptn_data);
    run("SELECT a from " + dbName + ".ptned_copy");
    verifyResults(ptn_data_1);

    run("DROP TABLE " + dbName + ".unptned");
    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION (b='2')");
    run("DROP TABLE " + dbName + ".ptned2");
    run("SELECT a from " + dbName + ".ptned WHERE b=2");
    verifyResults(empty);
    run("SELECT a from " + dbName + ".ptned");
    verifyResults(ptn_data_1);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String postDropReplDumpLocn = getResult(0,0);
    String postDropReplDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}->{}", postDropReplDumpLocn, replDumpId, postDropReplDumpId);

    // Drop table after dump
    run("DROP TABLE " + dbName + ".unptned_copy");
    // Drop partition after dump
    run("ALTER TABLE " + dbName + ".ptned_copy DROP PARTITION(b='1')");

    run("REPL LOAD " + dbName + "_dupe FROM '" + postDropReplDumpLocn + "'");

    Exception e = null;
    try {
      Table tbl = metaStoreClient.getTable(dbName + "_dupe", "unptned");
      assertNull(tbl);
    } catch (TException te) {
      e = te;
    }
    assertNotNull(e);
    assertEquals(NoSuchObjectException.class, e.getClass());

    run("SELECT a from " + dbName + "_dupe.ptned WHERE b=2");
    verifyResults(empty);
    run("SELECT a from " + dbName + "_dupe.ptned");
    verifyResults(ptn_data_1);

    verifyIfTableNotExist(dbName +"_dupe", "ptned2");

    run("SELECT a from " + dbName + "_dupe.unptned_copy");
    verifyResults(unptn_data);
    run("SELECT a from " + dbName + "_dupe.ptned_copy");
    verifyResults(ptn_data_1);
  }

  @Test
  public void testTableAlters() throws IOException {

    String testName = "TableAlters";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".unptned2(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned2(a string) partitioned by (b string) STORED AS TEXTFILE");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH , testName + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH , testName + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH , testName + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned2");
    verifySetup("SELECT * from " + dbName + ".unptned2", unptn_data);

    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b='1')");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b='1'", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b='2')");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b='2'", ptn_data_2);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b='1')");
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b='1'",ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b='2')");
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b='2'", ptn_data_2);

    // base tables set up, let's replicate them over

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    run("REPL STATUS " + dbName + "_dupe");
    verifyResults(new String[] {replDumpId});

    verifySetup("SELECT * from " + dbName + "_dupe.unptned", unptn_data);
    verifySetup("SELECT * from " + dbName + "_dupe.unptned2", unptn_data);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned WHERE b='1'", ptn_data_1);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned WHERE b='2'", ptn_data_2);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned2 WHERE b='1'", ptn_data_1);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned2 WHERE b='2'", ptn_data_2);

    // tables have been replicated over, and verified to be identical. Now, we do a couple of
    // alters on the source

    // Rename unpartitioned table
    run("ALTER TABLE " + dbName + ".unptned RENAME TO " + dbName + ".unptned_rn");
    verifySetup("SELECT * from " + dbName + ".unptned_rn", unptn_data);

    // Alter unpartitioned table set table property
    String testKey = "blah";
    String testVal = "foo";
    run("ALTER TABLE " + dbName + ".unptned2 SET TBLPROPERTIES ('" + testKey + "' = '" + testVal + "')");
    if (VERIFY_SETUP_STEPS){
      try {
        Table unptn2 = metaStoreClient.getTable(dbName,"unptned2");
        assertTrue(unptn2.getParameters().containsKey(testKey));
        assertEquals(testVal,unptn2.getParameters().get(testKey));
      } catch (TException e) {
        assertNull(e);
      }
    }

    // alter partitioned table, rename partition
    run("ALTER TABLE " + dbName + ".ptned PARTITION (b='2') RENAME TO PARTITION (b='22')");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", empty);
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=22", ptn_data_2);

    // alter partitioned table set table property
    run("ALTER TABLE " + dbName + ".ptned SET TBLPROPERTIES ('" + testKey + "' = '" + testVal + "')");
    if (VERIFY_SETUP_STEPS){
      try {
        Table ptned = metaStoreClient.getTable(dbName,"ptned");
        assertTrue(ptned.getParameters().containsKey(testKey));
        assertEquals(testVal,ptned.getParameters().get(testKey));
      } catch (TException e) {
        assertNull(e);
      }
    }

    // alter partitioned table's partition set partition property
    // Note : No DDL way to alter a partition, so we use the MSC api directly.
    try {
      List<String> ptnVals1 = new ArrayList<String>();
      ptnVals1.add("1");
      Partition ptn1 = metaStoreClient.getPartition(dbName, "ptned", ptnVals1);
      ptn1.getParameters().put(testKey,testVal);
      metaStoreClient.alter_partition(dbName,"ptned",ptn1,null);
    } catch (TException e) {
      assertNull(e);
    }

    // rename partitioned table
    verifySetup("SELECT a from " + dbName + ".ptned2 WHERE b=2", ptn_data_2);
    run("ALTER TABLE " + dbName + ".ptned2 RENAME TO " + dbName + ".ptned2_rn");
    verifySetup("SELECT a from " + dbName + ".ptned2_rn WHERE b=2", ptn_data_2);

    // All alters done, now we replicate them over.

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String postAlterReplDumpLocn = getResult(0,0);
    String postAlterReplDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}->{}", postAlterReplDumpLocn, replDumpId, postAlterReplDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + postAlterReplDumpLocn + "'");

    // Replication done, we now do the following verifications:

    // verify that unpartitioned table rename succeeded.
    verifyIfTableNotExist(dbName + "_dupe", "unptned");
    verifyRun("SELECT * from " + dbName + "_dupe.unptned_rn", unptn_data);

    // verify that partition rename succeded.
    try {
      Table unptn2 = metaStoreClient.getTable(dbName + "_dupe" , "unptned2");
      assertTrue(unptn2.getParameters().containsKey(testKey));
      assertEquals(testVal,unptn2.getParameters().get(testKey));
    } catch (TException te) {
      assertNull(te);
    }

    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=2", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=22", ptn_data_2);

    // verify that ptned table rename succeded.
    verifyIfTableNotExist(dbName + "_dupe", "ptned2");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned2_rn WHERE b=2", ptn_data_2);

    // verify that ptned table property set worked
    try {
      Table ptned = metaStoreClient.getTable(dbName + "_dupe" , "ptned");
      assertTrue(ptned.getParameters().containsKey(testKey));
      assertEquals(testVal, ptned.getParameters().get(testKey));
    } catch (TException te) {
      assertNull(te);
    }

    // verify that partitioned table partition property set worked.
    try {
      List<String> ptnVals1 = new ArrayList<String>();
      ptnVals1.add("1");
      Partition ptn1 = metaStoreClient.getPartition(dbName + "_dupe", "ptned", ptnVals1);
      assertTrue(ptn1.getParameters().containsKey(testKey));
      assertEquals(testVal,ptn1.getParameters().get(testKey));
    } catch (TException te) {
      assertNull(te);
    }

  }

  @Test
  public void testDatabaseAlters() throws IOException {

    String testName = "DatabaseAlters";
    String dbName = createDB(testName);
    String replDbName = dbName + "_dupe";
    String ownerName = "test";

    run("ALTER DATABASE " + dbName + " SET OWNER USER " + ownerName);

    // Trigger bootstrap replication
    Tuple bootstrap = bootstrapLoadAndVerify(dbName, replDbName);

    try {
      Database replDb = metaStoreClient.getDatabase(replDbName);
      assertEquals(ownerName, replDb.getOwnerName());
      assertEquals("USER", replDb.getOwnerType().toString());
    } catch (TException e) {
      assertNull(e);
    }

    // Alter database set DB property
    String testKey = "blah";
    String testVal = "foo";
    run("ALTER DATABASE " + dbName + " SET DBPROPERTIES ('" + testKey + "' = '" + testVal + "')");

    // All alters done, now we replicate them over.
    Tuple incremental = incrementalLoadAndVerify(dbName, bootstrap.lastReplId, replDbName);

    // Replication done, we need to check if the new property is added
    try {
      Database replDb = metaStoreClient.getDatabase(replDbName);
      assertTrue(replDb.getParameters().containsKey(testKey));
      assertEquals(testVal, replDb.getParameters().get(testKey));
    } catch (TException e) {
      assertNull(e);
    }

    String newValue = "newFoo";
    String newOwnerName = "newTest";
    run("ALTER DATABASE " + dbName + " SET DBPROPERTIES ('" + testKey + "' = '" + newValue + "')");
    run("ALTER DATABASE " + dbName + " SET OWNER ROLE " + newOwnerName);

    incremental = incrementalLoadAndVerify(dbName, incremental.lastReplId, replDbName);

    // Replication done, we need to check if new value is set for existing property
    try {
      Database replDb = metaStoreClient.getDatabase(replDbName);
      assertTrue(replDb.getParameters().containsKey(testKey));
      assertEquals(newValue, replDb.getParameters().get(testKey));
      assertEquals(newOwnerName, replDb.getOwnerName());
      assertEquals("ROLE", replDb.getOwnerType().toString());
    } catch (TException e) {
      assertNull(e);
    }
  }

  @Test
  public void testIncrementalLoad() throws IOException {
    String testName = "incrementalLoad";
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".unptned_empty(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName
        + ".ptned_empty(a string) partitioned by (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] unptn_data = new String[] { "eleven", "twelve" };
    String[] ptn_data_1 = new String[] { "thirteen", "fourteen", "fifteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "sixteen", "seventeen" };
    String[] empty = new String[] {};

    String unptn_locn = new Path(TEST_PATH, testName + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH, testName + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, testName + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    verifySetup("SELECT a from " + dbName + ".ptned_empty", empty);
    verifySetup("SELECT * from " + dbName + ".unptned_empty", empty);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    run("CREATE TABLE " + dbName + ".unptned_late LIKE " + dbName + ".unptned");
    run("INSERT INTO TABLE " + dbName + ".unptned_late SELECT * FROM " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned_late", unptn_data);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT * from " + dbName + "_dupe.unptned_late", unptn_data);

    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=1)");
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName
        + ".ptned PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName
        + ".ptned PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", ptn_data_2);

    run("CREATE TABLE " + dbName
        + ".ptned_late(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");
    run("INSERT INTO TABLE " + dbName + ".ptned_late PARTITION(b=1) SELECT a FROM " + dbName
        + ".ptned WHERE b=1");
    verifySetup("SELECT a from " + dbName + ".ptned_late WHERE b=1", ptn_data_1);

    run("INSERT INTO TABLE " + dbName + ".ptned_late PARTITION(b=2) SELECT a FROM " + dbName
        + ".ptned WHERE b=2");
    verifySetup("SELECT a from " + dbName + ".ptned_late WHERE b=2", ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.ptned_late WHERE b=1", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_late WHERE b=2", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=1", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned WHERE b=2", ptn_data_2);
  }

  @Test
  public void testIncrementalInserts() throws IOException {
    String testName = "incrementalInserts";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] unptn_data = new String[] { "eleven", "twelve" };

    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);

    run("CREATE TABLE " + dbName + ".unptned_late LIKE " + dbName + ".unptned");
    run("INSERT INTO TABLE " + dbName + ".unptned_late SELECT * FROM " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned_late ORDER BY a", unptn_data);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + ".unptned_late ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned_late ORDER BY a", unptn_data);

    String[] unptn_data_after_ins = new String[] { "eleven", "thirteen", "twelve" };
    String[] data_after_ovwrite = new String[] { "hundred" };
    run("INSERT INTO TABLE " + dbName + ".unptned_late values('" + unptn_data_after_ins[1] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned_late ORDER BY a", unptn_data_after_ins);
    run("INSERT OVERWRITE TABLE " + dbName + ".unptned values('" + data_after_ovwrite[0] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned", data_after_ovwrite);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.unptned_late ORDER BY a", unptn_data_after_ins);

    verifyRun("SELECT a from " + dbName + "_dupe.unptned", data_after_ovwrite);
  }

  @Test
  public void testEventTypesForDynamicAddPartitionByInsert() throws IOException {
    String name = testName.getMethodName();
    final String dbName = createDB(name);
    String replDbName = dbName + "_dupe";
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    Tuple bootstrap = bootstrapLoadAndVerify(dbName, replDbName);

    String[] ptn_data = new String[]{ "ten"};
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data[0] + "')");

    // Inject a behaviour where it throws exception if an INSERT event is found
    // As we dynamically add a partition through INSERT INTO cmd, it should just add ADD_PARTITION
    // event not an INSERT event
    BehaviourInjection<NotificationEventResponse,NotificationEventResponse> eventTypeValidator
            = new BehaviourInjection<NotificationEventResponse,NotificationEventResponse>(){

      @Nullable
      @Override
      public NotificationEventResponse apply(@Nullable NotificationEventResponse eventsList) {
        if (null != eventsList) {
          List<NotificationEvent> events = eventsList.getEvents();
          for (int i = 0; i < events.size(); i++) {
            NotificationEvent event = events.get(i);

            // Skip all the events belong to other DBs/tables.
            if (event.getDbName().equalsIgnoreCase(dbName)) {
              if (event.getEventType().equalsIgnoreCase("INSERT")) {
                // If an insert event is found, then return null hence no event is dumped.
                LOG.error("Encountered INSERT event when it was not expected to");
                return null;
              }
            }
          }
          injectionPathCalled = true;
        }
        return eventsList;
      }
    };
    InjectableBehaviourObjectStore.setGetNextNotificationBehaviour(eventTypeValidator);

    incrementalLoadAndVerify(dbName, bootstrap.lastReplId, replDbName);

    eventTypeValidator.assertInjectionsPerformed(true,false);
    InjectableBehaviourObjectStore.resetGetNextNotificationBehaviour(); // reset the behaviour

    verifyRun("SELECT a from " + replDbName + ".ptned where (b=1)", ptn_data);
  }

  @Test
  public void testIdempotentMoveTaskForInsertFiles() throws IOException {
    String name = testName.getMethodName();
    final String dbName = createDB(name);
    String replDbName = dbName + "_dupe";
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    Tuple bootstrap = bootstrapLoadAndVerify(dbName, replDbName);

    String[] unptn_data = new String[]{ "ten"};
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");

    // Inject a behaviour where it repeats the INSERT event twice with different event IDs
    BehaviourInjection<NotificationEventResponse,NotificationEventResponse> insertEventRepeater
            = new BehaviourInjection<NotificationEventResponse,NotificationEventResponse>(){

      @Nullable
      @Override
      public NotificationEventResponse apply(@Nullable NotificationEventResponse eventsList) {
        if (null != eventsList) {
          List<NotificationEvent> events = eventsList.getEvents();
          List<NotificationEvent> outEvents = new ArrayList<>();
          long insertEventId = -1;

          for (int i = 0; i < events.size(); i++) {
            NotificationEvent event = events.get(i);

            // Skip all the events belong to other DBs/tables.
            if (event.getDbName().equalsIgnoreCase(dbName)) {
              if (event.getEventType().equalsIgnoreCase("INSERT")) {
                // Add insert event twice with different event ID to allow apply of both events.
                NotificationEvent newEvent = new NotificationEvent(event);
                outEvents.add(newEvent);
                insertEventId = newEvent.getEventId();
              }
            }

            NotificationEvent newEvent = new NotificationEvent(event);
            if (insertEventId != -1) {
              insertEventId++;
              newEvent.setEventId(insertEventId);
            }
            outEvents.add(newEvent);
          }
          eventsList.setEvents(outEvents);
          injectionPathCalled = true;
        }
        return eventsList;
      }
    };
    InjectableBehaviourObjectStore.setGetNextNotificationBehaviour(insertEventRepeater);

    incrementalLoadAndVerify(dbName, bootstrap.lastReplId, replDbName);

    insertEventRepeater.assertInjectionsPerformed(true,false);
    InjectableBehaviourObjectStore.resetGetNextNotificationBehaviour(); // reset the behaviour

    verifyRun("SELECT a from " + replDbName + ".unptned", unptn_data);
  }

  @Test
  public void testIncrementalInsertToPartition() throws IOException {
    String testName = "incrementalInsertToPartition";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] ptn_data_1 = new String[] { "fifteen", "fourteen", "thirteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen", "sixteen" };

    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[2] + "')");

    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=2)");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[2] + "')");
    verifySetup("SELECT a from " + dbName + ".ptned where (b=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned where (b=2) ORDER BY a", ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + ".ptned where (b=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", ptn_data_2);

    String[] data_after_ovwrite = new String[] { "hundred" };
    // Insert overwrite on existing partition
    run("INSERT OVERWRITE TABLE " + dbName + ".ptned partition(b=2) values('" + data_after_ovwrite[0] + "')");
    verifySetup("SELECT a from " + dbName + ".ptned where (b=2)", data_after_ovwrite);
    // Insert overwrite on dynamic partition
    run("INSERT OVERWRITE TABLE " + dbName + ".ptned partition(b=3) values('" + data_after_ovwrite[0] + "')");
    verifySetup("SELECT a from " + dbName + ".ptned where (b=3)", data_after_ovwrite);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2)", data_after_ovwrite);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=3)", data_after_ovwrite);
  }

  @Test
  public void testInsertToMultiKeyPartition() throws IOException {
    String testName = "insertToMultiKeyPartition";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);


    run("CREATE TABLE " + dbName + ".namelist(name string) partitioned by (year int, month int, day int) STORED AS TEXTFILE");
    run("USE " + dbName);

    String[] ptn_data_1 = new String[] { "abraham", "bob", "carter" };
    String[] ptn_year_1980 = new String[] { "abraham", "bob" };
    String[] ptn_day_1 = new String[] { "abraham", "carter" };
    String[] ptn_year_1984_month_4_day_1_1 = new String[] { "carter" };
    String[] ptn_list_1 = new String[] { "year=1980/month=4/day=1", "year=1980/month=5/day=5", "year=1984/month=4/day=1" };

    run("INSERT INTO TABLE " + dbName + ".namelist partition(year=1980,month=4,day=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".namelist partition(year=1980,month=5,day=5) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".namelist partition(year=1984,month=4,day=1) values('" + ptn_data_1[2] + "')");

    verifySetup("SELECT name from " + dbName + ".namelist where (year=1980) ORDER BY name", ptn_year_1980);
    verifySetup("SELECT name from " + dbName + ".namelist where (day=1) ORDER BY name", ptn_day_1);
    verifySetup("SELECT name from " + dbName + ".namelist where (year=1984 and month=4 and day=1) ORDER BY name",
                                                                                ptn_year_1984_month_4_day_1_1);
    verifySetup("SELECT name from " + dbName + ".namelist ORDER BY name", ptn_data_1);
    verifySetup("SHOW PARTITIONS " + dbName + ".namelist", ptn_list_1);
    verifyRunWithPatternMatch("SHOW TABLE EXTENDED LIKE namelist PARTITION (year=1980,month=4,day=1)",
                              "location", "namelist/year=1980/month=4/day=1");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    verifyRun("SELECT name from " + dbName + "_dupe.namelist where (year=1980) ORDER BY name", ptn_year_1980);
    verifyRun("SELECT name from " + dbName + "_dupe.namelist where (day=1) ORDER BY name", ptn_day_1);
    verifyRun("SELECT name from " + dbName + "_dupe.namelist where (year=1984 and month=4 and day=1) ORDER BY name",
                                                                                   ptn_year_1984_month_4_day_1_1);
    verifyRun("SELECT name from " + dbName + "_dupe.namelist ORDER BY name", ptn_data_1);
    verifyRun("SHOW PARTITIONS " + dbName + "_dupe.namelist", ptn_list_1);

    run("USE " + dbName + "_dupe");
    verifyRunWithPatternMatch("SHOW TABLE EXTENDED LIKE namelist PARTITION (year=1980,month=4,day=1)",
            "location", "namelist/year=1980/month=4/day=1");
    run("USE " + dbName);

    String[] ptn_data_2 = new String[] { "abraham", "bob", "carter", "david", "eugene" };
    String[] ptn_year_1984_month_4_day_1_2 = new String[] { "carter", "david" };
    String[] ptn_day_1_2 = new String[] { "abraham", "carter", "david" };
    String[] ptn_list_2 = new String[] { "year=1980/month=4/day=1", "year=1980/month=5/day=5",
                                         "year=1984/month=4/day=1", "year=1990/month=5/day=25" };

    run("INSERT INTO TABLE " + dbName + ".namelist partition(year=1984,month=4,day=1) values('" + ptn_data_2[3] + "')");
    run("INSERT INTO TABLE " + dbName + ".namelist partition(year=1990,month=5,day=25) values('" + ptn_data_2[4] + "')");

    verifySetup("SELECT name from " + dbName + ".namelist where (year=1980) ORDER BY name", ptn_year_1980);
    verifySetup("SELECT name from " + dbName + ".namelist where (day=1) ORDER BY name", ptn_day_1_2);
    verifySetup("SELECT name from " + dbName + ".namelist where (year=1984 and month=4 and day=1) ORDER BY name",
                                                                                ptn_year_1984_month_4_day_1_2);
    verifySetup("SELECT name from " + dbName + ".namelist ORDER BY name", ptn_data_2);
    verifyRun("SHOW PARTITIONS " + dbName + ".namelist", ptn_list_2);
    verifyRunWithPatternMatch("SHOW TABLE EXTENDED LIKE namelist PARTITION (year=1990,month=5,day=25)",
            "location", "namelist/year=1990/month=5/day=25");

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT name from " + dbName + "_dupe.namelist where (year=1980) ORDER BY name", ptn_year_1980);
    verifyRun("SELECT name from " + dbName + "_dupe.namelist where (day=1) ORDER BY name", ptn_day_1_2);
    verifyRun("SELECT name from " + dbName + "_dupe.namelist where (year=1984 and month=4 and day=1) ORDER BY name",
                                                                                   ptn_year_1984_month_4_day_1_2);
    verifyRun("SELECT name from " + dbName + "_dupe.namelist ORDER BY name", ptn_data_2);
    verifyRun("SHOW PARTITIONS " + dbName + "_dupe.namelist", ptn_list_2);
    run("USE " + dbName + "_dupe");
    verifyRunWithPatternMatch("SHOW TABLE EXTENDED LIKE namelist PARTITION (year=1990,month=5,day=25)",
            "location", "namelist/year=1990/month=5/day=25");
    run("USE " + dbName);

    String[] ptn_data_3 = new String[] { "abraham", "bob", "carter", "david", "fisher" };
    String[] data_after_ovwrite = new String[] { "fisher" };
    // Insert overwrite on existing partition
    run("INSERT OVERWRITE TABLE " + dbName + ".namelist partition(year=1990,month=5,day=25) values('" + data_after_ovwrite[0] + "')");
    verifySetup("SELECT name from " + dbName + ".namelist where (year=1990 and month=5 and day=25)", data_after_ovwrite);
    verifySetup("SELECT name from " + dbName + ".namelist ORDER BY name", ptn_data_3);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifySetup("SELECT name from " + dbName + "_dupe.namelist where (year=1990 and month=5 and day=25)", data_after_ovwrite);
    verifySetup("SELECT name from " + dbName + "_dupe.namelist ORDER BY name", ptn_data_3);
  }

  @Test
  public void testIncrementalInsertDropUnpartitionedTable() throws IOException {
    String testName = "incrementalInsertDropUnpartitionedTable";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] unptn_data = new String[] { "eleven", "twelve" };

    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);

    run("CREATE TABLE " + dbName + ".unptned_tmp AS SELECT * FROM " + dbName + ".unptned");
    verifySetup("SELECT a from " + dbName + ".unptned_tmp ORDER BY a", unptn_data);

    // Get the last repl ID corresponding to all insert/alter/create events except DROP.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String lastDumpIdWithoutDrop = getResult(0, 1);

    // Drop all the tables
    run("DROP TABLE " + dbName + ".unptned");
    run("DROP TABLE " + dbName + ".unptned_tmp");
    verifyFail("SELECT * FROM " + dbName + ".unptned");
    verifyFail("SELECT * FROM " + dbName + ".unptned_tmp");

    // Dump all the events except DROP
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + lastDumpIdWithoutDrop);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    // Need to find the tables and data as drop is not part of this dump
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned_tmp ORDER BY a", unptn_data);

    // Dump the drop events and check if tables are getting dropped in target as well
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyFail("SELECT * FROM " + dbName + ".unptned");
    verifyFail("SELECT * FROM " + dbName + ".unptned_tmp");
  }

  @Test
  public void testIncrementalInsertDropPartitionedTable() throws IOException {
    String testName = "incrementalInsertDropPartitionedTable";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".ptned(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] ptn_data_1 = new String[] { "fifteen", "fourteen", "thirteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen", "sixteen" };

    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[2] + "')");

    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=20)");
    run("ALTER TABLE " + dbName + ".ptned RENAME PARTITION (b=20) TO PARTITION (b=2");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[2] + "')");
    verifySetup("SELECT a from " + dbName + ".ptned where (b=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned where (b=2) ORDER BY a", ptn_data_2);

    run("CREATE TABLE " + dbName + ".ptned_tmp AS SELECT * FROM " + dbName + ".ptned");
    verifySetup("SELECT a from " + dbName + ".ptned_tmp where (b=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned_tmp where (b=2) ORDER BY a", ptn_data_2);

    // Get the last repl ID corresponding to all insert/alter/create events except DROP.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String lastDumpIdWithoutDrop = getResult(0, 1);

    // Drop all the tables
    run("DROP TABLE " + dbName + ".ptned_tmp");
    run("DROP TABLE " + dbName + ".ptned");
    verifyFail("SELECT * FROM " + dbName + ".ptned_tmp");
    verifyFail("SELECT * FROM " + dbName + ".ptned");

    // Dump all the events except DROP
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + lastDumpIdWithoutDrop);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    // Need to find the tables and data as drop is not part of this dump
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_tmp where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_tmp where (b=2) ORDER BY a", ptn_data_2);

    // Dump the drop events and check if tables are getting dropped in target as well
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyFail("SELECT * FROM " + dbName + ".ptned_tmp");
    verifyFail("SELECT * FROM " + dbName + ".ptned");
  }

  @Test
  public void testInsertOverwriteOnUnpartitionedTableWithCM() throws IOException {
    String testName = "insertOverwriteOnUnpartitionedTableWithCM";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    // After INSERT INTO operation, get the last Repl ID
    String[] unptn_data = new String[] { "thirteen" };
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String insertDumpId = getResult(0, 1, false);

    // Insert overwrite on unpartitioned table
    String[] data_after_ovwrite = new String[] { "hundred" };
    run("INSERT OVERWRITE TABLE " + dbName + ".unptned values('" + data_after_ovwrite[0] + "')");

    // Dump only one INSERT INTO operation on the table.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + insertDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    // After Load from this dump, all target tables/partitions will have initial set of data but source will have latest data.
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data);

    // Dump the remaining INSERT OVERWRITE operations on the table.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);

    // After load, shall see the overwritten data.
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", data_after_ovwrite);
  }

  @Test
  public void testInsertOverwriteOnPartitionedTableWithCM() throws IOException {
    String testName = "insertOverwriteOnPartitionedTableWithCM";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    // INSERT INTO 2 partitions and get the last repl ID
    String[] ptn_data_1 = new String[] { "fourteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "sixteen" };
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[1] + "')");
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String insertDumpId = getResult(0, 1, false);

    // Insert overwrite on one partition with multiple files
    String[] data_after_ovwrite = new String[] { "hundred" };
    run("INSERT OVERWRITE TABLE " + dbName + ".ptned partition(b=2) values('" + data_after_ovwrite[0] + "')");
    verifySetup("SELECT a from " + dbName + ".ptned where (b=2)", data_after_ovwrite);

    // Dump only 2 INSERT INTO operations.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + insertDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    // After Load from this dump, all target tables/partitions will have initial set of data.
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", ptn_data_2);

    // Dump the remaining INSERT OVERWRITE operation on the table.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);

    // After load, shall see the overwritten data.
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", data_after_ovwrite);
  }

  @Test
  public void testDropPartitionEventWithPartitionOnTimestampColumn() throws IOException {
    String testName = "dropPartitionEventWithPartitionOnTimestampColumn";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".ptned(a string) PARTITIONED BY (b timestamp)");
    String[] ptn_data = new String[] { "fourteen" };
    String ptnVal = "2017-10-01 01:00:10.1";
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=\"" + ptnVal +"\") values('" + ptn_data[0] + "')");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    ptn_data = new String[] { "fifteen" };
    ptnVal = "2017-10-24 00:00:00.0";
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=\"" + ptnVal +"\") values('" + ptn_data[0] + "')");

    // Replicate insert event and verify
    Tuple incrDump = incrementalLoadAndVerify(dbName, bootstrapDump.lastReplId, replDbName);
    verifyRun("SELECT a from " + replDbName + ".ptned where (b=\"" + ptnVal + "\") ORDER BY a", ptn_data);

    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION(b=\"" + ptnVal + "\")");

    // Replicate drop partition event and verify
    incrementalLoadAndVerify(dbName, incrDump.lastReplId, replDbName);
    verifyIfPartitionNotExist(replDbName, "ptned", new ArrayList<>(Arrays.asList(ptnVal)));
  }

  /**
   * Verify replication when string partition column value has special chars
   * @throws IOException
   */
  @Test
  public void testWithStringPartitionSpecialChars() throws IOException {
    String testName = "testWithStringPartitionSpecialChars";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".ptned(v string) PARTITIONED BY (p string)");
    String[] ptn_data = new String[] { "fourteen", "fifteen" };
    String[] ptnVal = new String [] {"has a space, /, and \t tab", "another set of '#@ chars" };
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(p=\"" + ptnVal[0] +"\") values('" + ptn_data[0] + "')");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(p=\"" + ptnVal[1] +"\") values('" + ptn_data[1] + "')");
    // Replicate insert event and verify
    Tuple incrDump = incrementalLoadAndVerify(dbName, bootstrapDump.lastReplId, replDbName);
    verifyRun("SELECT p from " + replDbName + ".ptned ORDER BY p desc", ptnVal);

    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION(p=\"" + ptnVal[0] + "\")");

    // Replicate drop partition event and verify
    incrementalLoadAndVerify(dbName, incrDump.lastReplId, replDbName);
    verifyIfPartitionNotExist(replDbName, "ptned", new ArrayList<>(Arrays.asList(ptnVal[0])));
  }
  
  @Test
  public void testRenameTableWithCM() throws IOException {
    String testName = "renameTableWithCM";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] unptn_data = new String[] { "ten", "twenty" };
    String[] ptn_data_1 = new String[] { "fifteen", "fourteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen" };

    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");

    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[1] + "')");

    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=2)");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[1] + "')");

    // Get the last repl ID corresponding to all insert events except RENAME.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String lastDumpIdWithoutRename = getResult(0, 1);

    run("ALTER TABLE " + dbName + ".unptned RENAME TO " + dbName + ".unptned_renamed");
    run("ALTER TABLE " + dbName + ".ptned RENAME TO " + dbName + ".ptned_renamed");

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + lastDumpIdWithoutRename);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyFail("SELECT a from " + dbName + "_dupe.unptned ORDER BY a");
    verifyFail("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a");
    verifyRun("SELECT a from " + dbName + "_dupe.unptned_renamed ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_renamed where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_renamed where (b=2) ORDER BY a", ptn_data_2);
  }

  @Test
  public void testRenamePartitionWithCM() throws IOException {
    String testName = "renamePartitionWithCM";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] empty = new String[] {};
    String[] ptn_data_1 = new String[] { "fifteen", "fourteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen" };

    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[1] + "')");

    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=2)");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[1] + "')");

    // Get the last repl ID corresponding to all insert events except RENAME.
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String lastDumpIdWithoutRename = getResult(0, 1);

    run("ALTER TABLE " + dbName + ".ptned PARTITION (b=2) RENAME TO PARTITION (b=10)");

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + lastDumpIdWithoutRename);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=10) ORDER BY a", empty);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=10) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where (b=2) ORDER BY a", empty);
  }

  @Test
  public void testRenameTableAcrossDatabases() throws IOException {
    String testName = "renameTableAcrossDatabases";
    LOG.info("Testing " + testName);
    String dbName1 = createDB(testName+"_1");
    String dbName2 = createDB(testName+"_2");
    String replDbName1 = dbName1 + "_dupe";
    String replDbName2 = dbName2 + "_dupe";

    run("CREATE TABLE " + dbName1 + ".unptned(a string) STORED AS TEXTFILE");

    String[] unptn_data = new String[] { "ten", "twenty" };
    String unptn_locn = new Path(TEST_PATH, testName + "_unptn").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName1 + ".unptned");

    Tuple bootstrap1 = bootstrapLoadAndVerify(dbName1, replDbName1);
    Tuple bootstrap2 = bootstrapLoadAndVerify(dbName2, replDbName2);

    verifyRun("SELECT a from " + replDbName1 + ".unptned ORDER BY a", unptn_data);
    verifyIfTableNotExist(replDbName2, "unptned");

    verifyFail("ALTER TABLE " + dbName1 + ".unptned RENAME TO " + dbName2 + ".unptned_renamed");

    incrementalLoadAndVerify(dbName1, bootstrap1.lastReplId, replDbName1);
    incrementalLoadAndVerify(dbName2, bootstrap2.lastReplId, replDbName2);

    verifyIfTableNotExist(replDbName2, "unptned");
    verifyIfTableNotExist(replDbName1, "unptned_renamed");
    verifyRun("SELECT a from " + replDbName1 + ".unptned ORDER BY a", unptn_data);
  }

  @Test
  public void testRenamePartitionedTableAcrossDatabases() throws IOException {
    String testName = "renamePartitionedTableAcrossDatabases";
    LOG.info("Testing " + testName);
    String dbName1 = createDB(testName+"_1");
    String dbName2 = createDB(testName+"_2");
    String replDbName1 = dbName1 + "_dupe";
    String replDbName2 = dbName2 + "_dupe";

    run("CREATE TABLE " + dbName1 + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");

    String[] ptn_data = new String[] { "fifteen", "fourteen" };
    String ptn_locn = new Path(TEST_PATH, testName + "_ptn").toUri().getPath();

    createTestDataFile(ptn_locn, ptn_data);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn + "' OVERWRITE INTO TABLE " + dbName1 + ".ptned PARTITION(b=1)");

    Tuple bootstrap1 = bootstrapLoadAndVerify(dbName1, replDbName1);
    Tuple bootstrap2 = bootstrapLoadAndVerify(dbName2, replDbName2);

    verifyRun("SELECT a from " + replDbName1 + ".ptned where (b=1) ORDER BY a", ptn_data);
    verifyIfTableNotExist(replDbName2, "ptned");

    verifyFail("ALTER TABLE " + dbName1 + ".ptned RENAME TO " + dbName2 + ".ptned_renamed");

    incrementalLoadAndVerify(dbName1, bootstrap1.lastReplId, replDbName1);
    incrementalLoadAndVerify(dbName2, bootstrap2.lastReplId, replDbName2);

    verifyIfTableNotExist(replDbName2, "ptned_renamed");
    verifyIfTableNotExist(replDbName1, "ptned_renamed");
    verifyRun("SELECT a from " + replDbName1 + ".ptned where (b=1) ORDER BY a", ptn_data);
  }

  @Test
  public void testViewsReplication() throws IOException {
    String testName = "viewsReplication";
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    run("CREATE VIEW " + dbName + ".virtual_view AS SELECT * FROM " + dbName + ".unptned");

    String[] unptn_data = new String[]{ "eleven" , "twelve" };
    String[] ptn_data_1 = new String[]{ "thirteen", "fourteen", "fifteen"};
    String[] ptn_data_2 = new String[]{ "fifteen", "sixteen", "seventeen"};
    String[] empty = new String[]{};

    String unptn_locn = new Path(TEST_PATH , testName + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH , testName + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH , testName + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    verifySetup("SELECT a from " + dbName + ".ptned", empty);
    verifySetup("SELECT * from " + dbName + ".unptned", empty);
    verifySetup("SELECT * from " + dbName + ".virtual_view", empty);

    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    verifySetup("SELECT * from " + dbName + ".virtual_view", unptn_data);

    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_2 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=2", ptn_data_2);

    //run("CREATE MATERIALIZED VIEW " + dbName + ".mat_view AS SELECT a FROM " + dbName + ".ptned where b=1");
    //verifySetup("SELECT a from " + dbName + ".mat_view", ptn_data_1);

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    LOG.info("Bootstrap-dump: Dumped to {} with id {}",replDumpLocn,replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    verifyRun("SELECT * from " + dbName + "_dupe.virtual_view", unptn_data);
    //verifyRun("SELECT a from " + dbName + "_dupe.mat_view", ptn_data_1);

    run("CREATE VIEW " + dbName + ".virtual_view2 AS SELECT a FROM " + dbName + ".ptned where b=2");
    verifySetup("SELECT a from " + dbName + ".virtual_view2", ptn_data_2);

    // Create a view with name already exist. Just to verify if failure flow clears the added create_table event.
    run("CREATE VIEW " + dbName + ".virtual_view2 AS SELECT a FROM " + dbName + ".ptned where b=2");

    //run("CREATE MATERIALIZED VIEW " + dbName + ".mat_view2 AS SELECT * FROM " + dbName + ".unptned");
    //verifySetup("SELECT * from " + dbName + ".mat_view2", unptn_data);

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId );
    String incrementalDumpLocn = getResult(0,0);
    String incrementalDumpId = getResult(0,1,true);
    LOG.info("Incremental-dump: Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '"+incrementalDumpLocn+"'");

    run("REPL STATUS " + dbName + "_dupe");
    verifyResults(new String[] {incrementalDumpId});

    verifyRun("SELECT * from " + dbName + "_dupe.unptned", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned where b=1", ptn_data_1);
    verifyRun("SELECT * from " + dbName + "_dupe.virtual_view", unptn_data);
    //verifyRun("SELECT a from " + dbName + "_dupe.mat_view", ptn_data_1);
    verifyRun("SELECT * from " + dbName + "_dupe.virtual_view2", ptn_data_2);
    //verifyRun("SELECT * from " + dbName + "_dupe.mat_view2", unptn_data);

    // Test "alter table"
    run("ALTER VIEW " + dbName + ".virtual_view2 AS SELECT a FROM " + dbName + ".ptned where b=1");
    verifyRun("SELECT * from " + dbName + ".virtual_view2", ptn_data_1);

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + incrementalDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0,1,true);
    LOG.info("Incremental-dump: Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT * from " + dbName + "_dupe.virtual_view2", ptn_data_1);

    // Test "DROP VIEW"
    run("DROP VIEW " + dbName + ".virtual_view");
    verifyIfTableNotExist(dbName, "virtual_view");

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + incrementalDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-dump: Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyIfTableNotExist(dbName + "_dupe", "virtual_view");
  }

  @Test
  public void testDumpLimit() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);

    String[] unptn_data = new String[] { "eleven", "thirteen", "twelve" };
    String[] unptn_data_load1 = new String[] { "eleven" };
    String[] unptn_data_load2 = new String[] { "eleven", "thirteen" };

    // x events to insert, last repl ID: replDumpId+x
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    String firstInsertLastReplId = replDumpDb(dbName, replDumpId, null, null).lastReplId;
    Integer numOfEventsIns1 = Integer.valueOf(firstInsertLastReplId) - Integer.valueOf(replDumpId);

    // x events to insert, last repl ID: replDumpId+2x
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    String secondInsertLastReplId = replDumpDb(dbName, firstInsertLastReplId, null, null).lastReplId;
    Integer numOfEventsIns2 = Integer.valueOf(secondInsertLastReplId) - Integer.valueOf(firstInsertLastReplId);

    // x events to insert, last repl ID: replDumpId+3x
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[2] + "')");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);

    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " LIMIT " + numOfEventsIns1);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data_load1);

    advanceDumpDir();
    Integer lastReplID = Integer.valueOf(replDumpId);
    lastReplID += 1000;
    String toReplID = String.valueOf(lastReplID);

    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + toReplID + " LIMIT " + numOfEventsIns2);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data_load2);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data);
  }

  @Test
  public void testExchangePartition() throws IOException {
    String testName = "exchangePartition";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".ptned_src(a string) partitioned by (b int, c int) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned_dest(a string) partitioned by (b int, c int) STORED AS TEXTFILE");

    String[] empty = new String[] {};
    String[] ptn_data_1 = new String[] { "fifteen", "fourteen", "thirteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen", "sixteen" };

    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=1, c=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=1, c=1) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=1, c=1) values('" + ptn_data_1[2] + "')");

    run("ALTER TABLE " + dbName + ".ptned_src ADD PARTITION (b=2, c=2)");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=2, c=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=2, c=2) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=2, c=2) values('" + ptn_data_2[2] + "')");

    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=2, c=3) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=2, c=3) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_src partition(b=2, c=3) values('" + ptn_data_2[2] + "')");
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=2 and c=3) ORDER BY a", ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".ptned_src where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + ".ptned_src where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + ".ptned_src where (b=2 and c=3) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=2 and c=3) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=1 and c=1)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=2 and c=2)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=2 and c=3)", empty);

    // Exchange single partitions using complete partition-spec (all partition columns)
    run("ALTER TABLE " + dbName + ".ptned_dest EXCHANGE PARTITION (b=1, c=1) WITH TABLE " + dbName + ".ptned_src");
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=1 and c=1)", empty);
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=2 and c=3) ORDER BY a", ptn_data_2);
    verifySetup("SELECT a from " + dbName + ".ptned_dest where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned_dest where (b=2 and c=2)", empty);
    verifySetup("SELECT a from " + dbName + ".ptned_dest where (b=2 and c=3)", empty);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=1 and c=1)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=2 and c=3) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=2 and c=2)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=2 and c=3)", empty);

    // Exchange multiple partitions using partial partition-spec (only one partition column)
    run("ALTER TABLE " + dbName + ".ptned_dest EXCHANGE PARTITION (b=2) WITH TABLE " + dbName + ".ptned_src");
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=1 and c=1)", empty);
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=2 and c=2)", empty);
    verifySetup("SELECT a from " + dbName + ".ptned_src where (b=2 and c=3)", empty);
    verifySetup("SELECT a from " + dbName + ".ptned_dest where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned_dest where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifySetup("SELECT a from " + dbName + ".ptned_dest where (b=2 and c=3) ORDER BY a", ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=1 and c=1)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=2 and c=2)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_src where (b=2 and c=3)", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=1 and c=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=2 and c=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_dest where (b=2 and c=3) ORDER BY a", ptn_data_2);
  }

  @Test
  public void testTruncateTable() throws IOException {
    String testName = "truncateTable";
    LOG.info("Testing " + testName);

    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    String[] unptn_data = new String[] { "eleven", "twelve" };
    String[] empty = new String[] {};
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data);

    run("TRUNCATE TABLE " + dbName + ".unptned");
    verifySetup("SELECT a from " + dbName + ".unptned", empty);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".unptned", empty);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned", empty);

    String[] unptn_data_after_ins = new String[] { "thirteen" };
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data_after_ins[0] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data_after_ins);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data_after_ins);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data_after_ins);
  }

  @Test
  public void testTruncatePartitionedTable() throws IOException {
    String testName = "truncatePartitionedTable";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".ptned_1(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned_2(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");

    String[] ptn_data_1 = new String[] { "fifteen", "fourteen", "thirteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen", "sixteen" };
    String[] empty = new String[] {};
    run("INSERT INTO TABLE " + dbName + ".ptned_1 PARTITION(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_1 PARTITION(b=1) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_1 PARTITION(b=1) values('" + ptn_data_1[2] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_1 PARTITION(b=2) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_1 PARTITION(b=2) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_1 PARTITION(b=2) values('" + ptn_data_2[2] + "')");

    run("INSERT INTO TABLE " + dbName + ".ptned_2 PARTITION(b=10) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_2 PARTITION(b=10) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_2 PARTITION(b=10) values('" + ptn_data_1[2] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_2 PARTITION(b=20) values('" + ptn_data_2[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_2 PARTITION(b=20) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned_2 PARTITION(b=20) values('" + ptn_data_2[2] + "')");

    verifyRun("SELECT a from " + dbName + ".ptned_1 where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + ".ptned_1 where (b=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + ".ptned_2 where (b=10) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + ".ptned_2 where (b=20) ORDER BY a", ptn_data_2);

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_1 where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_1 where (b=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_2 where (b=10) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + dbName + "_dupe.ptned_2 where (b=20) ORDER BY a", ptn_data_2);

    run("TRUNCATE TABLE " + dbName + ".ptned_1 PARTITION(b=2)");
    verifySetup("SELECT a from " + dbName + ".ptned_1 where (b=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + ".ptned_1 where (b=2)", empty);

    run("TRUNCATE TABLE " + dbName + ".ptned_2");
    verifySetup("SELECT a from " + dbName + ".ptned_2 where (b=10)", empty);
    verifySetup("SELECT a from " + dbName + ".ptned_2 where (b=20)", empty);

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifySetup("SELECT a from " + dbName + "_dupe.ptned_1 where (b=1) ORDER BY a", ptn_data_1);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned_1 where (b=2)", empty);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned_2 where (b=10)", empty);
    verifySetup("SELECT a from " + dbName + "_dupe.ptned_2 where (b=20)", empty);
  }

  @Test
  public void testTruncateWithCM() throws IOException {
    String testName = "truncateWithCM";
    LOG.info("Testing " + testName);
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);

    String[] empty = new String[] {};
    String[] unptn_data = new String[] { "eleven", "thirteen" };
    String[] unptn_data_load1 = new String[] { "eleven" };
    String[] unptn_data_load2 = new String[] { "eleven", "thirteen" };

    // x events to insert, last repl ID: replDumpId+x
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    String firstInsertLastReplId = replDumpDb(dbName, replDumpId, null, null).lastReplId;
    Integer numOfEventsIns1 = Integer.valueOf(firstInsertLastReplId) - Integer.valueOf(replDumpId);

    // x events to insert, last repl ID: replDumpId+2x
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);
    String secondInsertLastReplId = replDumpDb(dbName, firstInsertLastReplId, null, null).lastReplId;
    Integer numOfEventsIns2 = Integer.valueOf(secondInsertLastReplId) - Integer.valueOf(firstInsertLastReplId);

    // y event to truncate, last repl ID: replDumpId+2x+y
    run("TRUNCATE TABLE " + dbName + ".unptned");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", empty);
    String thirdTruncLastReplId = replDumpDb(dbName, secondInsertLastReplId, null, null).lastReplId;
    Integer numOfEventsTrunc3 = Integer.valueOf(thirdTruncLastReplId) - Integer.valueOf(secondInsertLastReplId);

    // x events to insert, last repl ID: replDumpId+3x+y
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data_load1[0] + "')");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data_load1);

    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    // Dump and load only first insert (1 record)
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " LIMIT " + numOfEventsIns1);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;

    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");
    verifyRun("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data_load1);
    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data_load1);

    // Dump and load only second insert (2 records)
    advanceDumpDir();
    Integer lastReplID = Integer.valueOf(replDumpId);
    lastReplID += 1000;
    String toReplID = String.valueOf(lastReplID);

    run("REPL DUMP " + dbName + " FROM " + replDumpId + " TO " + toReplID + " LIMIT " + numOfEventsIns2);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data_load2);

    // Dump and load only truncate (0 records)
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId + " LIMIT " + numOfEventsTrunc3);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", empty);

    // Dump and load insert after truncate (1 record)
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-Dump: Dumped to {} with id {} from {}", incrementalDumpLocn, incrementalDumpId, replDumpId);
    replDumpId = incrementalDumpId;
    run("REPL LOAD " + dbName + "_dupe FROM '" + incrementalDumpLocn + "'");

    verifyRun("SELECT a from " + dbName + "_dupe.unptned ORDER BY a", unptn_data_load1);
  }

  @Test
  public void testIncrementalRepeatEventOnExistingObject() throws IOException {
    String testName = "incrementalRepeatEventOnExistingObject";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    // List to maintain the incremental dumps for each operation
    List<Tuple> incrementalDumpList = new ArrayList<Tuple>();

    String[] empty = new String[] {};
    String[] unptn_data = new String[] { "ten" };
    String[] ptn_data_1 = new String[] { "fifteen" };
    String[] ptn_data_2 = new String[] { "seventeen" };

    // INSERT EVENT to unpartitioned table
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    Tuple replDump = dumpDbFromLastDump(dbName, bootstrapDump);
    incrementalDumpList.add(replDump);

    // INSERT EVENT to partitioned table with dynamic ADD_PARTITION
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=1) values('" + ptn_data_1[0] + "')");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // ADD_PARTITION EVENT to partitioned table
    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=2)");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // INSERT EVENT to partitioned table on existing partition
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=2) values('" + ptn_data_2[0] + "')");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // TRUNCATE_PARTITION EVENT on partitioned table
    run("TRUNCATE TABLE " + dbName + ".ptned PARTITION (b=1)");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // TRUNCATE_TABLE EVENT on unpartitioned table
    run("TRUNCATE TABLE " + dbName + ".unptned");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // CREATE_TABLE EVENT with multiple partitions
    run("CREATE TABLE " + dbName + ".unptned_tmp AS SELECT * FROM " + dbName + ".ptned");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // Replicate all the events happened so far
    Tuple incrDump = incrementalLoadAndVerify(dbName, bootstrapDump.lastReplId, replDbName);

    verifyRun("SELECT a from " + replDbName + ".unptned ORDER BY a", empty);
    verifyRun("SELECT a from " + replDbName + ".ptned where (b=1) ORDER BY a", empty);
    verifyRun("SELECT a from " + replDbName + ".ptned where (b=2) ORDER BY a", ptn_data_2);
    verifyRun("SELECT a from " + replDbName + ".unptned_tmp where (b=1) ORDER BY a", empty);
    verifyRun("SELECT a from " + replDbName + ".unptned_tmp where (b=2) ORDER BY a", ptn_data_2);

    // Load each incremental dump from the list. Each dump have only one operation.
    for (Tuple currDump : incrementalDumpList) {
      // Load the incremental dump and ensure it does nothing and lastReplID remains same
      loadAndVerify(replDbName, currDump.dumpLocation, incrDump.lastReplId);

      // Verify if the data are intact even after applying an applied event once again on existing objects
      verifyRun("SELECT a from " + replDbName + ".unptned ORDER BY a", empty);
      verifyRun("SELECT a from " + replDbName + ".ptned where (b=1) ORDER BY a", empty);
      verifyRun("SELECT a from " + replDbName + ".ptned where (b=2) ORDER BY a", ptn_data_2);
      verifyRun("SELECT a from " + replDbName + ".unptned_tmp where (b=1) ORDER BY a", empty);
      verifyRun("SELECT a from " + replDbName + ".unptned_tmp where (b=2) ORDER BY a", ptn_data_2);
    }
  }

  @Test
  public void testIncrementalRepeatEventOnMissingObject() throws IOException {
    String testName = "incrementalRepeatEventOnMissingObject";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("CREATE TABLE " + dbName + ".ptned(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    // List to maintain the incremental dumps for each operation
    List<Tuple> incrementalDumpList = new ArrayList<Tuple>();

    String[] empty = new String[] {};
    String[] unptn_data = new String[] { "ten" };
    String[] ptn_data_1 = new String[] { "fifteen" };
    String[] ptn_data_2 = new String[] { "seventeen" };

    // INSERT EVENT to unpartitioned table
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    Tuple replDump = dumpDbFromLastDump(dbName, bootstrapDump);
    incrementalDumpList.add(replDump);

    // INSERT EVENT to partitioned table with dynamic ADD_PARTITION
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=1) values('" + ptn_data_1[0] + "')");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // ADD_PARTITION EVENT to partitioned table
    run("ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=2)");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // INSERT EVENT to partitioned table on existing partition
    run("INSERT INTO TABLE " + dbName + ".ptned partition(b=2) values('" + ptn_data_2[0] + "')");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // TRUNCATE_PARTITION EVENT on partitioned table
    run("TRUNCATE TABLE " + dbName + ".ptned PARTITION(b=1)");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // TRUNCATE_TABLE EVENT on unpartitioned table
    run("TRUNCATE TABLE " + dbName + ".unptned");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // CREATE_TABLE EVENT on partitioned table
    run("CREATE TABLE " + dbName + ".ptned_tmp (a string) PARTITIONED BY (b int) STORED AS TEXTFILE");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // INSERT EVENT to partitioned table with dynamic ADD_PARTITION
    run("INSERT INTO TABLE " + dbName + ".ptned_tmp partition(b=10) values('" + ptn_data_1[0] + "')");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // INSERT EVENT to partitioned table with dynamic ADD_PARTITION
    run("INSERT INTO TABLE " + dbName + ".ptned_tmp partition(b=20) values('" + ptn_data_2[0] + "')");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // DROP_PARTITION EVENT to partitioned table
    run("ALTER TABLE " + dbName + ".ptned DROP PARTITION (b=1)");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // RENAME_PARTITION EVENT to partitioned table
    run("ALTER TABLE " + dbName + ".ptned PARTITION (b=2) RENAME TO PARTITION (b=20)");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // RENAME_TABLE EVENT to unpartitioned table
    run("ALTER TABLE " + dbName + ".unptned RENAME TO " + dbName + ".unptned_new");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // DROP_TABLE EVENT to partitioned table
    run("DROP TABLE " + dbName + ".ptned_tmp");
    replDump = dumpDbFromLastDump(dbName, replDump);
    incrementalDumpList.add(replDump);

    // Replicate all the events happened so far
    Tuple incrDump = incrementalLoadAndVerify(dbName, bootstrapDump.lastReplId, replDbName);

    verifyIfTableNotExist(replDbName, "unptned");
    verifyIfTableNotExist(replDbName, "ptned_tmp");
    verifyIfTableExist(replDbName, "unptned_new");
    verifyIfTableExist(replDbName, "ptned");
    verifyIfPartitionNotExist(replDbName, "ptned", new ArrayList<>(Arrays.asList("1")));
    verifyIfPartitionNotExist(replDbName, "ptned", new ArrayList<>(Arrays.asList("2")));
    verifyIfPartitionExist(replDbName, "ptned", new ArrayList<>(Arrays.asList("20")));

    // Load each incremental dump from the list. Each dump have only one operation.
    for (Tuple currDump : incrementalDumpList) {
      // Load the current incremental dump and ensure it does nothing and lastReplID remains same
      loadAndVerify(replDbName, currDump.dumpLocation, incrDump.lastReplId);

      // Verify if the data are intact even after applying an applied event once again on missing objects
      verifyIfTableNotExist(replDbName, "unptned");
      verifyIfTableNotExist(replDbName, "ptned_tmp");
      verifyIfTableExist(replDbName, "unptned_new");
      verifyIfTableExist(replDbName, "ptned");
      verifyIfPartitionNotExist(replDbName, "ptned", new ArrayList<>(Arrays.asList("1")));
      verifyIfPartitionNotExist(replDbName, "ptned", new ArrayList<>(Arrays.asList("2")));
      verifyIfPartitionExist(replDbName, "ptned", new ArrayList<>(Arrays.asList("20")));
    }
  }

  @Test
  public void testConcatenateTable() throws IOException {
    String testName = "concatenateTable";
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS ORC");

    String[] unptn_data = new String[] { "eleven", "twelve" };
    String[] empty = new String[] {};
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    run("ALTER TABLE " + dbName + ".unptned CONCATENATE");

    // Replicate all the events happened after bootstrap
    Tuple incrDump = incrementalLoadAndVerify(dbName, bootstrapDump.lastReplId, replDbName);
    verifyRun("SELECT a from " + replDbName + ".unptned ORDER BY a", unptn_data);
  }

  @Test
  public void testConcatenatePartitionedTable() throws IOException {
    String testName = "concatenatePartitionedTable";
    String dbName = createDB(testName);

    run("CREATE TABLE " + dbName + ".ptned(a string) PARTITIONED BY (b int) STORED AS ORC");

    String[] ptn_data_1 = new String[] { "fifteen", "fourteen", "thirteen" };
    String[] ptn_data_2 = new String[] { "fifteen", "seventeen", "sixteen" };

    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=1) values('" + ptn_data_1[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=2) values('" + ptn_data_2[0] + "')");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=1) values('" + ptn_data_1[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=1) values('" + ptn_data_1[2] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=2) values('" + ptn_data_2[1] + "')");
    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=2) values('" + ptn_data_2[2] + "')");

    run("ALTER TABLE " + dbName + ".ptned PARTITION(b=2) CONCATENATE");

    // Replicate all the events happened so far
    Tuple incrDump = incrementalLoadAndVerify(dbName, bootstrapDump.lastReplId, replDbName);
    verifyRun("SELECT a from " + replDbName + ".ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + replDbName + ".ptned where (b=2) ORDER BY a", ptn_data_2);
  }

  @Test
  public void testIncrementalLoadFailAndRetry() throws IOException {
    String testName = "incrementalLoadFailAndRetry";
    String dbName = createDB(testName);
    run("CREATE TABLE " + dbName + ".ptned(a string) PARTITIONED BY (b int) STORED AS TEXTFILE");

    // Bootstrap dump/load
    String replDbName = dbName + "_dupe";
    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);

    // Prefixed with incrementalLoadFailAndRetry to avoid finding entry in cmpath
    String[] ptn_data_1 = new String[] { "incrementalLoadFailAndRetry_fifteen" };
    String[] empty = new String[] {};

    run("INSERT INTO TABLE " + dbName + ".ptned PARTITION(b=1) values('" + ptn_data_1[0] + "')");
    run("CREATE TABLE " + dbName + ".ptned_tmp AS SELECT * FROM " + dbName + ".ptned");

    // Move the data files of this newly created partition to a temp location
    Partition ptn = null;
    try {
      ptn = metaStoreClient.getPartition(dbName, "ptned", new ArrayList<>(Arrays.asList("1")));
    } catch (Exception e) {
      assert(false);
    }

    Path ptnLoc = new Path(ptn.getSd().getLocation());
    Path tmpLoc = new Path(TEST_PATH + "/incrementalLoadFailAndRetry");
    FileSystem dataFs = ptnLoc.getFileSystem(hconf);
    assert(dataFs.rename(ptnLoc, tmpLoc));

    // Replicate all the events happened so far. It should fail as the data files missing in
    // original path and not available in CM as well.
    Tuple incrDump = replDumpDb(dbName, bootstrapDump.lastReplId, null, null);
    verifyFail("REPL LOAD " + replDbName + " FROM '" + incrDump.dumpLocation + "'");

    verifyRun("SELECT a from " + replDbName + ".ptned where (b=1) ORDER BY a", empty);
    verifyFail("SELECT a from " + replDbName + ".ptned_tmp where (b=1) ORDER BY a");

    // Move the files back to original data location
    assert(dataFs.rename(tmpLoc, ptnLoc));
    loadAndVerify(replDbName, incrDump.dumpLocation, incrDump.lastReplId);

    verifyRun("SELECT a from " + replDbName + ".ptned where (b=1) ORDER BY a", ptn_data_1);
    verifyRun("SELECT a from " + replDbName + ".ptned_tmp where (b=1) ORDER BY a", ptn_data_1);
  }

  @Test
  public void testStatus() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String lastReplDumpLocn = getResult(0, 0);
    String lastReplDumpId = getResult(0, 1, true);
    run("REPL LOAD " + dbName + "_dupe FROM '" + lastReplDumpLocn + "'");

    // Bootstrap done, now on to incremental. First, we test db-level REPL LOADs.
    // Both db-level and table-level repl.last.id must be updated.

    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, "ptned", lastReplDumpId,
        "CREATE TABLE " + dbName + ".ptned(a string) partitioned by (b int) STORED AS TEXTFILE");
    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, "ptned", lastReplDumpId,
        "ALTER TABLE " + dbName + ".ptned ADD PARTITION (b=1)");
    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, "ptned", lastReplDumpId,
        "ALTER TABLE " + dbName + ".ptned PARTITION (b=1) RENAME TO PARTITION (b=11)");
    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, "ptned", lastReplDumpId,
        "ALTER TABLE " + dbName + ".ptned SET TBLPROPERTIES ('blah'='foo')");
    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, "ptned_rn", lastReplDumpId,
        "ALTER TABLE " + dbName + ".ptned RENAME TO  " + dbName + ".ptned_rn");
    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, "ptned_rn", lastReplDumpId,
        "ALTER TABLE " + dbName + ".ptned_rn DROP PARTITION (b=11)");
    lastReplDumpId = verifyAndReturnDbReplStatus(dbName, null, lastReplDumpId,
        "DROP TABLE " + dbName + ".ptned_rn");

    // DB-level REPL LOADs testing done, now moving on to table level repl loads.
    // In each of these cases, the table-level repl.last.id must move forward, but the
    // db-level last.repl.id must not.

    String lastTblReplDumpId = lastReplDumpId;
    lastTblReplDumpId = verifyAndReturnTblReplStatus(
        dbName, "ptned2", lastReplDumpId, lastTblReplDumpId,
        "CREATE TABLE " + dbName + ".ptned2(a string) partitioned by (b int) STORED AS TEXTFILE");
    lastTblReplDumpId = verifyAndReturnTblReplStatus(
        dbName, "ptned2", lastReplDumpId, lastTblReplDumpId,
        "ALTER TABLE " + dbName + ".ptned2 ADD PARTITION (b=1)");
    lastTblReplDumpId = verifyAndReturnTblReplStatus(
        dbName, "ptned2", lastReplDumpId, lastTblReplDumpId,
        "ALTER TABLE " + dbName + ".ptned2 PARTITION (b=1) RENAME TO PARTITION (b=11)");
    lastTblReplDumpId = verifyAndReturnTblReplStatus(
        dbName, "ptned2", lastReplDumpId, lastTblReplDumpId,
        "ALTER TABLE " + dbName + ".ptned2 SET TBLPROPERTIES ('blah'='foo')");
    // Note : Not testing table rename because table rename replication is not supported for table-level repl.
    String finalTblReplDumpId = verifyAndReturnTblReplStatus(
        dbName, "ptned2", lastReplDumpId, lastTblReplDumpId,
        "ALTER TABLE " + dbName + ".ptned2 DROP PARTITION (b=11)");

    /*
    Comparisons using Strings for event Ids is wrong. This should be numbers since lexical string comparison
    and numeric comparision differ. This requires a broader change where we return the dump Id as long and not string
    fixing this here for now as it was observed in one of the builds where "1001".compareTo("998") results
    in failure of the assertion below.
     */
    assertTrue(new Long(Long.parseLong(finalTblReplDumpId)).compareTo(Long.parseLong(lastTblReplDumpId)) > 0);

    // TODO : currently not testing the following scenarios:
    //   a) Multi-db wh-level REPL LOAD - need to add that
    //   b) Insert into tables - quite a few cases need to be enumerated there, including dyn adds.

  }

  @Test
  public void testRemoveStats() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);

    String[] unptn_data = new String[]{ "1" , "2" };
    String[] ptn_data_1 = new String[]{ "5", "7", "8"};
    String[] ptn_data_2 = new String[]{ "3", "2", "9"};

    String unptn_locn = new Path(TEST_PATH, name + "_unptn").toUri().getPath();
    String ptn_locn_1 = new Path(TEST_PATH, name + "_ptn1").toUri().getPath();
    String ptn_locn_2 = new Path(TEST_PATH, name + "_ptn2").toUri().getPath();

    createTestDataFile(unptn_locn, unptn_data);
    createTestDataFile(ptn_locn_1, ptn_data_1);
    createTestDataFile(ptn_locn_2, ptn_data_2);

    run("CREATE TABLE " + dbName + ".unptned(a int) STORED AS TEXTFILE");
    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    run("CREATE TABLE " + dbName + ".ptned(a int) partitioned by (b int) STORED AS TEXTFILE");
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned PARTITION(b=1)");
    run("ANALYZE TABLE " + dbName + ".unptned COMPUTE STATISTICS FOR COLUMNS");
    run("ANALYZE TABLE " + dbName + ".unptned COMPUTE STATISTICS");
    run("ANALYZE TABLE " + dbName + ".ptned partition(b) COMPUTE STATISTICS FOR COLUMNS");
    run("ANALYZE TABLE " + dbName + ".ptned partition(b) COMPUTE STATISTICS");

    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);
    verifySetup("SELECT a from " + dbName + ".ptned WHERE b=1", ptn_data_1);
    verifySetup("SELECT count(*) from " + dbName + ".unptned", new String[]{"2"});
    verifySetup("SELECT count(*) from " + dbName + ".ptned", new String[]{"3"});
    verifySetup("SELECT max(a) from " + dbName + ".unptned", new String[]{"2"});
    verifySetup("SELECT max(a) from " + dbName + ".ptned where b=1", new String[]{"8"});

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}",replDumpLocn,replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    verifyRun("SELECT count(*) from " + dbName + "_dupe.unptned", new String[]{"2"});
    verifyRun("SELECT count(*) from " + dbName + "_dupe.ptned", new String[]{"3"});
    verifyRun("SELECT max(a) from " + dbName + "_dupe.unptned", new String[]{"2"});
    verifyRun("SELECT max(a) from " + dbName + "_dupe.ptned where b=1", new String[]{"8"});

    run("CREATE TABLE " + dbName + ".unptned2(a int) STORED AS TEXTFILE");
    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned2");
    run("CREATE TABLE " + dbName + ".ptned2(a int) partitioned by (b int) STORED AS TEXTFILE");
    run("LOAD DATA LOCAL INPATH '" + ptn_locn_1 + "' OVERWRITE INTO TABLE " + dbName + ".ptned2 PARTITION(b=1)");
    run("ANALYZE TABLE " + dbName + ".unptned2 COMPUTE STATISTICS FOR COLUMNS");
    run("ANALYZE TABLE " + dbName + ".unptned2 COMPUTE STATISTICS");
    run("ANALYZE TABLE " + dbName + ".ptned2 partition(b) COMPUTE STATISTICS FOR COLUMNS");
    run("ANALYZE TABLE " + dbName + ".ptned2 partition(b) COMPUTE STATISTICS");

    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0,0);
    String incrementalDumpId = getResult(0,1,true);
    LOG.info("Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '"+incrementalDumpLocn+"'");

    verifyRun("SELECT count(*) from " + dbName + "_dupe.unptned2", new String[]{"2"});
    verifyRun("SELECT count(*) from " + dbName + "_dupe.ptned2", new String[]{"3"});
    verifyRun("SELECT max(a) from " + dbName + "_dupe.unptned2", new String[]{"2"});
    verifyRun("SELECT max(a) from " + dbName + "_dupe.ptned2 where b=1", new String[]{"8"});
  }

  @Test
  public void testCMConflict() throws IOException {
    String testName = "cmConflict";
    String dbName = createDB(testName);

    // Create table and insert two file of the same content
    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("INSERT INTO TABLE " + dbName + ".unptned values('ten')");
    run("INSERT INTO TABLE " + dbName + ".unptned values('ten')");

    // Bootstrap test
    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);

    // Drop two files so they are moved to CM
    run("TRUNCATE TABLE " + dbName + ".unptned");

    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    verifyRun("SELECT count(*) from " + dbName + "_dupe.unptned", new String[]{"2"});
  }

  @Test
  public void testDeleteStagingDir() throws IOException {
	String testName = "deleteStagingDir";
	String dbName = createDB(testName);
	String tableName = "unptned";
    run("CREATE TABLE " + dbName + "." + tableName + "(a string) STORED AS TEXTFILE");

    String[] unptn_data = new String[] {"one", "two"};
    String unptn_locn = new Path(TEST_PATH , testName + "_unptn").toUri().getPath();
    createTestDataFile(unptn_locn, unptn_data);
    run("LOAD DATA LOCAL INPATH '" + unptn_locn + "' OVERWRITE INTO TABLE " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned", unptn_data);

    // Perform repl
    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    // Reset the driver
    driver.close();
    driver.init();
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");
    // Calling close() explicitly to clean up the staging dirs
    driver.close();
    // Check result
    Path warehouse = new Path(System.getProperty("test.warehouse.dir", "/tmp"));
    FileSystem fs = FileSystem.get(warehouse.toUri(), hconf);
    try {
      Path path = new Path(warehouse, dbName + "_dupe.db" + Path.SEPARATOR + tableName);
      // First check if the table dir exists (could have been deleted for some reason in pre-commit tests)
      if (!fs.exists(path))
      {
        return;
      }
      PathFilter filter = new PathFilter()
      {
        @Override
        public boolean accept(Path path)
        {
          return path.getName().startsWith(HiveConf.getVar(hconf, HiveConf.ConfVars.STAGINGDIR));
        }
      };
      FileStatus[] statuses = fs.listStatus(path, filter);
      assertEquals(0, statuses.length);
    } catch (IOException e) {
      LOG.error("Failed to list files in: " + warehouse, e);
      assert(false);
    }
  }

  @Test
  public void testDumpWithTableDirMissing() throws Exception {
    String dbName = createDB(testName.getMethodName());
    run("CREATE TABLE " + dbName + ".normal(a int)", true);
    run("INSERT INTO " + dbName + ".normal values (1)", true);

    Path path = new Path(System.getProperty("test.warehouse.dir", ""));
    path = new Path(path, dbName.toLowerCase() + ".db");
    path = new Path(path, "normal");
    FileSystem fs = path.getFileSystem(hconf);
    fs.delete(path);

    advanceDumpDir();
    CommandProcessorResponse ret = driver.run("REPL DUMP " + dbName);
    Assert.assertEquals(ret.getResponseCode(), ErrorMsg.FILE_NOT_FOUND.getErrorCode());

    run("DROP TABLE " + dbName + ".normal", true);
    run("drop database " + dbName, true);
  }

  @Test
  public void testDumpWithPartitionDirMissing() throws Exception {
    String dbName = createDB(testName.getMethodName());
    run("CREATE TABLE " + dbName + ".normal(a int) PARTITIONED BY (part int)", true);
    run("INSERT INTO " + dbName + ".normal partition (part= 124) values (1)", true);

    Path path = new Path(System.getProperty("test.warehouse.dir",""));
    path = new Path(path, dbName.toLowerCase()+".db");
    path = new Path(path, "normal");
    path = new Path(path, "part=124");
    FileSystem fs = path.getFileSystem(hconf);
    fs.delete(path);

    advanceDumpDir();
    CommandProcessorResponse ret = driver.run("REPL DUMP " + dbName);
    Assert.assertEquals(ret.getResponseCode(), ErrorMsg.FILE_NOT_FOUND.getErrorCode());

    run("DROP TABLE " + dbName + ".normal", true);
    run("drop database " + dbName, true);
  }

  @Test
  public void testDumpWithEmptyTables() throws IOException {
    String dbName = createDB(testName.getMethodName());
    run("CREATE TABLE " + dbName + ".normal (a int) PARTITIONED BY (part int)", true);
    run("CREATE TABLE " + dbName + ".normal_part (a int) PARTITIONED BY (part int)", true);
    run("ALTER TABLE " + dbName + ".normal_part ADD PARTITION (b=1)", true);

    advanceDumpDir();
    run("REPL DUMP " + dbName, true);
    String dumpLocation = getResult(0, 0);
    run("REPL LOAD " + dbName + "_dupe FROM '" + dumpLocation + "'", true);

    run("use " + dbName + "_dupe", true);
    verifyRun("show tables", new String[]{"normal", "normal_part"});

    run("DROP TABLE " + dbName + ".normal", true);
    run("DROP TABLE " + dbName + ".normal_part", true);
    run("drop database " + dbName, true);
    run("drop database " + dbName + "_dupe", true);
  }

  @Test
  public void testSkipTables() throws IOException {
    String testName = "skipTables";
    String dbName = createDB(testName);

    // Create table
    run("CREATE TABLE " + dbName + ".acid_table (key int, value int) PARTITIONED BY (load_date date) " +
        "CLUSTERED BY(key) INTO 2 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional'='true')");
    verifyIfTableExist(dbName, "acid_table");

    // Bootstrap test
    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0, 0);
    String replDumpId = getResult(0, 1, true);
    LOG.info("Bootstrap-Dump: Dumped to {} with id {}", replDumpLocn, replDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");
    verifyIfTableNotExist(dbName + "_dupe", "acid_table");

    // Test alter table
    run("ALTER TABLE " + dbName + ".acid_table RENAME TO " + dbName + ".acid_table_rename");
    verifyIfTableExist(dbName, "acid_table_rename");

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + replDumpId);
    String incrementalDumpLocn = getResult(0, 0);
    String incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-dump: Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '"+incrementalDumpLocn+"'");
    verifyIfTableNotExist(dbName + "_dupe", "acid_table_rename");

    // Create another table for incremental repl verification
    run("CREATE TABLE " + dbName + ".acid_table_incremental (key int, value int) PARTITIONED BY (load_date date) " +
        "CLUSTERED BY(key) INTO 2 BUCKETS STORED AS ORC TBLPROPERTIES ('transactional'='true')");
    verifyIfTableExist(dbName, "acid_table_incremental");

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + incrementalDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-dump: Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '"+incrementalDumpLocn+"'");
    verifyIfTableNotExist(dbName + "_dupe", "acid_table_incremental");

    // Perform REPL-DUMP/LOAD
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + incrementalDumpId);
    incrementalDumpLocn = getResult(0, 0);
    incrementalDumpId = getResult(0, 1, true);
    LOG.info("Incremental-dump: Dumped to {} with id {}", incrementalDumpLocn, incrementalDumpId);
    run("REPL LOAD " + dbName + "_dupe FROM '"+incrementalDumpLocn+"'");
    verifyIfTableNotExist(dbName + "_dupe", "acid_table_incremental");
  }

  @Test
  public void testReplStatusWithCluase() throws IOException {
    String dbName = createDB(testName.getMethodName());

    advanceDumpDir();
    run("REPL DUMP " + dbName);
    String replDumpLocn = getResult(0,0);
    String replDumpId = getResult(0,1,true);
    run("REPL LOAD " + dbName + "_dupe FROM '" + replDumpLocn + "'");

    run("REPL STATUS " + dbName + "_dupe");
    verifyResults(new String[] {replDumpId});
    verifyFail("REPL STATUS " + dbName + "_dupe with ('hive.metastore.uris' = 'thrift://localhost:9999')");
  }

  @Test
  public void testDumpNonReplDatabase() throws IOException {
    String dbName = createDBNonRepl(testName.getMethodName());
    verifyFail("REPL DUMP " + dbName);
    verifyFail("REPL DUMP " + dbName + " from 1 ");
    run("alter database " + dbName + " set dbproperties ('repl.source.for' = '1, 2, 3')");
    assertTrue(run("REPL DUMP " + dbName, true));
    assertTrue(run("REPL DUMP " + dbName + " from 1 ", true));
    dbName = createDBNonRepl(testName.getMethodName() + "_case");
    run("alter database " + dbName + " set dbproperties ('repl.SOURCE.for' = '1, 2, 3')");
    assertTrue(run("REPL DUMP " + dbName, true));
    assertTrue(run("REPL DUMP " + dbName + " from 1 ", true));
  }

  @Test
  public void testRecycleFileNonReplDatabase() throws IOException {
    String dbName = createDBNonRepl(testName.getMethodName());

    String cmDir = hconf.getVar(HiveConf.ConfVars.REPLCMDIR);
    Path path = new Path(cmDir);
    FileSystem fs = path.getFileSystem(hconf);
    FileStatus[] statuses = fs.listStatus(path);
    long fileCount = statuses.length;

    run("CREATE TABLE " + dbName + ".normal(a int)");
    run("INSERT INTO " + dbName + ".normal values (1)");

    long fileCountAfter = fs.listStatus(path).length;
    assertTrue(fileCount == fileCountAfter);

    run("INSERT INTO " + dbName + ".normal values (3)");
    run("TRUNCATE TABLE " + dbName + ".normal");

    fileCountAfter = fs.listStatus(path).length;
    assertTrue(fileCount == fileCountAfter);

    run("INSERT INTO " + dbName + ".normal values (4)");
    run("ALTER TABLE " + dbName + ".normal RENAME to " + dbName + ".normal1");
    verifyRun("SELECT count(*) from " + dbName + ".normal1", new String[]{"1"});

    fileCountAfter = fs.listStatus(path).length;
    assertTrue(fileCount == fileCountAfter);

    run("INSERT INTO " + dbName + ".normal1 values (5)");
    run("DROP TABLE " + dbName + ".normal1");

    fileCountAfter = fs.listStatus(path).length;
    assertTrue(fileCount == fileCountAfter);
  }

  @Test
  public void testRecycleFile() throws IOException {
    String dbName = createDB(testName.getMethodName());

    String cmDir = hconf.getVar(HiveConf.ConfVars.REPLCMDIR);
    Path path = new Path(cmDir);
    FileSystem fs = path.getFileSystem(hconf);
    FileStatus[] statuses = fs.listStatus(path);
    long fileCount = statuses.length;

    run("CREATE TABLE " + dbName + ".normal(a int)");
    run("INSERT INTO " + dbName + ".normal values (1)");

    long fileCountAfter = fs.listStatus(path).length;
    assertTrue(fileCount == fileCountAfter);

    run("INSERT INTO " + dbName + ".normal values (3)");
    run("TRUNCATE TABLE " + dbName + ".normal");

    fileCountAfter = fs.listStatus(path).length;
    assertTrue(fileCount != fileCountAfter);

    run("INSERT INTO " + dbName + ".normal values (4)");
    run("ALTER TABLE " + dbName + ".normal RENAME to " + dbName + ".normal1");
    verifyRun("SELECT count(*) from " + dbName + ".normal1", new String[]{"1"});

    assertTrue(fs.listStatus(path).length != fileCountAfter);
    fileCountAfter = fs.listStatus(path).length;

    run("INSERT INTO " + dbName + ".normal1 values (5)");
    run("DROP TABLE " + dbName + ".normal1");

    assertTrue(fs.listStatus(path).length != fileCountAfter);
    fileCountAfter = fs.listStatus(path).length;
  }

  @Test
  public void testMoveOptimizationBootstrap() throws IOException {
    String name = testName.getMethodName();
    String dbName = createDB(name);
    String tableNameNoPart = dbName + "_no_part";
    String tableNamePart = dbName + "_part";

    run(" use " + dbName);
    run("CREATE TABLE " + tableNameNoPart + " (fld int) STORED AS TEXTFILE");
    run("CREATE TABLE " + tableNamePart + " (fld int) partitioned by (part int) STORED AS TEXTFILE");

    run("insert into " + tableNameNoPart + " values (1) ");
    run("insert into " + tableNameNoPart + " values (2) ");
    verifyRun("SELECT fld from " + tableNameNoPart + " order by fld", new String[]{ "1" , "2" });

    run("insert into " + tableNamePart + " partition (part=10) values (1) ");
    run("insert into " + tableNamePart + " partition (part=10) values (2) ");
    run("insert into " + tableNamePart + " partition (part=11) values (3) ");
    verifyRun("SELECT fld from " + tableNamePart + " order by fld ", new String[]{ "1" , "2" , "3"});
    verifyRun("SELECT fld from " + tableNamePart + " where part = 10 order by fld" , new String[]{ "1" , "2"});
    verifyRun("SELECT fld from " + tableNamePart + " where part = 11" , new String[]{ "3" });

    String replDbName = dbName + "_replica";
    advanceDumpDir();
    Tuple dump = replDumpDb(dbName, null, null, null);
    run("REPL LOAD " + replDbName + " FROM '" + dump.dumpLocation +
            "' with ('hive.repl.enable.move.optimization'='true')");
    verifyRun("REPL STATUS " + replDbName, dump.lastReplId);

    run(" use " + replDbName);
    verifyRun("SELECT fld from " + tableNamePart + " order by fld ", new String[]{ "1" , "2" , "3"});
    verifyRun("SELECT fld from " + tableNamePart + " where part = 10  order by fld" , new String[]{ "1" , "2"});
    verifyRun("SELECT fld from " + tableNamePart + " where part = 11" , new String[]{ "3" });
    verifyRun("SELECT fld from " + tableNameNoPart + " order by fld ", new String[]{ "1" , "2" });
    verifyRun("SELECT count(*) from " + tableNamePart , new String[]{ "3"});
    verifyRun("SELECT count(*) from " + tableNamePart + " where part = 10" , new String[]{ "2"});
    verifyRun("SELECT count(*) from " + tableNamePart + " where part = 11" , new String[]{ "1" });
    verifyRun("SELECT count(*) from " + tableNameNoPart , new String[]{ "2" });
  }

  @Test
  public void testMoveOptimizationIncremental() throws IOException {
    String testName = "testMoveOptimizationIncremental";
    String dbName = createDB(testName);
    String replDbName = dbName + "_replica";

    Tuple bootstrapDump = bootstrapLoadAndVerify(dbName, replDbName);
    String replDumpId = bootstrapDump.lastReplId;

    String[] unptn_data = new String[] { "eleven", "twelve" };

    run("CREATE TABLE " + dbName + ".unptned(a string) STORED AS TEXTFILE");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[0] + "')");
    run("INSERT INTO TABLE " + dbName + ".unptned values('" + unptn_data[1] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned ORDER BY a", unptn_data);

    run("CREATE TABLE " + dbName + ".unptned_late AS SELECT * FROM " + dbName + ".unptned");
    verifySetup("SELECT * from " + dbName + ".unptned_late ORDER BY a", unptn_data);

    advanceDumpDir();
    Tuple incrementalDump = replDumpDb(dbName, replDumpId, null, null);
    run("REPL LOAD " + replDbName + " FROM '" + incrementalDump.dumpLocation +
            "' with ('hive.repl.enable.move.optimization'='true')");
    verifyRun("REPL STATUS " + replDbName, incrementalDump.lastReplId);
    replDumpId = incrementalDump.lastReplId;

    verifyRun("SELECT a from " + replDbName + ".unptned ORDER BY a", unptn_data);
    verifyRun("SELECT a from " + replDbName + ".unptned_late ORDER BY a", unptn_data);
    verifyRun("SELECT count(*) from " + replDbName + ".unptned ", "2");
    verifyRun("SELECT count(*) from " + replDbName + ".unptned_late", "2");

    String[] unptn_data_after_ins = new String[] { "eleven", "thirteen", "twelve" };
    String[] data_after_ovwrite = new String[] { "hundred" };
    run("INSERT INTO TABLE " + dbName + ".unptned_late values('" + unptn_data_after_ins[1] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned_late ORDER BY a", unptn_data_after_ins);
    run("INSERT OVERWRITE TABLE " + dbName + ".unptned values('" + data_after_ovwrite[0] + "')");
    verifySetup("SELECT a from " + dbName + ".unptned", data_after_ovwrite);

    advanceDumpDir();
    incrementalDump = replDumpDb(dbName, replDumpId, null, null);
    run("REPL LOAD " + replDbName + " FROM '" + incrementalDump.dumpLocation +
            "' with ('hive.repl.enable.move.optimization'='true')");
    verifyRun("REPL STATUS " + replDbName, incrementalDump.lastReplId);

    verifyRun("SELECT a from " + replDbName + ".unptned_late ORDER BY a", unptn_data_after_ins);
    verifyRun("SELECT a from " + replDbName + ".unptned", data_after_ovwrite);
    verifyRun("SELECT count(*) from " + replDbName + ".unptned", "1");
    verifyRun("SELECT count(*) from " + replDbName + ".unptned_late ", "3");
  }

  private static String createDBNonRepl(String name) {
    LOG.info("Testing " + name);
    String dbName = name + "_" + tid;
    run("CREATE DATABASE " + dbName);
    return dbName;
  }

  private static String createDB(String name) {
    LOG.info("Testing " + name);
    String dbName = name + "_" + tid;
    run("CREATE DATABASE " + dbName + " WITH DBPROPERTIES ( '" +
            SOURCE_OF_REPLICATION + "' = '1,2,3')");
    return dbName;
  }

  @Test
  public void testEventFilters(){
    // Test testing that the filters introduced by EventUtils are working correctly.

    // The current filters we use in ReplicationSemanticAnalyzer is as follows:
    //    IMetaStoreClient.NotificationFilter evFilter = EventUtils.andFilter(
    //        EventUtils.getDbTblNotificationFilter(dbNameOrPattern, tblNameOrPattern),
    //        EventUtils.getEventBoundaryFilter(eventFrom, eventTo),
    //        EventUtils.restrictByMessageFormat(MessageFactory.getInstance().getMessageFormat()));
    // So, we test each of those three filters, and then test andFilter itself.


    String dbname = "testfilter_db";
    String tblname = "testfilter_tbl";

    // Test EventUtils.getDbTblNotificationFilter - this is supposed to restrict
    // events to those that match the dbname and tblname provided to the filter.
    // If the tblname passed in to the filter is null, then it restricts itself
    // to dbname-matching alone.
    IMetaStoreClient.NotificationFilter dbTblFilter = new DatabaseAndTableFilter(dbname,tblname);
    IMetaStoreClient.NotificationFilter dbFilter = new DatabaseAndTableFilter(dbname,null);

    assertFalse(dbTblFilter.accept(null));
    assertTrue(dbTblFilter.accept(createDummyEvent(dbname, tblname, 0)));
    assertFalse(dbTblFilter.accept(createDummyEvent(dbname, tblname + "extra",0)));
    assertFalse(dbTblFilter.accept(createDummyEvent(dbname + "extra", tblname,0)));

    assertFalse(dbFilter.accept(null));
    assertTrue(dbFilter.accept(createDummyEvent(dbname, tblname,0)));
    assertTrue(dbFilter.accept(createDummyEvent(dbname, tblname + "extra", 0)));
    assertFalse(dbFilter.accept(createDummyEvent(dbname + "extra", tblname,0)));


    // Test EventUtils.getEventBoundaryFilter - this is supposed to only allow events
    // within a range specified.
    long evBegin = 50;
    long evEnd = 75;
    IMetaStoreClient.NotificationFilter evRangeFilter = new EventBoundaryFilter(evBegin,evEnd);

    assertTrue(evBegin < evEnd);
    assertFalse(evRangeFilter.accept(null));
    assertFalse(evRangeFilter.accept(createDummyEvent(dbname, tblname, evBegin - 1)));
    assertTrue(evRangeFilter.accept(createDummyEvent(dbname, tblname, evBegin)));
    assertTrue(evRangeFilter.accept(createDummyEvent(dbname, tblname, evBegin + 1)));
    assertTrue(evRangeFilter.accept(createDummyEvent(dbname, tblname, evEnd - 1)));
    assertTrue(evRangeFilter.accept(createDummyEvent(dbname, tblname, evEnd)));
    assertFalse(evRangeFilter.accept(createDummyEvent(dbname, tblname, evEnd + 1)));


    // Test EventUtils.restrictByMessageFormat - this restricts events generated to those
    // that match a provided message format

    IMetaStoreClient.NotificationFilter restrictByDefaultMessageFormat =
        new MessageFormatFilter(MessageFactory.getInstance().getMessageFormat());
    IMetaStoreClient.NotificationFilter restrictByArbitraryMessageFormat =
        new MessageFormatFilter(MessageFactory.getInstance().getMessageFormat() + "_bogus");
    NotificationEvent dummyEvent = createDummyEvent(dbname,tblname,0);

    assertEquals(MessageFactory.getInstance().getMessageFormat(),dummyEvent.getMessageFormat());

    assertFalse(restrictByDefaultMessageFormat.accept(null));
    assertTrue(restrictByDefaultMessageFormat.accept(dummyEvent));
    assertFalse(restrictByArbitraryMessageFormat.accept(dummyEvent));

    // Test andFilter operation.

    IMetaStoreClient.NotificationFilter yes = new IMetaStoreClient.NotificationFilter() {
      @Override
      public boolean accept(NotificationEvent notificationEvent) {
        return true;
      }
    };

    IMetaStoreClient.NotificationFilter no = new IMetaStoreClient.NotificationFilter() {
      @Override
      public boolean accept(NotificationEvent notificationEvent) {
        return false;
      }
    };

    assertTrue(new AndFilter(yes, yes).accept(dummyEvent));
    assertFalse(new AndFilter(yes, no).accept(dummyEvent));
    assertFalse(new AndFilter(no, yes).accept(dummyEvent));
    assertFalse(new AndFilter(no, no).accept(dummyEvent));

    assertTrue(new AndFilter(yes, yes, yes).accept(dummyEvent));
    assertFalse(new AndFilter(yes, yes, no).accept(dummyEvent));
    assertFalse(new AndFilter(yes, no, yes).accept(dummyEvent));
    assertFalse(new AndFilter(yes, no, no).accept(dummyEvent));
    assertFalse(new AndFilter(no, yes, yes).accept(dummyEvent));
    assertFalse(new AndFilter(no, yes, no).accept(dummyEvent));
    assertFalse(new AndFilter(no, no, yes).accept(dummyEvent));
    assertFalse(new AndFilter(no, no, no).accept(dummyEvent));
  }

  private NotificationEvent createDummyEvent(String dbname, String tblname, long evid) {
    MessageFactory msgFactory = MessageFactory.getInstance();
    Table t = new Table();
    t.setDbName(dbname);
    t.setTableName(tblname);
    NotificationEvent event = new NotificationEvent(
        evid,
        (int)System.currentTimeMillis(),
        MessageFactory.CREATE_TABLE_EVENT,
        msgFactory.buildCreateTableMessage(t, Arrays.asList("/tmp/").iterator()).toString()
    );
    event.setDbName(t.getDbName());
    event.setTableName(t.getTableName());
    event.setMessageFormat(msgFactory.getMessageFormat());
    return event;
  }

  private String verifyAndReturnDbReplStatus(String dbName, String tblName, String prevReplDumpId, String cmd) throws IOException {
    run(cmd);
    advanceDumpDir();
    run("REPL DUMP " + dbName + " FROM " + prevReplDumpId);
    String lastDumpLocn = getResult(0, 0);
    String lastReplDumpId = getResult(0, 1, true);
    run("REPL LOAD " + dbName + "_dupe FROM '" + lastDumpLocn + "' with ('hive.query.id' = 'hiveCustomTag')");
    verifyRun("REPL STATUS " + dbName + "_dupe", lastReplDumpId);
    if (tblName != null){
      verifyRun("REPL STATUS " + dbName + "_dupe." + tblName, lastReplDumpId);
    }
    assertTrue(Long.parseLong(lastReplDumpId) > Long.parseLong(prevReplDumpId));
    return lastReplDumpId;
  }

  // Tests that doing a table-level REPL LOAD updates table repl.last.id, but not db-level repl.last.id
  private String verifyAndReturnTblReplStatus(
      String dbName, String tblName, String lastDbReplDumpId, String prevReplDumpId, String cmd) throws IOException {
    run(cmd);
    advanceDumpDir();
    run("REPL DUMP " + dbName + "."+ tblName + " FROM " + prevReplDumpId);
    String lastDumpLocn = getResult(0, 0);
    String lastReplDumpId = getResult(0, 1, true);
    run("REPL LOAD " + dbName + "_dupe." + tblName + " FROM '" + lastDumpLocn + "'");
    verifyRun("REPL STATUS " + dbName + "_dupe", lastDbReplDumpId);
    verifyRun("REPL STATUS " + dbName + "_dupe." + tblName, lastReplDumpId);
    assertTrue(Long.parseLong(lastReplDumpId) > Long.parseLong(prevReplDumpId));
    return lastReplDumpId;
  }


  private String getResult(int rowNum, int colNum) throws IOException {
    return getResult(rowNum,colNum,false);
  }
  private String getResult(int rowNum, int colNum, boolean reuse) throws IOException {
    if (!reuse) {
      lastResults = new ArrayList<String>();
      try {
        driver.getResults(lastResults);
      } catch (CommandNeedRetryException e) {
        e.printStackTrace();
        throw new RuntimeException(e);
      }
    }
    // Split around the 'tab' character
    return (lastResults.get(rowNum).split("\\t"))[colNum];
  }

  /**
   * All the results that are read from the hive output will not preserve
   * case sensitivity and will all be in lower case, hence we will check against
   * only lower case data values.
   * Unless for Null Values it actually returns in UpperCase and hence explicitly lowering case
   * before assert.
   */
  private void verifyResults(String[] data) throws IOException {
    List<String> results = getOutput();
    LOG.info("Expecting {}", data);
    LOG.info("Got {}", results);
    assertEquals(data.length, results.size());
    for (int i = 0; i < data.length; i++) {
      assertEquals(data[i].toLowerCase(), results.get(i).toLowerCase());
    }
  }

  private List<String> getOutput() throws IOException {
    List<String> results = new ArrayList<>();
    try {
      driver.getResults(results);
    } catch (CommandNeedRetryException e) {
      LOG.warn(e.getMessage(),e);
      throw new RuntimeException(e);
    }
    return results;
  }

  private void printOutput() throws IOException {
    for (String s : getOutput()){
      LOG.info(s);
    }
  }

  private void verifyIfTableNotExist(String dbName, String tableName){
    Exception e = null;
    try {
      Table tbl = metaStoreClient.getTable(dbName, tableName);
      assertNull(tbl);
    } catch (TException te) {
      e = te;
    }
    assertNotNull(e);
    assertEquals(NoSuchObjectException.class, e.getClass());
  }

  private void verifyIfTableExist(String dbName, String tableName){
    Exception e = null;
    try {
      Table tbl = metaStoreClient.getTable(dbName, tableName);
      assertNotNull(tbl);
    } catch (TException te) {
      assert(false);
    }
  }

  private void verifyIfPartitionNotExist(String dbName, String tableName, List<String> partValues){
    Exception e = null;
    try {
      Partition ptn = metaStoreClient.getPartition(dbName, tableName, partValues);
      assertNull(ptn);
    } catch (TException te) {
      e = te;
    }
    assertNotNull(e);
    assertEquals(NoSuchObjectException.class, e.getClass());
  }

  private void verifyIfPartitionExist(String dbName, String tableName, List<String> partValues){
    Exception e = null;
    try {
      Partition ptn = metaStoreClient.getPartition(dbName, tableName, partValues);
      assertNotNull(ptn);
    } catch (TException te) {
      assert(false);
    }
  }

  private void verifyIfDirNotExist(FileSystem fs, Path path, PathFilter filter){
    try {
      FileStatus[] statuses = fs.listStatus(path, filter);
      assertEquals(0, statuses.length);
    } catch (IOException e) {
      assert(false);
    }
  }

  private void verifySetup(String cmd, String[] data) throws  IOException {
    if (VERIFY_SETUP_STEPS){
      run(cmd);
      verifyResults(data);
    }
  }

  private void verifyRun(String cmd, String data) throws IOException {
    verifyRun(cmd, new String[] { data });
  }

  private void verifyRun(String cmd, String[] data) throws IOException {
    run(cmd);
    verifyResults(data);
  }

  private void verifyFail(String cmd) throws RuntimeException {
    boolean success = false;
    try {
      success = run(cmd,false);
    } catch (AssertionError ae){
      LOG.warn("AssertionError:",ae);
      throw new RuntimeException(ae);
    }

    assertFalse(success);
  }

  private void verifyRunWithPatternMatch(String cmd, String key, String pattern) throws IOException {
    run(cmd);
    List<String> results = getOutput();
    assertTrue(results.size() > 0);
    boolean success = false;
    for (int i = 0; i < results.size(); i++) {
      if (results.get(i).contains(key) && results.get(i).contains(pattern)) {
         success = true;
         break;
      }
    }

    assertTrue(success);
  }

  private static void run(String cmd) throws RuntimeException {
    try {
    run(cmd,false); // default arg-less run simply runs, and does not care about failure
    } catch (AssertionError ae){
      // Hive code has AssertionErrors in some cases - we want to record what happens
      LOG.warn("AssertionError:",ae);
      throw new RuntimeException(ae);
    }
  }

  private static boolean run(String cmd, boolean errorOnFail) throws RuntimeException {
    boolean success = false;
    try {
      CommandProcessorResponse ret = driver.run(cmd);
      success = ((ret.getException() == null) && (ret.getErrorMessage() == null));
      if (!success){
        LOG.warn("Error : {} running [{}].", ret.getErrorMessage(), cmd);
      }
    } catch (CommandNeedRetryException e) {
      if (errorOnFail){
        throw new RuntimeException(e);
      } else {
        LOG.warn(e.getMessage(),e);
        // do nothing else
      }
    }
    return success;
  }

  private static void createTestDataFile(String filename, String[] lines) throws IOException {
    FileWriter writer = null;
    try {
      File file = new File(filename);
      file.deleteOnExit();
      writer = new FileWriter(file);
      for (String line : lines) {
        writer.write(line + "\n");
      }
    } finally {
      if (writer != null) {
        writer.close();
      }
    }
  }
}
