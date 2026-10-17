/* The in-app native bridge (see system/shell/NativeBridge.kt).
 *
 * Spike for the ladder's NDK rung: today this exposes only a build-identity string.
 * No game behavior ships here; future Unity/Vulkan work (M152/M142/M155) extends this
 * library once its product decision + ban-risk review + on-device measurement exist.
 */
#include <dlfcn.h>
#include <elf.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <android/log.h>

#define LOG_TAG "CatsmokerNDK"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

JNIEXPORT jstring JNICALL
Java_com_catsmoker_app_system_shell_NativeBridge_nativeVersion(JNIEnv *env, jclass clazz) {
    (void)clazz;
    return (*env)->NewStringUTF(env, "catsmoker-bridge 1");
}

/* Direct ELF resolution of il2cpp icalls (M152).
 *
 * Why not dlopen: on Android 10+ every ClassLoader gets its own linker namespace,
 * so an LSPosed-loaded module .so cannot see the game's libil2cpp.so by SONAME —
 * dlopen either fails or, by full path, loads a SECOND UNINITIALIZED copy whose
 * icall table is empty (all resolves NULL). Both failure modes were observed live.
 * The reference project solves the same problem with xdl (solist walk); this does
 * the smaller equivalent: function addresses are process-global, so the resolver is
 * located by parsing the game's OWN mapped file (same UID, always readable):
 * /proc/self/maps gives the load bias, the ELF dynamic symbol table gives
 * il2cpp_resolve_icall's offset, and the sum is called directly. No second copy is
 * ever loaded, no game memory is written, nothing is hidden from any detector.
 * Returns the resolved address, or NULL with the reason logged.
 */

typedef void *(*resolve_icall_f)(const char *);

static uintptr_t g_bias;
static char g_path[768];

static int read_paths(void) {
    FILE *maps = fopen("/proc/self/maps", "r");
    if (!maps) {
        LOGD("maps unreadable");
        return 0;
    }
    char line[1024];
    int found = 0;
    while (fgets(line, sizeof(line), maps)) {
        if (!strstr(line, "libil2cpp.so")) continue;
        // Fields: start-end perms offset dev inode [path]. The load bias is the
        // start of the offset-0 mapping.
        unsigned long start = 0, end = 0, offset = 0;
        char perms[8] = {0};
        char path[768] = {0};
        if (sscanf(line, "%lx-%lx %7s %lx %*s %*s %767s", &start, &end, perms, &offset, path) < 5)
            continue;
        if (path[0] != '/') continue;
        if (offset == 0 && !found) {
            g_bias = (uintptr_t)start;
            strncpy(g_path, path, sizeof(g_path) - 1);
            found = 1;
        }
    }
    fclose(maps);
    if (!found) LOGD("libil2cpp.so absent from maps");
    else LOGD("lib bias=%p path=%s", (void *)g_bias, g_path);
    return found;
}

static uint32_t elf_hash(const char *name) {
    uint32_t h = 0, g;
    while (*name) {
        h = (h << 4) + (uint8_t)*name++;
        if ((g = h & 0xf0000000)) h ^= g >> 24;
        h &= ~g;
    }
    return h;
}

static void *file_offset(FILE *f, Elf64_Addr vaddr, Elf64_Phdr *phdrs, int phnum) {
    for (int i = 0; i < phnum; ++i) {
        if (phdrs[i].p_type != PT_LOAD) continue;
        if (vaddr >= phdrs[i].p_vaddr && vaddr < phdrs[i].p_vaddr + phdrs[i].p_filesz)
            return (void *)(uintptr_t)(phdrs[i].p_offset + (vaddr - phdrs[i].p_vaddr));
    }
    return NULL;
}

static uint32_t gnu_hash(const char *name) {
    uint32_t h = 5381;
    while (*name) h = h * 33 + (uint8_t)*name++;
    return h;
}

