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
package org.apache.sentry.binding.hive;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hive.SentryHiveConstants;
import org.apache.hadoop.hive.conf.HiveConf;
import org.apache.hadoop.hive.metastore.api.PrincipalType;
import org.apache.hadoop.hive.ql.exec.SentryGrantRevokeTask;
import org.apache.hadoop.hive.ql.exec.SentryHivePrivilegeObjectDesc;
import org.apache.hadoop.hive.ql.exec.Task;
import org.apache.hadoop.hive.ql.exec.TaskFactory;
import org.apache.hadoop.hive.ql.hooks.ReadEntity;
import org.apache.hadoop.hive.ql.hooks.WriteEntity;
import org.apache.hadoop.hive.ql.metadata.Hive;
import org.apache.hadoop.hive.ql.metadata.HiveException;
import org.apache.hadoop.hive.ql.parse.ASTNode;
import org.apache.hadoop.hive.ql.parse.BaseSemanticAnalyzer;
import org.apache.hadoop.hive.ql.parse.HiveParser;
import org.apache.hadoop.hive.ql.parse.SemanticException;
import org.apache.hadoop.hive.ql.parse.authorization.HiveAuthorizationTaskFactory;
import org.apache.hadoop.hive.ql.plan.DDLWork;
import org.apache.hadoop.hive.ql.plan.GrantDesc;
import org.apache.hadoop.hive.ql.plan.GrantRevokeRoleDDL;
import org.apache.hadoop.hive.ql.plan.PrincipalDesc;
import org.apache.hadoop.hive.ql.plan.PrivilegeDesc;
import org.apache.hadoop.hive.ql.plan.PrivilegeObjectDesc;
import org.apache.hadoop.hive.ql.plan.RevokeDesc;
import org.apache.hadoop.hive.ql.plan.RoleDDLDesc;
import org.apache.hadoop.hive.ql.plan.ShowGrantDesc;
import org.apache.hadoop.hive.ql.security.authorization.Privilege;
import org.apache.hadoop.hive.ql.security.authorization.PrivilegeRegistry;
import org.apache.hadoop.hive.ql.security.authorization.PrivilegeType;
import org.apache.hadoop.hive.ql.session.SessionState;
import org.apache.sentry.core.model.db.AccessConstants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Preconditions;

public class SentryHiveAuthorizationTaskFactoryImpl implements HiveAuthorizationTaskFactory {

  private static final Logger LOG = LoggerFactory.getLogger(SentryHiveAuthorizationTaskFactoryImpl.class);

  private final Hive db;

  public SentryHiveAuthorizationTaskFactoryImpl(HiveConf conf, Hive db) { //NOPMD
    this.db = db;
  }

