# In-app native bridge (see system/shell/NativeBridge.kt).
# ndk-build (not CMake): the NDK ships its own build scripts, so the spike needs no
# extra SDK component download.
LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE := catsmoker_bridge
LOCAL_SRC_FILES := catsmoker_bridge.c
# The spoof half (catsmoker_spoof.c) is built for the full distribution only: the
# playstore APK must carry neither it nor the class that calls it. Gradle passes
# CATSMOKER_FULL=1 from the full flavor's ndkBuild arguments.
ifeq ($(CATSMOKER_FULL),1)
LOCAL_SRC_FILES += catsmoker_spoof.c
endif
LOCAL_LDLIBS := -llog
include $(BUILD_SHARED_LIBRARY)
