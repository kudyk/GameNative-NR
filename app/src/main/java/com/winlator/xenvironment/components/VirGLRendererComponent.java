package com.winlator.xenvironment.components;

import androidx.annotation.Keep;

import com.winlator.renderer.GLRenderer;
import com.winlator.renderer.Texture;
import com.winlator.xconnector.Client;
import com.winlator.xconnector.ConnectionHandler;
import com.winlator.xconnector.RequestHandler;
import com.winlator.xconnector.UnixSocketConfig;
import com.winlator.xconnector.XConnectorEpoll;
import com.winlator.xenvironment.EnvironmentComponent;
import com.winlator.xserver.Drawable;
import com.winlator.xserver.XServer;

import java.io.IOException;
import timber.log.Timber;

public class VirGLRendererComponent extends EnvironmentComponent implements ConnectionHandler, RequestHandler {
    private final XServer xServer;
    private final UnixSocketConfig socketConfig;
    private XConnectorEpoll connector;
    private long sharedEGLContextPtr;

    static {
        System.loadLibrary("virglrenderer");
    }

    public VirGLRendererComponent(XServer xServer, UnixSocketConfig socketConfig) {
        this.xServer = xServer;
        this.socketConfig = socketConfig;
    }

    @Override
    public void start() {
        Timber.tag("VirGLRendererComponent").d("Starting...");
        if (connector != null) return;
        connector = new XConnectorEpoll(socketConfig, this, this);
        connector.start();
    }

    @Override
    public void stop() {
        Timber.tag("VirGLRendererComponent").d("Stopping...");
        if (connector != null) {
            connector.stop();
            connector = null;
        }
    }

    @Keep
    private void killConnection(int fd) {
        connector.killConnection(connector.getClient(fd));
    }

    @Keep
    private long getSharedEGLContext() {
        Timber.tag("VirGLRendererComponent").d("Calling getSharedEGLContext");
        if (sharedEGLContextPtr != 0) return sharedEGLContextPtr;
        if (!(xServer.getRenderer() instanceof GLRenderer)) {
            Timber.tag("VirGLRendererComponent").w("getSharedEGLContext: not on the GL renderer path; returning 0");
            return 0;
        }
        GLRenderer renderer = (GLRenderer) xServer.getRenderer();
        final Thread thread = Thread.currentThread();
        try {
            renderer.xServerView.queueEvent(() -> {
                sharedEGLContextPtr = getCurrentEGLContextPtr();
                synchronized (thread) {
                    thread.notify();
                }
            });
            synchronized (thread) {
                thread.wait();
            }
        } catch (Exception e) {
            return 0;
        }
        Timber.tag("VirGLRendererComponent").d("Finished getSharedEGLContext");
        return sharedEGLContextPtr;
    }

    @Override
    public void handleConnectionShutdown(Client client) {
        long clientPtr = (long)client.getTag();
        destroyClient(clientPtr);
    }

    @Override
    public void handleNewConnection(Client client) {
        Timber.tag("VirGLRendererComponent").d("Calling handleNewConnection");
        getSharedEGLContext();
        long clientPtr = handleNewConnection(client.clientSocket.fd);
        client.setTag(clientPtr);
        Timber.tag("VirGLRendererComponent").d("Finished handleNewConnection");
    }

    @Override
    public boolean handleRequest(Client client) throws IOException {
        Timber.tag("VirGLRendererComponent").d("Calling handleRequest");
        long clientPtr = (long)client.getTag();
        handleRequest(clientPtr);
        Timber.tag("VirGLRendererComponent").d("Finished handleRequest");
        return true;
    }

    @Keep
    private void flushFrontbuffer(int drawableId, int framebuffer) {
        Timber.tag("VirGLRendererComponent").d("Calling flushFrontbuffer");
        Drawable drawable = xServer.drawableManager.getDrawable(drawableId);
        if (drawable == null) return;

        synchronized (drawable.renderLock) {
            drawable.setData(null);
            Texture texture = drawable.getTexture();
            texture.copyFromFramebuffer(framebuffer, drawable.width, drawable.height);
        }

        Runnable onDrawListener = drawable.getOnDrawListener();
        if (onDrawListener != null) onDrawListener.run();
        Timber.tag("VirGLRendererComponent").d("Finished flushFrontbuffer");
    }

    private native long handleNewConnection(int fd);

    private native void handleRequest(long clientPtr);

    private native long getCurrentEGLContextPtr();

    private native void destroyClient(long clientPtr);

    private native void destroyRenderer(long clientPtr);
}
