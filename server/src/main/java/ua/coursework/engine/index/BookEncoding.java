package ua.coursework.engine.index;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Chooses the charset of a book: UTF-8 if the whole file is valid UTF-8, otherwise ISO-8859-1.
 *
 * <p>The indexer and the snippet builder must read a file with the same charset, because the same
 * text has a different number of characters in the two charsets and the chunk boundaries depend on
 * it. That is why the decision is made in one place, for the whole file.
 */
public final class BookEncoding {

  private static final int BUFFER = 64 * 1024;

  private BookEncoding() {}

  public static Charset detect(Path file) throws IOException {
    CharsetDecoder decoder =
        StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    ByteBuffer bytes = ByteBuffer.allocate(BUFFER);
    CharBuffer chars = CharBuffer.allocate(BUFFER); // never overflows: 1 byte gives at most 1 char
    try (InputStream in = Files.newInputStream(file)) {
      boolean endOfInput = false;
      while (!endOfInput) {
        int read = in.read(bytes.array(), bytes.position(), bytes.remaining());
        if (read < 0) {
          endOfInput = true;
        } else {
          bytes.position(bytes.position() + read);
        }
        bytes.flip();
        CoderResult result = decoder.decode(bytes, chars, endOfInput);
        if (result.isError()) {
          return StandardCharsets.ISO_8859_1;
        }
        chars.clear();
        bytes.compact(); // keeps the start of a character that is cut at the end of the buffer
      }
      if (decoder.flush(chars).isError()) {
        return StandardCharsets.ISO_8859_1;
      }
    }
    return StandardCharsets.UTF_8;
  }
}
