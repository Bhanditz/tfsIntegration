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

import com.intellij.ui.DocumentAdapter;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.core.tfs.TfsUtil;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.net.URI;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;

public class ProxySettingsForm {

  private JPanel myContentPane;

  public interface Listener {
    void stateChanged();
  }

  private JRadioButton myNoProxyRadioButton;
  private JRadioButton myProxyServerRadioButton;
  private JTextField myProxyServerTextField;
  private JLabel myMessageLabel;
  private JLabel myInfoLabel;
  private JLabel myProxyUrlLabel;

  private final List<Listener> myListeners = new ArrayList<Listener>();

  public ProxySettingsForm(@Nullable URI initialProxyUri, @Nullable String serverQualifiedUsername) {
    if (initialProxyUri == null) {
      myNoProxyRadioButton.setSelected(true);
    }
    else {
      myProxyServerRadioButton.setSelected(true);
      myProxyServerTextField.setText(initialProxyUri.toString());
    }

    final ActionListener radioButtonListener = new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        updateContols();
        fireStateChanged();
      }
    };

    myNoProxyRadioButton.addActionListener(radioButtonListener);
    myProxyServerRadioButton.addActionListener(radioButtonListener);
    myProxyServerTextField.getDocument().addDocumentListener(new DocumentAdapter() {
      protected void textChanged(final DocumentEvent e) {
        fireStateChanged();
      }
    });

    String infoMessage = MessageFormat
      .format("Credentials to connect to the proxy: {0}", serverQualifiedUsername != null ? serverQualifiedUsername : "(not specified)");
    myInfoLabel.setText(infoMessage);

    updateContols();
  }

  private void updateContols() {
    myProxyServerTextField.setEnabled(myProxyServerRadioButton.isSelected());
    myInfoLabel.setEnabled(myProxyServerRadioButton.isSelected());
    myProxyUrlLabel.setEnabled(myProxyServerRadioButton.isSelected());

    if (myProxyServerRadioButton.isSelected()) {
      myProxyServerTextField.requestFocus();
    }
  }

  public JComponent getContentPane() {
    return myContentPane;
  }

  private void fireStateChanged() {
    Listener[] listeners = myListeners.toArray(new Listener[myListeners.size()]);
    for (Listener listener : listeners) {
      listener.stateChanged();
    }
  }

  public void addListener(final Listener listener) {
    myListeners.add(listener);
  }

  public void removeListener(final Listener listener) {
    myListeners.remove(listener);
  }

  public boolean isValid() {
    return myNoProxyRadioButton.isSelected() || TfsUtil.getHostUri(myProxyServerTextField.getText(), true) != null;
  }

  @Nullable
  public URI getProxyUri() {
    if (myNoProxyRadioButton.isSelected()) {
      return null;
    }
    else {
      return TfsUtil.getHostUri(myProxyServerTextField.getText(), true);
    }
  }

  public void setMessage(@Nullable String message) {
    myMessageLabel.setText(message);
  }

}