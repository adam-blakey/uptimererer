package family.blakey.uptimererer.core;

public class Helpers {
    public static String describe(Exception e) {
        String message = e.getMessage();
        return (message == null || message.isBlank()) ? e.getClass().getSimpleName() : message;
    }
}