  @Override
  public Task<? extends Serializable> createCreateRoleTask(ASTNode ast, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) throws SemanticException {
    String roleName = BaseSemanticAnalyzer.unescapeIdentifier(ast.getChild(0).getText());
    if (AccessConstants.RESERVED_ROLE_NAMES.contains(roleName.toUpperCase())) {
      String msg = "Roles cannot be one of the reserved roles: " + AccessConstants.RESERVED_ROLE_NAMES;
      throw new SemanticException(msg);
    }
    RoleDDLDesc roleDesc = new RoleDDLDesc(roleName, RoleDDLDesc.RoleOperation.CREATE_ROLE);
    return createTask(new DDLWork(inputs, outputs, roleDesc));
  }
  @Override
  public Task<? extends Serializable> createDropRoleTask(ASTNode ast, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) throws SemanticException {
    String roleName = BaseSemanticAnalyzer.unescapeIdentifier(ast.getChild(0).getText());
    if (AccessConstants.RESERVED_ROLE_NAMES.contains(roleName.toUpperCase())) {
      String msg = "Roles cannot be one of the reserved roles: " + AccessConstants.RESERVED_ROLE_NAMES;
      throw new SemanticException(msg);
    }
    RoleDDLDesc roleDesc = new RoleDDLDesc(roleName, RoleDDLDesc.RoleOperation.DROP_ROLE);
    return createTask(new DDLWork(inputs, outputs, roleDesc));
  }
  @Override
  public Task<? extends Serializable> createShowRoleGrantTask(ASTNode ast, Path resultFile,
      HashSet<ReadEntity> inputs, HashSet<WriteEntity> outputs) throws SemanticException {
    ASTNode child = (ASTNode) ast.getChild(0);
    PrincipalType principalType = PrincipalType.USER;
    switch (child.getType()) {
    case HiveParser.TOK_USER:
      principalType = PrincipalType.USER;
      break;
    case HiveParser.TOK_GROUP:
      principalType = PrincipalType.GROUP;
      break;
    case HiveParser.TOK_ROLE:
      principalType = PrincipalType.ROLE;
      break;
    }
    if (principalType != PrincipalType.GROUP) {
      String msg = SentryHiveConstants.GRANT_REVOKE_NOT_SUPPORTED_FOR_PRINCIPAL + principalType;
      throw new SemanticException(msg);
    }
    String principalName = BaseSemanticAnalyzer.unescapeIdentifier(child.getChild(0).getText());
    RoleDDLDesc roleDesc = new RoleDDLDesc(principalName, principalType,
        RoleDDLDesc.RoleOperation.SHOW_ROLE_GRANT, null);
    roleDesc.setResFile(resultFile.toString());
    return createTask(new DDLWork(inputs, outputs,  roleDesc));
  }

  @Override
  public Task<? extends Serializable> createGrantTask(ASTNode ast, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) throws SemanticException {
    List<PrivilegeDesc> privilegeDesc = analyzePrivilegeListDef(
        (ASTNode) ast.getChild(0));
    List<PrincipalDesc> principalDesc = analyzePrincipalListDef(
        (ASTNode) ast.getChild(1));
    SentryHivePrivilegeObjectDesc privilegeObj = null;
    boolean grantOption = false;
    if (ast.getChildCount() > 2) {
      for (int i = 2; i < ast.getChildCount(); i++) {
        ASTNode astChild = (ASTNode) ast.getChild(i);
        if (astChild.getType() == HiveParser.TOK_GRANT_WITH_OPTION) {
          grantOption = true;
        } else if (astChild.getType() == HiveParser.TOK_PRIV_OBJECT) {
          privilegeObj = analyzePrivilegeObject(astChild);
        }
      }
    }
    String userName = null;
    if (SessionState.get() != null
        && SessionState.get().getAuthenticator() != null) {
      userName = SessionState.get().getAuthenticator().getUserName();
    }
    Preconditions.checkNotNull(privilegeObj, "privilegeObj is null for " + ast.dump());
    if (privilegeObj.getPartSpec() != null) {
      throw new SemanticException(SentryHiveConstants.PARTITION_PRIVS_NOT_SUPPORTED);
    }
    for (PrincipalDesc princ : principalDesc) {
      if (princ.getType() != PrincipalType.ROLE) {
        String msg = SentryHiveConstants.GRANT_REVOKE_NOT_SUPPORTED_FOR_PRINCIPAL + princ.getType();
        throw new SemanticException(msg);
      }
    }
    GrantDesc grantDesc = new GrantDesc(privilegeObj, privilegeDesc,
        principalDesc, userName, PrincipalType.USER, grantOption);
    return createTask(new DDLWork(inputs, outputs, grantDesc));
  }
  @Override
  public Task<? extends Serializable> createRevokeTask(ASTNode ast, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) throws SemanticException {
    List<PrivilegeDesc> privilegeDesc = analyzePrivilegeListDef((ASTNode) ast.getChild(0));
    List<PrincipalDesc> principalDesc = analyzePrincipalListDef((ASTNode) ast.getChild(1));
    PrivilegeObjectDesc privilegeObj = null;
    if (ast.getChildCount() > 2) {
      ASTNode astChild = (ASTNode) ast.getChild(2);
      privilegeObj = analyzePrivilegeObject(astChild);
    }
    if (privilegeObj != null && privilegeObj.getPartSpec() != null) {
      throw new SemanticException(SentryHiveConstants.PARTITION_PRIVS_NOT_SUPPORTED);
    }
    for (PrincipalDesc princ : principalDesc) {
      if (princ.getType() != PrincipalType.ROLE) {
        String msg = SentryHiveConstants.GRANT_REVOKE_NOT_SUPPORTED_FOR_PRINCIPAL + princ.getType();
        throw new SemanticException(msg);
      }
    }
    RevokeDesc revokeDesc = new RevokeDesc(privilegeDesc, principalDesc, privilegeObj);
    return createTask(new DDLWork(inputs, outputs, revokeDesc));
  }

