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

package org.jetbrains.tfsIntegration.core.configuration;

import com.intellij.notification.Notification;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.Notifications;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.PersistentStateComponent;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.components.State;
import com.intellij.openapi.components.Storage;
import com.intellij.openapi.project.Project;
import com.intellij.util.xmlb.annotations.MapAnnotation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.config.TfsServerConnectionHelper;
import org.jetbrains.tfsIntegration.core.TFSBundle;
import org.jetbrains.tfsIntegration.core.tfs.ServerInfo;
import org.jetbrains.tfsIntegration.core.tfs.TfsUtil;
import org.jetbrains.tfsIntegration.core.tfs.Workstation;
import org.jetbrains.tfsIntegration.exceptions.TfsException;

import javax.swing.event.HyperlinkEvent;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.HashMap;
import java.util.Map;

@State(
  name = "org.jetbrains.tfsIntegration.core.configuration.TFSConfigurationManager",
  storages = {@Storage("tfs.xml")})
public class TFSConfigurationManager implements PersistentStateComponent<TFSConfigurationManager.State> {
  private static final String TFS_NOTIFICATION_GROUP = "TFS";

  public static class State {

    @MapAnnotation(entryTagName = "server", keyAttributeName = "uri", surroundValueWithTag = false)
    public Map<String, ServerConfiguration> config = new HashMap<>();

    public boolean useIdeaHttpProxy = true;
    public boolean supportTfsCheckinPolicies = true;
    public boolean supportStatefulCheckinPolicies = true;
    public boolean reportNotInstalledCheckinPolicies = true;

  }

  private Map<String, ServerConfiguration> myServersConfig = new HashMap<>();
  private boolean myUseIdeaHttpProxy = true;
  private boolean mySupportTfsCheckinPolicies = true;
  private boolean mySupportStatefulCheckinPolicies = true;
  private boolean myReportNotInstalledCheckinPolicies = true;

  @NotNull
  public static synchronized TFSConfigurationManager getInstance() {
    return ServiceManager.getService(TFSConfigurationManager.class);
  }

  /**
   * @return null if not found
   */
  @Nullable
  public synchronized Credentials getCredentials(@NotNull URI serverUri) {
    final ServerConfiguration serverConfiguration = getConfiguration(serverUri);
    return serverConfiguration != null ? serverConfiguration.getCredentials() : null;
  }

  public synchronized boolean isAuthCanceled(URI serverUri) {
    final ServerConfiguration serverConfiguration = getConfiguration(serverUri);
    return serverConfiguration != null && serverConfiguration.getAuthCanceledNotification() != null;
  }

  public synchronized void setAuthCanceled(final URI serverUri, @Nullable final Object projectOrComponent) {
    final ServerConfiguration serverConfiguration = getOrCreateServerConfiguration(serverUri);
    if (serverConfiguration.getAuthCanceledNotification() != null) {
      return;
    }

    final Project project = projectOrComponent instanceof Project ? (Project)projectOrComponent : null;
    final Notification notification = new Notification(TFS_NOTIFICATION_GROUP,
                                                       TFSBundle.message("notification.auth.canceled.title",
                                                                         TfsUtil.getPresentableUri(serverUri)),
                                                       TFSBundle.message("notification.auth.canceled.text"),
                                                       NotificationType.ERROR, new NotificationListener() {
        @Override
        public void hyperlinkUpdate(@NotNull Notification notification, @NotNull HyperlinkEvent event) {
          try {
            TfsServerConnectionHelper.ensureAuthenticated(project, serverUri, true);
            notification.expire();
          }
          catch (TfsException e) {
            // ignore
          }
        }
      });
    serverConfiguration.setAuthCanceledNotification(notification);

    // notification should be application-wide not to be hidden on project close
    Notifications.Bus.notify(notification, null);
  }

  @Nullable
  public URI getProxyUri(@NotNull URI serverUri) {
    final ServerConfiguration serverConfiguration = getConfiguration(serverUri);
    try {
      return serverConfiguration != null && serverConfiguration.getProxyUri() != null ? new URI(serverConfiguration.getProxyUri()) : null;
    }
    catch (URISyntaxException e) {
      throw new RuntimeException(e);
    }
  }

  public boolean shouldTryProxy(@NotNull URI serverUri) {
    final ServerConfiguration serverConfiguration = getConfiguration(serverUri);
    return serverConfiguration != null && serverConfiguration.getProxyUri() != null && !serverConfiguration.isProxyInaccessible();
  }

