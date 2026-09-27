package aideos.kt.management;

public interface MemoryPoolMXBean {
    String getName();
    MemoryType getType();
    MemoryUsage getUsage();
    boolean isUsageThresholdSupported();
    boolean isCollectionUsageThresholdSupported();
    void setUsageThreshold(long threshold);
    void setCollectionUsageThreshold(long threshold);
}