  @Override
  public Task<? extends Serializable> createGrantRoleTask(ASTNode ast, HashSet<ReadEntity> inputs, HashSet<WriteEntity> outputs)
      throws SemanticException {
    return analyzeGrantRevokeRole(true, ast, inputs, outputs);
  }

  @Override
  public Task<? extends Serializable> createShowGrantTask(ASTNode ast, Path resultFile, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) throws SemanticException {
    SentryHivePrivilegeObjectDesc privHiveObj = null;

    ASTNode principal = (ASTNode) ast.getChild(0);
    PrincipalType type = PrincipalType.USER;
    switch (principal.getType()) {
    case HiveParser.TOK_USER:
      type = PrincipalType.USER;
      break;
    case HiveParser.TOK_GROUP:
      type = PrincipalType.GROUP;
      break;
    case HiveParser.TOK_ROLE:
      type = PrincipalType.ROLE;
      break;
    }
    if (type != PrincipalType.ROLE) {
      String msg = SentryHiveConstants.GRANT_REVOKE_NOT_SUPPORTED_FOR_PRINCIPAL + type;
      throw new SemanticException(msg);
    }
    String principalName = BaseSemanticAnalyzer.unescapeIdentifier(principal.getChild(0).getText());
    PrincipalDesc principalDesc = new PrincipalDesc(principalName, type);

    // Partition privileges are not supported by Sentry
    if (ast.getChildCount() > 1) {
      ASTNode child = (ASTNode) ast.getChild(1);
      if (child.getToken().getType() == HiveParser.TOK_PRIV_OBJECT_COL) {
        privHiveObj = analyzePrivilegeObject(child);
      } else {
        throw new SemanticException("Unrecognized Token: " + child.getToken().getType());
      }
    }

    ShowGrantDesc showGrant = new ShowGrantDesc(resultFile.toString(),
        principalDesc, privHiveObj);
    return createTask(new DDLWork(inputs, outputs, showGrant));
  }

  @Override
  public Task<? extends Serializable> createRevokeRoleTask(ASTNode ast, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) throws SemanticException {
    return analyzeGrantRevokeRole(false, ast, inputs, outputs);
  }

  private Task<? extends Serializable> analyzeGrantRevokeRole(boolean isGrant, ASTNode ast,
      HashSet<ReadEntity> inputs, HashSet<WriteEntity> outputs) throws SemanticException {
    List<PrincipalDesc> principalDesc = analyzePrincipalListDef(
        (ASTNode) ast.getChild(0));

    List<String> roles = new ArrayList<String>();
    for (int i = 1; i < ast.getChildCount(); i++) {
      roles.add(BaseSemanticAnalyzer.unescapeIdentifier(ast.getChild(i).getText()));
    }
    String roleOwnerName = "";
    if (SessionState.get() != null
        && SessionState.get().getAuthenticator() != null) {
      roleOwnerName = SessionState.get().getAuthenticator().getUserName();
    }
    for (PrincipalDesc princ : principalDesc) {
      if (princ.getType() != PrincipalType.GROUP) {
        String msg = SentryHiveConstants.GRANT_REVOKE_NOT_SUPPORTED_ON_OBJECT + princ.getType();
        throw new SemanticException(msg);
      }
    }
    GrantRevokeRoleDDL grantRevokeRoleDDL = new GrantRevokeRoleDDL(isGrant,
        roles, principalDesc, roleOwnerName, PrincipalType.USER, false);
    return createTask(new DDLWork(inputs, outputs, grantRevokeRoleDDL));
  }

