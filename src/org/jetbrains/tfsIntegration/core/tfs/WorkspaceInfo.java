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

import com.intellij.openapi.vcs.FilePath;
import com.intellij.util.containers.ContainerUtil;
import com.intellij.vcsUtil.VcsUtil;
import com.microsoft.schemas.teamfoundation._2005._06.versioncontrol.clientservices._03.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.TFSBundle;
import org.jetbrains.tfsIntegration.core.TFSVcs;
import org.jetbrains.tfsIntegration.exceptions.TfsException;
import org.jetbrains.tfsIntegration.exceptions.WorkspaceNotFoundException;

import java.util.*;

public class WorkspaceInfo {

  private static final Collection<String> WORKSPACE_NAME_INVALID_CHARS = Arrays.asList("\"", "/", ":", "<", ">", "|", "*", "?");

  private static final Collection<String> WORKSPACE_NAME_INVALID_ENDING_CHARS = Arrays.asList(" ", ".");

  private final ServerInfo myServerInfo;
  private final String myOwnerName;
  private final String myComputer;

  private String myOriginalName;
  private String myComment;
  private Calendar myTimestamp;
  private boolean myLoaded;
  private String myModifiedName;
  @NotNull private Location myLocation = Location.SERVER;
  @Nullable private String myOwnerDisplayName;
  @NotNull private final List<String> myOwnerAliases = ContainerUtil.newArrayList();
  @Nullable private String mySecurityToken;
  private int myOptions;

  private List<WorkingFolderInfo> myWorkingFoldersInfos = new ArrayList<>();

  public WorkspaceInfo(final @NotNull ServerInfo serverInfo, final @NotNull String owner, final @NotNull String computer) {
    myServerInfo = serverInfo;
    myOwnerName = owner;
    myComputer = computer;
    myTimestamp = new GregorianCalendar();
  }

  public WorkspaceInfo(final @NotNull ServerInfo serverInfo,
                       final @NotNull String name,
                       final String owner,
                       final String computer,
                       final String comment,
                       final Calendar timestamp,
                       boolean isLocal,
                       @Nullable String ownerDisplayName,
                       @Nullable String securityToken,
                       int options) {
    this(serverInfo, owner, computer);

    myOriginalName = name;
    myComment = comment;
    myTimestamp = timestamp;
    myLocation = Location.from(isLocal);
    myOwnerDisplayName = ownerDisplayName;
    mySecurityToken = securityToken;
    myOptions = options;
  }

  // TODO: make private

  @NotNull
  public ServerInfo getServer() {
    return myServerInfo;
  }

  public String getOwnerName() {
    return myOwnerName;
  }

  public String getComputer() {
    return myComputer;
  }

  public String getName() {
    return myModifiedName != null ? myModifiedName : myOriginalName;
  }

  public void setName(final String name) {
    checkCurrentOwnerAndComputer();
    myModifiedName = name;
  }

  @NotNull
  public Location getLocation() {
    return myLocation;
  }

  public void setLocation(@NotNull Location location) {
    myLocation = location;
  }

  public boolean isLocal() {
    return Location.LOCAL.equals(myLocation);
  }

  public String getComment() {
    return myComment;
  }

  public void setComment(final String comment) {
    checkCurrentOwnerAndComputer();
    myComment = comment;
  }

  public Calendar getTimestamp() {
    return myTimestamp;
  }

  public void setTimestamp(final Calendar timestamp) {
    checkCurrentOwnerAndComputer();
    myTimestamp = timestamp;
  }

  @Nullable
  public String getOwnerDisplayName() {
    return myOwnerDisplayName;
  }

  @Nullable
  public String getSecurityToken() {
    return mySecurityToken;
  }

  public int getOptions() {
    return myOptions;
  }

  @NotNull
  public List<String> getOwnerAliases() {
    return myOwnerAliases;
  }

  public List<WorkingFolderInfo> getWorkingFolders(Object projectOrComponent) throws TfsException {
    loadFromServer(projectOrComponent, false);
    return getWorkingFoldersCached();
  }

  public List<WorkingFolderInfo> getWorkingFoldersCached() {
    return Collections.unmodifiableList(myWorkingFoldersInfos);
  }

