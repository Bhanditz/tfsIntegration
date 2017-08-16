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

package org.jetbrains.tfsIntegration.tests.conflicts;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.ChangeType_type0;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.Conflict;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.tfs.ChangeTypeMask;
import org.jetbrains.tfsIntegration.core.tfs.VersionControlPath;
import org.jetbrains.tfsIntegration.exceptions.TfsException;
import org.junit.Assert;
import org.junit.Test;

import java.io.IOException;

// Notes
// 1. When choosing 'AcceptYours' solution, TFS 2005 SP1 and TFS 2008 server return GetOperation to rename local file to 'theirs' name.
// In the same time TFS 2005 RTM server does not return any get operation
//

public class TestFileYoursModifiedTheirsRenamed extends TestFileConflict {

  private FilePath myBaseFile;
  private FilePath myTheirsFile;
  private FilePath myMergedFile;

  protected boolean canMerge() {
    return true;
  }

  protected void preparePaths() {
    myBaseFile = getChildPath(mySandboxRoot, BASE_FILENAME);
    myTheirsFile = getChildPath(mySandboxRoot, THEIRS_FILENAME);
    myMergedFile = getChildPath(mySandboxRoot, MERGED_FILENAME);
  }

  protected void prepareBaseRevision() {
    createFileInCommand(myBaseFile, BASE_CONTENT);
  }

  protected void prepareTargetRevision() {
    rename(myBaseFile, myTheirsFile.getName());
  }

  protected void makeLocalChanges() throws IOException, VcsException {
    editFiles(myBaseFile);
    setFileContent(myBaseFile, YOURS_CONTENT);
  }

  protected void checkResolvedYoursState() throws VcsException {
    if (SERVER_VERSION == TfsServerVersion.TFS_2005_RTM) {
      // see also Note 1
      getChanges().assertTotalItems(1);
      getChanges().assertRenamedOrMoved(myTheirsFile, myBaseFile, BASE_CONTENT, YOURS_CONTENT);

      assertFolder(mySandboxRoot, 1);
      assertFile(myBaseFile, YOURS_CONTENT, true);
    }
    else {
      getChanges().assertTotalItems(1);
      getChanges().assertModified(myTheirsFile, BASE_CONTENT, YOURS_CONTENT);

      assertFolder(mySandboxRoot, 1);
      assertFile(myTheirsFile, YOURS_CONTENT, true);
    }
  }

  protected void checkResolvedTheirsState() throws VcsException {
    getChanges().assertTotalItems(0);

    assertFolder(mySandboxRoot, 1);
    assertFile(myTheirsFile, BASE_CONTENT, false);
  }

  protected void checkResolvedMergeState() throws VcsException {
    getChanges().assertTotalItems(1);

    getChanges().assertRenamedOrMoved(myTheirsFile, myMergedFile, BASE_CONTENT, MERGED_CONTENT);

    assertFolder(mySandboxRoot, 1);
    assertFile(myMergedFile, MERGED_CONTENT, true);
  }

  protected void checkConflictProperties(final Conflict conflict) throws TfsException {
    Assert.assertTrue(new ChangeTypeMask(conflict.getYchg()).containsOnly(ChangeType_type0.Edit));
    Assert.assertTrue(new ChangeTypeMask(conflict.getBchg()).containsOnly(ChangeType_type0.Rename));
    Assert.assertEquals(myBaseFile, VersionControlPath.getFilePath(conflict.getSrclitem(), false));
    Assert.assertEquals(myTheirsFile, VersionControlPath.getFilePath(conflict.getTgtlitem(), false));
    Assert.assertEquals(findServerPath(myTheirsFile), conflict.getYsitem());
    Assert.assertEquals(findServerPath(myBaseFile), conflict.getYsitemsrc());
    Assert.assertEquals(findServerPath(myBaseFile), conflict.getBsitem());
    Assert.assertEquals(findServerPath(myTheirsFile), conflict.getTsitem());
  }

  @Nullable
  protected String mergeName() throws TfsException {
    return findServerPath(myMergedFile);
  }

  @Nullable
  protected String mergeContent() {
    return MERGED_CONTENT;
  }

  @Nullable
  protected String getExpectedBaseContent() {
    return BASE_CONTENT;
  }

  @Nullable
  protected String getExpectedYoursContent() {
    return YOURS_CONTENT;
  }

  @Nullable
  protected String getExpectedTheirsContent() {
    return BASE_CONTENT;
  }

  @Test
  public void testAcceptYours() throws VcsException, IOException {
    super.testAcceptYours();
  }

  @Test
  public void testAcceptTheirs() throws VcsException, IOException {
    super.testAcceptTheirs();
  }

  @Test
  public void testAcceptMerge() throws VcsException, IOException {
    super.testAcceptMerge();
  }
}