  @Override
  public Task<? extends Serializable> createSetRoleTask(String role, HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs) {
    RoleDDLDesc roleDesc = new RoleDDLDesc(role, RoleDDLDesc.RoleOperation.SET_ROLE);
    return createTask(new DDLWork(inputs, outputs, roleDesc));
  }

  @Override
  public Task<? extends Serializable> createShowCurrentRoleTask(HashSet<ReadEntity> inputs,
      HashSet<WriteEntity> outputs, Path resultFile) throws SemanticException {
    RoleDDLDesc ddlDesc = new RoleDDLDesc(null, RoleDDLDesc.RoleOperation.SHOW_CURRENT_ROLE);
    ddlDesc.setResFile(resultFile.toString());
    return createTask(new DDLWork(inputs, outputs, ddlDesc));
  }

  @Override
  public Task<? extends Serializable> createShowRolesTask(ASTNode ast, Path resFile,
      HashSet<ReadEntity> inputs, HashSet<WriteEntity> outputs) throws SemanticException {
    RoleDDLDesc showRolesDesc = new RoleDDLDesc(null, null, RoleDDLDesc.RoleOperation.SHOW_ROLES,
        null);
    showRolesDesc.setResFile(resFile.toString());
    return createTask(new DDLWork(inputs, outputs, showRolesDesc));
  }

  private SentryHivePrivilegeObjectDesc analyzePrivilegeObject(ASTNode ast)
      throws SemanticException {
    SentryHivePrivilegeObjectDesc subject = new SentryHivePrivilegeObjectDesc();
    ASTNode astChild = (ASTNode) ast.getChild(0);
    ASTNode gchild = (ASTNode) astChild.getChild(0);

    String privilegeObject = BaseSemanticAnalyzer.unescapeIdentifier(gchild.getText());
    subject.setObject(privilegeObject);
      if (astChild.getToken().getType() == HiveParser.TOK_PARTSPEC) {
          throw new SemanticException(SentryHiveConstants.PARTITION_PRIVS_NOT_SUPPORTED);
        } else if (astChild.getToken().getType() == HiveParser.TOK_URI_TYPE) {
          privilegeObject = privilegeObject.replaceAll("'", "").replaceAll("\"", "");
          subject.setObject(privilegeObject);
          subject.setUri(true);
        } else if (astChild.getToken().getType() == HiveParser.TOK_SERVER_TYPE) {
          subject.setServer(true);
        } else if (astChild.getToken().getType() == HiveParser.TOK_TABLE_TYPE) {
          subject.setTable(true);
          String[] qualified = BaseSemanticAnalyzer.getQualifiedTableName(gchild);
          subject.setObject(qualified[1]);
          try {
            subject.setOwner(db.getTable(qualified[1]).getOwner());
          } catch (HiveException e) {
            // Ignore the exception.
          }
        } else if (astChild.getToken().getType() == HiveParser.TOK_DB_TYPE) {
          try {
            subject.setOwner(db.getDatabase(privilegeObject).getOwnerName());
          } catch (HiveException e) {
            // Ignore the exception.
          }
        }
      for (int i = 1; i < astChild.getChildCount(); i++) {
        gchild = (ASTNode) astChild.getChild(i);
        if (gchild.getType() == HiveParser.TOK_PARTSPEC) {
          throw new SemanticException(SentryHiveConstants.PARTITION_PRIVS_NOT_SUPPORTED);
        } else if (gchild.getType() == HiveParser.TOK_TABCOLNAME) {
          subject.setColumns(BaseSemanticAnalyzer.getColumnNames(gchild));
        }
      }

    return subject;
  }

