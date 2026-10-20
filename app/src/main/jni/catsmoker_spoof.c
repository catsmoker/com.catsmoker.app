/* The native half of the spoof channel (see features/spoofdevice/root/NativeSpoofChannel.kt).
 *
 * What this is: hooks for a *target process's own native readers* — a game SDK calling bionic's
 * property getters, a native renderer calling glGetString — so they see the assigned profile
 * instead of the device. The Java hooks cannot reach those readers, and a process answering two
 * different models to two readers is worse off than one answering the real device to both.
 *
 * Where the hook engine comes from: LSPosed/Vector's native module API. The module declares this
 * library in its full-only assets/native_init; the framework's do_dlopen hook notices the load
 * and calls the native_init export below with {version, hookFunc, unhookFunc} (Dobby inline
 * hooking, provided by the framework — nothing is bundled here). On a framework without that API
 * the library still loads and every JNI entry point still works: no hook exists, and
 * nativeChannelState() reports exactly that.
 *
 * Rules this file lives by:
 * - Nothing is written to the device: the hooks answer reads with the profile's values and call
 *   the real function for everything else. No file, property, setting or mapping is touched.
 * - Nothing is installed until a profile arrives (nativeSetSpoofProps with entries). A process
 *   that is never assigned the module keeps a zero native footprint — CatSmoker's own process
 *   included, which loads this library for the M152 Unity channel.
 * - No fabricated readings: a key the profile does not carry falls through to the device's own
 *   answer, an absent property stays absent, and values are truncated to PROP_VALUE_MAX - 1 the
 *   way bionic truncates them.
 * - This file is full-flavor only (Android.mk), like the class that calls it.
 */
#include <dlfcn.h>
#include <jni.h>
#include <pthread.h>
#include <stdint.h>
#include <string.h>

#include <android/log.h>

#define LOG_TAG "CatsmokerNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

/* bionic's limits: a property name is at most 31 characters plus NUL, a value 91 plus NUL.
 * Names here are only compared, never written to the property store, so the name buffer is
 * generous: a profile may carry a longer spelling that the Java side answers, and a native
 * reader asking for it must get the same answer. Values are truncated to the real store's
 * 91 characters, because that is what a reader of the real property would have got. */
#define CS_KEY_MAX 64
#define CS_VALUE_MAX 92

/* Fixed-size tables: a rendered profile is a few dozen keys, and an unbounded parser in a
 * hooked process is a footgun. Overflow is reported to Java (the setter returns what landed).
 *
 * The capacity carries real headroom over the largest render the app can produce — the
 * Pixel-class presets render ~82 lines, including the alias expansion — because an entry that
 * does not fit is not a missing extra: the Java lookup answers the profile for that key while a
 * native reader in the same process answers the device, which is the exact disagreement this
 * file exists to remove. A device report of `dropped > 0` therefore means the preset shape has
 * outgrown the table (raise this, or trim the render), never that the drop is harmless. */
#define CS_MAX_PROPS 128
#define CS_MAX_RULES 16

/* --------------------------------------------------------------------- framework API */

/* Layout must match the framework's NativeAPIEntries (public ABI, version 2):
 *   uint32_t version; int (*hookFunc)(void *, void *, void **); void (*unhookFunc)(void *);
 * Only plugin-free C types here, so no framework header is needed. */
typedef int (*cs_hook_f)(void *func, void *replace, void **backup);

struct cs_entries {
    uint32_t version;
    cs_hook_f hook_func;
    void *unhook_func;
};

typedef void (*cs_on_module_loaded_f)(const char *name, void *handle);

/* -------------------------------------------------------------------------- state */

struct cs_prop {
    char key[CS_KEY_MAX];
    char value[CS_VALUE_MAX];
};

struct cs_rule {
    char prefix[CS_KEY_MAX];
    char suffix[CS_KEY_MAX];
    char canonical[CS_KEY_MAX];
};

