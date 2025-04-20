#include <jni.h>
#include <string>
#include <unistd.h>
#include <sys/types.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "ShizukuNative"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_catsmoker_app_ShizukuActivity_replaceFileWithShizuku(
        JNIEnv *env,
        jobject thiz, /*thiz*/
        jstring source_path,
        jstring dest_path) {

    const char *src_path = env->GetStringUTFChars(source_path, nullptr);
    const char *dst_path = env->GetStringUTFChars(dest_path, nullptr);

    if (!src_path || !dst_path) {
        LOGE("Error getting path strings");
        return JNI_FALSE;
    }

    // Open source file
    int src_fd = open(src_path, O_RDONLY);
    if (src_fd < 0) {
        LOGE("Failed to open source file: %s", src_path);
        env->ReleaseStringUTFChars(source_path, src_path);
        env->ReleaseStringUTFChars(dest_path, dst_path);
        return JNI_FALSE;
    }

    // Get file size
    struct stat st;
    if (fstat(src_fd, &st) != 0) {
        LOGE("Failed to get file size");
        close(src_fd);
        env->ReleaseStringUTFChars(source_path, src_path);
        env->ReleaseStringUTFChars(dest_path, dst_path);
        return JNI_FALSE;
    }

    // Create destination file with Shizuku permissions
    int dst_fd = open(dst_path, O_WRONLY | O_CREAT | O_TRUNC, 0644);
    if (dst_fd < 0) {
        LOGE("Failed to open destination file: %s", dst_path);
        close(src_fd);
        env->ReleaseStringUTFChars(source_path, src_path);
        env->ReleaseStringUTFChars(dest_path, dst_path);
        return JNI_FALSE;
    }

    // Copy file contents
    char buffer[4096];
    ssize_t bytes_read;
    while ((bytes_read = read(src_fd, buffer, sizeof(buffer))) > 0) {
        if (write(dst_fd, buffer, bytes_read) != bytes_read) {
            LOGE("Write failed");
            close(src_fd);
            close(dst_fd);
            env->ReleaseStringUTFChars(source_path, src_path);
            env->ReleaseStringUTFChars(dest_path, dst_path);
            return JNI_FALSE;
        }
    }

    close(src_fd);
    close(dst_fd);
    env->ReleaseStringUTFChars(source_path, src_path);
    env->ReleaseStringUTFChars(dest_path, dst_path);

    LOGD("File replaced successfully");
    return JNI_TRUE;
}

} // extern "C"
