APP_OPTIM := release
APP_PLATFORM := android-26
# PKGNAME must match the Java package of TProxyService.java (hev builds its JNI registration from it)
APP_CFLAGS := -O3 -DPKGNAME=com/pooy/vpn/service
# 16 KB page size compatibility (Android 15+)
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
