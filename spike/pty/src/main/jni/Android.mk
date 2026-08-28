LOCAL_PATH := $(call my-dir)

include $(CLEAR_VARS)
LOCAL_MODULE    := aide-pty
LOCAL_SRC_FILES := pty.c
# forkpty(3) lives in libc on Bionic, unlike glibc where it is in libutil.
LOCAL_LDLIBS    := -llog
include $(BUILD_SHARED_LIBRARY)
