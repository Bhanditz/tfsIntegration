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

package org.jetbrains.tfsIntegration.tests.parentchildchange;

import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vcs.changes.Change;
import org.jetbrains.annotations.Nullable;
import org.junit.Test;

import java.io.IOException;

@SuppressWarnings({"HardCodedStringLiteral"})
public class DeletedFileInRenamed extends ParentChildChangeTestCase {
  private FilePath myOriginalParentFolder;
  private FilePath myRenamedParentFolder;
  private FilePath myDeletedFileInOriginalFolder;
  private FilePath myDeletedFileInRenamedFolder;

  protected void preparePaths() {
    myOriginalParentFolder = getChildPath(mySandboxRoot, "OriginalFolder");
    myRenamedParentFolder = getChildPath(mySandboxRoot, "RenamedFolder");

    final String filename = "deleted_file.txt";
    myDeletedFileInOriginalFolder = getChildPath(myOriginalParentFolder, filename);
    myDeletedFileInRenamedFolder = getChildPath(myRenamedParentFolder, filename);
  }

  protected void checkParentChangePendingChildRolledBack() throws VcsException {
    getChanges().assertTotalItems(1);
    getChanges().assertRenamedOrMoved(myOriginalParentFolder, myRenamedParentFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 1);
    assertFile(myDeletedFileInRenamedFolder, FILE_CONTENT, false);
  }

  protected void checkChildChangePendingParentRolledBack() throws VcsException {
    getChanges().assertTotalItems(1);
    getChanges().assertScheduledForDeletion(myDeletedFileInOriginalFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myOriginalParentFolder, 0);
  }

  protected void checkParentAndChildChangesPending() throws VcsException {
    getChanges().assertTotalItems(2);
    getChanges().assertRenamedOrMoved(myOriginalParentFolder, myRenamedParentFolder);
    getChanges().assertScheduledForDeletion(myDeletedFileInRenamedFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 0);
  }

  protected void checkOriginalStateAfterRollbackParentChild() throws VcsException {
    getChanges().assertTotalItems(0);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myOriginalParentFolder, 1);
    assertFile(myDeletedFileInOriginalFolder, FILE_CONTENT, false);
  }

  protected void checkOriginalStateAfterUpdate() throws VcsException {
    getChanges().assertTotalItems(0);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myOriginalParentFolder, 1);
    assertFile(myDeletedFileInOriginalFolder, FILE_CONTENT, false);
  }

  protected void checkParentChangeCommittedChildPending() throws VcsException {
    getChanges().assertTotalItems(1);
    getChanges().assertScheduledForDeletion(myDeletedFileInRenamedFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 0);
  }

  protected void checkChildChangeCommittedParentPending() throws VcsException {
    getChanges().assertTotalItems(1);
    getChanges().assertRenamedOrMoved(myOriginalParentFolder, myRenamedParentFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 0);
  }

  protected void checkParentChangePending() throws VcsException {
    getChanges().assertTotalItems(1);
    getChanges().assertRenamedOrMoved(myOriginalParentFolder, myRenamedParentFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 1);
    assertFile(myDeletedFileInRenamedFolder, FILE_CONTENT, false);
  }

  protected void checkChildChangePending() throws VcsException {
    getChanges().assertTotalItems(1);
    getChanges().assertScheduledForDeletion(myDeletedFileInOriginalFolder);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myOriginalParentFolder, 0);
  }

  protected void checkParentChangeCommitted() throws VcsException {
    getChanges().assertTotalItems(0);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 1);
    assertFile(myDeletedFileInRenamedFolder, FILE_CONTENT, false);
  }

  protected void checkChildChangeCommitted() throws VcsException {
    getChanges().assertTotalItems(0);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myOriginalParentFolder, 0);
  }

  protected void checkParentAndChildChangesCommitted() throws VcsException {
    getChanges().assertTotalItems(0);

    assertFolder(mySandboxRoot, 1);
    assertFolder(myRenamedParentFolder, 0);
  }

  protected void makeOriginalState() {
    createDirInCommand(myOriginalParentFolder);
    createFileInCommand(myDeletedFileInOriginalFolder, FILE_CONTENT);
  }

  protected void makeParentChange() {
    renameFileInCommand(myOriginalParentFolder, myRenamedParentFolder.getName());
  }

  protected void makeChildChange(ParentChangeState parentChangeState) {
    deleteFileInCommand(parentChangeState == ParentChangeState.NotDone ? myDeletedFileInOriginalFolder : myDeletedFileInRenamedFolder);
  }

  @Nullable
  protected Change getPendingParentChange() throws VcsException {
    return getChanges().getMoveChange(myOriginalParentFolder, myRenamedParentFolder);
  }

  protected Change getPendingChildChange(ParentChangeState parentChangeState) throws VcsException {
    return getChanges()
      .getDeleteChange(parentChangeState == ParentChangeState.NotDone ? myDeletedFileInOriginalFolder : myDeletedFileInRenamedFolder);
  }

  @Test
  public void testPendingAndRollback() throws VcsException, IOException {
    super.testPendingAndRollback();
  }

  @Test
  public void testCommitParentThenChildChanges() throws VcsException, IOException {
    super.testCommitParentThenChildChanges();
  }

  @Test
  public void testCommitChildThenParentChanges() throws VcsException, IOException {
    super.testCommitChildThenParentChanges();
  }

  @Test
  public void testCommitParentChangesChildPending() throws VcsException, IOException {
    super.testCommitParentChangesChildPending();
  }

  @Test
  public void testCommitChildChangesParentPending() throws VcsException, IOException {
    super.testCommitChildChangesParentPending();
  }

}