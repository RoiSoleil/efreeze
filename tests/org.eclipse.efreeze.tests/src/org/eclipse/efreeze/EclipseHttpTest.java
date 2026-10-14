package org.eclipse.efreeze;

import static org.junit.jupiter.api.Assertions.*;

import java.net.Authenticator;
import java.net.Authenticator.RequestorType;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;

import org.eclipse.core.net.proxy.IProxyData;
import org.eclipse.core.net.proxy.IProxyService;
import org.junit.jupiter.api.Test;

/**
 * The proxies of Eclipse for the HTTP client of Java, with proxies of the test.
 */
class EclipseHttpTest {

  private static final URI RELAY = URI.create("https://efreeze-relay.example.workers.dev/reports");

  /**
   * @return proxy data that answer the given values, by the name of the method
   */
  private static IProxyData proxy(Map<String, Object> values) {
    return (IProxyData) java.lang.reflect.Proxy.newProxyInstance(EclipseHttpTest.class.getClassLoader(), new Class<?>[] { IProxyData.class },
        (instance, method, arguments) -> {
          Object value = values.get(method.getName());
          return value != null || method.getReturnType() != boolean.class && method.getReturnType() != int.class ? value
              : method.getReturnType() == boolean.class ? Boolean.FALSE : Integer.valueOf(-1);
        });
  }

  private static IProxyData http(String host, int port) {
    return proxy(Map.of("getType", IProxyData.HTTPS_PROXY_TYPE, "getHost", host, "getPort", port));
  }

  /**
   * @param selected
   *          the proxies of the relay
   * @param all
   *          all the proxies of the preferences
   */
  private static IProxyService service(List<IProxyData> selected, List<IProxyData> all) {
    return (IProxyService) java.lang.reflect.Proxy.newProxyInstance(EclipseHttpTest.class.getClassLoader(), new Class<?>[] { IProxyService.class },
        (instance, method, arguments) -> switch (method.getName()) {
        case "select" -> {
          assertEquals(RELAY, arguments[0]);
          yield selected.toArray(IProxyData[]::new);
        }
        case "getProxyData" -> all.toArray(IProxyData[]::new);
        default -> throw new UnsupportedOperationException(method.getName());
        });
  }

  @Test
  void clientIsTheOneOfEcf() {
    HttpClient client = EclipseHttp.httpClient();

    assertEquals(HttpClient.Redirect.NORMAL, client.followRedirects(), "the clients of ECF follow the redirections");
    assertTrue(client.proxy().isPresent());
    assertTrue(client.authenticator().isPresent());
  }

  @Test
  void sharedClientIsClosedWithThePlugIn() {
    HttpClient client = EclipseHttp.sharedClient();
    assertSame(client, EclipseHttp.sharedClient());

    EclipseHttp.close();
    try {
      assertTrue(client.isTerminated() || client.awaitTermination(java.time.Duration.ofSeconds(10)));
      assertThrows(java.util.concurrent.CancellationException.class, EclipseHttp::sharedClient, "no client once the plug-in has stopped");
    } catch (InterruptedException e) {
      fail(e);
    } finally {
      EclipseHttp.open();
    }
  }

  @Test
  void relayGoesThroughTheProxyOfEclipse() {
    IProxyService service = service(List.of(http("proxy.example.org", 3128)), List.of());

    assertEquals(List.of(new Proxy(Proxy.Type.HTTP, InetSocketAddress.createUnresolved("proxy.example.org", 3128))),
        EclipseHttp.selector(() -> service).select(RELAY));
  }

  @Test
  void relayWithoutProxyIsReachedDirectly() {
    IProxyService direct = service(List.of(), List.of());
    IProxyService socks = service(List.of(proxy(Map.of("getType", IProxyData.SOCKS_PROXY_TYPE, "getHost", "socks.example.org", "getPort", 1080))),
        List.of());

    assertEquals(List.of(Proxy.NO_PROXY), EclipseHttp.selector(() -> direct).select(RELAY), "no proxy, or a host that bypasses it");
    assertEquals(List.of(Proxy.NO_PROXY), EclipseHttp.selector(() -> socks).select(RELAY), "the HTTP client of Java has no SOCKS");
    assertEquals(List.of(Proxy.NO_PROXY), EclipseHttp.selector(() -> null).select(RELAY), "no network service");
  }

  @Test
  void proxyGetsTheUserAndPasswordOfEclipse() {
    IProxyData withUser = proxy(Map.of("getType", IProxyData.HTTPS_PROXY_TYPE, "getHost", "proxy.example.org", "getPort", 3128,
        "isRequiresAuthentication", true, "getUserId", "jane", "getPassword", "secret"));
    Authenticator authenticator = EclipseHttp.authenticator(() -> service(List.of(), List.of(http("other.example.org", 8080), withUser)));

    PasswordAuthentication authentication = authenticator.requestPasswordAuthenticationInstance("PROXY.example.org", null, 3128, "https",
        "proxy", "basic", null, RequestorType.PROXY);
    assertEquals("jane", authentication.getUserName());
    assertArrayEquals("secret".toCharArray(), authentication.getPassword());

    assertNull(authenticator.requestPasswordAuthenticationInstance("proxy.example.org", null, 8080, "https", "proxy", "basic", null,
        RequestorType.PROXY), "another port");
    assertNull(authenticator.requestPasswordAuthenticationInstance("proxy.example.org", null, 3128, "https", "server", "basic", null,
        RequestorType.SERVER), "only the proxies");
  }
}
