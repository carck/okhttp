/*
 * Copyright (C) 2013 Square, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.squareup.okhttp.internal.spdy;

import com.squareup.okhttp.internal.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import org.junit.Test;

import static com.squareup.okhttp.internal.Util.byteStringList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class Http20Draft09Test {
  static final int expectedStreamId = 15;

  @Test public void onlyOneLiteralHeadersFrame() throws IOException {
    final List<ByteString> sentHeaders = byteStringList("name", "value");

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    // Write the headers frame, specifying no more frames are expected.
    {
      byte[] headerBytes = literalHeaders(sentHeaders);
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft09.TYPE_HEADERS);
      dataOut.write(Http20Draft09.FLAG_END_HEADERS | Http20Draft09.FLAG_END_STREAM);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    FrameReader fr = new Http20Draft09.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Consume the headers frame.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<ByteString> nameValueBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertTrue(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(sentHeaders, nameValueBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void headersFrameThenContinuation() throws IOException {

    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    // Write the first headers frame.
    {
      byte[] headerBytes = literalHeaders(byteStringList("foo", "bar"));
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft09.TYPE_HEADERS);
      dataOut.write(0); // no flags
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    // Write the continuation frame, specifying no more frames are expected.
    {
      byte[] headerBytes = literalHeaders(byteStringList("baz", "qux"));
      dataOut.writeShort(headerBytes.length);
      dataOut.write(Http20Draft09.TYPE_CONTINUATION);
      dataOut.write(Http20Draft09.FLAG_END_HEADERS | Http20Draft09.FLAG_END_STREAM);
      dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
      dataOut.write(headerBytes);
    }

    FrameReader fr = new Http20Draft09.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Reading the above frames should result in a concatenated nameValueBlock.
    fr.nextFrame(new BaseTestHandler() {

      @Override
      public void headers(boolean outFinished, boolean inFinished, int streamId,
          int associatedStreamId, int priority, List<ByteString> nameValueBlock,
          HeadersMode headersMode) {
        assertFalse(outFinished);
        assertFalse(inFinished);
        assertEquals(expectedStreamId, streamId);
        assertEquals(-1, associatedStreamId);
        assertEquals(-1, priority);
        assertEquals(byteStringList("foo", "bar", "baz", "qux"), nameValueBlock);
        assertEquals(HeadersMode.HTTP_20_HEADERS, headersMode);
      }
    });
  }

  @Test public void readRstStreamFrame() throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    DataOutputStream dataOut = new DataOutputStream(out);

    dataOut.writeShort(4);
    dataOut.write(Http20Draft09.TYPE_RST_STREAM);
    dataOut.write(0); // No flags
    dataOut.writeInt(expectedStreamId & 0x7fffffff); // stream with reserved bit set
    dataOut.writeInt(ErrorCode.COMPRESSION_ERROR.httpCode);

    FrameReader fr = new Http20Draft09.Reader(new ByteArrayInputStream(out.toByteArray()), false);

    // Consume the reset frame.
    fr.nextFrame(new BaseTestHandler() {
      @Override public void rstStream(int streamId, ErrorCode errorCode) {
        assertEquals(expectedStreamId, streamId);
        assertEquals(ErrorCode.COMPRESSION_ERROR, errorCode);
      }
    });
  }

  private byte[] literalHeaders(List<ByteString> sentHeaders) throws IOException {
    ByteArrayOutputStream headerBytes = new ByteArrayOutputStream();
    new HpackDraft05.Writer(new DataOutputStream(headerBytes)).writeHeaders(sentHeaders);
    return headerBytes.toByteArray();
  }
}
