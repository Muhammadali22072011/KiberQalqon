/*
 * KiberQalqon — libkqguard.so
 *
 * Faqat ikkita ish qiladi, lekin nativeda (DEX'dan qiyin patch qilinadi):
 *   1) nSigInvalid(hexUpper) — release imzo SHA-256'ini tekshiradi. true = NOTO'G'RI.
 *   2) nAntiDebug()          — /proc/self/status TracerPid orqali debugger ulanganmi.
 *
 * Ramzlar JNI_OnLoad ichida RegisterNatives bilan bog'lanadi — shunda R8 Kotlin
 * tomonidagi metod nomlarini o'zgartirsa ham bog'lash (binding) buzilmaydi.
 *
 * Bu yerda MAXFIY SIR YO'Q (sirlar Shield orqali Secrets.kt'da). KQ_EXPECTED_SIG —
 * ochiq imzo-xesh(lar), vergul bilan ajratilgan to'plam (SD-01: sideload + Play
 * App Signing), build vaqtida -D bilan beriladi (CMakeLists.txt).
 */
#include <jni.h>
#include <string.h>
#include <strings.h>   /* strcasecmp */
#include <unistd.h>
#include <fcntl.h>

#ifndef KQ_EXPECTED_SIG
#define KQ_EXPECTED_SIG ""
#endif

/* Kutubxona yuklanib, ramzlar bog'langanini tasdiqlash uchun. */
static jboolean nPing(JNIEnv *e, jclass c) {
    (void) e; (void) c;
    return JNI_TRUE;
}

/*
 * Anti-debug: /proc/self/status'dan TracerPid o'qiymiz. 0 dan farqli bo'lsa —
 * jarayon kuzatilmoqda (debugger/ptrace). O'qib bo'lmasa — JNI_FALSE (qoqilmagan),
 * fail-open: profiler/legit holatlarda yolg'on ijobiy bermaymiz.
 */
static jboolean nAntiDebug(JNIEnv *e, jclass c) {
    (void) e; (void) c;
    int fd = open("/proc/self/status", O_RDONLY);
    if (fd < 0) return JNI_FALSE;
    char buf[4096];
    ssize_t n = read(fd, buf, sizeof(buf) - 1);
    close(fd);
    if (n <= 0) return JNI_FALSE;
    buf[n] = 0;
    const char *p = strstr(buf, "TracerPid:");
    if (!p) return JNI_FALSE;
    p += 10; /* strlen("TracerPid:") */
    while (*p == ' ' || *p == '\t') p++;
    return (*p != '0' && *p != '\n' && *p != '\r' && *p != 0) ? JNI_TRUE : JNI_FALSE;
}

/*
 * SD-01: KQ_EXPECTED_SIG — VERGUL bilan ajratilgan ruxsat etilgan imzolar TO'PLAMI
 * (sideload release.keystore + kelajakda Play App Signing serti). Bo'sh segmentlar
 * e'tiborga olinmaydi. 1 = mos keldi.
 */
static int kq_sig_matches(const char *got) {
    const char *p = KQ_EXPECTED_SIG;
    size_t got_len = strlen(got);
    while (*p) {
        const char *end = p;
        while (*end && *end != ',') end++;
        size_t len = (size_t) (end - p);
        if (len > 0 && len == got_len && strncasecmp(got, p, len) == 0) return 1;
        p = (*end == ',') ? end + 1 : end;
    }
    return 0;
}

/*
 * Imzo tekshiruvi. Kotlin SecurityGuard hisoblagan imzo SHA-256'sini (UPPERCASE hex,
 * cert DER baytlaridan) uzatadi. Native ichidagi kutilgan to'plam bilan solishtiradi.
 * Qaytaradi: JNI_TRUE = NOTO'G'RI (mos kelmadi) — isSignatureInvalid semantikasi.
 * Aniq tasdiqlay olmasa (kalit yo'q / null / mos emas) → JNI_TRUE (himoya tomon).
 */
static jboolean nSigInvalid(JNIEnv *e, jclass c, jstring hexIn) {
    (void) c;
    if (KQ_EXPECTED_SIG[0] == 0) return JNI_TRUE; /* sozlanmagan → tasdiqlay olmaymiz */
    if (hexIn == NULL) return JNI_TRUE;
    const char *got = (*e)->GetStringUTFChars(e, hexIn, NULL);
    int bad = (got == NULL) || !kq_sig_matches(got);
    if (got) (*e)->ReleaseStringUTFChars(e, hexIn, got);
    return bad ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    (void) reserved;
    JNIEnv *env;
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_6) != JNI_OK) return -1;
    jclass cls = (*env)->FindClass(env, "com/kiberqalqon/NativeBridge");
    if (!cls) return -1;
    static const JNINativeMethod methods[] = {
        {"nPing",       "()Z",                   (void *) nPing},
        {"nAntiDebug",  "()Z",                   (void *) nAntiDebug},
        {"nSigInvalid", "(Ljava/lang/String;)Z", (void *) nSigInvalid},
    };
    if ((*env)->RegisterNatives(env, cls, methods,
                                sizeof(methods) / sizeof(methods[0])) != 0) {
        return -1;
    }
    return JNI_VERSION_1_6;
}
