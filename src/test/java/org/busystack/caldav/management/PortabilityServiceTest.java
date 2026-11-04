package org.busystack.caldav.management;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.busystack.caldav.security.audit.SecurityAudit;
import org.busystack.caldav.security.session.SessionSecurity;
import org.busystack.caldav.storage.CalendarStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;

class PortabilityServiceTest {
  @TempDir Path temporary;

  @Test
  void interruptedExportReleasesPrivateFileAndReservationEvenForMemoryFailure() throws Exception {
    UUID actor = UUID.randomUUID(), collection = UUID.randomUUID();
    var auth =
        UsernamePasswordAuthenticationToken.authenticated(
            "fixture",
            null,
            List.of(
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.PASSWORD_AUTHORITY),
                FactorGrantedAuthority.fromAuthority(FactorGrantedAuthority.WEBAUTHN_AUTHORITY)));
    var sessions = mock(SessionSecurity.class);
    when(sessions.assertCurrent(auth))
        .thenReturn(new SessionSecurity.Account(actor, "fixture", UUID.randomUUID()));
    for (boolean afterWrite : new boolean[] {false, true}) {
      var calendars = mock(CalendarStore.class);
      when(calendars.collection(actor, collection))
          .thenReturn(
              new CalendarStore.Collection(
                  collection, actor, "Private", "VEVENT", UUID.randomUUID(), 1, 0));
      if (afterWrite) {
        byte[] body =
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Fixture//EN\r\nBEGIN:VEVENT\r\nUID:memory\r\nDTSTAMP:20261101T000000Z\r\nDTSTART:20261102T000000Z\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        when(calendars.resourceBatch(actor, collection, ""))
            .thenReturn(
                List.of(
                    new CalendarStore.Resource("fixture.ics", "memory", body, "\"fixture\"", 1)));
        when(calendars.resourceBatch(actor, collection, "fixture.ics"))
            .thenThrow(new OutOfMemoryError("injected qualification failure"));
      } else
        when(calendars.resourceBatch(actor, collection, ""))
            .thenThrow(new OutOfMemoryError("injected qualification failure"));
      Path directory = temporary.resolve("private-" + afterWrite);
      try (var storage = new PortabilityStorage(directory.toString())) {
        var service =
            new PortabilityService(
                null,
                sessions,
                calendars,
                null,
                null,
                null,
                null,
                mock(SecurityAudit.class),
                storage);
        assertThatThrownBy(() -> service.export(auth, collection))
            .isInstanceOf(OutOfMemoryError.class);
        assertThat(storage.reservedBytes()).isZero();
        assertThat(storage.usedBytes()).isZero();
        try (var entries = java.nio.file.Files.list(directory)) {
          assertThat(entries.map(p -> p.getFileName().toString())).containsExactly("instance.lock");
        }
      }
    }
  }
}