static void *find_dyn_symbol(const char *name) {
    FILE *f = fopen(g_path, "rb");
    if (!f) {
        LOGD("cannot open %s", g_path);
        return NULL;
    }
    Elf64_Ehdr ehdr;
    if (fread(&ehdr, 1, sizeof(ehdr), f) != sizeof(ehdr) ||
        memcmp(ehdr.e_ident, ELFMAG, SELFMAG) != 0 ||
        ehdr.e_ident[EI_CLASS] != ELFCLASS64) {
        LOGD("not a 64-bit ELF");
        fclose(f);
        return NULL;
    }
    Elf64_Phdr *phdrs = malloc(ehdr.e_phentsize * ehdr.e_phnum);
    if (!phdrs) {
        fclose(f);
        return NULL;
    }
    fseek(f, ehdr.e_phoff, SEEK_SET);
    if (fread(phdrs, ehdr.e_phentsize, ehdr.e_phnum, f) != ehdr.e_phnum) {
        free(phdrs);
        fclose(f);
        return NULL;
    }
    Elf64_Addr symtab = 0, strtab = 0, strsz = 0, hash = 0, gnu_hash_tab = 0;
    for (int i = 0; i < ehdr.e_phnum; ++i) {
        if (phdrs[i].p_type != PT_DYNAMIC) continue;
        long off = (long)(uintptr_t)file_offset(f, phdrs[i].p_vaddr, phdrs, ehdr.e_phnum);
        if (!off) continue;
        fseek(f, off, SEEK_SET);
        for (uint64_t j = 0; j < phdrs[i].p_filesz / sizeof(Elf64_Dyn); ++j) {
            Elf64_Dyn dyn;
            if (fread(&dyn, 1, sizeof(dyn), f) != sizeof(dyn)) break;
            if (dyn.d_tag == DT_NULL) break;
            else if (dyn.d_tag == DT_SYMTAB) symtab = dyn.d_un.d_ptr;
            else if (dyn.d_tag == DT_STRTAB) strtab = dyn.d_un.d_ptr;
            else if (dyn.d_tag == DT_STRSZ) strsz = dyn.d_un.d_val;
            else if (dyn.d_tag == DT_HASH) hash = dyn.d_un.d_ptr;
            else if (dyn.d_tag == DT_GNU_HASH) gnu_hash_tab = dyn.d_un.d_ptr;
        }
    }
    void *found = NULL;
    long soff = 0, toff = 0;
    {
        long o1 = (long)(uintptr_t)file_offset(f, symtab, phdrs, ehdr.e_phnum);
        long o2 = (long)(uintptr_t)file_offset(f, strtab, phdrs, ehdr.e_phnum);
        if (symtab && strtab && strsz && o1 && o2) {
            soff = o1;
            toff = o2;
        }
    }
    if (!soff || !toff) {
        LOGD("no usable tables (symtab=%d strtab=%d)", !!soff, !!toff);
        free(phdrs);
        fclose(f);
        return NULL;
    }
    if (gnu_hash_tab) {
        // DT_GNU_HASH: header + bloom + buckets + chains (indexed from symoffset).
        long goff = (long)(uintptr_t)file_offset(f, gnu_hash_tab, phdrs, ehdr.e_phnum);
        if (goff) {
            uint32_t nbuckets = 0, symoffset = 0, bloom_size = 0, bloom_shift = 0;
            fseek(f, goff, SEEK_SET);
            if (fread(&nbuckets, 4, 1, f) == 1 && fread(&symoffset, 4, 1, f) == 1 &&
                fread(&bloom_size, 4, 1, f) == 1 && fread(&bloom_shift, 4, 1, f) == 1 &&
                nbuckets) {
                uint32_t h = gnu_hash(name);
                uint64_t word = 0;
                long bloomoff = goff + 16 + (long)((h / 64) % bloom_size) * 8;
                fseek(f, bloomoff, SEEK_SET);
                if (fread(&word, 8, 1, f) == 1 &&
                    (word & ((uint64_t)1 << (h % 64))) &&
                    (word & ((uint64_t)1 << ((h >> bloom_shift) % 64)))) {
                    uint32_t idx = 0;
                    fseek(f, goff + 16 + (long)bloom_size * 8 + (long)(h % nbuckets) * 4, SEEK_SET);
                    if (fread(&idx, 4, 1, f) == 1 && idx >= symoffset) {
                        for (;;) {
                            uint32_t h2 = 0;
                            fseek(f, goff + 16 + (long)bloom_size * 8 + (long)nbuckets * 4 +
                                           (long)(idx - symoffset) * 4,
                                  SEEK_SET);
                            if (fread(&h2, 4, 1, f) != 1) break;
                            if ((h2 | 1) == (h | 1)) {
                                Elf64_Sym sym;
                                fseek(f, soff + (long)idx * sizeof(sym), SEEK_SET);
                                if (fread(&sym, 1, sizeof(sym), f) == sizeof(sym) &&
                                    sym.st_name < strsz) {
                                    char symname[256];
                                    fseek(f, toff + sym.st_name, SEEK_SET);
                                    size_t k = 0;
                                    int ch;
                                    while (k < sizeof(symname) - 1 && (ch = fgetc(f)) > 0)
                                        symname[k++] = (char)ch;
                                    symname[k] = '\0';
                                    if (!strcmp(symname, name) && sym.st_value) {
                                        found = (void *)(g_bias + sym.st_value);
                                        break;
                                    }
                                }
                            }
                            if (h2 & 1) break;
                            ++idx;
                        }
                    }
                }
            }
        }
        LOGD("gnu-hash %s %s", name, found ? "resolved" : "missing");
    }
    if (!found && hash) {
        long hoff = (long)(uintptr_t)file_offset(f, hash, phdrs, ehdr.e_phnum);
        long soff = (long)(uintptr_t)file_offset(f, symtab, phdrs, ehdr.e_phnum);
        long toff = (long)(uintptr_t)file_offset(f, strtab, phdrs, ehdr.e_phnum);
        if (hoff && soff && toff) {
            uint32_t nbucket = 0, nchain = 0;
            fseek(f, hoff, SEEK_SET);
            if (fread(&nbucket, 4, 1, f) == 1 && fread(&nchain, 4, 1, f) == 1 && nbucket) {
                uint32_t *buckets = malloc(nbucket * 4);
                if (buckets && fread(buckets, 4, nbucket, f) == nbucket) {
                    uint32_t h = elf_hash(name) % nbucket;
                    uint32_t idx = buckets[h];
                    while (idx && idx < nchain) {
                        Elf64_Sym sym;
                        fseek(f, soff + (long)idx * sizeof(sym), SEEK_SET);
                        if (fread(&sym, 1, sizeof(sym), f) != sizeof(sym)) break;
                        if (sym.st_name < strsz) {
                            char symname[256];
                            fseek(f, toff + sym.st_name, SEEK_SET);
                            size_t k = 0;
                            int ch;
                            while (k < sizeof(symname) - 1 && (ch = fgetc(f)) > 0) symname[k++] = (char)ch;
                            symname[k] = '\0';
                            if (!strcmp(symname, name) && sym.st_value) {
                                found = (void *)(g_bias + sym.st_value);
                                break;
                            }
                        }
                        uint32_t next = 0;
                        long chainoff = hoff + 8 + (long)(nbucket + idx) * 4;
                        fseek(f, chainoff, SEEK_SET);
                        if (fread(&next, 4, 1, f) != 1) break;
                        idx = next;
                    }
                }
                free(buckets);
            }
        }
        LOGD("dynsym %s %s", name, found ? "resolved" : "missing");
    } else {
        LOGD("no usable tables (symtab=%d strtab=%d hash=%d)",
             !!symtab, !!strtab, !!hash);
    }
    free(phdrs);
    fclose(f);
    return found;
}