  public void setProxyInaccessible(@NotNull URI serverUri) {
    getConfiguration(serverUri).setProxyInaccessible();
  }

  public void setProxyUri(@NotNull URI serverUri, @Nullable URI proxyUri) {
    String proxyUriString = proxyUri != null ? proxyUri.toString() : null;
    getOrCreateServerConfiguration(serverUri).setProxyUri(proxyUriString);
  }

  public synchronized void storeCredentials(@NotNull URI serverUri, final @NotNull Credentials credentials) {
    ServerConfiguration serverConfiguration = getOrCreateServerConfiguration(serverUri);
    serverConfiguration.setCredentials(credentials);
    final Notification notification = serverConfiguration.getAuthCanceledNotification();
    if (notification != null) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          notification.expire();
        }
      });
    }
    serverConfiguration.setAuthCanceledNotification(null);
  }

  public synchronized void resetStoredPasswords() {
    for (ServerConfiguration serverConfiguration : myServersConfig.values()) {
      final Credentials credentials = serverConfiguration.getCredentials();
      if (credentials != null) {
        credentials.resetPassword();
      }
    }
  }

  public void loadState(final State state) {
    myServersConfig = state.config;
    myUseIdeaHttpProxy = state.useIdeaHttpProxy;
    mySupportTfsCheckinPolicies = state.supportTfsCheckinPolicies;
    mySupportStatefulCheckinPolicies = state.supportStatefulCheckinPolicies;
    myReportNotInstalledCheckinPolicies = state.reportNotInstalledCheckinPolicies;
  }

  public State getState() {
    final State state = new State();
    state.config = myServersConfig;
    state.supportStatefulCheckinPolicies = mySupportStatefulCheckinPolicies;
    state.supportTfsCheckinPolicies = mySupportTfsCheckinPolicies;
    state.useIdeaHttpProxy = myUseIdeaHttpProxy;
    state.reportNotInstalledCheckinPolicies = myReportNotInstalledCheckinPolicies;
    return state;
  }

  private static String getConfigKey(URI serverUri) {
    String uriString = serverUri.toString();
    if (!uriString.endsWith("/")) {
      // backward compatibility
      uriString += "/";
    }
    return uriString;
  }

  @Nullable
  private ServerConfiguration getConfiguration(URI serverUri) {
    return myServersConfig.get(getConfigKey(serverUri));
  }

  @NotNull
  private ServerConfiguration getOrCreateServerConfiguration(@NotNull URI serverUri) {
    ServerConfiguration config = myServersConfig.get(getConfigKey(serverUri));
    if (config == null) {
      config = new ServerConfiguration();
      myServersConfig.put(getConfigKey(serverUri), config);
    }
    return config;
  }

  public boolean serverKnown(final @NotNull String instanceId) {
    for (ServerInfo server : Workstation.getInstance().getServers()) {
      if (server.getGuid().equalsIgnoreCase(instanceId)) {
        return true;
      }
    }
    return false;
  }

  public void remove(final @NotNull URI serverUri) {
    final ServerConfiguration config = myServersConfig.get(getConfigKey(serverUri));
    if (config != null && config.getAuthCanceledNotification() != null) {
      ApplicationManager.getApplication().invokeLater(new Runnable() {
        @Override
        public void run() {
          config.getAuthCanceledNotification().expire();
        }
      });
    }
    myServersConfig.remove(getConfigKey(serverUri));
  }

  public void setUseIdeaHttpProxy(boolean useIdeaHttpProxy) {
    myUseIdeaHttpProxy = useIdeaHttpProxy;
  }

  public boolean useIdeaHttpProxy() {
    return myUseIdeaHttpProxy;
  }

  public TfsCheckinPoliciesCompatibility getCheckinPoliciesCompatibility() {
    return new TfsCheckinPoliciesCompatibility(mySupportStatefulCheckinPolicies, mySupportTfsCheckinPolicies,
                                               myReportNotInstalledCheckinPolicies);
  }

  public void setSupportTfsCheckinPolicies(boolean supportTfsCheckinPolicies) {
    mySupportTfsCheckinPolicies = supportTfsCheckinPolicies;
  }

  public void setSupportStatefulCheckinPolicies(boolean supportStatefulCheckinPolicies) {
    mySupportStatefulCheckinPolicies = supportStatefulCheckinPolicies;
  }

  public void setReportNotInstalledCheckinPolicies(boolean reportNotInstalledCheckinPolicies) {
    myReportNotInstalledCheckinPolicies = reportNotInstalledCheckinPolicies;
  }
}
