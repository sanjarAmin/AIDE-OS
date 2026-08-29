/*
 * A `java`-compatible launcher that does not re-exec.
 *
 * OpenJDK's own `bin/java` re-execs itself through /proc/self/exe to fix its
 * environment. Under the only launch route this app has, /proc/self/exe is
 * /system/bin/linker64, so the re-exec becomes `linker64 -version` and dies.
 * tools/rootfs/FINDINGS.md section 3.
 *
 * The launcher is not the VM, though: it is a wrapper around JNI_CreateJavaVM
 * in libjvm.so. Calling that directly skips the wrapper and everything it does
 * to its own process.
 *
 * It takes `java`'s arguments rather than its own, so that anything which
 * shells out to `java` -- Gradle starting its daemon, above all -- works when
 * `<jdk>/bin/java` is a symlink to this file. The symlink matters: the kernel
 * checks the *resolved* file against the no-execute rule, and this lives in
 * nativeLibraryDir, which is executable. Section 6.
 *
 *   java [-D…] [-X…] [-cp PATH] [@argfile] MAIN-CLASS [args…]
 *   java -version
 *
 * java.home is taken from JAVA_HOME, or derived from argv[0] the way the real
 * launcher does -- which is what makes the symlink work, since argv[0] is then
 * the path inside the JDK rather than this file.
 */
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/stat.h>

/*
 * The NDK's jni.h describes *Android's* JNI, which stops at 1.6. This launcher
 * talks to OpenJDK's VM, which wants at least 1.8 in JavaVMInitArgs.version --
 * so the constant has to be supplied rather than included. A reminder that the
 * header at hand and the runtime being driven are not the same implementation.
 */
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

#define MAX_OPTIONS 256
#define MAX_ARGS 1024

typedef jint (*CreateVm)(JavaVM **, void **, void *);

struct parsed {
    char *options[MAX_OPTIONS];
    int option_count;
    char *class_path;       /* the last -cp wins, as with java */
    char *main_class;
    char *jar;              /* -jar: the main class comes from its manifest */
    char *program_args[MAX_ARGS];
    int program_arg_count;
    int print_version;
};

static void add_option(struct parsed *out, char *option) {
    if (out->option_count < MAX_OPTIONS) out->options[out->option_count++] = option;
}

static int is_directory(const char *path) {
    struct stat info;
    return stat(path, &info) == 0 && S_ISDIR(info.st_mode);
}

/*
 * java.home, in the order the real launcher would find it.
 *
 * argv[0] first: when Gradle execs `<jdk>/bin/java` -- a symlink to this file
 * -- argv[0] is that path, and the JDK is two directories up. Deriving it this
 * way is what lets the symlink stand in for the real launcher without anyone
 * having to set an environment variable.
 */
static char *resolve_java_home(const char *argv0) {
    char *from_env = getenv("JAVA_HOME");
    if (from_env && *from_env) return strdup(from_env);

    const char *slash = strrchr(argv0, '/');
    if (slash == NULL) return NULL;
    size_t bin_length = (size_t) (slash - argv0);
    char bin[4096];
    if (bin_length >= sizeof(bin)) return NULL;
    memcpy(bin, argv0, bin_length);
    bin[bin_length] = '\0';

    const char *parent_slash = strrchr(bin, '/');
    if (parent_slash == NULL) return NULL;
    size_t home_length = (size_t) (parent_slash - bin);
    char *home = malloc(home_length + 1);
    memcpy(home, bin, home_length);
    home[home_length] = '\0';
    return home;
}

static int parse(int argc, char **argv, struct parsed *out);

