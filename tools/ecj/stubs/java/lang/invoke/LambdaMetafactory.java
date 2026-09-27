package java.lang.invoke;

/**
 * Compile-time stub. Never loaded at run time, never executed.
 *
 * <p>Every lambda and method reference compiles to an {@code invokedynamic}
 * whose bootstrap method is one of these, so the compiler must be able to
 * resolve them. android.jar does not contain this class: on a desktop build the
 * java.* platform comes from the JDK's own java.base and only android.* comes
 * from android.jar, but on-device there is no java.base at all. Without this
 * stub ECJ rejects any source containing a lambda -- which is to say, any real
 * Java source -- with "LambdaMetafactory cannot be resolved".
 *
 * <p>Nothing needs it later: D8 desugars the invokedynamic into an anonymous
 * class at dex time, and the reference is gone before the APK exists. That is
 * why a stub is sufficient and not a lie -- the method really is never called.
 *
 * <p>The descriptors must match the JDK's exactly, because that is what the
 * compiler emits into the constant pool. `verify-platform-stubs.sh` diffs them.
 */
public final class LambdaMetafactory {

    /**
     * Passed in the extra bootstrap arguments of an altMetafactory call. A
     * compiler emits the values rather than field references, so these are here
     * for fidelity with the JDK rather than because anything resolves them --
     * and fidelity is the point, since verify-platform-stubs.sh compares the
     * two surfaces.
     */
    public static final int FLAG_SERIALIZABLE = 1;
    public static final int FLAG_MARKERS = 2;
    public static final int FLAG_BRIDGES = 4;

    private LambdaMetafactory() {}

    public static CallSite metafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            MethodType interfaceMethodType,
            MethodHandle implementation,
            MethodType dynamicMethodType) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static CallSite altMetafactory(
            MethodHandles.Lookup caller,
            String interfaceMethodName,
            MethodType factoryType,
            Object... args) {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
