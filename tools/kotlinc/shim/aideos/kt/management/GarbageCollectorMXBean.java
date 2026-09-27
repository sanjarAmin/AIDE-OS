package aideos.kt.management;

public interface GarbageCollectorMXBean {
    String getName();
    long getCollectionCount();
    long getCollectionTime();
}
