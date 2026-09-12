package com.winlator.xconnector;

import androidx.annotation.Keep;

@Keep
public final class XConnectorEpollNative {
    static {
        System.loadLibrary("xconnectorpatch");
    }

    private XConnectorEpollNative() {
    }

    @Keep
    public static native boolean addFdToEpoll(int epollFd, int fd);

    @Keep
    public static native int createAFUnixSocket(String path);

    @Keep
    public static native int createEpollFd();

    @Keep
    public static native int createEventFd();

    @Keep
    public static native boolean doEpollIndefinitely(XConnectorEpoll connector, int epollFd, int serverFd, boolean addClientToEpoll);

    @Keep
    public static native void removeFdFromEpoll(int epollFd, int fd);

    @Keep
    public static native boolean waitForSocketRead(XConnectorEpoll connector, int clientFd, int shutdownFd);

    @Keep
    public static native void closeFd(int fd);

    @Keep
    public static native void setLoggingEnabled(boolean enabled);
}