  public void loadFromServer(Object projectOrComponent, boolean force) throws TfsException {
    if (myOriginalName == null || myLoaded || !hasCurrentOwnerAndComputer()) {
      return;
    }

    Workspace workspaceBean = getServer().getVCS().loadWorkspace(getName(), getOwnerName(), projectOrComponent, force);
    if (!hasCurrentOwnerAndComputer()) {
      // owner can now be different if server credentials have been changed while executing this server call
      throw new WorkspaceNotFoundException(TFSBundle.message("workspace.wrong.owner", getName(), getOwnerName()));
    }
    fromBean(workspaceBean, this);
    myLoaded = true;
  }

  boolean hasMappingCached(FilePath localPath, boolean considerChildMappings) {
    return hasMapping(getWorkingFoldersCached(), localPath, considerChildMappings);
  }

  boolean hasMapping(FilePath localPath, boolean considerChildMappings, Object projectOrComponent) throws TfsException {
    // post-check current owner since it might have just been changed dirung getWorkingFolders() call
    return hasMapping(getWorkingFolders(projectOrComponent), localPath, considerChildMappings) && hasCurrentOwnerAndComputer();
  }

  boolean hasCurrentOwnerAndComputer() {
    String owner = getServer().getQualifiedUsername();
    if (owner == null || !owner.equalsIgnoreCase(getOwnerName())) {
      return false;
    }
    if (!Workstation.getComputerName().equalsIgnoreCase(getComputer())) {
      return false;
    }
    return true;
  }

  private void checkCurrentOwnerAndComputer() {
    if (!hasCurrentOwnerAndComputer()) {
      throw new IllegalStateException("Workspace " + getName() + " has other owner");
    }
  }


  /**
   * @param localPath local path to find server path for
   * @return nearest server path according to one of workspace mappings (max 1 if considerChildMappings=false)
   * @throws TfsException in case of error during request to TFS
   */
  // TODO review usage: item can be updated to revision where corresponding server item does not exist
  public Collection<String> findServerPathsByLocalPath(final @NotNull FilePath localPath, boolean considerChildMappings,
                                                       Object projectOrComponent)
    throws TfsException {
    final FilePath localPathOnLocalFileSystem = VcsUtil.getFilePath(localPath.getPath(), localPath.isDirectory());
    final WorkingFolderInfo parentMapping = findNearestParentMapping(localPathOnLocalFileSystem, projectOrComponent);
    if (parentMapping != null) {
      return Collections.singletonList(parentMapping.getServerPathByLocalPath(localPathOnLocalFileSystem));
    }

    if (considerChildMappings) {
      Collection<String> childMappings = new ArrayList<>();
      for (WorkingFolderInfo workingFolder : getWorkingFolders(projectOrComponent)) {
        if (workingFolder.getLocalPath().isUnder(localPathOnLocalFileSystem, false)) {
          childMappings.add(workingFolder.getServerPath());
        }
      }
      return childMappings;
    }
    else {
      return Collections.emptyList();
    }
  }

  @Nullable
  public FilePath findLocalPathByServerPath(final @NotNull String serverPath, final boolean isDirectory,
                                            Object projectOrComponent) throws TfsException {
    final WorkingFolderInfo parentMapping = findNearestParentMapping(serverPath, isDirectory, projectOrComponent);
    return parentMapping != null ? parentMapping.getLocalPathByServerPath(serverPath, isDirectory) : null;
  }

  public boolean hasLocalPathForServerPath(final @NotNull String serverPath, Object projectOrComponent) throws TfsException {
    return findLocalPathByServerPath(serverPath, false, projectOrComponent) != null;
  }

  // TODO inline?

  @Nullable
  private WorkingFolderInfo findNearestParentMapping(final @NotNull FilePath localPath, Object projectOrComponent) throws TfsException {
    WorkingFolderInfo mapping = null;
    for (WorkingFolderInfo folderInfo : getWorkingFolders(projectOrComponent)) {
      if (folderInfo.getServerPathByLocalPath(localPath) != null &&
          (mapping == null || folderInfo.getLocalPath().isUnder(mapping.getLocalPath(), false))) {
        mapping = folderInfo;
      }
    }
    return mapping;
  }

