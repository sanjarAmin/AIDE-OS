package javax.management;

import java.io.Serializable;

/** A notification filter. Referenced in signatures; never consulted here. */
public interface NotificationFilter extends Serializable {
    boolean isNotificationEnabled(Notification notification);
}