static struct cs_prop g_props[CS_MAX_PROPS];
static int g_prop_count;
static struct cs_rule g_rules[CS_MAX_RULES];
static int g_rule_count;
static char g_gpu_vendor[CS_VALUE_MAX];
static char g_gpu_renderer[CS_VALUE_MAX];

static cs_hook_f g_hook;
static uint32_t g_api_version;
static int g_configured;
static int g_property_hooks_attempted;
static int g_state;

/* One lock for both tables, the GL strings and the configured flag. Held for the duration of a
 * lookup (the caller gets a copy) and never across a call into the real function, so no hook can
 * deadlock. Lock order is always framework-mutex → this lock, never the reverse. */
static pthread_mutex_t g_lock = PTHREAD_MUTEX_INITIALIZER;

#define CS_STATE_NATIVE_INIT 1
#define CS_STATE_PROP_GET 2
#define CS_STATE_PROP_READ_CALLBACK 4
#define CS_STATE_PROP_READ 8
#define CS_STATE_GL_GLES2 16
#define CS_STATE_CONFIGURED 32
#define CS_STATE_GL_GLES1 64

/* ------------------------------------------------------------------------- helpers */

/* NUL-terminated copy of at most cap-1 bytes: bionic's truncate-and-return behaviour. */
static void cs_copy_n(char *out, size_t cap, const char *src, size_t src_len) {
    size_t n = src_len;
    if (n > cap - 1) n = cap - 1;
    if (n > 0) memcpy(out, src, n);
    out[n] = 0;
}

/* Same, from a NUL-terminated source (null copies as empty). */
static void cs_copy(char *out, size_t cap, const char *src) {
    cs_copy_n(out, cap, src, src != NULL ? strlen(src) : 0);
}

/* A profile has arrived in this process: the hooks are worth installing. */
static int cs_is_configured(void) {
    pthread_mutex_lock(&g_lock);
    int configured = g_configured;
    pthread_mutex_unlock(&g_lock);
    return configured;
}

static int cs_ends_with(const char *value, const char *tail) {
    size_t value_len = strlen(value);
    size_t tail_len = strlen(tail);
    if (tail_len > value_len) return 0;
    return memcmp(value + (value_len - tail_len), tail, tail_len) == 0;
}

/*
 * The one lookup both property flavours share. Exact key first; then the alias rules, each of
 * which answers only when its canonical key is in the table (so a rule never invents a value,
 * and the order matches the Java lookup's). Returns 1 and copies into [out] when the profile
 * answers [name], else 0 — the caller must then leave the real value alone.
 */
static int cs_lookup(const char *name, char *out, size_t out_size) {
    if (name == NULL || name[0] == 0) return 0;
    int found = 0;
    pthread_mutex_lock(&g_lock);
    for (int i = 0; i < g_prop_count; ++i) {
        if (strcmp(g_props[i].key, name) == 0) {
            cs_copy(out, out_size, g_props[i].value);
            found = 1;
            break;
        }
    }
    for (int i = 0; !found && i < g_rule_count; ++i) {
        const struct cs_rule *rule = &g_rules[i];
        if (!strncmp(name, rule->prefix, strlen(rule->prefix)) &&
            cs_ends_with(name, rule->suffix)) {
            for (int j = 0; j < g_prop_count; ++j) {
                if (strcmp(g_props[j].key, rule->canonical) == 0) {
                    cs_copy(out, out_size, g_props[j].value);
                    found = 1;
                    break;
                }
            }
        }
    }
    pthread_mutex_unlock(&g_lock);
    return found;
}

/* ------------------------------------------------------------------- property hooks */

typedef int (*cs_prop_get_f)(const char *name, char *value);
typedef void (*cs_prop_read_callback_f)(const void *pi,
                                       void (*cb)(void *, const char *, const char *, uint32_t),
                                       void *cookie);
typedef int (*cs_prop_read_f)(const void *pi, char *name, char *value);

static cs_prop_get_f g_real_prop_get;
static cs_prop_read_callback_f g_real_prop_read_callback;
static cs_prop_read_f g_real_prop_read;

