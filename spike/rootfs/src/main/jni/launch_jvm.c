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
            /* Refused rather than half-implemented: running a jar means
             * reading Main-Class out of its manifest, and silently running
             * the wrong thing would be worse than saying no. */
            fprintf(stderr, "-jar is not supported; pass -cp and a main class\n");
            return -1;
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

    if (parse(argc, argv, &parsed) != 0) return 2;
    if (parsed.main_class == NULL && !parsed.print_version) {
        fprintf(stderr, "usage: java [options] <main-class> [args...]\n");
        return 2;
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
