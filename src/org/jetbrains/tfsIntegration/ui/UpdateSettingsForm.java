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

package org.jetbrains.tfsIntegration.ui;

import com.intellij.openapi.options.ConfigurationException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.wm.IdeFocusManager;
import com.intellij.ui.CollectionListModel;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.tfsIntegration.core.TFSProjectConfiguration;
import org.jetbrains.tfsIntegration.core.tfs.WorkspaceInfo;
import org.jetbrains.tfsIntegration.core.tfs.version.LatestVersionSpec;
import org.jetbrains.tfsIntegration.core.tfs.version.VersionSpecBase;

import javax.swing.*;
import javax.swing.event.ListSelectionEvent;
import javax.swing.event.ListSelectionListener;
import java.awt.*;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class UpdateSettingsForm {

  public static class WorkspaceSettings {
    public final String serverPath;
    public final boolean isDirectory;
    public VersionSpecBase version = LatestVersionSpec.INSTANCE;

    public WorkspaceSettings(final @NotNull String serverPath, final boolean isDirectory) {
      this.serverPath = serverPath;
      this.isDirectory = isDirectory;
    }
  }

  private final Map<WorkspaceInfo, WorkspaceSettings> myWorkspaceSettings;

  private JPanel myPanel;
  private JCheckBox myRecursiveBox;
  private JList myWorkspacesList;
  private SelectRevisionForm mySelectRevisionForm;
  @SuppressWarnings({"UnusedDeclaration"})
  private JPanel myWorkspaceSettingsPanel;
  private WorkspaceInfo mySelectedWorkspace;

  public UpdateSettingsForm(final Project project, final String title, final Map<WorkspaceInfo, WorkspaceSettings> workspaceSettings) {
    myWorkspaceSettings = workspaceSettings;
    List<WorkspaceInfo> workspaces = new ArrayList<>(myWorkspaceSettings.keySet());
    Collections.sort(workspaces, (o1, o2) -> o1.getName().compareTo(o2.getName()));

    myWorkspacesList.setModel(new CollectionListModel(workspaces));
    myWorkspacesList.getSelectionModel().setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

    myWorkspacesList.setCellRenderer(new DefaultListCellRenderer() {
      @Override
      public Component getListCellRendererComponent(final JList list,
                                                    final Object value,
                                                    final int index,
                                                    final boolean isSelected,
                                                    final boolean cellHasFocus) {
        final Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
        final WorkspaceInfo workspace = ((WorkspaceInfo)value);
        String label = MessageFormat.format("{0} [{1}]", workspace.getName(), workspace.getServer().getPresentableUri());
        setText(label);
        return c;
      }
    });

    myWorkspacesList.addListSelectionListener(new ListSelectionListener() {
      @Override
      public void valueChanged(ListSelectionEvent e) {
        try {
          applyCurrentValue();
        }
        catch (ConfigurationException ex) {
          Messages.showErrorDialog(project, ex.getMessage(), title);
        }

        mySelectedWorkspace = ((WorkspaceInfo)myWorkspacesList.getSelectedValue());
        if (mySelectedWorkspace != null) {
          final WorkspaceSettings workspaceSettings = myWorkspaceSettings.get(mySelectedWorkspace);
          mySelectRevisionForm.init(project, mySelectedWorkspace, workspaceSettings.serverPath, workspaceSettings.isDirectory);
          mySelectRevisionForm.setVersionSpec(workspaceSettings.version);
        }
        else {
          mySelectRevisionForm.disable();
        }
      }
    });

    if (workspaces.isEmpty()) {
      mySelectRevisionForm.disable();
    }
    else {
      myWorkspacesList.setSelectedIndex(0);
      IdeFocusManager.getGlobalInstance().doWhenFocusSettlesDown(() -> {
        IdeFocusManager.getGlobalInstance().requestFocus(myWorkspacesList, true);
      });
    }
  }

  private void applyCurrentValue() throws ConfigurationException {
    if (mySelectedWorkspace != null) {
      VersionSpecBase version = mySelectRevisionForm.getVersionSpec();
      if (version != null) {
        myWorkspaceSettings.get(mySelectedWorkspace).version = version;
      }
      else {
        throw new ConfigurationException("Invalid version specified");
      }
    }
  }


  public void reset(final TFSProjectConfiguration configuration) {
    myRecursiveBox.setSelected(configuration.getState().UPDATE_RECURSIVELY);

    for (Map.Entry<WorkspaceInfo, WorkspaceSettings> e : myWorkspaceSettings.entrySet()) {
      e.getValue().version = configuration.getUpdateWorkspaceInfo(e.getKey()).getVersion();
    }
  }

  public void apply(final TFSProjectConfiguration configuration) throws ConfigurationException {
    applyCurrentValue();
    configuration.getState().UPDATE_RECURSIVELY = myRecursiveBox.isSelected();

    for (Map.Entry<WorkspaceInfo, WorkspaceSettings> e : myWorkspaceSettings.entrySet()) {
      configuration.getUpdateWorkspaceInfo(e.getKey()).setVersion(e.getValue().version);
    }
  }

  public JComponent getPanel() {
    return myPanel;
  }

}
