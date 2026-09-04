package javax.management;

import java.util.EventObject;

/** A JMX notification. Carries what IntelliJ's watcher reads and nothing else. */
public class Notification extends EventObject {

    private static final long serialVersionUID = 1L;

    private final String type;
    private final long sequenceNumber;
    private String message;

    public Notification(String type, Object source, long sequenceNumber) {
        super(source);
        this.type = type;
        this.sequenceNumber = sequenceNumber;
    }

    public Notification(String type, Object source, long sequenceNumber, String message) {
        this(type, source, sequenceNumber);
        this.message = message;
    }

    public String getType() {
        return type;
    }

    public long getSequenceNumber() {
        return sequenceNumber;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /**
     * The user data a real JMX notification would carry.
     *
     * Always null here: nothing on Android constructs these, so there is never
     * a payload to hand back. A caller that reads it gets the same answer it
     * would get from a notification that carried none.
     */
    public Object getUserData() {
        return null;
    }

    public void setUserData(Object userData) {
        // Deliberately dropped; see getUserData.
    }
}
