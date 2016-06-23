/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.sentry.tests.e2e.dbprovider;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

import junit.framework.Assert;

import org.apache.hadoop.hive.ql.plan.HiveOperation;
import org.apache.sentry.binding.hive.conf.HiveAuthzConf;
import org.apache.sentry.provider.db.SentryAccessDeniedException;
import org.apache.sentry.tests.e2e.hive.AbstractTestWithStaticConfiguration;
import org.apache.sentry.tests.e2e.hive.DummySentryOnFailureHook;
import org.apache.sentry.tests.e2e.hive.hiveserver.HiveServerFactory;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class TestPrivilegeWithOwnerPermission extends AbstractTestWithStaticConfiguration {

  private static boolean isInternalServer = false;

  @BeforeClass
  public static void setupTestStaticConfiguration() throws Exception {
    useSentryService = true;
    String hiveServer2Type = System
        .getProperty(HiveServerFactory.HIVESERVER2_TYPE);
    if ((hiveServer2Type == null)
        || HiveServerFactory.isInternalServer(HiveServerFactory.HiveServer2Type
            .valueOf(hiveServer2Type.trim()))) {
      System.setProperty(
        HiveAuthzConf.AuthzConfVars.AUTHZ_ONFAILURE_HOOKS.getVar(),
        DummySentryOnFailureHook.class.getName());
      isInternalServer = true;
    }
    enableSessionAuthenticator = true;
    AbstractTestWithStaticConfiguration.setupTestStaticConfiguration();
  }

  @Override
  @Before
  public void setup() throws Exception {
    DummySentryOnFailureHook.invoked = false;
    super.setupAdmin();
    super.setup();
  }

  /*
   * Admin grant DB_1 user1 without grant option, grant user3 with grant option,
   * user1 tries to grant it to user2, but failed.
   * user3 can grant it to user2.
   * user1 tries to revoke, but failed.
   * user3 tries to revoke user2, user3 and user1, user3 revoke user1 will failed.
   * permissions for DB_1.
   */
  @Test
  public void testOnGrantPrivilege() throws Exception {

    // setup db objects needed by the test
    Connection connection = context.createConnection(ADMIN1);
    Statement statement = context.createStatement(connection);
    statement.execute("DROP DATABASE IF EXISTS db_1 CASCADE");
    statement.execute("DROP DATABASE IF EXISTS db_2 CASCADE");
    statement.execute("CREATE DATABASE db_1");
    statement.execute("CREATE ROLE group1_role");
    statement.execute("GRANT ALL ON DATABASE db_1 TO ROLE group1_role");
    statement.execute("GRANT ROLE group1_role TO GROUP " + USERGROUP1);
    statement.execute("CREATE ROLE group3_grant_role");
    statement.execute("GRANT ALL ON DATABASE db_1 TO ROLE group3_grant_role WITH GRANT OPTION");
    statement.execute("GRANT ROLE group3_grant_role TO GROUP " + USERGROUP3);
    statement.execute("CREATE ROLE group2_role");
    statement.execute("GRANT ROLE group2_role TO GROUP " + USERGROUP2);

    connection.close();

    connection = context.createConnection(USER1_1);
    statement = context.createStatement(connection);

    statement.execute("USE db_1");
    statement.execute("CREATE TABLE foo (id int)");
    runSQLWithError(statement, "GRANT ALL ON DATABASE db_1 TO ROLE group2_role",
        HiveOperation.GRANT_PRIVILEGE, null, null, true);
    runSQLWithError(statement,
        "GRANT ALL ON DATABASE db_1 TO ROLE group2_role WITH GRANT OPTION",
        HiveOperation.GRANT_PRIVILEGE, null, null, true);
    statement.execute("GRANT ALL ON TABLE foo TO ROLE role2");

    connection.close();
    context.close();
  }


  // run the given statement and verify that failure hook is invoked as expected
  private void runSQLWithError(Statement statement, String sqlStr,
      HiveOperation expectedOp, String dbName, String tableName,
      boolean checkSentryAccessDeniedException) throws Exception {
    // negative test case: non admin user can't create role
    assertFalse(DummySentryOnFailureHook.invoked);
    try {
      statement.execute(sqlStr);
      Assert.fail("Expected SQL exception for " + sqlStr);
    } catch (SQLException e) {
      verifyFailureHook(expectedOp, dbName, tableName, checkSentryAccessDeniedException);
    } finally {
      DummySentryOnFailureHook.invoked = false;
    }

  }

  // run the given statement and verify that failure hook is invoked as expected
  private void verifyFailureHook(HiveOperation expectedOp,
      String dbName, String tableName, boolean checkSentryAccessDeniedException)
      throws Exception {
    if (!isInternalServer) {
      return;
    }

    assertTrue(DummySentryOnFailureHook.invoked);
    if (expectedOp != null) {
      Assert.assertNotNull("Hive op is null for op: " + expectedOp, DummySentryOnFailureHook.hiveOp);
      assertTrue(expectedOp.equals(DummySentryOnFailureHook.hiveOp));
    }
    if (checkSentryAccessDeniedException) {
      assertTrue("Expected SentryDeniedException for op: " + expectedOp,
          DummySentryOnFailureHook.exception.getCause() instanceof SentryAccessDeniedException);
    }
    if(tableName != null) {
      Assert.assertNotNull("Table object is null for op: " + expectedOp, DummySentryOnFailureHook.table);
      assertTrue(tableName.equalsIgnoreCase(DummySentryOnFailureHook.table.getName()));
    }
    if(dbName != null) {
      Assert.assertNotNull("Database object is null for op: " + expectedOp, DummySentryOnFailureHook.db);
      assertTrue(dbName.equalsIgnoreCase(DummySentryOnFailureHook.db.getName()));
    }
  }

}
