package family.blakey.uptimererer.core;

import static family.blakey.uptimererer.core.Helpers.describe;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class HelpersTest {

  @Test
  void returnsMessageWhenPresent() {
    assertEquals("IOException: whoopsie", describe(new IOException("whoopsie")));
  }

  @Test
  void fallsBackToClassNameWhenMessageIsNull() {
    assertEquals("IOException", describe(new IOException()));
  }

  @Test
  void fallsBackToClassNameWhenMessageIsBlank() {
    assertEquals("IOException", describe(new IOException("   ")));
  }
}