  @Nullable
  private WorkingFolderInfo findNearestParentMapping(final @NotNull String serverPath, boolean isDirectory, Object projectOrComponent)
    throws TfsException {
    WorkingFolderInfo mapping = null;
    for (WorkingFolderInfo folderInfo : getWorkingFolders(projectOrComponent)) {
      if (folderInfo.getLocalPathByServerPath(serverPath, isDirectory) != null &&
          (mapping == null || VersionControlPath.isUnder(mapping.getServerPath(), folderInfo.getServerPath()))) {
        mapping = folderInfo;
      }
    }
    return mapping;
  }


  public void addWorkingFolderInfo(final WorkingFolderInfo workingFolderInfo) {
    myWorkingFoldersInfos.add(workingFolderInfo);
  }

  public void addOwnerAlias(@NotNull String alias) {
    myOwnerAliases.add(alias);
  }

  public void removeWorkingFolderInfo(final WorkingFolderInfo folderInfo) {
    checkCurrentOwnerAndComputer();
    myWorkingFoldersInfos.remove(folderInfo);
  }

  public void setWorkingFolders(final List<WorkingFolderInfo> workingFolders) {
    checkCurrentOwnerAndComputer();
    myWorkingFoldersInfos.clear();
    myWorkingFoldersInfos.addAll(workingFolders);
  }

  public void saveToServer(Object projectOrComponent, WorkspaceInfo originalWorkspace) throws TfsException {
    checkCurrentOwnerAndComputer();
    if (myOriginalName != null) {
      getServer().getVCS().updateWorkspace(myOriginalName, toBean(this), projectOrComponent, true);
      getServer().replaceWorkspace(originalWorkspace, this);
    }
    else {
      // TODO: refactor
      getServer().getVCS().createWorkspace(toBean(this), projectOrComponent);
      getServer().addWorkspaceInfo(this);
    }
    myOriginalName = getName();
    Workstation.getInstance().update();
  }

  private static Workspace toBean(WorkspaceInfo info) {
    final ArrayOfWorkingFolder folders = new ArrayOfWorkingFolder();
    List<WorkingFolderInfo> workingFolders = info.getWorkingFoldersCached();
    List<WorkingFolder> foldersList = new ArrayList<>(workingFolders.size());
    for (WorkingFolderInfo folderInfo : workingFolders) {
      foldersList.add(toBean(folderInfo));
    }
    folders.setWorkingFolder(foldersList.toArray(new WorkingFolder[foldersList.size()]));

    Workspace bean = new Workspace();
    bean.setComment(info.getComment());
    bean.setComputer(info.getComputer());
    bean.setFolders(folders);
    bean.setLastAccessDate(info.getTimestamp());
    bean.setName(info.getName());
    bean.setOwner(info.getOwnerName());
    bean.setIslocal(info.isLocal());
    bean.setOwnerdisp(info.myOwnerDisplayName);
    bean.setOwnerAliases(TfsUtil.toArrayOfString(ContainerUtil.nullize(info.myOwnerAliases)));
    bean.setSecuritytoken(info.mySecurityToken);
    bean.setOptions(info.myOptions);
    return bean;
  }

  @NotNull
  private static WorkingFolder toBean(final WorkingFolderInfo folderInfo) {
    WorkingFolder bean = new WorkingFolder();
    bean.setItem(folderInfo.getServerPath());
    bean.setLocal(VersionControlPath.toTfsRepresentation(folderInfo.getLocalPath()));
    bean.setType(folderInfo.getStatus() == WorkingFolderInfo.Status.Cloaked ? WorkingFolderType.Cloak : WorkingFolderType.Map);
    return bean;
  }

  @Nullable
  private static WorkingFolderInfo fromBean(WorkingFolder bean) {
    WorkingFolderInfo.Status status =
      WorkingFolderType.Cloak.equals(bean.getType()) ? WorkingFolderInfo.Status.Cloaked : WorkingFolderInfo.Status.Active;
    if (bean.getLocal() != null) {
      //noinspection ConstantConditions
      return new WorkingFolderInfo(status, VersionControlPath.getFilePath(bean.getLocal(), true), bean.getItem());
    }
    else {
      TFSVcs.LOG.info("null local folder mapping for " + bean.getItem());
      return null;
    }
  }

