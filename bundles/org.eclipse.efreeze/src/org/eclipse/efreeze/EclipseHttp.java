package org.eclipse.efreeze;

import java.io.IOException;
import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.function.Supplier;

import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.eclipse.core.runtime.ServiceCaller;
import org.eclipse.ecf.internal.provider.filetransfer.httpclientjava.IHttpClientFactory;

/**
 * The HTTP transport of Eclipse, for a request that ECF does not send: its file transfers only download.
 * <p>
 * The client is the one of the HTTP provider of ECF, with the SSL context of Eclipse, and goes through the proxies of
 * Eclipse (<i>Window &gt; Preferences &gt; General &gt; Network Connections</i>), as ECF does for a transfer: the proxy of
 * each address, the hosts that bypass it, and the user and password of the proxy. The HTTP client of Java does not go
 * through SOCKS proxies: only the HTTP and HTTPS proxies are used. Without ECF, the client is a plain one of Java.
 */
public class EclipseHttp {

  private static final ServiceCaller<IProxyService> PROXY_SERVICE = new ServiceCaller<>(EclipseHttp.class, IProxyService.class);

  /** The client of the plug-in, created when a first request is sent: creating it reads the certificates of Eclipse. */
  private static HttpClient shared;
  /** The plug-in has stopped: no client is created any more. */
  private static boolean closed;

  private EclipseHttp() {
  }

  /**
   * @return the HTTP client of the plug-in, created at the first call: not in the UI thread
   */
  public static synchronized HttpClient sharedClient() {
    if (closed) {
      throw new CancellationException("EFreeze has stopped");
    }
    if (shared == null) {
      shared = httpClient();
    }
    return shared;
  }

  /**
   * Closes the HTTP client of the plug-in, if it has been created, without waiting for the reports that are sent: the
   * plug-in stops, Eclipse may be closing.
   */
  public static synchronized void close() {
    closed = true;
    if (shared != null) {
      shared.shutdownNow();
      shared = null;
    }
  }

  /**
   * Allows the HTTP client again: the plug-in starts, maybe again in the same virtual machine.
   */
  public static synchronized void open() {
    closed = false;
  }

  /**
   * @return an HTTP client that goes through the proxies of Eclipse, as they are when a request is sent
   */
  public static HttpClient httpClient() {
    Supplier<IProxyService> service = () -> PROXY_SERVICE.current().orElse(null);
    return newBuilder().connectTimeout(Duration.ofSeconds(30)).proxy(selector(service)).authenticator(authenticator(service)).build();
  }

  /**
   * @return the builder of the clients of ECF, or of a plain client of Java without ECF
   */
  static HttpClient.Builder newBuilder() {
    try {
      HttpClient.Builder builder = Ecf.newBuilder();
      if (builder != null) {
        return builder;
      }
    } catch (LinkageError e) {
      // ECF and its HTTP provider are not installed, or another version of their internal API is.
    }
    return HttpClient.newBuilder();
  }

  /** The HTTP provider of ECF, an optional dependency: its classes are loaded only if it is there. */
  private static final class Ecf {

    private static final ServiceCaller<IHttpClientFactory> FACTORY = new ServiceCaller<>(Ecf.class, IHttpClientFactory.class);

    static HttpClient.Builder newBuilder() {
      return FACTORY.current().map(IHttpClientFactory::newClient).orElse(null);
    }
  }

  static ProxySelector selector(Supplier<IProxyService> service) {
    return new ProxySelector() {
      @Override
      public List<Proxy> select(URI uri) {
        IProxyService proxies = service.get();
        List<Proxy> selected = new ArrayList<>();
        if (proxies != null) {
          for (IProxyData proxy : proxies.select(uri)) {
            if (!IProxyData.SOCKS_PROXY_TYPE.equals(proxy.getType()) && proxy.getHost() != null && proxy.getPort() > 0) {
              selected.add(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved(proxy.getHost(), proxy.getPort())));
            }
          }
        }
        return selected.isEmpty() ? List.of(Proxy.NO_PROXY) : selected;
      }

      @Override
      public void connectFailed(URI uri, SocketAddress address, IOException exception) {
        // The next proxy, if any, is tried.
      }
    };
  }

  static Authenticator authenticator(Supplier<IProxyService> service) {
    return new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        IProxyService proxies = service.get();
        if (getRequestorType() != RequestorType.PROXY || proxies == null) {
          return null;
        }
        for (IProxyData proxy : proxies.getProxyData()) {
          if (proxy.isRequiresAuthentication() && getRequestingHost().equalsIgnoreCase(proxy.getHost()) && getRequestingPort() == proxy.getPort()
              && proxy.getUserId() != null) {
            String password = proxy.getPassword();
            return new PasswordAuthentication(proxy.getUserId(), password == null ? new char[0] : password.toCharArray());
          }
        }
        return null;
      }
    };
  }
}
