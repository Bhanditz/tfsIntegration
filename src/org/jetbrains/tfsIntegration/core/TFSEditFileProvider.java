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

import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.EditFileProvider;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.GetOperation;
import org.jetbrains.tfsIntegration.core.tfs.*;
import org.jetbrains.tfsIntegration.exceptions.TfsException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

public class TFSEditFileProvider implements EditFileProvider {
  private final Project myProject;

  public TFSEditFileProvider(Project project) {
    myProject = project;
  }

  public void editFiles(final VirtualFile[] files) throws VcsException {
    final Collection<VcsException> errors = new ArrayList<>();
    try {
      Collection<FilePath> orphans =
        WorkstationHelper.processByWorkspaces(TfsFileUtil.getFilePaths(files), false, myProject,
                                              new WorkstationHelper.VoidProcessDelegate() {
          public void executeRequest(final WorkspaceInfo workspace, final List<ItemPath> paths) throws TfsException {
            final ResultWithFailures<GetOperation> processResult =
              workspace.getServer().getVCS()
                .checkoutForEdit(workspace.getName(), workspace.getOwnerName(), paths, myProject, TFSBundle.message("checking.out"));
            Collection<VirtualFile> makeWritable = new ArrayList<>();
            for (GetOperation getOperation : processResult.getResult()) {
              TFSVcs.assertTrue(getOperation.getSlocal().equals(getOperation.getTlocal()));
              VirtualFile file = VersionControlPath.getVirtualFile(getOperation.getSlocal());
              if (file != null && file.isValid() && !file.isDirectory()) {
                makeWritable.add(file);
              }
            }
            try {
              TfsFileUtil.setReadOnly(makeWritable, false);
            }
            catch (IOException e) {
              errors.add(new VcsException(e));
            }
            errors.addAll(TfsUtil.getVcsExceptions(processResult.getFailures()));
          }
        });

      if (!orphans.isEmpty()) {
        StringBuilder s = new StringBuilder("Mappings not found for files:\n");
        for (FilePath path : orphans) {
          s.append(path.getPresentableUrl()).append("\n");
        }
        throw new VcsException(s.toString());
      }
    }
    catch (TfsException e) {
      throw new VcsException(e);
    }
    if (!errors.isEmpty()) {
      throw TfsUtil.collectExceptions(errors);
    }
  }

  public String getRequestText() {
    return "Perform a checkout?";

  }
}