/* `__system_property_get(name, value)`: the caller's buffer is PROP_VALUE_MAX bytes by
 * contract, and the return value is the length written — the same as bionic reports. */
static int cs_prop_get(const char *name, char *value) {
    char spoofed[CS_VALUE_MAX];
    if (value != NULL && cs_lookup(name, spoofed, sizeof(spoofed))) {
        size_t len = strlen(spoofed);
        memcpy(value, spoofed, len + 1);
        return (int)len;
    }
    return g_real_prop_get(name, value);
}

struct cs_callback_ctx {
    void (*real_cb)(void *, const char *, const char *, uint32_t);
    void *real_cookie;
};

/* The wrapper the real read_callback calls instead of the target's: same name, same serial,
 * the profile's value when it has one. [cookie] is a stack context, valid because the real
 * function invokes the callback before returning (bionic's contract). */
static void cs_callback_wrapper(void *cookie, const char *name, const char *value, uint32_t serial) {
    struct cs_callback_ctx *ctx = (struct cs_callback_ctx *)cookie;
    char spoofed[CS_VALUE_MAX];
    if (cs_lookup(name, spoofed, sizeof(spoofed))) value = spoofed;
    ctx->real_cb(ctx->real_cookie, name, value, serial);
}

static void cs_prop_read_callback(const void *pi,
                                  void (*cb)(void *, const char *, const char *, uint32_t),
                                  void *cookie) {
    struct cs_callback_ctx ctx = {cb, cookie};
    g_real_prop_read_callback(pi, cs_callback_wrapper, &ctx);
}

/* Legacy `__system_property_read(pi, name, value)`: read the real property first (which also
 * names it), then answer with the profile's value when it has one. Both caller buffers are
 * PROP_NAME_MAX / PROP_VALUE_MAX by contract. */
static int cs_prop_read(const void *pi, char *name, char *value) {
    if (value == NULL) return g_real_prop_read(pi, name, value);
    char real_name[CS_KEY_MAX];
    real_name[0] = 0;
    int len = g_real_prop_read(pi, real_name, value);
    if (len < 0) return len;
    char spoofed[CS_VALUE_MAX];
    if (!cs_lookup(real_name, spoofed, sizeof(spoofed))) return len;
    if (name != NULL) memcpy(name, real_name, strlen(real_name) + 1);
    size_t spoofed_len = strlen(spoofed);
    memcpy(value, spoofed, spoofed_len + 1);
    return (int)spoofed_len;
}

/* ------------------------------------------------------------------------- GL hooks */

#define CS_GL_VENDOR 0x1F00u
#define CS_GL_RENDERER 0x1F01u

typedef const unsigned char *(*cs_gl_get_string_f)(unsigned int name);

static cs_gl_get_string_f g_real_gl2;
static cs_gl_get_string_f g_real_gl1;

/* The profile's GL string, or NULL to let the real one through. The returned pointer aliases
 * the static buffer, which never moves: a later profile assignment can change what a stale
 * pointer reads, but it can never dangle. */
static const unsigned char *cs_gpu_string(unsigned int name) {
    const char *value = NULL;
    pthread_mutex_lock(&g_lock);
    if (name == CS_GL_VENDOR && g_gpu_vendor[0] != 0) value = g_gpu_vendor;
    else if (name == CS_GL_RENDERER && g_gpu_renderer[0] != 0) value = g_gpu_renderer;
    pthread_mutex_unlock(&g_lock);
    return (const unsigned char *)value;
}

/* The GLES2 and GLES1 wrappers are separate libraries with separate originals, hence two
 * replacements. Both answer the same rule. */
static const unsigned char *cs_gl2_get_string(unsigned int name) {
    const unsigned char *spoofed = cs_gpu_string(name);
    return spoofed != NULL ? spoofed : g_real_gl2(name);
}

static const unsigned char *cs_gl1_get_string(unsigned int name) {
    const unsigned char *spoofed = cs_gpu_string(name);
    return spoofed != NULL ? spoofed : g_real_gl1(name);
}