/* `@file` holds more arguments, one per line or space separated. */
static int expand_argfile(const char *path, struct parsed *out) {
    FILE *file = fopen(path, "r");
    if (file == NULL) {
        fprintf(stderr, "cannot read argument file %s\n", path);
        return -1;
    }
    static char buffer[1 << 16];
    size_t read = fread(buffer, 1, sizeof(buffer) - 1, file);
    fclose(file);
    buffer[read] = '\0';

    char *words[MAX_ARGS];
    int count = 1; /* words[0] stands in for argv[0] and is skipped by parse */
    words[0] = (char *) path;
    for (char *token = strtok(buffer, " \t\r\n"); token && count < MAX_ARGS; token = strtok(NULL, " \t\r\n")) {
        if (*token) words[count++] = token;
    }
    return parse(count, words, out);
}

/*
 * Only the option shapes that matter here are understood. Anything beginning
 * with `-` that is not recognised is handed to the VM verbatim, which is what
 * the real launcher does with `-XX:` and friends -- and means an unknown flag
 * produces the VM's own error rather than this launcher's.
 */
static int parse(int argc, char **argv, struct parsed *out) {
    int i = 1;
    for (; i < argc; i++) {
        char *arg = argv[i];

        if (out->main_class != NULL) break;
        if (arg[0] == '@') {
            if (expand_argfile(arg + 1, out) != 0) return -1;
            continue;
        }
        if (arg[0] != '-') {
            out->main_class = arg;
            i++;
            break;
        }
        if (strcmp(arg, "-cp") == 0 || strcmp(arg, "-classpath") == 0 ||
            strcmp(arg, "--class-path") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "%s requires a path\n", arg);
                return -1;
            }
            out->class_path = argv[++i];
            continue;
        }
        if (strncmp(arg, "--class-path=", 13) == 0) {
            out->class_path = arg + 13;
            continue;
        }
        if (strcmp(arg, "-version") == 0 || strcmp(arg, "--version") == 0) {
            out->print_version = 1;
            continue;
        }
        if (strcmp(arg, "-jar") == 0) {
            if (i + 1 >= argc) {
                fprintf(stderr, "-jar requires a path\n");
                return -1;
            }
            /* As with java, the jar becomes the whole classpath and its
             * manifest names the class; anything -cp said is discarded.
             *
             * And everything after it belongs to the program, not the VM --
             * `java -jar app.jar one two` passes one and two to main. Carrying
             * on parsing options here made the first program argument look
             * like a main class name. */
            out->jar = argv[++i];
            out->class_path = out->jar;
            i++;
            break;
        }
        add_option(out, arg);
    }

    for (; i < argc && out->program_arg_count < MAX_ARGS; i++) {
        out->program_args[out->program_arg_count++] = argv[i];
    }
    return 0;
}

