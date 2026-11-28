package org.busystack.caldav.security;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/** Unit fake. Concurrent production admission is tested over HTTP against PostgreSQL. */
public final class FlowStoreFixture {
  private FlowStoreFixture() {}

  @SuppressWarnings("unchecked")
  public static BrowserFlowStore create() {
    var store = mock(BrowserFlowStore.class);
    var stored = new HashMap<String, Object>();
    when(store.read(any(), anyString(), any(), any()))
        .thenAnswer(
            call -> {
              HttpServletRequest request = call.getArgument(0);
              Supplier<Object> empty = call.getArgument(3);
              String scope = BrowserFlowStore.scope(request, false);
              return stored.getOrDefault(scope + ":" + call.getArgument(1), empty.get());
            });
    when(store.update(any(), anyBoolean(), anyString(), any(), any(), any()))
        .thenAnswer(
            call -> {
              HttpServletRequest request = call.getArgument(0);
              Supplier<Object> empty = call.getArgument(4);
              Function<Object, Object> change = call.getArgument(5);
              String scope = BrowserFlowStore.scope(request, call.getArgument(1));
              if (scope == null) return change.apply(empty.get());
              return change.apply(
                  stored.computeIfAbsent(scope + ":" + call.getArgument(2), key -> empty.get()));
            });
    return store;
  }
}