/*
 * Installs one hook through the framework's engine. Returns 1 on success; a failure leaves the
 * function untouched and the caller records no state bit for it — never a claim it did not earn.
 */
static int cs_hook_install(void *target, void *replace, void **backup) {
    if (target == NULL || replace == NULL || backup == NULL || g_hook == NULL) return 0;
    *backup = NULL;
    if (g_hook(target, replace, backup) != 0) return 0;
    return *backup != NULL;
}

/*
 * Hooks the three bionic entry points, once per process. The symbols are resolved by name
 * because libc is the definition site for all of them; a missing symbol (older or stripped
 * libc) drops that hook and is reported through the state mask instead of failing the channel.
 *
 * Runs from the JNI setter — i.e. outside the framework's own dlopen hook and its mutex — so
 * installing here can never re-enter the loader. Called only when a profile has arrived.
 */
static void cs_install_property_hooks(void) {
    if (g_hook == NULL || g_property_hooks_attempted) return;
    g_property_hooks_attempted = 1;
    void *symbol = dlsym(RTLD_DEFAULT, "__system_property_get");
    if (symbol != NULL &&
        cs_hook_install(symbol, (void *)cs_prop_get, (void **)&g_real_prop_get)) {
        g_state |= CS_STATE_PROP_GET;
    } else {
        LOGD("__system_property_get not hooked (%s)", symbol ? "hook failed" : "symbol missing");
    }
    symbol = dlsym(RTLD_DEFAULT, "__system_property_read_callback");
    if (symbol != NULL && cs_hook_install(symbol, (void *)cs_prop_read_callback,
                                          (void **)&g_real_prop_read_callback)) {
        g_state |= CS_STATE_PROP_READ_CALLBACK;
    } else {
        LOGD("__system_property_read_callback not hooked (%s)",
             symbol ? "hook failed" : "symbol missing");
    }
    symbol = dlsym(RTLD_DEFAULT, "__system_property_read");
    if (symbol != NULL &&
        cs_hook_install(symbol, (void *)cs_prop_read, (void **)&g_real_prop_read)) {
        g_state |= CS_STATE_PROP_READ;
    } else {
        LOGD("__system_property_read not hooked (%s)", symbol ? "hook failed" : "symbol missing");
    }
}

/* Hooks glGetString in whichever GL wrapper was just loaded. Runs inside the framework's dlopen
 * hook (the only place a late load becomes visible), so it must stay cheap and must never call
 * back into the loader — dlsym on the handle it was handed is exactly what the framework itself
 * does there, and Dobby patches memory rather than loading anything. */
static void cs_hook_gl_library(void *handle, int gles1) {
    if (handle == NULL || g_hook == NULL || !cs_is_configured()) return;
    void *symbol = dlsym(handle, "glGetString");
    if (symbol == NULL) {
        LOGD("%s has no glGetString", gles1 ? "libGLESv1_CM.so" : "libGLESv2.so");
        return;
    }
    if (gles1) {
        if (cs_hook_install(symbol, (void *)cs_gl1_get_string, (void **)&g_real_gl1)) {
            g_state |= CS_STATE_GL_GLES1;
            LOGD("glGetString hooked in libGLESv1_CM.so");
        } else {
            LOGD("glGetString not hooked in libGLESv1_CM.so");
        }
    } else if (cs_hook_install(symbol, (void *)cs_gl2_get_string, (void **)&g_real_gl2)) {
        g_state |= CS_STATE_GL_GLES2;
        LOGD("glGetString hooked in libGLESv2.so");
    } else {
        LOGD("glGetString not hooked in libGLESv2.so");
    }
}

/* The framework calls this for every library loaded after the module registered, our own load
 * included. Only the two GL wrappers are interesting; everything else returns immediately. */
