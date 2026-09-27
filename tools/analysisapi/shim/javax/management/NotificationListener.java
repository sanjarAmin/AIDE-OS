package javax.management;

import java.util.EventListener;

/** The listener interface IntelliJ's LowMemoryWatcherManager implements. */
public interface NotificationListener extends EventListener {
    void handleNotification(Notification notification, Object handback);
}
