package org.busystack.caldav.security.passkey;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** A small, already bounded JSON body for synchronous framework converters. */
final class BoundedJsonRequest extends HttpServletRequestWrapper {
  private final byte[] body;
  private final ServletInputStream input;

  BoundedJsonRequest(HttpServletRequest request, byte[] body) {
    super(request);
    this.body = body;
    var bytes = new ByteArrayInputStream(body);
    input =
        new ServletInputStream() {
          @Override
          public int read() {
            return bytes.read();
          }

          @Override
          public int read(byte[] buffer, int offset, int length) {
            return bytes.read(buffer, offset, length);
          }

          @Override
          public boolean isFinished() {
            return bytes.available() == 0;
          }

          @Override
          public boolean isReady() {
            return true;
          }

          @Override
          public void setReadListener(ReadListener listener) {
            throw new IllegalStateException("Authentication reads the request synchronously");
          }
        };
  }

  @Override
  public ServletInputStream getInputStream() {
    return input;
  }

  @Override
  public BufferedReader getReader() {
    return new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
  }

  @Override
  public int getContentLength() {
    return body.length;
  }

  @Override
  public long getContentLengthLong() {
    return body.length;
  }
}