static void cs_on_module_loaded(const char *name, void *handle) {
    if (name == NULL || handle == NULL) return;
    if (cs_ends_with(name, "libGLESv2.so")) {
        cs_hook_gl_library(handle, 0);
    } else if (cs_ends_with(name, "libGLESv1_CM.so")) {
        cs_hook_gl_library(handle, 1);
    }
}

/* ------------------------------------------------------------------- module entry point */

/*
 * Called by the framework when this library is loaded into a target process. Only pointers are
 * stored here — no hook is installed and nothing is loaded: the tables arrive later through the
 * JNI setters, so a process that never gets a profile stays untouched.
 *
 * The framework's unhookFunc is deliberately never called: hooks become pass-throughs when the
 * profile is cleared (an empty table), and destroying an inline hook on a function that other
 * threads are executing is a crash, not a cleanup.
 */
__attribute__((visibility("default")))
cs_on_module_loaded_f native_init(const struct cs_entries *entries) {
    if (entries == NULL || entries->version < 2 || entries->hook_func == NULL) {
        LOGD("native_init without a usable hook engine (version %u)",
             entries != NULL ? entries->version : 0);
        return NULL;
    }
    g_api_version = entries->version;
    g_hook = entries->hook_func;
    g_state |= CS_STATE_NATIVE_INIT;
    LOGD("native_init: api version %u", g_api_version);
    return cs_on_module_loaded;
}

/* ---------------------------------------------------------------------- JNI setters */

/* Replaces the property table from `key=value` lines. Empty input clears it, which is how an
 * un-assigned profile takes effect: the hooks stay installed and pass every read through. */
JNIEXPORT jint JNICALL
Java_com_catsmoker_app_features_spoofdevice_root_NativeSpoofChannel_nativeSetSpoofProps(
    JNIEnv *env, jclass clazz, jstring blob) {
    (void)clazz;
    const char *text = blob != NULL ? (*env)->GetStringUTFChars(env, blob, NULL) : NULL;
    int count = 0;
    pthread_mutex_lock(&g_lock);
    if (text != NULL) {
        const char *line = text;
        while (*line != 0 && count < CS_MAX_PROPS) {
            const char *end = strchr(line, '\n');
            size_t len = end != NULL ? (size_t)(end - line) : strlen(line);
            if (len > 0 && line[len - 1] == '\r') --len;
            const char *eq = memchr(line, '=', len);
            if (eq != NULL && eq > line) {
                size_t key_len = (size_t)(eq - line);
                size_t value_len = len - key_len - 1;
                if (key_len < CS_KEY_MAX && value_len > 0) {
                    memcpy(g_props[count].key, line, key_len);
                    g_props[count].key[key_len] = 0;
                    cs_copy_n(g_props[count].value, CS_VALUE_MAX, eq + 1, value_len);
                    ++count;
                }
            }
            if (end == NULL) break;
            line = end + 1;
        }
    }
    g_prop_count = count;
    if (count > 0) g_configured = 1;
    pthread_mutex_unlock(&g_lock);
    if (text != NULL) (*env)->ReleaseStringUTFChars(env, blob, text);
    if (count > 0) cs_install_property_hooks();
    return count;
}

