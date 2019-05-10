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
package org.apache.hadoop.hive.ql.parse;

import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.hadoop.fs.FileStatus;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.fs.permission.FsPermission;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.InjectableBehaviourObjectStore;
import org.apache.hadoop.hive.metastore.InjectableBehaviourObjectStore.BehaviourInjection;
import org.apache.hadoop.hive.metastore.InjectableBehaviourObjectStore.CallerArguments;
import org.apache.hadoop.hive.metastore.api.Database;
import org.apache.hadoop.hive.metastore.api.NotificationEvent;
import org.apache.hadoop.hive.metastore.api.Partition;
import org.apache.hadoop.hive.metastore.api.Table;
import org.apache.hadoop.hive.metastore.messaging.json.gzip.GzipJSONMessageEncoder;
import org.apache.hadoop.hive.ql.ErrorMsg;
import org.apache.hadoop.hive.ql.exec.repl.incremental.IncrementalLoadTasksBuilder;
import org.apache.hadoop.hive.ql.exec.repl.util.ReplUtils;
import org.apache.hadoop.hive.ql.parse.repl.PathBuilder;
import org.apache.hadoop.hive.ql.processors.CommandProcessorResponse;
import org.apache.hadoop.hive.ql.session.DependencyResolver;
import org.apache.hadoop.security.UserGroupInformation;
import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.apache.hadoop.hive.metastore.ReplChangeManager.SOURCE_OF_REPLICATION;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class TestReplicationScenariosAcrossInstances extends BaseReplicationAcrossInstances {
  @BeforeClass
  public static void classLevelSetup() throws Exception {
    HashMap<String, String> overrides = new HashMap<>();
    overrides.put(HiveConf.ConfVars.METASTORE_EVENT_MESSAGE_FACTORY.varname,
        GzipJSONMessageEncoder.class.getCanonicalName());
    overrides.put(HiveConf.ConfVars.HIVE_DISTCP_DOAS_USER.varname,
        UserGroupInformation.getCurrentUser().getUserName());

    internalBeforeClassSetup(overrides, TestReplicationScenariosAcrossInstances.class);
  }

  @Test
  public void testCreateFunctionIncrementalReplication() throws Throwable {
    WarehouseInstance.Tuple bootStrapDump = primary.dump(primaryDbName, null);
    replica.load(replicatedDbName, bootStrapDump.dumpLocation)
        .run("REPL STATUS " + replicatedDbName)
        .verifyResult(bootStrapDump.lastReplicationId);

    primary.run("CREATE FUNCTION " + primaryDbName
        + ".testFunctionOne as 'hivemall.tools.string.StopwordUDF' "
        + "using jar  'ivy://io.github.myui:hivemall:0.4.0-2'");

    WarehouseInstance.Tuple incrementalDump =
        primary.dump(primaryDbName, bootStrapDump.lastReplicationId);
    replica.load(replicatedDbName, incrementalDump.dumpLocation)
        .run("REPL STATUS " + replicatedDbName)
        .verifyResult(incrementalDump.lastReplicationId)
        .run("SHOW FUNCTIONS LIKE '" + replicatedDbName + "*'")
        .verifyResult(replicatedDbName + ".testFunctionOne");

    // Test the idempotent behavior of CREATE FUNCTION
    replica.load(replicatedDbName, incrementalDump.dumpLocation)
        .run("REPL STATUS " + replicatedDbName)
        .verifyResult(incrementalDump.lastReplicationId)
        .run("SHOW FUNCTIONS LIKE '" + replicatedDbName + "*'")
        .verifyResult(replicatedDbName + ".testFunctionOne");
  }

  @Test
  public void testBootstrapReplLoadRetryAfterFailureForFunctions() throws Throwable {
    String funcName1 = "f1";
    String funcName2 = "f2";
    WarehouseInstance.Tuple tuple = primary.run("use " + primaryDbName)
            .run("CREATE FUNCTION " + primaryDbName + "." + funcName1 +
                    " as 'hivemall.tools.string.StopwordUDF' " +
                    "using jar  'ivy://io.github.myui:hivemall:0.4.0-2'")
            .run("CREATE FUNCTION " + primaryDbName + "." + funcName2 +
                    " as 'hivemall.tools.string.SplitWordsUDF' "+
                    "using jar  'ivy://io.github.myui:hivemall:0.4.0-1'")
            .dump(primaryDbName, null);

    // Allow create function only on f1. Create should fail for the second function.
    BehaviourInjection<CallerArguments, Boolean> callerVerifier
            = new BehaviourInjection<CallerArguments, Boolean>() {
              @Override
              public Boolean apply(CallerArguments args) {
                injectionPathCalled = true;
                if (!args.dbName.equalsIgnoreCase(replicatedDbName)) {
                  LOG.warn("Verifier - DB: " + String.valueOf(args.dbName));
                  return false;
                }
                if (args.funcName != null) {
                  LOG.debug("Verifier - Function: " + String.valueOf(args.funcName));
                  return args.funcName.equals(funcName1);
                }
                return true;
              }
            };
    InjectableBehaviourObjectStore.setCallerVerifier(callerVerifier);

    // Trigger bootstrap dump which just creates function f1 but not f2
    List<String> withConfigs = Arrays.asList("'hive.repl.approx.max.load.tasks'='1'",
            "'hive.in.repl.test.files.sorted'='true'");
    try {
      replica.loadFailure(replicatedDbName, tuple.dumpLocation, withConfigs);
      callerVerifier.assertInjectionsPerformed(true, false);
    } finally {
      InjectableBehaviourObjectStore.resetCallerVerifier(); // reset the behaviour
    }

    // Verify that only f1 got loaded
    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult("null")
            .run("show functions like '" + replicatedDbName + "*'")
            .verifyResult(replicatedDbName + "." + funcName1);

    // Verify no calls to load f1 only f2.
    callerVerifier = new BehaviourInjection<CallerArguments, Boolean>() {
      @Override
      public Boolean apply(CallerArguments args) {
        injectionPathCalled = true;
        if (!args.dbName.equalsIgnoreCase(replicatedDbName)) {
          LOG.warn("Verifier - DB: " + String.valueOf(args.dbName));
          return false;
        }
        if (args.funcName != null) {
          LOG.debug("Verifier - Function: " + String.valueOf(args.funcName));
          return args.funcName.equals(funcName2);
        }
        return true;
      }
    };
    InjectableBehaviourObjectStore.setCallerVerifier(callerVerifier);

    try {
      // Retry with same dump with which it was already loaded should resume the bootstrap load.
      // This time, it completes by adding just the function f2
      replica.load(replicatedDbName, tuple.dumpLocation);
      callerVerifier.assertInjectionsPerformed(true, false);
    } finally {
      InjectableBehaviourObjectStore.resetCallerVerifier(); // reset the behaviour
    }

    // Verify that both the functions are available.
    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuple.lastReplicationId)
            .run("show functions like '" + replicatedDbName +"*'")
            .verifyResults(new String[] {replicatedDbName + "." + funcName1,
                                         replicatedDbName +"." +funcName2});
  }

  @Test
  public void testDropFunctionIncrementalReplication() throws Throwable {
    primary.run("CREATE FUNCTION " + primaryDbName
        + ".testFunctionAnother as 'hivemall.tools.string.StopwordUDF' "
        + "using jar  'ivy://io.github.myui:hivemall:0.4.0-2'");
    WarehouseInstance.Tuple bootStrapDump = primary.dump(primaryDbName, null);
    replica.load(replicatedDbName, bootStrapDump.dumpLocation)
        .run("REPL STATUS " + replicatedDbName)
        .verifyResult(bootStrapDump.lastReplicationId);

    primary.run("Drop FUNCTION " + primaryDbName + ".testFunctionAnother ");

    WarehouseInstance.Tuple incrementalDump =
        primary.dump(primaryDbName, bootStrapDump.lastReplicationId);
    replica.load(replicatedDbName, incrementalDump.dumpLocation)
        .run("REPL STATUS " + replicatedDbName)
        .verifyResult(incrementalDump.lastReplicationId)
        .run("SHOW FUNCTIONS LIKE '*testfunctionanother*'")
        .verifyResult(null);

    // Test the idempotent behavior of DROP FUNCTION
    replica.load(replicatedDbName, incrementalDump.dumpLocation)
        .run("REPL STATUS " + replicatedDbName)
        .verifyResult(incrementalDump.lastReplicationId)
        .run("SHOW FUNCTIONS LIKE '*testfunctionanother*'")
        .verifyResult(null);
  }

  @Test
  public void testBootstrapFunctionReplication() throws Throwable {
    primary.run("CREATE FUNCTION " + primaryDbName
        + ".testFunction as 'hivemall.tools.string.StopwordUDF' "
        + "using jar  'ivy://io.github.myui:hivemall:0.4.0-2'");
    WarehouseInstance.Tuple bootStrapDump = primary.dump(primaryDbName, null);

    replica.load(replicatedDbName, bootStrapDump.dumpLocation)
        .run("SHOW FUNCTIONS LIKE '" + replicatedDbName + "*'")
        .verifyResult(replicatedDbName + ".testFunction");
  }

  @Test
  public void testCreateFunctionWithFunctionBinaryJarsOnHDFS() throws Throwable {
    Dependencies dependencies = dependencies("ivy://io.github.myui:hivemall:0.4.0-2", primary);
    String jarSubString = dependencies.toJarSubSql();

    primary.run("CREATE FUNCTION " + primaryDbName
        + ".anotherFunction as 'hivemall.tools.string.StopwordUDF' "
        + "using " + jarSubString);

    WarehouseInstance.Tuple tuple = primary.dump(primaryDbName, null);

    replica.load(replicatedDbName, tuple.dumpLocation)
        .run("SHOW FUNCTIONS LIKE '" + replicatedDbName + "*'")
        .verifyResult(replicatedDbName + ".anotherFunction");

    FileStatus[] fileStatuses = replica.miniDFSCluster.getFileSystem().globStatus(
        new Path(
            replica.functionsRoot + "/" + replicatedDbName.toLowerCase() + "/anotherfunction/*/*")
        , path -> path.toString().endsWith("jar"));
    List<String> expectedDependenciesNames = dependencies.jarNames();
    assertThat(fileStatuses.length, is(equalTo(expectedDependenciesNames.size())));
    List<String> jars =
        Arrays.stream(fileStatuses).map(fileStatus -> {
          String[] splits = fileStatus.getPath().toString().split("/");
          return splits[splits.length - 1];
        }).collect(Collectors.toList());
    assertThat(jars, containsInAnyOrder(expectedDependenciesNames.toArray()));
  }

  static class Dependencies {
    private final List<Path> fullQualifiedJarPaths;

    Dependencies(List<Path> fullQualifiedJarPaths) {
      this.fullQualifiedJarPaths = fullQualifiedJarPaths;
    }

    private String toJarSubSql() {
      List<String> result = fullQualifiedJarPaths.stream().map(path -> "jar '" + path + "'")
          .collect(Collectors.toList());
      return StringUtils.join(result, ",");
    }

    private List<String> jarNames() {
      return fullQualifiedJarPaths.stream().map(
          path -> {
            String[] splits = path.toString().split("/");
            return splits[splits.length - 1];
          }).collect(Collectors.toList());
    }
  }

  private Dependencies dependencies(String ivyPath, final WarehouseInstance onWarehouse)
      throws IOException, URISyntaxException, SemanticException {
    List<URI> localUris = new DependencyResolver().downloadDependencies(new URI(ivyPath));
    List<Path> remotePaths = onWarehouse.copyToHDFS(localUris);
    List<Path> collect = remotePaths.stream().map(path -> {
      try {
        return PathBuilder
            .fullyQualifiedHDFSUri(path, onWarehouse.miniDFSCluster.getFileSystem());

      } catch (Exception e) {
        throw new RuntimeException(e);
      }
    }).collect(Collectors.toList());
    return new Dependencies(collect);
  }

  /*
  From the hive logs(hive.log) we can also check for the info statement
  fgrep "Total Tasks" [location of hive.log]
  each line indicates one run of loadTask.
   */
  @Test
  public void testMultipleStagesOfReplicationLoadTask() throws Throwable {
    WarehouseInstance.Tuple tuple = primary
        .run("use " + primaryDbName)
        .run("create table t1 (id int)")
        .run("insert into t1 values (1), (2)")
        .run("create table t2 (place string) partitioned by (country string)")
        .run("insert into table t2 partition(country='india') values ('bangalore')")
        .run("insert into table t2 partition(country='us') values ('austin')")
        .run("insert into table t2 partition(country='france') values ('paris')")
        .run("create table t3 (rank int)")
        .dump(primaryDbName, null);

    // each table creation itself takes more than one task, give we are giving a max of 1, we should hit multiple runs.
    List<String> withClause = Collections.singletonList(
        "'" + HiveConf.ConfVars.REPL_APPROX_MAX_LOAD_TASKS.varname + "'='1'");

    replica.load(replicatedDbName, tuple.dumpLocation, withClause)
        .run("use " + replicatedDbName)
        .run("show tables")
        .verifyResults(new String[] { "t1", "t2", "t3" })
        .run("repl status " + replicatedDbName)
        .verifyResult(tuple.lastReplicationId)
        .run("select country from t2 order by country")
        .verifyResults(new String[] { "france", "india", "us" });
  }

  @Test
  public void testParallelExecutionOfReplicationBootStrapLoad() throws Throwable {
    WarehouseInstance.Tuple tuple = primary
        .run("use " + primaryDbName)
        .run("create table t1 (id int)")
        .run("create table t2 (place string) partitioned by (country string)")
        .run("insert into table t2 partition(country='india') values ('bangalore')")
        .run("insert into table t2 partition(country='australia') values ('sydney')")
        .run("insert into table t2 partition(country='russia') values ('moscow')")
        .run("insert into table t2 partition(country='uk') values ('london')")
        .run("insert into table t2 partition(country='us') values ('sfo')")
        .run("insert into table t2 partition(country='france') values ('paris')")
        .run("insert into table t2 partition(country='japan') values ('tokyo')")
        .run("insert into table t2 partition(country='china') values ('hkg')")
        .run("create table t3 (rank int)")
        .dump(primaryDbName, null);

    replica.hiveConf.setBoolVar(HiveConf.ConfVars.EXECPARALLEL, true);
    replica.load(replicatedDbName, tuple.dumpLocation)
        .run("use " + replicatedDbName)
        .run("repl status " + replicatedDbName)
        .verifyResult(tuple.lastReplicationId)
        .run("show tables")
        .verifyResults(new String[] { "t1", "t2", "t3" })
        .run("select country from t2")
        .verifyResults(Arrays.asList("india", "australia", "russia", "uk", "us", "france", "japan",
            "china"));
    replica.hiveConf.setBoolVar(HiveConf.ConfVars.EXECPARALLEL, false);
  }

  @Test
  public void testMetadataBootstrapDump() throws Throwable {
    WarehouseInstance.Tuple tuple = primary.run("use " + primaryDbName)
        .run("create table  acid_table (key int, value int) partitioned by (load_date date) " +
            "clustered by(key) into 2 buckets stored as orc tblproperties ('transactional'='true')")
        .run("create table table1 (i int, j int)")
        .run("insert into table1 values (1,2)")
        .dump(primaryDbName, null, Arrays.asList("'hive.repl.dump.metadata.only'='true'",
            "'hive.repl.dump.include.acid.tables'='true'"));

    replica.load(replicatedDbName, tuple.dumpLocation)
        .run("use " + replicatedDbName)
        .run("show tables")
        .verifyResults(new String[] { "acid_table", "table1" })
        .run("select * from table1")
        .verifyResults(Collections.emptyList());
  }

  @Test
  public void testIncrementalMetadataReplication() throws Throwable {
    ////////////  Bootstrap   ////////////
    WarehouseInstance.Tuple bootstrapTuple = primary
        .run("use " + primaryDbName)
        .run("create table table1 (i int, j int)")
        .run("create table table2 (a int, city string) partitioned by (country string)")
        .run("create table table3 (i int, j int)")
        .run("insert into table1 values (1,2)")
        .dump(primaryDbName, null, Arrays.asList("'hive.repl.dump.metadata.only'='true'",
            "'hive.repl.dump.include.acid.tables'='true'"));

    replica.load(replicatedDbName, bootstrapTuple.dumpLocation)
        .run("use " + replicatedDbName)
        .run("show tables")
        .verifyResults(new String[] { "table1", "table2", "table3" })
        .run("select * from table1")
        .verifyResults(Collections.emptyList());

    ////////////  First Incremental ////////////
    WarehouseInstance.Tuple incrementalOneTuple =
        primary
            .run("use " + primaryDbName)
            .run("alter table table1 rename to renamed_table1")
            .run("insert into table2 partition(country='india') values (1,'mumbai') ")
            .run("create table table4 (i int, j int)")
            .dump(
                "repl dump " + primaryDbName + " from " + bootstrapTuple.lastReplicationId + " to "
                    + Long.parseLong(bootstrapTuple.lastReplicationId) + 100L + " limit 100 "
                    + "with ('hive.repl.dump.metadata.only'='true')"
            );

    replica.load(replicatedDbName, incrementalOneTuple.dumpLocation)
        .run("use " + replicatedDbName)
        .run("show tables")
        .verifyResults(new String[] { "renamed_table1", "table2", "table3", "table4" })
        .run("select * from renamed_table1")
        .verifyResults(Collections.emptyList())
        .run("select * from table2")
        .verifyResults(Collections.emptyList());

    ////////////  Second Incremental ////////////
    WarehouseInstance.Tuple secondIncremental = primary
        .run("alter table table2 add columns (zipcode int)")
        .run("alter table table3 change i a string")
        .run("alter table table3 set tblproperties('custom.property'='custom.value')")
        .run("drop table renamed_table1")
        .dump("repl dump " + primaryDbName + " from " + incrementalOneTuple.lastReplicationId
            + " with ('hive.repl.dump.metadata.only'='true')"
        );

    replica.load(replicatedDbName, secondIncremental.dumpLocation)
        .run("use " + replicatedDbName)
        .run("show tables")
        .verifyResults(new String[] { "table2", "table3", "table4" })
        .run("desc table3")
        .verifyResults(new String[] {
            "a                   \tstring              \t                    ",
            "j                   \tint                 \t                    "
        })
        .run("desc table2")
        .verifyResults(new String[] {
            "a                   \tint                 \t                    ",
            "city                \tstring              \t                    ",
            "country             \tstring              \t                    ",
            "zipcode             \tint                 \t                    ",
            "\t \t ",
            "# Partition Information\t \t ",
            "# col_name            \tdata_type           \tcomment             ",
            "\t \t ",
            "country             \tstring              \t                    ",
        })
        .run("show tblproperties table3('custom.property')")
        .verifyResults(new String[] {
            "custom.value\t "
        });
  }

  @Test
  public void testBootStrapDumpOfWarehouse() throws Throwable {
    String randomOne = RandomStringUtils.random(10, true, false);
    String randomTwo = RandomStringUtils.random(10, true, false);
    String dbOne = primaryDbName + randomOne;
    String dbTwo = primaryDbName + randomTwo;
    primary.run("alter database default set dbproperties ('" + SOURCE_OF_REPLICATION + "' = '1, 2, 3')");
    WarehouseInstance.Tuple tuple = primary
        .run("use " + primaryDbName)
        .run("create table t1 (i int, j int)")
        .run("create database " + dbOne + " WITH DBPROPERTIES ( '" + SOURCE_OF_REPLICATION + "' = '1,2,3')")
        .run("use " + dbOne)
        .run("create table t1 (i int, j int) partitioned by (load_date date) "
            + "clustered by(i) into 2 buckets stored as orc tblproperties ('transactional'='true') ")
        .run("create database " + dbTwo + " WITH DBPROPERTIES ( '" + SOURCE_OF_REPLICATION + "' = '1,2,3')")
        .run("use " + dbTwo)
        .run("create table t1 (i int, j int)")
        .dump("`*`", null, Arrays.asList("'hive.repl.dump.metadata.only'='true'",
            "'hive.repl.dump.include.acid.tables'='true'"));

    /*
      Due to the limitation that we can only have one instance of Persistence Manager Factory in a JVM
      we are not able to create multiple embedded derby instances for two different MetaStore instances.
    */

    primary.run("drop database " + primaryDbName + " cascade");
    primary.run("drop database " + dbOne + " cascade");
    primary.run("drop database " + dbTwo + " cascade");

    /*
       End of additional steps
    */

    // Reset ckpt and last repl ID keys to empty set for allowing bootstrap load
    replica.run("show databases")
        .verifyFailure(new String[] { primaryDbName, dbOne, dbTwo })
        .run("alter database default set dbproperties ('hive.repl.ckpt.key'='', 'repl.last.id'='')")
        .load("", tuple.dumpLocation)
        .run("show databases")
        .verifyResults(new String[] { "default", primaryDbName, dbOne, dbTwo })
        .run("use " + primaryDbName)
        .run("show tables")
        .verifyResults(new String[] { "t1" })
        .run("use " + dbOne)
        .run("show tables")
        .verifyResults(new String[] { "t1" })
        .run("use " + dbTwo)
        .run("show tables")
        .verifyResults(new String[] { "t1" });

    /*
       Start of cleanup
    */

    replica.run("drop database " + primaryDbName + " cascade");
    replica.run("drop database " + dbOne + " cascade");
    replica.run("drop database " + dbTwo + " cascade");

    /*
       End of cleanup
    */
  }

  @Test
  public void testIncrementalDumpOfWarehouse() throws Throwable {
    String randomOne = RandomStringUtils.random(10, true, false);
    String randomTwo = RandomStringUtils.random(10, true, false);
    String dbOne = primaryDbName + randomOne;
    primary.run("alter database default set dbproperties ('" + SOURCE_OF_REPLICATION + "' = '1, 2, 3')");
    WarehouseInstance.Tuple bootstrapTuple = primary
        .run("use " + primaryDbName)
        .run("create table t1 (i int, j int)")
        .run("create database " + dbOne + " WITH DBPROPERTIES ( '" +
                SOURCE_OF_REPLICATION + "' = '1,2,3')")
        .run("use " + dbOne)
        .run("create table t1 (i int, j int) partitioned by (load_date date) "
            + "clustered by(i) into 2 buckets stored as orc tblproperties ('transactional'='true') ")
        .dump("`*`", null, Arrays.asList("'hive.repl.dump.metadata.only'='true'",
            "'hive.repl.dump.include.acid.tables'='true'"));

    String dbTwo = primaryDbName + randomTwo;
    WarehouseInstance.Tuple incrementalTuple = primary
        .run("create database " + dbTwo + " WITH DBPROPERTIES ( '" +
                SOURCE_OF_REPLICATION + "' = '1,2,3')")
        .run("use " + dbTwo)
        .run("create table t1 (i int, j int)")
        .run("use " + dbOne)
        .run("create table t2 (a int, b int)")
        .dump("`*`", bootstrapTuple.lastReplicationId,
            Arrays.asList("'hive.repl.dump.metadata.only'='true'",
                "'hive.repl.dump.include.acid.tables'='true'"));

    /*
      Due to the limitation that we can only have one instance of Persistence Manager Factory in a JVM
      we are not able to create multiple embedded derby instances for two different MetaStore instances.
    */

    primary.run("drop database " + primaryDbName + " cascade");
    primary.run("drop database " + dbOne + " cascade");
    primary.run("drop database " + dbTwo + " cascade");

    /*
      End of additional steps
    */

    // Reset ckpt and last repl ID keys to empty set for allowing bootstrap load
    replica.run("show databases")
        .verifyFailure(new String[] { primaryDbName, dbOne, dbTwo })
        .run("alter database default set dbproperties ('hive.repl.ckpt.key'='', 'repl.last.id'='')")
        .load("", bootstrapTuple.dumpLocation)
        .run("show databases")
        .verifyResults(new String[] { "default", primaryDbName, dbOne })
        .run("use " + primaryDbName)
        .run("show tables")
        .verifyResults(new String[] { "t1" })
        .run("use " + dbOne)
        .run("show tables")
        .verifyResults(new String[] { "t1" });

    replica.load("", incrementalTuple.dumpLocation)
        .run("show databases")
        .verifyResults(new String[] { "default", primaryDbName, dbOne, dbTwo })
        .run("use " + dbTwo)
        .run("show tables")
        .verifyResults(new String[] { "t1" })
        .run("use " + dbOne)
        .run("show tables")
        .verifyResults(new String[] { "t1", "t2" });

    /*
       Start of cleanup
    */

    replica.run("drop database " + primaryDbName + " cascade");
    replica.run("drop database " + dbOne + " cascade");
    replica.run("drop database " + dbTwo + " cascade");

    /*
       End of cleanup
    */

  }

  @Test
  public void testReplLoadFromSourceUsingWithClause() throws Throwable {
    HiveConf replicaConf = replica.getConf();
    List<String> withConfigs = Arrays.asList(
            "'hive.metastore.warehouse.dir'='" + replicaConf.getVar(HiveConf.ConfVars.METASTOREWAREHOUSE) + "'",
            "'hive.metastore.uris'='" + replicaConf.getVar(HiveConf.ConfVars.METASTOREURIS) + "'",
            "'hive.repl.replica.functions.root.dir'='" + replicaConf.getVar(HiveConf.ConfVars.REPL_FUNCTIONS_ROOT_DIR) + "'");

    ////////////  Bootstrap   ////////////
    WarehouseInstance.Tuple bootstrapTuple = primary
            .run("use " + primaryDbName)
            .run("create table table1 (i int)")
            .run("create table table2 (id int) partitioned by (country string)")
            .run("insert into table1 values (1)")
            .dump(primaryDbName, null);

    // Run load on primary itself
    primary.load(replicatedDbName, bootstrapTuple.dumpLocation, withConfigs)
            .status(replicatedDbName, withConfigs)
            .verifyResult(bootstrapTuple.lastReplicationId);

    replica.run("use " + replicatedDbName)
            .run("show tables")
            .verifyResults(new String[] { "table1", "table2" })
            .run("select * from table1")
            .verifyResults(new String[]{ "1" });

    ////////////  First Incremental ////////////
    WarehouseInstance.Tuple incrementalOneTuple = primary
                    .run("use " + primaryDbName)
                    .run("alter table table1 rename to renamed_table1")
                    .run("insert into table2 partition(country='india') values (1) ")
                    .run("insert into table2 partition(country='usa') values (2) ")
                    .run("create table table3 (i int)")
                    .run("insert into table3 values(10)")
                    .run("create function " + primaryDbName
                      + ".testFunctionOne as 'hivemall.tools.string.StopwordUDF' "
                      + "using jar  'ivy://io.github.myui:hivemall:0.4.0-2'")
                    .dump(primaryDbName, bootstrapTuple.lastReplicationId);

    // Run load on primary itself
    primary.load(replicatedDbName, incrementalOneTuple.dumpLocation, withConfigs)
            .status(replicatedDbName, withConfigs)
            .verifyResult(incrementalOneTuple.lastReplicationId);

    replica.run("use " + replicatedDbName)
            .run("show tables")
            .verifyResults(new String[] { "renamed_table1", "table2", "table3" })
            .run("select * from renamed_table1")
            .verifyResults(new String[] { "1" })
            .run("select id from table2 order by id")
            .verifyResults(new String[] { "1", "2" })
            .run("select * from table3")
            .verifyResults(new String[] { "10" })
            .run("show functions like '" + replicatedDbName + "*'")
            .verifyResult(replicatedDbName + ".testFunctionOne");

    ////////////  Second Incremental ////////////
    WarehouseInstance.Tuple secondIncremental = primary
            .run("use " + primaryDbName)
            .run("alter table table2 add columns (zipcode int)")
            .run("alter table table3 set tblproperties('custom.property'='custom.value')")
            .run("drop table renamed_table1")
            .run("alter table table2 drop partition(country='usa')")
            .run("truncate table table3")
            .run("drop function " + primaryDbName + ".testFunctionOne ")
            .dump(primaryDbName, incrementalOneTuple.lastReplicationId);

    // Run load on primary itself
    primary.load(replicatedDbName, secondIncremental.dumpLocation, withConfigs)
            .status(replicatedDbName, withConfigs)
            .verifyResult(secondIncremental.lastReplicationId);

    replica.run("use " + replicatedDbName)
            .run("show tables")
            .verifyResults(new String[] { "table2", "table3"})
            .run("desc table2")
            .verifyResults(new String[] {
                    "id                  \tint                 \t                    ",
                    "country             \tstring              \t                    ",
                    "zipcode             \tint                 \t                    ",
                    "\t \t ",
                    "# Partition Information\t \t ",
                    "# col_name            \tdata_type           \tcomment             ",
                    "\t \t ",
                    "country             \tstring              \t                    ",
            })
            .run("show tblproperties table3('custom.property')")
            .verifyResults(new String[] { "custom.value\t " })
            .run("select id from table2 order by id")
            .verifyResults(new String[] { "1" })
            .run("select * from table3")
            .verifyResults(Collections.emptyList())
            .run("show functions like '" + replicatedDbName + "*'")
            .verifyResult(null);
  }

  @Test
  public void testIncrementalReplWithEventsBatchHavingDropCreateTable() throws Throwable {
    // Bootstrap dump with empty db
    WarehouseInstance.Tuple bootstrapTuple = primary.dump(primaryDbName, null);

    // Bootstrap load in replica
    replica.load(replicatedDbName, bootstrapTuple.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(bootstrapTuple.lastReplicationId);

    // First incremental dump
    WarehouseInstance.Tuple firstIncremental = primary.run("use " + primaryDbName)
            .run("create table table1 (i int)")
            .run("create table table2 (id int) partitioned by (country string)")
            .run("insert into table1 values (1)")
            .run("insert into table2 partition(country='india') values(1)")
            .dump(primaryDbName, bootstrapTuple.lastReplicationId);

    // Second incremental dump
    WarehouseInstance.Tuple secondIncremental = primary.run("use " + primaryDbName)
            .run("drop table table1")
            .run("drop table table2")
            .run("create table table2 (id int) partitioned by (country string)")
            .run("alter table table2 add partition(country='india')")
            .run("alter table table2 drop partition(country='india')")
            .run("insert into table2 partition(country='us') values(2)")
            .run("create table table1 (i int)")
            .run("insert into table1 values (2)")
            .dump(primaryDbName, firstIncremental.lastReplicationId);

    // First incremental load
    replica.load(replicatedDbName, firstIncremental.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(firstIncremental.lastReplicationId)
            .run("use " + replicatedDbName)
            .run("show tables")
            .verifyResults(new String[] {"table1", "table2"})
            .run("select * from table1")
            .verifyResults(new String[] {"1"})
            .run("select id from table2 order by id")
            .verifyResults(new String[] {"1"});

    // Second incremental load
    replica.load(replicatedDbName, secondIncremental.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(secondIncremental.lastReplicationId)
            .run("use " + replicatedDbName)
            .run("show tables")
            .verifyResults(new String[] {"table1", "table2"})
            .run("select * from table1")
            .verifyResults(new String[] {"2"})
            .run("select id from table2 order by id")
            .verifyResults(new String[] {"2"});
  }

  @Test
  public void testIncrementalReplWithDropAndCreateTableDifferentPartitionTypeAndInsert() throws Throwable {
    // Bootstrap dump with empty db
    WarehouseInstance.Tuple bootstrapTuple = primary.dump(primaryDbName, null);

    // Bootstrap load in replica
    replica.load(replicatedDbName, bootstrapTuple.dumpLocation)
        .status(replicatedDbName)
        .verifyResult(bootstrapTuple.lastReplicationId);

    // First incremental dump
    WarehouseInstance.Tuple firstIncremental = primary.run("use " + primaryDbName)
        .run("create table table1 (id int) partitioned by (country string)")
        .run("create table table2 (id int)")
        .run("create table table3 (id int) partitioned by (country string)")
        .run("insert into table1 partition(country='india') values(1)")
        .run("insert into table2 values(2)")
        .run("insert into table3 partition(country='india') values(3)")
        .dump(primaryDbName, bootstrapTuple.lastReplicationId);

    // Second incremental dump
    WarehouseInstance.Tuple secondIncremental = primary.run("use " + primaryDbName)
        .run("drop table table1")
        .run("drop table table2")
        .run("drop table table3")
        .run("create table table1 (id int)")
        .run("insert into table1 values (10)")
        .run("create table table2 (id int) partitioned by (country string)")
        .run("insert into table2 partition(country='india') values(20)")
        .run("create table table3 (id int) partitioned by (name string, rank int)")
        .run("insert into table3 partition(name='adam', rank=100) values(30)")
        .dump(primaryDbName, firstIncremental.lastReplicationId);

    // First incremental load
    replica.load(replicatedDbName, firstIncremental.dumpLocation)
        .status(replicatedDbName)
        .verifyResult(firstIncremental.lastReplicationId)
        .run("use " + replicatedDbName)
        .run("select id from table1")
        .verifyResults(new String[] { "1" })
        .run("select * from table2")
        .verifyResults(new String[] { "2" })
        .run("select id from table3")
        .verifyResults(new String[] { "3" });

    // Second incremental load
    replica.load(replicatedDbName, secondIncremental.dumpLocation)
        .status(replicatedDbName)
        .verifyResult(secondIncremental.lastReplicationId)
        .run("use " + replicatedDbName)
        .run("select * from table1")
        .verifyResults(new String[] { "10" })
        .run("select id from table2")
        .verifyResults(new String[] { "20" })
        .run("select id from table3")
        .verifyResults(new String[] {"30"});
  }

  private void verifyIfCkptSet(Map<String, String> props, String dumpDir) {
    assertTrue(props.containsKey(ReplUtils.REPL_CHECKPOINT_KEY));
    assertTrue(props.get(ReplUtils.REPL_CHECKPOINT_KEY).equals(dumpDir));
  }

  private void verifyIfCkptPropMissing(Map<String, String> props) {
    assertFalse(props.containsKey(ReplUtils.REPL_CHECKPOINT_KEY));
  }

  private void verifyIfSrcOfReplPropMissing(Map<String, String> props) {
    assertFalse(props.containsKey(SOURCE_OF_REPLICATION));
  }

  public void testIfCkptAndSourceOfReplPropsIgnoredByReplDump() throws Throwable {
    WarehouseInstance.Tuple tuplePrimary = primary
            .run("use " + primaryDbName)
            .run("create table t1 (place string) partitioned by (country string) "
                    + " tblproperties('custom.property'='custom.value')")
            .run("insert into table t1 partition(country='india') values ('bangalore')")
            .dump(primaryDbName, null);

    // Bootstrap Repl A -> B
    WarehouseInstance.Tuple tupleReplica = replica.load(replicatedDbName, tuplePrimary.dumpLocation)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuplePrimary.lastReplicationId)
            .run("show tblproperties t1('custom.property')")
            .verifyResults(new String[] { "custom.value\t " })
            .dumpFailure(replicatedDbName, null)
            .run("alter database " + replicatedDbName
                    + " set dbproperties ('" + SOURCE_OF_REPLICATION + "' = '1, 2, 3')")
            .dump(replicatedDbName, null);

    // Bootstrap Repl B -> C
    String replDbFromReplica = replicatedDbName + "_dupe";
    replica.load(replDbFromReplica, tupleReplica.dumpLocation)
            .run("use " + replDbFromReplica)
            .run("repl status " + replDbFromReplica)
            .verifyResult(tupleReplica.lastReplicationId)
            .run("show tables")
            .verifyResults(new String[] { "t1" })
            .run("select country from t1")
            .verifyResults(Arrays.asList("india"))
            .run("show tblproperties t1('custom.property')")
            .verifyResults(new String[] { "custom.value\t " });

    // Check if DB/table/partition in C doesn't have repl.source.for props. Also ensure, ckpt property
    // is set to bootstrap dump location used in C.
    Database db = replica.getDatabase(replDbFromReplica);
    verifyIfSrcOfReplPropMissing(db.getParameters());
    verifyIfCkptSet(db.getParameters(), tupleReplica.dumpLocation);
    Table t1 = replica.getTable(replDbFromReplica, "t1");
    verifyIfCkptSet(t1.getParameters(), tupleReplica.dumpLocation);
    Partition india = replica.getPartition(replDbFromReplica, "t1", Collections.singletonList("india"));
    verifyIfCkptSet(india.getParameters(), tupleReplica.dumpLocation);

    // Perform alters in A for incremental replication
    WarehouseInstance.Tuple tuplePrimaryInc = primary.run("use " + primaryDbName)
            .run("alter database " + primaryDbName + " set dbproperties('dummy_key'='dummy_val')")
            .run("alter table t1 set tblproperties('dummy_key'='dummy_val')")
            .run("alter table t1 partition(country='india') set fileformat orc")
            .dump(primaryDbName, tuplePrimary.lastReplicationId);

    // Incremental Repl A -> B with alters on db/table/partition
    WarehouseInstance.Tuple tupleReplicaInc = replica.load(replicatedDbName, tuplePrimaryInc.dumpLocation)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuplePrimaryInc.lastReplicationId)
            .dump(replicatedDbName, tupleReplica.lastReplicationId);

    // Check if DB in B have ckpt property is set to bootstrap dump location used in B and missing for table/partition.
    db = replica.getDatabase(replicatedDbName);
    verifyIfCkptSet(db.getParameters(), tuplePrimary.dumpLocation);
    t1 = replica.getTable(replicatedDbName, "t1");
    verifyIfCkptPropMissing(t1.getParameters());
    india = replica.getPartition(replicatedDbName, "t1", Collections.singletonList("india"));
    verifyIfCkptPropMissing(india.getParameters());

    // Incremental Repl B -> C with alters on db/table/partition
    replica.load(replDbFromReplica, tupleReplicaInc.dumpLocation)
            .run("use " + replDbFromReplica)
            .run("repl status " + replDbFromReplica)
            .verifyResult(tupleReplicaInc.lastReplicationId)
            .run("show tblproperties t1('custom.property')")
            .verifyResults(new String[] { "custom.value\t " });

    // Check if DB/table/partition in C doesn't have repl.source.for props. Also ensure, ckpt property
    // in DB is set to bootstrap dump location used in C but for table/partition, it is missing.
    db = replica.getDatabase(replDbFromReplica);
    verifyIfCkptSet(db.getParameters(), tupleReplica.dumpLocation);
    verifyIfSrcOfReplPropMissing(db.getParameters());
    t1 = replica.getTable(replDbFromReplica, "t1");
    verifyIfCkptPropMissing(t1.getParameters());
    india = replica.getPartition(replDbFromReplica, "t1", Collections.singletonList("india"));
    verifyIfCkptPropMissing(india.getParameters());

    replica.run("drop database if exists " + replDbFromReplica + " cascade");
  }

  @Test
  public void testIfCkptPropIgnoredByExport() throws Throwable {
    WarehouseInstance.Tuple tuplePrimary = primary
            .run("use " + primaryDbName)
            .run("create table t1 (place string) partitioned by (country string)")
            .run("insert into table t1 partition(country='india') values ('bangalore')")
            .dump(primaryDbName, null);

    // Bootstrap Repl A -> B and then export table t1
    String path = "hdfs:///tmp/" + replicatedDbName + "/";
    String exportPath = "'" + path + "1/'";
    replica.load(replicatedDbName, tuplePrimary.dumpLocation)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuplePrimary.lastReplicationId)
            .run("use " + replicatedDbName)
            .run("export table t1 to " + exportPath);

    // Check if ckpt property set in table/partition in B after bootstrap load.
    Table t1 = replica.getTable(replicatedDbName, "t1");
    verifyIfCkptSet(t1.getParameters(), tuplePrimary.dumpLocation);
    Partition india = replica.getPartition(replicatedDbName, "t1", Collections.singletonList("india"));
    verifyIfCkptSet(india.getParameters(), tuplePrimary.dumpLocation);

    // Import table t1 to C
    String importDbFromReplica = replicatedDbName + "_dupe";
    replica.run("create database " + importDbFromReplica)
            .run("use " + importDbFromReplica)
            .run("import table t1 from " + exportPath)
            .run("select country from t1")
            .verifyResults(Collections.singletonList("india"));

    // Check if table/partition in C doesn't have ckpt property
    t1 = replica.getTable(importDbFromReplica, "t1");
    verifyIfCkptPropMissing(t1.getParameters());
    india = replica.getPartition(importDbFromReplica, "t1", Collections.singletonList("india"));
    verifyIfCkptPropMissing(india.getParameters());

    replica.run("drop database if exists " + importDbFromReplica + " cascade");
  }

  @Test
  public void testIfBootstrapReplLoadFailWhenRetryAfterBootstrapComplete() throws Throwable {
    WarehouseInstance.Tuple tuple = primary
            .run("use " + primaryDbName)
            .run("create table t1 (id int)")
            .run("insert into table t1 values (10)")
            .run("create table t2 (place string) partitioned by (country string)")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .run("insert into table t2 partition(country='uk') values ('london')")
            .run("insert into table t2 partition(country='us') values ('sfo')")
            .dump(primaryDbName, null);

    replica.load(replicatedDbName, tuple.dumpLocation)
            .run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuple.lastReplicationId)
            .run("show tables")
            .verifyResults(new String[] { "t1", "t2" })
            .run("select id from t1")
        .verifyResults(Collections.singletonList("10"))
            .run("select country from t2 order by country")
            .verifyResults(Arrays.asList("india", "uk", "us"));
    replica.verifyIfCkptSet(replicatedDbName, tuple.dumpLocation);

    WarehouseInstance.Tuple tuple_2 = primary
            .run("use " + primaryDbName)
            .dump(primaryDbName, null);

    // Retry with different dump should fail.
    replica.loadFailure(replicatedDbName, tuple_2.dumpLocation);

    // Retry with same dump with which it was already loaded also fails.
    replica.loadFailure(replicatedDbName, tuple.dumpLocation);

    // Retry from same dump when the database is empty is also not allowed.
    replica.run("drop table t1")
            .run("drop table t2")
            .loadFailure(replicatedDbName, tuple.dumpLocation);
  }

  @Test
  public void testBootstrapReplLoadRetryAfterFailureForTables() throws Throwable {
    WarehouseInstance.Tuple tuple = primary
            .run("use " + primaryDbName)
            .run("create table t1(a string, b string)")
            .run("create table t2(a string, b string)")
            .run("create table t3(a string, b string)")
            .dump(primaryDbName, null);

    WarehouseInstance.Tuple tuple2 = primary
            .run("use " + primaryDbName)
            .dump(primaryDbName, null);

    // Allow create table only on t1. Create should fail for rest of the tables are not loaded.
    BehaviourInjection<CallerArguments, Boolean> callerVerifier
            = new BehaviourInjection<CallerArguments, Boolean>() {
      @Nullable
      @Override
      public Boolean apply(@Nullable CallerArguments args) {
        injectionPathCalled = true;
        if (!args.dbName.equalsIgnoreCase(replicatedDbName)) {
          LOG.warn("Verifier - DB: " + String.valueOf(args.dbName));
          return false;
        }
        if (args.tblName != null) {
          LOG.warn("Verifier - Table: " + String.valueOf(args.tblName));
          return !args.tblName.equals("t2");
        }
        return true;
      }
    };
    InjectableBehaviourObjectStore.setCallerVerifier(callerVerifier);

    // Trigger bootstrap dump which just creates table t1 and other tables (t2, t3) and constraints not loaded.
    List<String> withConfigs = Arrays.asList("'hive.repl.approx.max.load.tasks'='1'");
    try {
      replica.loadFailure(replicatedDbName, tuple.dumpLocation, withConfigs);
      callerVerifier.assertInjectionsPerformed(true, false);
    } finally {
      InjectableBehaviourObjectStore.resetCallerVerifier(); // reset the behaviour
    }

    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult("null")
            .run("show tables like t2")
            .verifyResults(new String[] {  });

    // Retry with different dump should fail.
    replica.loadFailure(replicatedDbName, tuple2.dumpLocation, null,
            ErrorMsg.REPL_BOOTSTRAP_LOAD_PATH_NOT_VALID.getErrorCode());

    // Verify if create table is not called on table t1 but called for t2 and t3.
    callerVerifier = new BehaviourInjection<CallerArguments, Boolean>() {
      @Nullable
      @Override
      public Boolean apply(@Nullable CallerArguments args) {
        injectionPathCalled = true;
        if (!args.dbName.equalsIgnoreCase(replicatedDbName)) {
          LOG.warn("Verifier - DB: " + String.valueOf(args.dbName));
          return false;
        }
        return true;
      }
    };
    InjectableBehaviourObjectStore.setCallerVerifier(callerVerifier);

    try {
      // Retry with same dump with which it was already loaded should resume the bootstrap load.
      // This time, it completes by adding table t2 and t3.
      replica.load(replicatedDbName, tuple.dumpLocation);
      callerVerifier.assertInjectionsPerformed(true, false);
    } finally {
      InjectableBehaviourObjectStore.resetCallerVerifier(); // reset the behaviour
    }

    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuple.lastReplicationId)
            .run("show tables")
            .verifyResults(new String[] { "t1", "t2", "t3" });
  }

  @Test
  public void testBootstrapReplLoadRetryAfterFailureForPartitions() throws Throwable {
    WarehouseInstance.Tuple tuple = primary
            .run("use " + primaryDbName)
            .run("create table t2 (place string) partitioned by (country string)")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .run("insert into table t2 partition(country='uk') values ('london')")
            .run("insert into table t2 partition(country='us') values ('sfo')")
            .run("CREATE FUNCTION " + primaryDbName
                    + ".testFunctionOne as 'hivemall.tools.string.StopwordUDF' "
                    + "using jar  'ivy://io.github.myui:hivemall:0.4.0-2'")
            .dump(primaryDbName, null);

    WarehouseInstance.Tuple tuple2 = primary
            .run("use " + primaryDbName)
            .dump(primaryDbName, null);

    // Inject a behavior where REPL LOAD failed when try to load table "t2" and partition "uk".
    // So, table "t2" will exist and partition "india" will exist, rest failed as operation failed.
    BehaviourInjection<Partition, Partition> getPartitionStub
            = new BehaviourInjection<Partition, Partition>() {
      @Nullable
      @Override
      public Partition apply(@Nullable Partition ptn) {
        if (ptn.getValues().get(0).equals("india")) {
          injectionPathCalled = true;
          LOG.warn("####getPartition Stub called");
          return null;
        }
        return ptn;
      }
    };
    InjectableBehaviourObjectStore.setGetPartitionBehaviour(getPartitionStub);

    // Make sure that there's some order in which the objects are loaded.
    List<String> withConfigs = Arrays.asList("'hive.repl.approx.max.load.tasks'='1'",
            "'hive.in.repl.test.files.sorted'='true'");
    replica.loadFailure(replicatedDbName, tuple.dumpLocation, withConfigs);
    InjectableBehaviourObjectStore.resetGetPartitionBehaviour(); // reset the behaviour
    getPartitionStub.assertInjectionsPerformed(true, false);

    // For Hadoop-2, the functions are loaded only after table load and hence remove the validation for
    // functions. It is done after the successful REPL LOAD.
    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult("null")
            .run("show tables")
            .verifyResults(new String[] {"t2" })
            .run("select country from t2 order by country")
            .verifyResults(Arrays.asList("india"));

    // Retry with different dump should fail.
    replica.loadFailure(replicatedDbName, tuple2.dumpLocation);

    // Verify if no create table calls. Add partitions and create function calls expected.
    BehaviourInjection<CallerArguments, Boolean> callerVerifier
            = new BehaviourInjection<CallerArguments, Boolean>() {
      @Nullable
      @Override
      public Boolean apply(@Nullable CallerArguments args) {
        if (!args.dbName.equalsIgnoreCase(replicatedDbName) || (args.tblName != null)) {
          injectionPathCalled = true;
          LOG.warn("Verifier - DB: " + String.valueOf(args.dbName)
                  + " Table: " + String.valueOf(args.tblName));
          return false;
        }
        return true;
      }
    };
    InjectableBehaviourObjectStore.setCallerVerifier(callerVerifier);

    try {
      // Retry with same dump with which it was already loaded should resume the bootstrap load.
      // This time, it completes by adding remaining partitions and function.
      replica.load(replicatedDbName, tuple.dumpLocation);
      callerVerifier.assertInjectionsPerformed(false, false);
    } finally {
      InjectableBehaviourObjectStore.resetCallerVerifier(); // reset the behaviour
    }

    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuple.lastReplicationId)
            .run("show tables")
            .verifyResults(new String[] { "t2" })
            .run("select country from t2 order by country")
            .verifyResults(Arrays.asList("india", "uk", "us"))
            .run("show functions like '" + replicatedDbName + "*'")
            .verifyResult(replicatedDbName + ".testFunctionOne");
  }

  // This requires the tables are loaded in a fixed soretd order.
  @Test
  public void testBootstrapLoadRetryAfterFailureForAlterTable() throws Throwable {
    WarehouseInstance.Tuple tuple = primary
            .run("use " + primaryDbName)
            .run("create table t1 (place string)")
            .run("insert into table t1 values ('testCheck')")
            .run("create table t2 (place string) partitioned by (country string)")
            .run("insert into table t2 partition(country='china') values ('shenzhen')")
            .run("insert into table t2 partition(country='india') values ('banaglore')")
            .dump(primaryDbName, null);

    // fail setting ckpt directory property for table t1.
    BehaviourInjection<CallerArguments, Boolean> callerVerifier
            = new BehaviourInjection<CallerArguments, Boolean>() {
      @Nullable
      @Override
      public Boolean apply(@Nullable CallerArguments args) {
        if (args.tblName.equalsIgnoreCase("t1") && args.dbName.equalsIgnoreCase(replicatedDbName)) {
          injectionPathCalled = true;
          LOG.warn("Verifier - DB : " + args.dbName + " TABLE : " + args.tblName);
          return false;
        }
        return true;
      }
    };

    // Fail repl load before the ckpt proeprty is set for t1 and after it is set for t2. So in the next run, for
    // t2 it goes directly to partion load with no task for table tracker and for t1 it loads the table
    // again from start.
    InjectableBehaviourObjectStore.setAlterTableModifier(callerVerifier);
    try {
      replica.loadFailure(replicatedDbName, tuple.dumpLocation);
      callerVerifier.assertInjectionsPerformed(true, false);
    } finally {
      InjectableBehaviourObjectStore.resetAlterTableModifier();
    }

    // Retry with same dump with which it was already loaded should resume the bootstrap load. Make sure that table t1,
    // is loaded before t2. So that scope is set to table in first iteration for table t1. In the next iteration, it
    // loads only remaining partitions of t2, so that the table tracker has no tasks.
    List<String> withConfigs = Arrays.asList("'hive.in.repl.test.files.sorted'='true'");
    replica.load(replicatedDbName, tuple.dumpLocation, withConfigs);

    replica.run("use " + replicatedDbName)
            .run("repl status " + replicatedDbName)
            .verifyResult(tuple.lastReplicationId)
            .run("select country from t2 order by country")
            .verifyResults(Arrays.asList("china", "india"));
  }

  @Test
  public void testIncrementalDumpMultiIteration() throws Throwable {
    WarehouseInstance.Tuple bootstrapTuple = primary.dump(primaryDbName, null);

    replica.load(replicatedDbName, bootstrapTuple.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(bootstrapTuple.lastReplicationId);

    WarehouseInstance.Tuple incremental = primary.run("use " + primaryDbName)
            .run("create table table1 (id int) partitioned by (country string)")
            .run("create table table2 (id int)")
            .run("create table table3 (id int) partitioned by (country string)")
            .run("insert into table1 partition(country='india') values(1)")
            .run("insert into table2 values(2)")
            .run("insert into table3 partition(country='india') values(3)")
            .dump(primaryDbName, bootstrapTuple.lastReplicationId);

    replica.load(replicatedDbName, incremental.dumpLocation, Arrays.asList("'hive.repl.approx.max.load.tasks'='2'"))
            .status(replicatedDbName)
            .verifyResult(incremental.lastReplicationId)
            .run("use " + replicatedDbName)
            .run("select id from table1")
            .verifyResults(new String[] { "1" })
            .run("select * from table2")
            .verifyResults(new String[] { "2" })
            .run("select id from table3")
            .verifyResults(new String[] { "3" });
    assert(IncrementalLoadTasksBuilder.getNumIteration() > 1);

    incremental = primary.run("use " + primaryDbName)
            .run("create table  table5 (key int, value int) partitioned by (load_date date) " +
                    "clustered by(key) into 2 buckets stored as orc")
            .run("create table table4 (i int, j int)")
            .run("insert into table4 values (1,2)")
            .dump(primaryDbName, incremental.lastReplicationId);

    Path path = new Path(incremental.dumpLocation);
    FileSystem fs = path.getFileSystem(conf);
    FileStatus[] fileStatus = fs.listStatus(path);
    int numEvents = fileStatus.length - 1; //one is metadata file

    replica.load(replicatedDbName, incremental.dumpLocation, Arrays.asList("'hive.repl.approx.max.load.tasks'='1'"))
            .run("use " + replicatedDbName)
            .run("show tables")
            .verifyResults(new String[] {"table1", "table2", "table3", "table4", "table5" })
            .run("select i from table4")
            .verifyResult("1");
    Assert.assertEquals(IncrementalLoadTasksBuilder.getNumIteration(), numEvents);
  }

  public void testMoveOptimizationBootstrapReplLoadRetryAfterFailure() throws Throwable {
    String replicatedDbName_CM = replicatedDbName + "_CM";
    WarehouseInstance.Tuple tuple = primary
            .run("use " + primaryDbName)
            .run("create table t2 (place string) partitioned by (country string)")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .dump(primaryDbName, null);

    testMoveOptimization(primaryDbName, replicatedDbName, replicatedDbName_CM, "t2",
            "ADD_PARTITION", tuple);
  }

  @Test
  public void testMoveOptimizationIncrementalFailureAfterCopyReplace() throws Throwable {
    List<String> withConfigs = Arrays.asList("'hive.repl.enable.move.optimization'='true'");
    String replicatedDbName_CM = replicatedDbName + "_CM";
    WarehouseInstance.Tuple tuple = primary.run("use " + primaryDbName)
            .run("create table t2 (place string) partitioned by (country string)")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .run("create table t1 (place string) partitioned by (country string)")
            .run("insert into table t1 partition(country='india') values ('delhi')")
            .dump(primaryDbName, null);
    replica.load(replicatedDbName, tuple.dumpLocation, withConfigs);
    replica.load(replicatedDbName_CM, tuple.dumpLocation, withConfigs);
    replica.run("alter database " + replicatedDbName + " set DBPROPERTIES ('" + SOURCE_OF_REPLICATION + "' = '1,2,3')")
      .run("alter database " + replicatedDbName_CM + " set DBPROPERTIES ('" + SOURCE_OF_REPLICATION + "' = '1,2,3')");

    tuple = primary.run("use " + primaryDbName)
            .run("insert overwrite table " + primaryDbName + ".t1  partition(country='india') select place from t2")
            .dump(primaryDbName, tuple.lastReplicationId);

    testMoveOptimization(primaryDbName, replicatedDbName, replicatedDbName_CM, "t1", "INSERT", tuple);
  }

  @Test
  public void testMoveOptimizationIncrementalFailureAfterCopy() throws Throwable {
    List<String> withConfigs = Arrays.asList("'hive.repl.enable.move.optimization'='true'");
    String replicatedDbName_CM = replicatedDbName + "_CM";
    WarehouseInstance.Tuple tuple = primary.run("use " + primaryDbName)
            .run("create table t2 (place string) partitioned by (country string)")
            .run("ALTER TABLE t2 ADD PARTITION (country='india')")
            .dump(primaryDbName, null);
    replica.load(replicatedDbName, tuple.dumpLocation, withConfigs);
    replica.load(replicatedDbName_CM, tuple.dumpLocation, withConfigs);
    replica.run("alter database " + replicatedDbName + " set DBPROPERTIES ('" + SOURCE_OF_REPLICATION + "' = '1,2,3')")
            .run("alter database " + replicatedDbName_CM + " set DBPROPERTIES ('" + SOURCE_OF_REPLICATION + "' = '1,2,3')");

    tuple = primary.run("use " + primaryDbName)
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .dump(primaryDbName, tuple.lastReplicationId);

    testMoveOptimization(primaryDbName, replicatedDbName, replicatedDbName_CM, "t2", "INSERT", tuple);
  }

  @Test
  public void testIncrementalDumpEmptyDumpDirectory() throws Throwable {
    WarehouseInstance.Tuple tuple = primary.dump(primaryDbName, null);

    replica.load(replicatedDbName, tuple.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(tuple.lastReplicationId);

    tuple = primary.dump(primaryDbName, tuple.lastReplicationId);

    replica.load(replicatedDbName, tuple.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(tuple.lastReplicationId);

    // create events for some other database and then dump the primaryDbName to dump an empty directory.
    String testDbName = primaryDbName + "_test";
    tuple = primary.run(" create database " + testDbName)
            .run("create table " + testDbName + ".tbl (fld int)")
            .dump(primaryDbName, tuple.lastReplicationId);

    // Incremental load to existing database with empty dump directory should set the repl id to the last event at src.
    replica.load(replicatedDbName, tuple.dumpLocation)
            .status(replicatedDbName)
            .verifyResult(tuple.lastReplicationId);

    // Incremental load to non existing db should return database not exist error.
    tuple = primary.dump("someRandomDB", tuple.lastReplicationId);
    CommandProcessorResponse response = replica.runCommand("REPL LOAD someRandomDB from " + tuple.dumpLocation);
    response.getErrorMessage().toLowerCase().contains("org.apache.hadoop.hive.ql.metadata.hiveException: " +
            "database does not exist");

    // Bootstrap load from an empty dump directory should return empty load directory error.
    tuple = primary.dump("someRandomDB", null);
    response = replica.runCommand("REPL LOAD someRandomDB from " + tuple.dumpLocation);
    response.getErrorMessage().toLowerCase().contains("org.apache.hadoop.hive.ql.parse.semanticException:" +
            " no data to load in path");

    primary.run(" drop database if exists " + testDbName + " cascade");
  }

  private void testMoveOptimization(String primarydb, String replicadb, String replicatedDbName_CM,
                                    String tbl,  String eventType, WarehouseInstance.Tuple tuple) throws Throwable {
    List<String> withConfigs = Arrays.asList("'hive.repl.enable.move.optimization'='true'");

    // fail add notification for given event type.
    BehaviourInjection<NotificationEvent, Boolean> callerVerifier
            = new BehaviourInjection<NotificationEvent, Boolean>() {
      @Nullable
      @Override
      public Boolean apply(@Nullable NotificationEvent entry) {
        if (entry.getEventType().equalsIgnoreCase(eventType) && entry.getTableName().equalsIgnoreCase(tbl)) {
          injectionPathCalled = true;
          LOG.warn("Verifier - DB: " + String.valueOf(entry.getDbName())
                  + " Table: " + String.valueOf(entry.getTableName())
                  + " Event: " + String.valueOf(entry.getEventType()));
          return false;
        }
        return true;
      }
    };

    InjectableBehaviourObjectStore.setAddNotificationModifier(callerVerifier);
    try {
      replica.loadFailure(replicadb, tuple.dumpLocation, withConfigs);
    } finally {
      InjectableBehaviourObjectStore.resetAddNotificationModifier();
    }

    callerVerifier.assertInjectionsPerformed(true, false);
    replica.load(replicadb, tuple.dumpLocation, withConfigs);

    replica.run("use " + replicadb)
            .run("select country from " + tbl + " where country == 'india'")
            .verifyResults(Arrays.asList("india"));

    primary.run("use " + primarydb)
            .run("drop table " + tbl);

    InjectableBehaviourObjectStore.setAddNotificationModifier(callerVerifier);
    try {
      replica.loadFailure(replicatedDbName_CM, tuple.dumpLocation, withConfigs);
    } finally {
      InjectableBehaviourObjectStore.resetAddNotificationModifier();
    }

    callerVerifier.assertInjectionsPerformed(true, false);
    replica.load(replicatedDbName_CM, tuple.dumpLocation, withConfigs);

    replica.run("use " + replicatedDbName_CM)
            .run("select country from " + tbl + " where country == 'india'")
            .verifyResults(Arrays.asList("india"))
            .run(" drop database if exists " + replicatedDbName_CM + " cascade");
  }
  
  @Test
  public void dynamicallyConvertManagedToExternalTable() throws Throwable {
    // Db enabled for replication, it is not possible to convert external table to managed table.
    primary.run("use " + primaryDbName)
            .run("create table t1 (id int) clustered by(id) into 3 buckets stored as orc ")
            .run("insert into t1 values(1)")
            .run("create table t2 (place string) partitioned by (country string)")
            .run("insert into t2 partition (country='india') values ('bangalore')")
            .runFailure("alter table t1 set tblproperties('EXTERNAL'='true')")
            .runFailure("alter table t2 set tblproperties('EXTERNAL'='true')");
  }

  @Test
  public void dynamicallyConvertExternalToManagedTable() throws Throwable {
    // Db enabled for replication, it is not possible to convert external table to managed table.
    primary.run("use " + primaryDbName)
            .run("create external table t1 (id int) stored as orc")
            .run("insert into table t1 values (1)")
            .run("create external table t2 (place string) partitioned by (country string)")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .runFailure("alter table t1 set tblproperties('EXTERNAL'='false')")
            .runFailure("alter table t2 set tblproperties('EXTERNAL'='false')");
  }

  @Test
  public void dynamicallyConvertNonAcidToAcidTable() throws Throwable {
    // Non-acid table converted to an ACID table should be prohibited on source cluster with
    // strict managed false.
    primary.run("use " + primaryDbName)
            .run("create table t1 (id int) stored as orc")
            .run("insert into table t1 values (1)")
            .run("create table t2 (place string) partitioned by (country string) stored as orc")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .runFailure("alter table t1 set tblproperties('transactional'='true')")
            .runFailure("alter table t2 set tblproperties('transactional'='true')")
            .runFailure("alter table t1 set tblproperties('transactional'='true', " +
                    "'transactional_properties'='insert_only')")
            .runFailure("alter table t2 set tblproperties('transactional'='true', " +
                    "'transactional_properties'='insert_only')");

  }

  @Test
  public void prohibitManagedTableLocationChangeOnReplSource() throws Throwable {
    String tmpLocation = "/tmp/" + System.nanoTime();
    primary.miniDFSCluster.getFileSystem().mkdirs(new Path(tmpLocation), new FsPermission("777"));

    // For managed tables at source, the table location shouldn't be changed for the given
    // non-partitioned table and partition location shouldn't be changed for partitioned table as
    // alter event doesn't capture the new files list. So, it may cause data inconsistsency. So,
    // if database is enabled for replication at source, then alter location on managed tables
    // should be blocked.
    primary.run("use " + primaryDbName)
            .run("create table t1 (id int) clustered by(id) into 3 buckets stored as orc ")
            .run("insert into t1 values(1)")
            .run("create table t2 (place string) partitioned by (country string) stored as orc")
            .run("insert into table t2 partition(country='india') values ('bangalore')")
            .runFailure("alter table t1 set location '" + tmpLocation + "'")
            .runFailure("alter table t2 partition(country='india') set location '" + tmpLocation + "'")
            .runFailure("alter table t2 set location '" + tmpLocation + "'");

    primary.miniDFSCluster.getFileSystem().delete(new Path(tmpLocation), true);
  }
}
