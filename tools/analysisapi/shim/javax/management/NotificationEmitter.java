package javax.management;

/**
 * What a memory pool would be cast to in order to register a listener.
 *
 * On Android nothing implements this, so the cast that reaches it fails or the
 * bean is absent -- either way the watcher registers nothing, which is the
 * intended outcome. The type still has to exist for the calling code to link.
 */
public interface NotificationEmitter {

    void addNotificationListener(NotificationListener listener,
                                 NotificationFilter filter,
                                 Object handback);

    void removeNotificationListener(NotificationListener listener)
            throws ListenerNotFoundException;

    void removeNotificationListener(NotificationListener listener,
                                    NotificationFilter filter,
                                    Object handback) throws ListenerNotFoundException;
}