  private List<PrincipalDesc> analyzePrincipalListDef(ASTNode node) {
    List<PrincipalDesc> principalList = new ArrayList<PrincipalDesc>();
    for (int i = 0; i < node.getChildCount(); i++) {
      ASTNode child = (ASTNode) node.getChild(i);
      PrincipalType type = null;
      switch (child.getType()) {
      case 880:
        type = PrincipalType.USER;
        break;
      case HiveParser.TOK_USER:
        type = PrincipalType.USER;
        break;
      case 685:
        type = PrincipalType.GROUP;
        break;
      case HiveParser.TOK_GROUP:
        type = PrincipalType.GROUP;
        break;
      case 782:
        type = PrincipalType.ROLE;
        break;
      case HiveParser.TOK_ROLE:
        type = PrincipalType.ROLE;
        break;
      }
      String principalName = BaseSemanticAnalyzer.unescapeIdentifier(child.getChild(0).getText());
      PrincipalDesc principalDesc = new PrincipalDesc(principalName, type);
      LOG.debug("## Principal : [ " + principalName + ", " + type + "]");
      principalList.add(principalDesc);
    }
    return principalList;
  }

  private List<PrivilegeDesc> analyzePrivilegeListDef(ASTNode node)
      throws SemanticException {
    List<PrivilegeDesc> ret = new ArrayList<PrivilegeDesc>();
    for (int i = 0; i < node.getChildCount(); i++) {
      ASTNode privilegeDef = (ASTNode) node.getChild(i);
      ASTNode privilegeType = (ASTNode) privilegeDef.getChild(0);
      Privilege privObj = PrivilegeRegistry.getPrivilege(privilegeType.getType());
      if (privObj == null) {
        throw new SemanticException("undefined privilege " + privilegeType.getType());
      }
      if (!SentryHiveConstants.ALLOWED_PRIVS.contains(privObj.getPriv())) {
        String msg = SentryHiveConstants.PRIVILEGE_NOT_SUPPORTED + privObj.getPriv();
        throw new SemanticException(msg);
      }
      List<String> cols = null;
      if (privilegeDef.getChildCount() > 1) {
        cols = BaseSemanticAnalyzer.getColumnNames((ASTNode) privilegeDef.getChild(1));
      }
      if (cols != null && (privObj.getPriv().equals(PrivilegeType.INSERT)
              || privObj.getPriv().equals(PrivilegeType.ALL))) {
        String msg = SentryHiveConstants.PRIVILEGE_NOT_SUPPORTED + privObj.getPriv() + " on Column";
        throw new SemanticException(msg);
      }
      PrivilegeDesc privilegeDesc = new PrivilegeDesc(privObj, cols);
      ret.add(privilegeDesc);
    }
    return ret;
  }

  private static Task<? extends Serializable> createTask(DDLWork work) {
    SentryGrantRevokeTask task = new SentryGrantRevokeTask();
    task.setId("Stage-" + Integer.toString(TaskFactory.getAndIncrementId()));
    task.setWork(work);
    return task;
  }

  //TODO temp workaround and copied from HiveAuthorizationTaskFactoryImpl and modified
  @Override
  public Task<? extends Serializable> createShowRolePrincipalsTask(ASTNode ast, Path resFile,
      HashSet<ReadEntity> inputs, HashSet<WriteEntity> outputs) throws SemanticException {
    String roleName;

    if (ast.getChildCount() == 1) {
      roleName = ast.getChild(0).getText();
    } else {
      // the parser should not allow this
      throw new AssertionError("Unexpected Tokens in SHOW ROLE PRINCIPALS");
    }

    RoleDDLDesc roleDDLDesc = new RoleDDLDesc(roleName, PrincipalType.ROLE,
     RoleDDLDesc.RoleOperation.SHOW_ROLE_PRINCIPALS, null);
    roleDDLDesc.setResFile(resFile.toString());
    return createTask(new DDLWork(inputs, outputs, roleDDLDesc));
    //return TaskFactory.get(new DDLWork(inputs, outputs, roleDDLDesc), conf);
  }


}
