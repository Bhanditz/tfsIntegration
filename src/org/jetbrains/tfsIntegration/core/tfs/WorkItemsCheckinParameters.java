/*
 * Copyright 2000-2008 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.tfsIntegration.core.tfs;

import com.intellij.util.containers.ContainerUtil;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.CheckinWorkItemAction;
import com.microsoft.tfs.core.clients.workitem.query.WorkItemLinkInfo;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.tfs.workitems.WorkItem;
import org.jetbrains.tfsIntegration.ui.WorkItemsQueryResult;

import java.util.Collections;
import java.util.List;
import java.util.Map;

public class WorkItemsCheckinParameters {

  @NotNull private List<WorkItem> myWorkItems;
  @NotNull private Map<WorkItem, CheckinWorkItemAction> myActions;
  @Nullable private List<WorkItemLinkInfo> myLinks;

  private WorkItemsCheckinParameters(@NotNull List<WorkItem> workItems,
                                     @NotNull Map<WorkItem, CheckinWorkItemAction> actions,
                                     @Nullable List<WorkItemLinkInfo> links) {
    myWorkItems = workItems;
    myActions = actions;
    myLinks = links;
  }

  public WorkItemsCheckinParameters() {
    this(Collections.emptyList(), ContainerUtil.newHashMap(), null);
  }

  @Nullable
  public CheckinWorkItemAction getAction(@NotNull WorkItem workItem) {
    return myActions.get(workItem);
  }

  public void setAction(@NotNull WorkItem workItem, @NotNull CheckinWorkItemAction action) {
    myActions.put(workItem, action);
  }

  public void removeAction(@NotNull WorkItem workItem) {
    myActions.remove(workItem);
  }

  @NotNull
  public List<WorkItem> getWorkItems() {
    return Collections.unmodifiableList(myWorkItems);
  }

  @Nullable
  public List<WorkItemLinkInfo> getLinks() {
    return myLinks != null ? Collections.unmodifiableList(myLinks) : null;
  }

  @NotNull
  public WorkItemsCheckinParameters createCopy() {
    return new WorkItemsCheckinParameters(ContainerUtil.newArrayList(myWorkItems), ContainerUtil.newHashMap(myActions), getLinks());
  }

  public void update(@NotNull WorkItemsQueryResult queryResult) {
    myWorkItems = queryResult.getWorkItems();
    myLinks = queryResult.getLinks();
    myActions.clear();
  }

  public void update(@NotNull WorkItemsCheckinParameters parameters) {
    myWorkItems = parameters.myWorkItems;
    myLinks = parameters.myLinks;
    myActions = parameters.myActions;
  }

  @NotNull
  public Map<WorkItem, CheckinWorkItemAction> getWorkItemsActions() {
    return Collections.unmodifiableMap(myActions);
  }
}