static void print_version(JNIEnv *env) {
    jclass system = (*env)->FindClass(env, "java/lang/System");
    jmethodID get = (*env)->GetStaticMethodID(
        env, system, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");

    const char *keys[] = {"java.version", "java.vm.name", "java.vm.version"};
    char values[3][256];
    for (int i = 0; i < 3; i++) {
        jstring key = (*env)->NewStringUTF(env, keys[i]);
        jstring value = (jstring) (*env)->CallStaticObjectMethod(env, system, get, key);
        const char *text = value ? (*env)->GetStringUTFChars(env, value, NULL) : "unknown";
        snprintf(values[i], sizeof(values[i]), "%s", text);
        if (value) (*env)->ReleaseStringUTFChars(env, value, text);
    }
    /* stderr, and in this order, because that is where `java -version` puts
     * it and scripts parse the first line. */
    fprintf(stderr, "openjdk version \"%s\"\n", values[0]);
    fprintf(stderr, "OpenJDK Runtime Environment (build %s)\n", values[0]);
    fprintf(stderr, "%s (build %s, mixed mode)\n", values[1], values[2]);
}

/*
 * Runs a JDK tool -- `jlink`, `javac`, `jar` -- through
 * java.util.spi.ToolProvider.
 *
 * AGP execs `jlink` directly (its JdkImageTransform builds a system-modules
 * image), and every JDK tool is a copy of the same launcher stub that cannot
 * run here. Symlinking each one to this file solves the exec problem; this
 * solves what to do once we are running, and does it the supported way rather
 * than by guessing at internal main classes -- `jdk.tools.jlink.internal.Main`
 * is not exported to the unnamed module, so FindClass could not reach it
 * anyway.
 */
static int run_tool(JNIEnv *env, const char *tool, char **args, int arg_count) {
    jclass provider_class = (*env)->FindClass(env, "java/util/spi/ToolProvider");
    jmethodID find = (*env)->GetStaticMethodID(
        env, provider_class, "findFirst", "(Ljava/lang/String;)Ljava/util/Optional;");
    jobject optional = (*env)->CallStaticObjectMethod(
        env, provider_class, find, (*env)->NewStringUTF(env, tool));

    jclass optional_class = (*env)->FindClass(env, "java/util/Optional");
    jmethodID is_present = (*env)->GetMethodID(env, optional_class, "isPresent", "()Z");
    if (!(*env)->CallBooleanMethod(env, optional, is_present)) {
        fprintf(stderr, "no JDK tool named %s\n", tool);
        return 3;
    }
    jmethodID get = (*env)->GetMethodID(env, optional_class, "get", "()Ljava/lang/Object;");
    jobject instance = (*env)->CallObjectMethod(env, optional, get);

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray tool_args = (*env)->NewObjectArray(env, arg_count, string_class, NULL);
    for (int i = 0; i < arg_count; i++) {
        (*env)->SetObjectArrayElement(env, tool_args, i, (*env)->NewStringUTF(env, args[i]));
    }

    jclass system = (*env)->FindClass(env, "java/lang/System");
    jfieldID out_field = (*env)->GetStaticFieldID(env, system, "out", "Ljava/io/PrintStream;");
    jfieldID err_field = (*env)->GetStaticFieldID(env, system, "err", "Ljava/io/PrintStream;");
    jobject out = (*env)->GetStaticObjectField(env, system, out_field);
    jobject err = (*env)->GetStaticObjectField(env, system, err_field);

    jmethodID run = (*env)->GetMethodID(
        env, (*env)->GetObjectClass(env, instance), "run",
        "(Ljava/io/PrintStream;Ljava/io/PrintStream;[Ljava/lang/String;)I");
    jint result = (*env)->CallIntMethod(env, instance, run, out, err, tool_args);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        return 1;
    }
    return (int) result;
}

/*
 * The `Main-Class` a jar's manifest names.
 *
 * Read through `java.util.jar.JarFile` rather than by parsing the zip in C:
 * the VM is already running by this point, and the manifest's rules --
 * continuation lines, encoding, which attributes section wins -- are exactly
 * the kind of thing a hand-rolled reader gets subtly wrong on the one jar that
 * matters. `gradlew` runs `java -jar gradle-wrapper.jar`, so this is not
 * hypothetical.
 *
 * Returns a string owned by the caller, or NULL with the reason printed.
 */
static char *main_class_of_jar(JNIEnv *env, const char *jar) {
    jclass jar_file = (*env)->FindClass(env, "java/util/jar/JarFile");
    jmethodID open = (*env)->GetMethodID(env, jar_file, "<init>", "(Ljava/lang/String;)V");
    jobject instance = (*env)->NewObject(env, jar_file, open, (*env)->NewStringUTF(env, jar));
    if (instance == NULL) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "cannot open %s\n", jar);
        return NULL;
    }

    jmethodID get_manifest = (*env)->GetMethodID(
        env, jar_file, "getManifest", "()Ljava/util/jar/Manifest;");
    jobject manifest = (*env)->CallObjectMethod(env, instance, get_manifest);
    if (manifest == NULL) {
        fprintf(stderr, "%s has no manifest\n", jar);
        return NULL;
    }

    jclass manifest_class = (*env)->FindClass(env, "java/util/jar/Manifest");
    jmethodID main_attributes = (*env)->GetMethodID(
        env, manifest_class, "getMainAttributes", "()Ljava/util/jar/Attributes;");
    jobject attributes = (*env)->CallObjectMethod(env, manifest, main_attributes);

    jclass attributes_class = (*env)->FindClass(env, "java/util/jar/Attributes");
    jmethodID get_value = (*env)->GetMethodID(
        env, attributes_class, "getValue", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring value = (jstring) (*env)->CallObjectMethod(
        env, attributes, get_value, (*env)->NewStringUTF(env, "Main-Class"));
    if (value == NULL) {
        fprintf(stderr, "%s has no Main-Class in its manifest\n", jar);
        return NULL;
    }

    const char *text = (*env)->GetStringUTFChars(env, value, NULL);
    char *copy = strdup(text);
    (*env)->ReleaseStringUTFChars(env, value, text);
    return copy;
}

