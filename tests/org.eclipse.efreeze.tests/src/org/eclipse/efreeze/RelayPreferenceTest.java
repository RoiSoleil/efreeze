package org.eclipse.efreeze;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;

import org.junit.jupiter.api.Test;

/**
 * The address of the relay that shares the reports: the report is sent over HTTPS only.
 */
class RelayPreferenceTest {

  @Test
  void addressOverHttpsIsTheRelay() {
    assertEquals(URI.create("https://efreeze-relay.example.workers.dev/reports"), Activator.relay(" https://efreeze-relay.example.workers.dev/reports "));
    assertEquals(URI.create(PreferenceInitializer.DEFAULT_RELAY), Activator.relay(PreferenceInitializer.DEFAULT_RELAY));
  }

  @Test
  void otherAddressIsNoRelay() {
    assertNull(Activator.relay("http://efreeze-relay.example.workers.dev/reports"), "not over HTTPS");
    assertNull(Activator.relay("efreeze-relay.example.workers.dev"));
    assertNull(Activator.relay("https://"));
    assertNull(Activator.relay("not an address"));
    assertNull(Activator.relay(""));
    assertNull(Activator.relay(null));
  }
}
