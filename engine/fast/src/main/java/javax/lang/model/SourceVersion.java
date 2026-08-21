package javax.lang.model;

/**
 * Enough of {@code javax.lang.model.SourceVersion} for ECJ to start. Android
 * has no {@code javax.lang.model} at all.
 *
 * <p>ECJ's {@code FileSystem} asks whether it is running on JRE 12 or newer by
 * probing for a constant:
 *
 * <pre>
 *   try { isJRE12Plus = SourceVersion.valueOf("RELEASE_12") != null; }
 *   catch (IllegalArgumentException e) { }
 * </pre>
 *
 * <p>The handler catches {@code IllegalArgumentException} -- the failure an
 * older JRE produces. A missing class instead throws {@code NoClassDefFoundError},
 * which walks straight past it, so the static initializer dies and the whole
 * batch compiler becomes unloadable. One absent enum, and there is no Java
 * compiler on the device.
 *
 * <p><b>The constants stop at 11 on purpose.</b> That makes the probe throw
 * {@code IllegalArgumentException}, which is the answer we want: a device is not
 * a JRE 12+, and the flag gates {@code getOlderSystemRelease}, which reads
 * {@code ct.sym} out of a JDK installation that does not exist here. Adding
 * {@code RELEASE_12} would silently flip that on and send ECJ looking for it.
 *
 * <p>Nothing else on the batch-compilation path touches this type. ECJ's
 * annotation-processing and JSR-199 packages do, and they need far more of
 * {@code javax.lang.model} than this -- if those are ever wanted, this file is
 * not the thing to extend.
 */
public enum SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8,
    RELEASE_9,
    RELEASE_10,
    RELEASE_11,
}
