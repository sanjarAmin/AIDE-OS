/**
 * The StAX API (JSR 173), which Android does not ship.
 *
 * <p>The compiler parses its own plugin descriptors with a shaded copy of
 * aalto-xml, and aalto is only the <em>implementation</em> -- it expects these
 * interfaces from the JDK. On a JVM they come from the {@code java.xml} module;
 * on ART nothing provides them and the compiler dies loading its extension
 * points.
 *
 * <p>These are declarations, not an implementation: aalto supplies every method
 * body. Only the six types the compiler and its shaded aalto actually reference
 * are here, which is why there is no factory, no event API and no writer --
 * adding them would mean shipping machinery nothing calls.
 *
 * <p>They are written out rather than lifted from a JDK for two reasons. The
 * JBR's are compiled at class file version 69 and d8 accepts nothing past 65,
 * and hand-written sources carry no OpenJDK licence obligation into the APK.
 * {@code verify-stax-shim.sh} diffs these against a real JDK's, since aalto
 * links against them by exact descriptor.
 */
package javax.xml.stream;