/* Opens the resolver: maps + ELF direct resolution (see above). Return codes:
 *   1  resolver ready (out_resolve set)
 *   -1 libil2cpp.so absent from maps — not a Unity/IL2CPP process, clean abort
 *   -2 resolver symbol missing — incompatible build, clean abort
 */
static int open_resolver(resolve_icall_f *out_resolve) {
    if (!read_paths()) return -1;
    *out_resolve = (resolve_icall_f)find_dyn_symbol("il2cpp_resolve_icall");
    if (!*out_resolve) return -2;
    LOGD("il2cpp_resolve_icall=%p", *out_resolve);
    return 1;
}

/* M152 write half: calls Application.set_targetFrameRate(fps) in a Unity (IL2CPP) process.
 *
 * Same chain as the read probe (dlopen → resolve icall → call), same retry loop, same
 * clean-abort codes (-1/-2/-3). Returns 1 when the setter was invoked. Invocation is all
 * this reports — the effect is proven by the frame cadence afterwards, never by this code.
 * Peak values only: callers must pass a measured panel peak (never a cap below it).
 * No opcode patching (M154, rejected), no resolution writes (M153, rejected).
 */
JNIEXPORT jint JNICALL
Java_com_catsmoker_app_system_shell_NativeBridge_nativeSetTargetFrameRate(JNIEnv *env, jclass clazz, jint fps) {
    (void)env;
    (void)clazz;
    // The library appears when Unity finishes booting; retry like the reference.
    resolve_icall_f resolve_icall = NULL;
    int stage = -1;
    for (int i = 0; i < 10 && (stage = open_resolver(&resolve_icall)) == -1; ++i) sleep(1);
    if (stage != 1) return stage;
    void *control = resolve_icall("UnityEngine.Screen::get_width");
    LOGD("control Screen::get_width %s", control ? "resolved" : "missing");
    void *control2 = resolve_icall("UnityEngine.Time::get_deltaTime");
    LOGD("control Time::get_deltaTime %s", control2 ? "resolved" : "missing");
    void (*set_targetFrameRate)(int) =
        (void (*)(int))resolve_icall("UnityEngine.Application::set_targetFrameRate");
    if (!set_targetFrameRate) return -3;
    set_targetFrameRate(fps);
    LOGD("set_targetFrameRate(%d) invoked", fps);
    return 1;
}