/* The name this was invoked under, which decides whether it is `java`. */
static const char *invoked_as(const char *argv0) {
    const char *slash = strrchr(argv0, '/');
    return slash ? slash + 1 : argv0;
}

/*
 * Sets LD_LIBRARY_PATH for the JDK and restarts this process once.
 *
 * libjvm.so is linked against Termux's own libraries -- libandroid-shmem.so
 * above all, which supplies the System V shared memory Bionic lacks -- and the
 * loader finds them only if LD_LIBRARY_PATH says so. **A launcher cannot rely
 * on the environment it is handed**: AGP execs `jlink` with its own, and
 * Gradle starts its daemon rather than us. Preloading each library by absolute
 * path was tried first and does not work; the loader resolves a NEEDED entry
 * against the search path, not against whatever happens to be open.
 *
 * So it does what OpenJDK's own launcher does -- and here that is *safe*.
 * `/proc/self/exe` is the stock launcher's undoing because it is started
 * through the dynamic linker, so its own exe is the linker; this file is
 * exec'd directly from nativeLibraryDir, which is executable, so re-exec'ing
 * it is an ordinary thing to do.
 *
 * AIDE_JVM_LAUNCHER_REEXEC stops it happening twice, so a genuinely missing
 * library is reported rather than looping.
 */
static void set_library_path_and_restart(const char *java_home, char **argv) {
    if (getenv("AIDE_JVM_LAUNCHER_REEXEC") != NULL) return;

    char usr_lib[4096];
    snprintf(usr_lib, sizeof(usr_lib), "%s/../..", java_home);

    char value[1 << 14];
    const char *existing = getenv("LD_LIBRARY_PATH");
    snprintf(value, sizeof(value), "%s/lib/server:%s/lib:%s%s%s",
             java_home, java_home, usr_lib,
             existing && *existing ? ":" : "", existing ? existing : "");

    setenv("LD_LIBRARY_PATH", value, 1);
    setenv("AIDE_JVM_LAUNCHER_REEXEC", "1", 1);
    execv("/proc/self/exe", argv);
    /* Only reached if execv failed; the caller reports the original error. */
}

