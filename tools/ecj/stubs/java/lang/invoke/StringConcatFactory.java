package java.lang.invoke;

/**
 * Compile-time stub, for the same reason as {@link LambdaMetafactory} and with
 * the same guarantee: D8 desugars the call away before an APK exists.
 *
 * <p>At source level 9 and above, {@code "a" + b} no longer compiles to
 * StringBuilder -- it compiles to an {@code invokedynamic} bootstrapped here.
 * So without this stub the on-device compiler is capped at Java 8, and with it
 * the cap is lifted.
 */
public final class StringConcatFactory {

    private StringConcatFactory() {}

    public static CallSite makeConcat(
            MethodHandles.Lookup lookup,
            String name,
            MethodType concatType) {
        throw new UnsupportedOperationException("compile-time stub");
    }

    public static CallSite makeConcatWithConstants(
            MethodHandles.Lookup lookup,
            String name,
            MethodType concatType,
            String recipe,
            Object... constants) {
        throw new UnsupportedOperationException("compile-time stub");
    }
}
