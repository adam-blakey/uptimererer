package family.blakey.uptimererer.core;

public class Helpers {
  public static String describe(Exception e) {
    String output = e.getClass().getSimpleName();
    String message = e.getMessage();
    if (message != null && !message.isBlank()) {
      output += ": " + message;
    }
    return output;
  }
}