  static void fromBean(Workspace bean, WorkspaceInfo workspace) {
    workspace.myOriginalName = bean.getName();
    workspace.setLocation(Location.from(bean.getIslocal()));
    workspace.setComment(bean.getComment());
    workspace.setTimestamp(bean.getLastAccessDate());
    workspace.myOwnerDisplayName = bean.getOwnerdisp();
    workspace.myOwnerAliases.clear();
    if (bean.getOwnerAliases() != null) {
      ContainerUtil.addAll(workspace.myOwnerAliases, bean.getOwnerAliases().getString());
    }
    workspace.mySecurityToken = bean.getSecuritytoken();
    workspace.myOptions = bean.getOptions();
    final WorkingFolder[] folders;
    if (bean.getFolders().getWorkingFolder() != null) {
      folders = bean.getFolders().getWorkingFolder();
    }
    else {
      folders = new WorkingFolder[0];
    }
    List<WorkingFolderInfo> workingFoldersInfos = new ArrayList<>(folders.length);
    for (WorkingFolder folderBean : folders) {
      WorkingFolderInfo folderInfo = fromBean(folderBean);
      if (folderInfo != null) {
        workingFoldersInfos.add(folderInfo);
      }
    }
    workspace.myWorkingFoldersInfos = workingFoldersInfos;
  }

  public WorkspaceInfo getCopy() {
    WorkspaceInfo copy = new WorkspaceInfo(myServerInfo, myOwnerName, myComputer);
    copy.myLocation = myLocation;
    copy.myComment = myComment;
    copy.myLoaded = myLoaded;
    copy.myOriginalName = myOriginalName;
    copy.myModifiedName = myModifiedName;
    copy.myTimestamp = myTimestamp;
    copy.myOwnerDisplayName = myOwnerDisplayName;
    copy.myOwnerAliases.addAll(myOwnerAliases);
    copy.mySecurityToken = mySecurityToken;
    copy.myOptions = myOptions;

    for (WorkingFolderInfo workingFolder : myWorkingFoldersInfos) {
      copy.myWorkingFoldersInfos.add(workingFolder.getCopy());
    }
    return copy;
  }

  public Map<FilePath, ExtendedItem> getExtendedItems2(final List<ItemPath> paths, Object projectOrComponent, String progressTitle)
    throws TfsException {
    return getServer().getVCS()
      .getExtendedItems(getName(), getOwnerName(), TfsUtil.getLocalPaths(paths), DeletedState.Any, projectOrComponent, progressTitle);
  }

  public Map<FilePath, ExtendedItem> getExtendedItems(final List<FilePath> paths, Object projectOrComponent, String progressTitle)
    throws TfsException {
    return getServer().getVCS().getExtendedItems(getName(), getOwnerName(), paths, DeletedState.Any, projectOrComponent, progressTitle);
  }

  public static boolean isValidName(String name) {
    for (String invalid : WORKSPACE_NAME_INVALID_CHARS) {
      if (name.contains(invalid)) {
        return false;
      }
    }
    for (String invalidEnd : WORKSPACE_NAME_INVALID_ENDING_CHARS) {
      if (name.endsWith(invalidEnd)) {
        return false;
      }
    }
    return true;
  }

  private static boolean hasMapping(Collection<WorkingFolderInfo> mappings, FilePath localPath, boolean considerChildMappings) {
    final FilePath localPathOnLocalFileSystem = VcsUtil.getFilePath(localPath.getPath(), localPath.isDirectory());
    for (WorkingFolderInfo mapping : mappings) {
      if (localPathOnLocalFileSystem.isUnder(mapping.getLocalPath(), false) ||
          (considerChildMappings && mapping.getLocalPath().isUnder(localPathOnLocalFileSystem, false))) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings({"HardCodedStringLiteral"})
  public String toString() {
    return "WorkspaceInfo[server=" +
           getServer().getUri() +
           ",name=" +
           getName() +
           ",owner=" +
           getOwnerName() +
           ",computer=" +
           getComputer() +
           ",comment=" +
           getComment() +
           "]";
  }

  public enum Location {
    LOCAL,
    SERVER;

    @NotNull
    public static Location from(boolean isLocal) {
      return isLocal ? LOCAL : SERVER;
    }
  }
}