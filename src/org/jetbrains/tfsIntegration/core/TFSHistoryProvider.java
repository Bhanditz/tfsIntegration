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

package org.jetbrains.tfsIntegration.core;

import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsConfiguration;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.history.*;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ui.ColumnInfo;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.Changeset;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.ExtendedItem;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.Item;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.ItemType;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.tfs.TfsUtil;
import org.jetbrains.tfsIntegration.core.tfs.WorkspaceInfo;
import org.jetbrains.tfsIntegration.core.tfs.version.ChangesetVersionSpec;
import org.jetbrains.tfsIntegration.core.tfs.version.LatestVersionSpec;
import org.jetbrains.tfsIntegration.core.tfs.version.VersionSpecBase;
import org.jetbrains.tfsIntegration.exceptions.TfsException;

import javax.swing.*;
import java.util.ArrayList;
import java.util.List;

public class TFSHistoryProvider implements VcsHistoryProvider {
  private @NotNull final Project myProject;

  public TFSHistoryProvider(@NotNull Project project) {
    myProject = project;
  }

  public VcsDependentHistoryComponents getUICustomization(final VcsHistorySession session, JComponent forShortcutRegistration) {
    return VcsDependentHistoryComponents.createOnlyColumns(ColumnInfo.EMPTY_ARRAY);
  }

  public AnAction[] getAdditionalActions(final Runnable refresher) {
    return AnAction.EMPTY_ARRAY;
  }

  public boolean isDateOmittable() {
    return false;
  }

  @Nullable
  @NonNls
  public String getHelpId() {
    return null;
  }

  @Nullable
  public VcsHistorySession createSessionFor(final FilePath filePath) throws VcsException {
    try {
      final Pair<WorkspaceInfo, ExtendedItem> workspaceAndItem =
        TfsUtil.getWorkspaceAndExtendedItem(filePath, myProject, TFSBundle.message("loading.item"));
      if (workspaceAndItem == null || workspaceAndItem.second == null) {
        return null;
      }

      final List<TFSFileRevision> revisions =
        getRevisions(myProject, workspaceAndItem.second.getSitem(), filePath.isDirectory(), workspaceAndItem.first,
                     LatestVersionSpec.INSTANCE);
      if (revisions.isEmpty()) {
        return null;
      }

      return createSession(workspaceAndItem, revisions);
    }
    catch (TfsException e) {
      throw new VcsException(e);
    }
  }

  private static VcsAbstractHistorySession createSession(final Pair<WorkspaceInfo, ExtendedItem> workspaceAndItem,
                                                         final List<? extends VcsFileRevision> revisions) {
    return new VcsAbstractHistorySession(revisions) {
      public VcsRevisionNumber calcCurrentRevisionNumber() {
        return TfsUtil.getCurrentRevisionNumber(workspaceAndItem.second);
      }

      public HistoryAsTreeProvider getHistoryAsTreeProvider() {
        return null;
      }

      @Override
      public VcsHistorySession copy() {
        return createSession(workspaceAndItem, getRevisionList());
      }

      @Override
      public boolean isContentAvailable(VcsFileRevision revision) {
        return workspaceAndItem.second.getType() == ItemType.File;
      }
    };
  }

  public void reportAppendableHistory(FilePath path, VcsAppendableHistorySessionPartner partner) throws VcsException {
    //??
    final VcsHistorySession session = createSessionFor(path);
    partner.reportCreatedEmptySession((VcsAbstractHistorySession)session);
  }

  public static List<TFSFileRevision> getRevisions(final Project project,
                                                   final String serverPath,
                                                   final boolean isDirectory,
                                                   WorkspaceInfo workspace,
                                                   VersionSpecBase versionTo) throws TfsException {
    VcsConfiguration vcsConfiguration = VcsConfiguration.getInstance(project);
    int maxCount = vcsConfiguration.LIMIT_HISTORY ? vcsConfiguration.MAXIMUM_HISTORY_ROWS : Integer.MAX_VALUE;
    List<Changeset> changesets =
      workspace.getServer().getVCS().queryHistory(workspace, serverPath, isDirectory, null, new ChangesetVersionSpec(1), versionTo, project,
                                                  TFSBundle.message("loading.item"), maxCount);

    List<TFSFileRevision> revisions = new ArrayList<>(changesets.size());
    for (Changeset changeset : changesets) {
      final Item item = changeset.getChanges().getChange()[0].getItem();
      revisions.add(
        new TFSFileRevision(project, workspace, item.getItemid(), changeset.getDate().getTime(), changeset.getComment(),
                            changeset.getOwner(),
                            changeset.getCset()));
    }
    return revisions;
  }

  public boolean supportsHistoryForDirectories() {
    return true;
  }

  @Override
  public DiffFromHistoryHandler getHistoryDiffHandler() {
    return null;
  }

  @Override
  public boolean canShowHistoryFor(@NotNull VirtualFile file) {
    return true;
  }
}
