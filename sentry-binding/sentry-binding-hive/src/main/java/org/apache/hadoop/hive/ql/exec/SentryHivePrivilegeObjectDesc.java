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

package org.apache.hadoop.hive.ql.exec;

import org.apache.hadoop.hive.ql.plan.PrivilegeObjectDesc;

public class SentryHivePrivilegeObjectDesc extends PrivilegeObjectDesc {
  private boolean isUri;
  private boolean isServer;
  private String owner;

  public SentryHivePrivilegeObjectDesc() {
    // reset table type which is on by default
    super.setTable(false);
  }

  public boolean getUri() {
    return isUri;
  }

  public void setUri(boolean isUri) {
    this.isUri = isUri;
  }

  public boolean getServer() {
    return isServer;
  }

  public void setServer(boolean isServer) {
    this.isServer = isServer;
  }

  public boolean isSentryPrivObjectDesc() {
    return isServer || isUri;
  }

  public String getOwner() {
    return owner;
  }

  public void setOwner(String owner) {
    this.owner = owner;
  }
}