/* M152 read probe: asks a Unity (IL2CPP) runtime for Application.targetFrameRate.
 *
 * Follows the reference (`unity-fps-unlocker` Unity::Init, read in full) minus everything
 * that writes: dlopen libil2cpp with the reference's 10x1s retry (the library may not be
 * loaded yet when the module runs), dlsym il2cpp_resolve_icall, resolve the
 * get_targetFrameRate icall, call it. Return codes are data, never guesses:
 *   >= 0  the rate Unity reports (typical: 30 mobile default, 60, or -1 unset)
 *   -1    libil2cpp.so never appeared (not a Unity/IL2CPP process — clean abort)
 *   -2    il2cpp_resolve_icall missing (not a compatible IL2CPP build — clean abort)
 *   -3    get_targetFrameRate icall missing (clean abort)
 *
 * Threading contract (device-proven, Helix Jump Unity 6, 2026-10-13): the caller MUST
 * run on a runtime-attached thread — resolving is safe anywhere, but CALLING a resolved
 * icall from a raw thread segfaults inside libil2cpp (stable pc; even il2cpp_domain_get
 * itself faults there, so foreign-thread attach is not a way in). In practice: call from
 * an engine-owned thread (e.g. a UnityPlayer Activity callback hooked by the module).
 * The dlopen handle is deliberately never closed: closing could unload the library
 * while the resolved pointer is live (the reference never closes either).
 * Runs on the caller's thread and blocks up to ~10s — call off the main thread.
 */
JNIEXPORT jint JNICALL
Java_com_catsmoker_app_system_shell_NativeBridge_unityGetTargetFrameRate(JNIEnv *env, jclass clazz) {
    (void)env;
    (void)clazz;
    // The library appears when Unity finishes booting; retry like the reference.
    resolve_icall_f resolve_icall = NULL;
    int stage = -1;
    for (int i = 0; i < 10 && (stage = open_resolver(&resolve_icall)) == -1; ++i) sleep(1);
    if (stage != 1) return stage;
    void *control = resolve_icall("UnityEngine.Screen::get_width");
    LOGD("control Screen::get_width %s", control ? "resolved" : "missing");
    void *control2 = resolve_icall("UnityEngine.Time::get_deltaTime");
    LOGD("control Time::get_deltaTime %s", control2 ? "resolved" : "missing");
    int (*get_targetFrameRate)(void) =
        (int (*)(void))resolve_icall("UnityEngine.Application::get_targetFrameRate");
    if (!get_targetFrameRate) return -3;
    int rate = get_targetFrameRate();
    LOGD("get_targetFrameRate()=%d", rate);
    return rate;
}
