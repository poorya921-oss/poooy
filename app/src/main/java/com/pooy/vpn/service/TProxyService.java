package com.pooy.vpn.service;

/**
 * JNI facade for hev-socks5-tunnel. The package + class name are baked into the native
 * library through -DPKGNAME (see app/src/main/jni/Application.mk) and must not change
 * independently. All three natives must be declared: the library registers all of them
 * in JNI_OnLoad and fails to load if one is missing.
 */
public final class TProxyService {
    static {
        System.loadLibrary("hev-socks5-tunnel");
    }

    private TProxyService() {}

    public static native void TProxyStartService(String configPath, int fd);

    public static native void TProxyStopService();

    public static native long[] TProxyGetStats();
}