/* Replaces the alias rules from `prefix|suffix=canonical` lines (see PropAliases.SUFFIX). */
JNIEXPORT jint JNICALL
Java_com_catsmoker_app_features_spoofdevice_root_NativeSpoofChannel_nativeSetAliasRules(
    JNIEnv *env, jclass clazz, jstring blob) {
    (void)clazz;
    const char *text = blob != NULL ? (*env)->GetStringUTFChars(env, blob, NULL) : NULL;
    int count = 0;
    pthread_mutex_lock(&g_lock);
    if (text != NULL) {
        const char *line = text;
        while (*line != 0 && count < CS_MAX_RULES) {
            const char *end = strchr(line, '\n');
            size_t len = end != NULL ? (size_t)(end - line) : strlen(line);
            if (len > 0 && line[len - 1] == '\r') --len;
            const char *eq = memchr(line, '=', len);
            const char *bar = eq != NULL ? memchr(line, '|', (size_t)(eq - line)) : NULL;
            if (eq != NULL && bar != NULL && bar > line && eq > bar + 1) {
                size_t prefix_len = (size_t)(bar - line);
                size_t suffix_len = (size_t)(eq - bar - 1);
                size_t canonical_len = len - (size_t)(eq - line) - 1;
                if (prefix_len < CS_KEY_MAX && suffix_len < CS_KEY_MAX &&
                    canonical_len > 0 && canonical_len < CS_KEY_MAX) {
                    memcpy(g_rules[count].prefix, line, prefix_len);
                    g_rules[count].prefix[prefix_len] = 0;
                    memcpy(g_rules[count].suffix, bar + 1, suffix_len);
                    g_rules[count].suffix[suffix_len] = 0;
                    memcpy(g_rules[count].canonical, eq + 1, canonical_len);
                    g_rules[count].canonical[canonical_len] = 0;
                    ++count;
                }
            }
            if (end == NULL) break;
            line = end + 1;
        }
    }
    g_rule_count = count;
    pthread_mutex_unlock(&g_lock);
    if (text != NULL) (*env)->ReleaseStringUTFChars(env, blob, text);
    return count;
}

/* Sets the GL strings a profile answers with; null or empty leaves that string to the device. */
JNIEXPORT void JNICALL
Java_com_catsmoker_app_features_spoofdevice_root_NativeSpoofChannel_nativeSetGpuStrings(
    JNIEnv *env, jclass clazz, jstring vendor, jstring renderer) {
    (void)clazz;
    const char *vendor_text = vendor != NULL ? (*env)->GetStringUTFChars(env, vendor, NULL) : NULL;
    const char *renderer_text =
        renderer != NULL ? (*env)->GetStringUTFChars(env, renderer, NULL) : NULL;
    pthread_mutex_lock(&g_lock);
    cs_copy(g_gpu_vendor, CS_VALUE_MAX, vendor_text);
    cs_copy(g_gpu_renderer, CS_VALUE_MAX, renderer_text);
    if (g_gpu_vendor[0] != 0 || g_gpu_renderer[0] != 0) g_configured = 1;
    pthread_mutex_unlock(&g_lock);
    if (vendor_text != NULL) (*env)->ReleaseStringUTFChars(env, vendor, vendor_text);
    if (renderer_text != NULL) (*env)->ReleaseStringUTFChars(env, renderer, renderer_text);
}

/* The state bitmask; the Kotlin side mirrors the bit names (NativeSpoofChannel). */
JNIEXPORT jint JNICALL
Java_com_catsmoker_app_features_spoofdevice_root_NativeSpoofChannel_nativeChannelState(
    JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    return g_state | (g_configured ? CS_STATE_CONFIGURED : 0);
}

/*
 * What a native read of [key] returns in this process, or null when the value is empty.
 *
 * Deliberately goes through `dlsym` rather than a direct call: the address dlsym returns is the
 * one the hooks patched, so the answer is what this process's own native code would get — the
 * evidence the Kotlin side logs beside the profile's value. Returns null (never "") when the
 * process has no such property, so a miss is never reported as a spoofed empty string.
 */
JNIEXPORT jstring JNICALL
Java_com_catsmoker_app_features_spoofdevice_root_NativeSpoofChannel_nativeProbeProperty(
    JNIEnv *env, jclass clazz, jstring key) {
    (void)clazz;
    if (key == NULL) return NULL;
    const char *name = (*env)->GetStringUTFChars(env, key, NULL);
    if (name == NULL) return NULL;
    jstring result = NULL;
    cs_prop_get_f get = (cs_prop_get_f)dlsym(RTLD_DEFAULT, "__system_property_get");
    if (get != NULL) {
        char value[CS_VALUE_MAX];
        value[0] = 0;
        if (get(name, value) > 0 && value[0] != 0) result = (*env)->NewStringUTF(env, value);
    }
    (*env)->ReleaseStringUTFChars(env, key, name);
    return result;
}
