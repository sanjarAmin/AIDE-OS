/*
 * A JVM launcher that does not re-exec.
 *
 * OpenJDK's own `bin/java` re-execs itself through /proc/self/exe to fix its
 * environment. Under the only launch route this app has, /proc/self/exe is
 * /system/bin/linker64, so the re-exec becomes `linker64 -version` and dies.
 * tools/rootfs/FINDINGS.md section 3.
 *
 * The launcher is not the VM, though: it is a wrapper around
 * JNI_CreateJavaVM in libjvm.so. Calling that directly skips the wrapper and
 * everything it does to its own process.
 *
 * Usage:  libjvmlauncher.so <java-home> <main-class> [args...]
 *
 * Options for the VM are taken from the JVM_OPTIONS environment variable,
 * space separated, because passing them as argv would make it ambiguous where
 * the VM's arguments end and the program's begin.
 */
#include <dlfcn.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

/*
 * The NDK's jni.h describes *Android's* JNI, which stops at 1.6. This launcher
 * talks to OpenJDK's VM, which wants at least 1.8 in JavaVMInitArgs.version --
 * so the constant has to be supplied rather than included. A reminder that the
 * header at hand and the runtime being driven are not the same implementation.
 */
#ifndef JNI_VERSION_1_8
#define JNI_VERSION_1_8 0x00010008
#endif

typedef jint (*CreateVm)(JavaVM **, void **, void *);

/* Splits JVM_OPTIONS on spaces. Returns how many were found. */
static int collect_options(char *raw, JavaVMOption *options, int limit) {
    int count = 0;
    if (raw == NULL) return 0;
    for (char *token = strtok(raw, " "); token && count < limit; token = strtok(NULL, " ")) {
        if (*token) options[count++].optionString = token;
    }
    return count;
}

int main(int argc, char **argv) {
    if (argc < 3) {
        fprintf(stderr, "usage: %s <java-home> <main-class> [args...]\n", argv[0]);
        return 2;
    }
    const char *java_home = argv[1];
    const char *main_class = argv[2];

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

    JavaVMOption options[64];
    int option_count = 0;

    /*
     * java.home has to be told, because the VM would otherwise derive it from
     * the launcher's own path -- and the launcher is in nativeLibraryDir,
     * nowhere near the JDK. Without it the VM cannot find its own modules and
     * fails before running anything.
     */
    char home_option[4096];
    snprintf(home_option, sizeof(home_option), "-Djava.home=%s", java_home);
    options[option_count++].optionString = home_option;

    char *raw_options = getenv("JVM_OPTIONS");
    char *copy = raw_options ? strdup(raw_options) : NULL;
    option_count += collect_options(copy, options + option_count, 60 - option_count);

    JavaVMInitArgs args;
    args.version = JNI_VERSION_1_8;
    args.nOptions = option_count;
    args.options = options;
    args.ignoreUnrecognized = JNI_FALSE;

    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint created = create(&vm, (void **) &env, &args);
    if (created != JNI_OK) {
        fprintf(stderr, "JNI_CreateJavaVM failed: %d\n", created);
        return 5;
    }

    /* FindClass wants slashes, and a caller naturally writes dots. */
    char class_name[1024];
    snprintf(class_name, sizeof(class_name), "%s", main_class);
    for (char *c = class_name; *c; c++) {
        if (*c == '.') *c = '/';
    }

    jclass type = (*env)->FindClass(env, class_name);
    if (type == NULL) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "class not found: %s\n", main_class);
        return 6;
    }

    jmethodID entry = (*env)->GetStaticMethodID(env, type, "main", "([Ljava/lang/String;)V");
    if (entry == NULL) {
        (*env)->ExceptionDescribe(env);
        fprintf(stderr, "no static void main(String[]) on %s\n", main_class);
        return 7;
    }

    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    jobjectArray program_args = (*env)->NewObjectArray(env, argc - 3, string_class, NULL);
    for (int i = 3; i < argc; i++) {
        jstring value = (*env)->NewStringUTF(env, argv[i]);
        (*env)->SetObjectArrayElement(env, program_args, i - 3, value);
    }

    (*env)->CallStaticVoidMethod(env, type, entry, program_args);

    int failed = 0;
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionDescribe(env);
        failed = 8;
    }

    /*
     * DestroyJavaVM waits for non-daemon threads, which is what makes stdout
     * flush before this process exits. Skipping it truncates the program's
     * output, which looks like the program having produced less than it did.
     */
    (*vm)->DestroyJavaVM(vm);
    free(copy);
    return failed;
}
