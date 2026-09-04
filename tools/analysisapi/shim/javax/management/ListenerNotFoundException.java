package javax.management;

/** Thrown by removeNotificationListener; present so those signatures compile. */
public class ListenerNotFoundException extends Exception {

    private static final long serialVersionUID = 1L;

    public ListenerNotFoundException() {
        super();
    }

    public ListenerNotFoundException(String message) {
        super(message);
    }
}
