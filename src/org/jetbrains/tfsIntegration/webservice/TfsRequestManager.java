package org.jetbrains.tfsIntegration.webservice;

import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.ClassLoaderUtil;
import com.intellij.openapi.util.Condition;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.ThrowableComputable;
import com.intellij.util.WaitForProgressToShow;
import com.intellij.util.concurrency.Semaphore;
import org.apache.commons.httpclient.HttpStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.tfsIntegration.config.TfsServerConnectionHelper;
import org.jetbrains.tfsIntegration.core.TFSBundle;
import org.jetbrains.tfsIntegration.core.configuration.Credentials;
import org.jetbrains.tfsIntegration.core.configuration.TFSConfigurationManager;
import org.jetbrains.tfsIntegration.exceptions.*;
import org.jetbrains.tfsIntegration.ui.TfsLoginDialog;

import javax.swing.*;
import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class TfsRequestManager {

  public static abstract class Request<T> {
    private final String myProgressTitle;

    public Request(String progressTitle) {
      myProgressTitle = progressTitle;
    }

    public abstract T execute(Credentials credentials, URI serverUri, @Nullable ProgressIndicator pi) throws Exception;

    @NotNull
    public String getProgressTitle(Credentials credentials, URI serverUri) {
      return myProgressTitle;
    }

    public boolean retrieveAuthorizedCredentials() {
      return true;
    }
  }

  private static final long POLL_TIMEOUT = 200; //ms

  private static final Map<URI, TfsRequestManager> ourInstances = new HashMap<>();
  private static final Logger LOG = Logger.getInstance(TfsRequestManager.class.getName());

  @Nullable
  private final URI myServerUri; // null new when adding new server

  // shared lock to avoid showing login dialog for several servers at the same time
  private static final ReentrantLock ourShowDialogLock = new ReentrantLock();

  // Don't make several requests to the same server simultaneously.
  // Indeed, this way we don't protect from simultaneous request to the existing server when trying to add it as a new one
  // (finally and getting 'duplicate server' error), but I believe it won't hurt
  private final ReentrantLock myRequestLock = new ReentrantLock();

  private TfsRequestManager(URI serverUri) {
    myServerUri = serverUri;
  }

  public static synchronized TfsRequestManager getInstance(@Nullable URI serverUri) {
    TfsRequestManager result = ourInstances.get(serverUri);
    if (result == null) {
      result = new TfsRequestManager(serverUri);
      ourInstances.put(serverUri, result);
    }
    return result;
  }

  /**
   * @param request
   * @param <T>
   * @return
   * @throws TfsException on error
   */
  public <T> T executeRequestInBackground(final Object projectOrComponent, final boolean force, final Request<T> request)
    throws TfsException {
    LOG.assertTrue(!ApplicationManager.getApplication().isDispatchThread());
    LOG.assertTrue(myServerUri != null);

    boolean showDialog = shouldShowDialog(force);
    final Ref<String> message = new Ref<>();
    final Ref<Credentials> credentials = new Ref<>(TFSConfigurationManager.getInstance().getCredentials(myServerUri));

    while (true) {
      if (showDialog || !message.isNull()) {
        try {
          ourShowDialogLock.lock();
          ProgressManager.checkCanceled();
          showDialog = shouldShowDialog(force); // check again since another thread could already enter right credentials
          // TODO we probably have to compare original password and current one
          if (!message.isNull() || showDialog) {
            final Ref<Boolean> ok = new Ref<>();
            Runnable showDialogRunnable = new Runnable() {
              @Override
              public void run() {
                if (message.isNull()) {
                  try {
                    if (!shouldShowDialog(force)) {
                      ok.set(true);
                      return; // check one more time since UI thread call could already enter right credentials
                    }
                  }
                  catch (UserCancelledException e) {
                    ok.set(false);
                    return;
                  }
                }
                TfsLoginDialog d;
                if (projectOrComponent instanceof JComponent) {
                  d = new TfsLoginDialog((JComponent)projectOrComponent, myServerUri, credentials.get(), false, null);
                }
                else {
                  d = new TfsLoginDialog((Project)projectOrComponent, myServerUri, credentials.get(), false, null);
                }
                d.setMessage(message.get(), false);
                if (d.showAndGet()) {
                  credentials.set(d.getCredentials());
                  ok.set(true);
                }
                else {
                  ok.set(false);
                }
              }
            };
            final ProgressIndicator pi = ProgressManager.getInstance().getProgressIndicator();
            if (pi != null) {
              WaitForProgressToShow.execute(pi);
            }
            ApplicationManager.getApplication().invokeAndWait(showDialogRunnable);
            if (!ok.get()) {
              if (!force) {
                TFSConfigurationManager.getInstance().setAuthCanceled(myServerUri, projectOrComponent);
              }
              throw new AuthCancelledException(myServerUri);
            }
          }
          else {
            credentials.set(TFSConfigurationManager.getInstance().getCredentials(myServerUri));
          }
        }
        finally {
          ourShowDialogLock.unlock();
        }
      }
      LOG.assertTrue(!credentials.isNull());
      try {
        myRequestLock.lock();
        ProgressManager.checkCanceled();
        ProgressIndicator pi = ProgressManager.getInstance().getProgressIndicator();
        T result = executeRequestImpl(myServerUri, credentials, request, pi);
        TFSConfigurationManager.getInstance().storeCredentials(myServerUri, credentials.get());
        return result;
      }
      catch (Exception e) {
        final TfsException tfsException = TfsExceptionManager.processException(e);
        LOG.warn(tfsException);
        if (tfsException instanceof UnauthorizedException) {
          message.set(getMessage(tfsException, credentials.get().getType()));
          continue;
        }
        else if (!(tfsException instanceof ConnectionFailedException)) {
          TFSConfigurationManager.getInstance().storeCredentials(myServerUri, credentials.get());
        }
        throw tfsException;
      }
      finally {
        myRequestLock.unlock();
      }
    }
  }

  private class ExecuteSession<T> implements Runnable {
    private final Ref<Credentials> myCredentials;
    private final Object myProjectOrComponent;
    private final Request<T> myRequest;
    private final URI myCurrentServerUri;

    private T myResult;
    private TfsException myError;

    public ExecuteSession(Credentials credentials,
                          Object projectOrComponent,
                          final Request<T> request,
                          URI currentServerUri) {
      myCredentials = Ref.create(credentials);
      myProjectOrComponent = projectOrComponent;
      myRequest = request;
      myCurrentServerUri = currentServerUri;
    }

    public TfsException getError() {
      return myError;
    }

    public Credentials getCredentials() {
      return myCredentials.get();
    }

    @Override
    public void run() {
      final ProgressIndicator pi = ProgressManager.getInstance().getProgressIndicator();

      final Semaphore done = new Semaphore();
      final Runnable innerRunnable = new Runnable() {
        @Override
        public void run() {
          try {
            myRequestLock.lock();
            myResult = executeRequestImpl(myCurrentServerUri, myCredentials, myRequest, pi);
          }
          catch (Exception e) {
            LOG.warn(e);
            myError = TfsExceptionManager.processException(e);
          }
          finally {
            myRequestLock.unlock();
            done.up();
          }
        }
      };

      pi.setIndeterminate(true);
      done.down();
      ApplicationManager.getApplication().executeOnPooledThread(innerRunnable);
      while (!done.waitFor(POLL_TIMEOUT)) {
        if (pi.isCanceled()) {
          break;
        }
      }
    }

    /**
     * @return true if succeeded, false if cancelled
     */
    public boolean execute() {
      Project project = myProjectOrComponent instanceof Project ? (Project)myProjectOrComponent : null;
      JComponent component = myProjectOrComponent instanceof JComponent ? (JComponent)myProjectOrComponent : null;
      return ProgressManager.getInstance()
        .runProcessWithProgressSynchronously(this, myRequest.getProgressTitle(myCredentials.get(), myCurrentServerUri), true, project,
                                             component);
    }
  }

  public static <T> T executeRequest(URI serverUri, Object projectOrComponent, final Request<T> request) throws TfsException {
    return executeRequest(serverUri, projectOrComponent, false, request);
  }

  public static <T> T executeRequest(URI serverUri, Object projectOrComponent, boolean force, final Request<T> request)
    throws TfsException {
    if (ApplicationManager.getApplication().isDispatchThread()) {
      return getInstance(serverUri).executeRequestInForeground(projectOrComponent, false, null, force, request);
    }
    else {
      return getInstance(serverUri).executeRequestInBackground(projectOrComponent, force, request);
    }
  }

  public <T> T executeRequestInForeground(Object projectOrComponent,
                                          boolean reportErrorsInDialog,
                                          @Nullable Credentials overrideCredentials,
                                          boolean force,
                                          final Request<T> request)
    throws TfsException {
    return executeRequestInForeground(projectOrComponent, request, null, reportErrorsInDialog, overrideCredentials, force);
  }

  private <T> T executeRequestInForeground(Object projectOrComponent,
                                           final Request<T> request,
                                           @Nullable String errorMessage,
                                           final boolean reportErrorsInDialog,
                                           @Nullable Credentials overrideCredentials,
                                           boolean force)
    throws TfsException {
    LOG.assertTrue(ApplicationManager.getApplication().isDispatchThread());

    final Ref<T> result = new Ref<>();
    final Ref<TfsException> fatalError = new Ref<>();
    if (errorMessage != null || overrideCredentials == null && shouldShowDialog(force)) {
      final Ref<Credentials> credentials =
        new Ref<>(overrideCredentials != null
                  ? overrideCredentials
                  : myServerUri != null ? TFSConfigurationManager.getInstance().getCredentials(myServerUri) : null);

      // show the dialog first, then run in modal progress over it
      Condition<TfsLoginDialog> condition = new Condition<TfsLoginDialog>() {
        @Override
        public boolean value(TfsLoginDialog dialog) {
          ExecuteSession<T> session = new ExecuteSession<>(dialog.getCredentials(), dialog.getContentPane(), request,
                                                           dialog.getUri());
          if (!session.execute()) {
            return false;
          }

          TfsException error = session.getError();
          if (error != null) {
            if (error instanceof UnauthorizedException || myServerUri == null || reportErrorsInDialog) {
              // continue with the dialog
              dialog.setMessage(getMessage(error, dialog.getCredentials().getType()), false);
              return false;
            }
            else {
              fatalError.set(error);
              if (!(error instanceof ConnectionFailedException)) {
                // we've connected succsesfully, so it's time to store right credentials
                TFSConfigurationManager.getInstance().storeCredentials(dialog.getUri(), session.getCredentials());
              }
            }
          }
          else {
            TFSConfigurationManager.getInstance().storeCredentials(dialog.getUri(), session.getCredentials());
            result.set(session.myResult);
          }
          return true;
        }
      };

      TfsLoginDialog d;
      if (projectOrComponent instanceof JComponent) {
        d = new TfsLoginDialog((JComponent)projectOrComponent, myServerUri, credentials.get(), myServerUri == null, condition);
      }
      else {
        d = new TfsLoginDialog((Project)projectOrComponent, myServerUri, credentials.get(), myServerUri == null, condition);
      }

      if (errorMessage != null) {
        d.setMessage(errorMessage, false);
      }
      if (d.showAndGet()) {
        if (fatalError.isNull()) {
          return result.get();
        }
        else {
          throw fatalError.get();
        }
      }
      else {
        if (!force && myServerUri != null) {
          TFSConfigurationManager.getInstance().setAuthCanceled(myServerUri, projectOrComponent);
        }
        throw new AuthCancelledException(myServerUri);
      }
    }

    // run the progress, show the dialog if error occurs
    LOG.assertTrue(myServerUri != null);

    Credentials credentials =
      overrideCredentials != null ? overrideCredentials : TFSConfigurationManager.getInstance().getCredentials(myServerUri);
    ExecuteSession<T> session = new ExecuteSession<>(credentials, projectOrComponent, request, myServerUri);
    if (!session.execute()) {
      throw new UserCancelledException();
    }

    TfsException error = session.getError();
    if (error instanceof UnauthorizedException) {
      return executeRequestInForeground(projectOrComponent, request, getMessage(error, credentials.getType()), reportErrorsInDialog,
                                        overrideCredentials, force);
    }

    TFSConfigurationManager.getInstance().storeCredentials(myServerUri, session.getCredentials());

    if (error == null) {
      return session.myResult;
    }
    else {
      throw error;
    }
  }

  private static String getMessage(final TfsException error, final Credentials.Type type) {
    if (error instanceof ConnectionFailedException &&
        ((ConnectionFailedException)error).getHttpStatusCode() == HttpStatus.SC_MOVED_TEMPORARILY) {
      if (type == Credentials.Type.Alternate) {
        return TFSBundle.message("unauthorized");
      }
      else {
        return TFSBundle.message("consider.using.alternate.credentials", error.getMessage());
      }
    }
    return error.getMessage();
  }

  private boolean shouldShowDialog(boolean force) throws UserCancelledException {
    if (myServerUri == null) {
      return true;
    }

    if (!force && TFSConfigurationManager.getInstance().isAuthCanceled(myServerUri)) {
      throw new AuthCancelledException(myServerUri);
    }
    // TODO current credentials may be different if another thread changed them in background
    return shouldShowLoginDialog(myServerUri);
  }

  public static boolean shouldShowLoginDialog(URI serverUri) {
    Credentials credentials = TFSConfigurationManager.getInstance().getCredentials(serverUri);
    return credentials == null ||
           credentials.shouldShowLoginDialog() ||
           TfsLoginDialog.shouldPromptForProxyPassword(true);
  }

  private static <T> T executeRequestImpl(final URI serverUri,
                                          final Ref<Credentials> credentialsRef,
                                          final Request<T> request,
                                          final ProgressIndicator pi)
    throws Exception {
    return ClassLoaderUtil.runWithClassLoader(TfsRequestManager.class.getClassLoader(), new ThrowableComputable<T, Exception>() {
      @Override
      public T compute() throws Exception {
        Credentials credentials = credentialsRef.get();
        boolean needsAuthentication =
          credentials == null ||
          request.retrieveAuthorizedCredentials() && (credentials.getUserName().length() == 0 || credentials.getDomain().length() == 0);
        if (needsAuthentication) {
          TfsServerConnectionHelper.ServerDescriptor descriptor =
            TfsServerConnectionHelper.connect(serverUri, credentialsRef.get(), true, pi);
          credentialsRef.set(descriptor.authorizedCredentials);
        }
        return request.execute(credentialsRef.get(), serverUri, pi);
      }
    });
  }
}