int main(int argc, char **argv) {
    struct parsed parsed;
    memset(&parsed, 0, sizeof(parsed));

    char *java_home = resolve_java_home(argv[0]);
    if (java_home == NULL || !is_directory(java_home)) {
        fprintf(stderr,
                "cannot determine java.home: set JAVA_HOME, or invoke this as "
                "<jdk>/bin/java\n");
        return 2;
    }

    /*
     * Invoked as anything but `java` (or this file's own name), every argument
     * belongs to the tool rather than to the VM. `jlink -h` is jlink's `-h`,
     * not a VM option.
     */
    const char *name = invoked_as(argv[0]);
    int as_tool = strcmp(name, "java") != 0 && strncmp(name, "libjvmlauncher", 14) != 0;

    if (!as_tool) {
        if (parse(argc, argv, &parsed) != 0) return 2;
        if (parsed.main_class == NULL && parsed.jar == NULL && !parsed.print_version) {
            fprintf(stderr, "usage: java [options] <main-class> [args...]\n");
            return 2;
        }
    }

    char libjvm[4096];
    snprintf(libjvm, sizeof(libjvm), "%s/lib/server/libjvm.so", java_home);

    /*
     * dlopen, not execve. The JDK lives in app-private storage, which may not
     * be executed out of -- but it may be *mapped*, which is what spike R9
     * measured and what makes this whole approach possible.
     */
    void *handle = dlopen(libjvm, RTLD_NOW);
    if (handle == NULL) {
        set_library_path_and_restart(java_home, argv);
        handle = dlopen(libjvm, RTLD_NOW);
    }
    if (handle == NULL) {
        fprintf(stderr, "cannot load %s: %s\n", libjvm, dlerror());
        return 3;
    }
    CreateVm create = (CreateVm) dlsym(handle, "JNI_CreateJavaVM");
    if (create == NULL) {
        fprintf(stderr, "libjvm.so has no JNI_CreateJavaVM: %s\n", dlerror());
        return 4;
    }

    /*
     * java.home has to be told, because the VM would otherwise derive it from
     * this process's own path -- and this file is in nativeLibraryDir, nowhere
     * near the JDK. Without it the VM cannot find its own modules.
     */
    char home_option[4096];
    snprintf(home_option, sizeof(home_option), "-Djava.home=%s", java_home);

    char class_path_option[1 << 15];
    JavaVMOption options[MAX_OPTIONS + 2];
    int count = 0;
    options[count++].optionString = home_option;
    if (parsed.class_path) {
        snprintf(class_path_option, sizeof(class_path_option),
                 "-Djava.class.path=%s", parsed.class_path);
        options[count++].optionString = class_path_option;
    }
    for (int i = 0; i < parsed.option_count && count < MAX_OPTIONS; i++) {
        options[count++].optionString = parsed.options[i];
    }

    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_8;
    args.nOptions = count;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    if (create(&vm, (void **) &env, &args) != JNI_OK) {
        fprintf(stderr, "JNI_CreateJavaVM failed\n");
        return 5;
    }

    if (as_tool) {
        int result = run_tool(env, name, argv + 1, argc - 1);
        (*vm)->DestroyJavaVM(vm);
        return result;
    }

    /* With -jar the class is not known until the VM can read the manifest. */
    if (parsed.jar != NULL && parsed.main_class == NULL) {
        parsed.main_class = main_class_of_jar(env, parsed.jar);
        if (parsed.main_class == NULL) {
            (*vm)->DestroyJavaVM(vm);
            return 6;
        }
    }

    if (parsed.print_version && parsed.main_class == NULL) {
        print_version(env);
        (*vm)->DestroyJavaVM(vm);
        return 0;
    }

    /* FindClass wants slashes, and a caller naturally writes dots. */
    char class_name[1024];
    snprintf(class_name, sizeof(class_name), "%s", parsed.main_class);
    for (char *c = class_name; *c; c++) {
        if (*c == '.') *c = '/';
    }

    jclass type = (*env)->FindClass(env, class_name);
    if (type == NULL) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "class not found: %s\n", parsed.main_class);
        return 6;
    }
    jmethodID entry = (*env)->GetStaticMethodID(env, type, "main", "([Ljava/lang/String;)V");
    if (entry == NULL) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "no static void main(String[]) on %s\n", parsed.main_class);
        return 7;
    }

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray program_args =
        (*env)->NewObjectArray(env, parsed.program_arg_count, string_class, NULL);
    for (int i = 0; i < parsed.program_arg_count; i++) {
        (*env)->SetObjectArrayElement(env, program_args, i,
                                      (*env)->NewStringUTF(env, parsed.program_args[i]));
    }

    (*env)->CallStaticVoidMethod(env, type, entry, program_args);

    int failed = 0;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        failed = 1;
    }

    /*
     * DestroyJavaVM waits for non-daemon threads, which is what makes stdout
     * flush before this process exits. Skipping it truncates the program's
     * output, which looks like the program having produced less than it did.
     */
    (*vm)->DestroyJavaVM(vm);
    return failed;
}
