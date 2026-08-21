package org.jetbrains.kotlin.com.intellij.util.containers;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;

/**
 * Drop-in replacement for IntelliJ's {@code containers.Unsafe}, which the
 * compiler cannot load on ART.
 *
 * <p>The original resolves ten {@code sun.misc.Unsafe} methods in its static
 * initializer and lets any failure escape. ART provides nine of them but not
 * {@code copyMemory(Object, long, Object, long, long)}, so the class fails to
 * initialize -- and it is not an obscure class: {@code ConcurrentLongObjectHashMap}
 * needs it, {@code CoreProgressManager} needs that, and every compilation needs
 * that. The compiler dies before parsing a single file.
 *
 * <p>Two changes fix it:
 *
 * <ol>
 *   <li>Each handle is resolved independently, and a failure stores {@code null}
 *       instead of propagating. A method this run never calls can no longer
 *       decide whether the compiler starts.
 *   <li>{@code copyMemory} is emulated. ART deliberately drops the general form
 *       -- an arbitrary object address is meaningless under a moving collector --
 *       but keeps the pinned-array forms java.nio needs. The only caller in the
 *       compiler, {@code ByteBufferUtil}, passes {@code (null, address, byte[],
 *       offset, length)}, which is precisely {@code copyMemoryToPrimitiveArray}.
 * </ol>
 *
 * <p>The signatures below must stay byte-identical to the class this replaces:
 * callers are already-compiled bytecode and link against them by exact
 * descriptor. `invokeExact` is equally unforgiving -- each call site's static
 * types must match the bound handle's type exactly, or it throws at runtime.
 */
public final class Unsafe {

  private static final Object THE_UNSAFE = theUnsafe();

  private static final MethodHandle putObjectVolatile =
      find("putObjectVolatile", void.class, Object.class, long.class, Object.class);
  private static final MethodHandle getObjectVolatile =
      find("getObjectVolatile", Object.class, Object.class, long.class);
  private static final MethodHandle compareAndSwapObject =
      find("compareAndSwapObject", boolean.class, Object.class, long.class, Object.class, Object.class);
  private static final MethodHandle compareAndSwapInt =
      find("compareAndSwapInt", boolean.class, Object.class, long.class, int.class, int.class);
  private static final MethodHandle compareAndSwapLong =
      find("compareAndSwapLong", boolean.class, Object.class, long.class, long.class, long.class);
  private static final MethodHandle getAndAddInt =
      find("getAndAddInt", int.class, Object.class, long.class, int.class);
  private static final MethodHandle objectFieldOffset =
      find("objectFieldOffset", long.class, Field.class);
  private static final MethodHandle arrayIndexScale =
      find("arrayIndexScale", int.class, Class.class);
  private static final MethodHandle arrayBaseOffset =
      find("arrayBaseOffset", int.class, Class.class);

  /** Present on a JVM, absent on ART. */
  private static final MethodHandle copyMemory =
      find("copyMemory", void.class, Object.class, long.class, Object.class, long.class, long.class);

  /** ART's replacements: address to array, array to address, address to address. */
  private static final MethodHandle copyToArray =
      find("copyMemoryToPrimitiveArray", void.class, long.class, Object.class, long.class, long.class);
  private static final MethodHandle copyFromArray =
      find("copyMemoryFromPrimitiveArray", void.class, Object.class, long.class, long.class, long.class);
  private static final MethodHandle copyDirect =
      find("copyMemory", void.class, long.class, long.class, long.class);

  private Unsafe() {}

  private static Object theUnsafe() {
    try {
      Field field = Class.forName("sun.misc.Unsafe").getDeclaredField("theUnsafe");
      field.setAccessible(true);
      return field.get(null);
    } catch (Throwable t) {
      throw new Error("sun.misc.Unsafe is unavailable", t);
    }
  }

  /**
   * Binds one {@code sun.misc.Unsafe} method, or returns null if this runtime
   * does not have it. Returning null rather than throwing is the whole point:
   * see the class comment.
   */
  private static MethodHandle find(String name, Class<?> returnType, Class<?>... parameterTypes) {
    try {
      return MethodHandles.publicLookup()
          .findVirtual(THE_UNSAFE.getClass(), name, MethodType.methodType(returnType, parameterTypes))
          .bindTo(THE_UNSAFE);
    } catch (Throwable t) {
      return null;
    }
  }

  private static MethodHandle require(MethodHandle handle, String name) {
    if (handle == null) {
      throw new UnsupportedOperationException("sun.misc.Unsafe." + name + " is unavailable on this runtime");
    }
    return handle;
  }

  public static void putObjectVolatile(Object o, long offset, Object value) {
    try {
      require(putObjectVolatile, "putObjectVolatile").invokeExact(o, offset, value);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static Object getObjectVolatile(Object o, long offset) {
    try {
      return require(getObjectVolatile, "getObjectVolatile").invokeExact(o, offset);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static boolean compareAndSwapObject(Object o, long offset, Object expected, Object value) {
    try {
      return (boolean) require(compareAndSwapObject, "compareAndSwapObject").invokeExact(o, offset, expected, value);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static boolean compareAndSwapInt(Object o, long offset, int expected, int value) {
    try {
      return (boolean) require(compareAndSwapInt, "compareAndSwapInt").invokeExact(o, offset, expected, value);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static boolean compareAndSwapLong(Object o, long offset, long expected, long value) {
    try {
      return (boolean) require(compareAndSwapLong, "compareAndSwapLong").invokeExact(o, offset, expected, value);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static int getAndAddInt(Object o, long offset, int delta) {
    try {
      return (int) require(getAndAddInt, "getAndAddInt").invokeExact(o, offset, delta);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static long objectFieldOffset(Field field) {
    try {
      return (long) require(objectFieldOffset, "objectFieldOffset").invokeExact(field);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static int arrayIndexScale(Class<?> arrayClass) {
    try {
      return (int) require(arrayIndexScale, "arrayIndexScale").invokeExact(arrayClass);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  public static int arrayBaseOffset(Class<?> arrayClass) {
    try {
      return (int) require(arrayBaseOffset, "arrayBaseOffset").invokeExact(arrayClass);
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }

  /**
   * When one side is null its offset is an absolute address, and the pair
   * (null, array) or (array, null) maps onto an ART primitive. Object-to-object
   * has no equivalent and no caller in the compiler.
   */
  public static void copyMemory(Object src, long srcOffset, Object dst, long dstOffset, long bytes) {
    try {
      if (copyMemory != null) {
        copyMemory.invokeExact(src, srcOffset, dst, dstOffset, bytes);
      } else if (src == null && dst != null) {
        require(copyToArray, "copyMemoryToPrimitiveArray").invokeExact(srcOffset, dst, dstOffset, bytes);
      } else if (src != null && dst == null) {
        require(copyFromArray, "copyMemoryFromPrimitiveArray").invokeExact(src, srcOffset, dstOffset, bytes);
      } else if (src == null) {
        require(copyDirect, "copyMemory").invokeExact(srcOffset, dstOffset, bytes);
      } else {
        throw new UnsupportedOperationException("object-to-object copyMemory is unavailable on this runtime");
      }
    } catch (Throwable t) {
      throw new RuntimeException(t);
    }
  }
}
