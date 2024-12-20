/*
 * Copyright (c) 2009-2021 jMonkeyEngine
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are
 * met:
 *
 * * Redistributions of source code must retain the above copyright
 *   notice, this list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright
 *   notice, this list of conditions and the following disclaimer in the
 *   documentation and/or other materials provided with the distribution.
 *
 * * Neither the name of 'jMonkeyEngine' nor the names of its contributors
 *   may be used to endorse or promote products derived from this software
 *   without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED
 * TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR
 * PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR
 * CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
 * EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
 * PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
 * PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
 * LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
 * NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.jme3.system.awt;

import com.jme3.post.SceneProcessor;
import com.jme3.profile.AppProfiler;
import com.jme3.renderer.RenderManager;
import com.jme3.renderer.ViewPort;
import com.jme3.renderer.queue.RenderQueue;
import com.jme3.texture.FrameBuffer;
import com.jme3.util.BufferUtils;
import com.jme3.util.Screenshots;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.geom.AffineTransform;
import java.awt.image.AffineTransformOp;
import java.awt.image.BufferStrategy;
import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

public class AwtPanel extends Canvas implements SceneProcessor {

    private static final Logger logger = Logger.getLogger(AwtPanel.class.getName());

    private boolean attachAsMain = false;

    private transient BufferedImage img;
    private transient FrameBuffer fb;
    private transient boolean srgb = false;
    private transient ByteBuffer byteBuf;
    private transient IntBuffer intBuf;
    private transient RenderManager rm;
    private PaintMode paintMode;
    private final java.util.List<ViewPort> viewPorts = new ArrayList<>();

    // Visibility/drawing vars
    private transient BufferStrategy strategy;
    private transient AffineTransformOp transformOp;
    private final AtomicBoolean hasNativePeer = new AtomicBoolean(false);
    private final AtomicBoolean showing = new AtomicBoolean(false);
    private final AtomicBoolean repaintRequest = new AtomicBoolean(false);

    // Reshape vars
    private int newWidth = 1;
    private int newHeight = 1;
    private final AtomicBoolean reshapeNeeded = new AtomicBoolean(false);
    private transient final Object lock = new Object();

    public AwtPanel(PaintMode paintMode) {
        this(paintMode, false);
    }

    public AwtPanel(PaintMode paintMode, boolean srgb) {
        this.paintMode = paintMode;
        this.srgb = srgb;
        if (paintMode == PaintMode.Accelerated) {
            setIgnoreRepaint(true);
        }

        addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                synchronized (lock) {
                    int newWidth2 = Math.max(getWidth(), 1);
                    int newHeight2 = Math.max(getHeight(), 1);
                    if (newWidth != newWidth2 || newHeight != newHeight2) {
                        newWidth = newWidth2;
                        newHeight = newHeight2;
                        reshapeNeeded.set(true);
                    }
                }
            }
        });
    }

    @Override
    public void addNotify() {
        super.addNotify();

        synchronized (lock) {
            hasNativePeer.set(true);
        }

        requestFocusInWindow();
    }

    @Override
    public void removeNotify() {
        synchronized (lock) {
            hasNativePeer.set(false);
        }

        super.removeNotify();
    }

    @Override
    public void paint(Graphics g) {
        Graphics2D g2d = (Graphics2D) g;
        synchronized (lock) {
            g2d.drawImage(img, transformOp, 0, 0);
        }
    }

    public boolean checkVisibilityState() {
        if (!hasNativePeer.get()) {
            if (strategy != null) {
                strategy = null;
            }
            return false;
        }

        boolean currentShowing = isShowing();
        showing.set(currentShowing);
        return currentShowing;
    }

    public void repaintInThread() {
        // Convert screenshot.
        byteBuf.clear();
        rm.getRenderer().readFrameBuffer(fb, byteBuf);

        synchronized (lock) {
            // All operations on img must be synchronized
            // as it is accessed from EDT.
            Screenshots.convertScreenShot2(intBuf, img);
            repaint();
        }
    }

    public void drawFrameInThread() {
        // Convert screenshot.
        byteBuf.clear();
        rm.getRenderer().readFrameBuffer(fb, byteBuf);
        Screenshots.convertScreenShot2(intBuf, img);

        synchronized (lock) {
            // All operations on strategy should be synchronized (?)
            if (strategy == null) {
                try {
                    createBufferStrategy(1,
                            new BufferCapabilities(
                                    new ImageCapabilities(true),
                                    new ImageCapabilities(true),
                                    BufferCapabilities.FlipContents.UNDEFINED)
                    );
                } catch (AWTException ex) {
                    logger.log(Level.WARNING, "Failed to create buffer strategy!", ex);
                }
                strategy = getBufferStrategy();
            }

            // Draw screenshot.
            do {
                do {
                    Graphics2D g2d = (Graphics2D) strategy.getDrawGraphics();
                    if (g2d == null) {
                        logger.log(Level.WARNING, "OGL: DrawGraphics was null.");
                        return;
                    }

                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING,
                            RenderingHints.VALUE_RENDER_SPEED);

                    g2d.drawImage(img, transformOp, 0, 0);
                    g2d.dispose();
                    strategy.show();
                } while (strategy.contentsRestored());
            } while (strategy.contentsLost());
        }
    }

    public boolean isActiveDrawing() {
        return paintMode != PaintMode.OnRequest && showing.get();
    }

    public void attachTo(boolean overrideMainFramebuffer, ViewPort... vps) {
        if (!viewPorts.isEmpty()) {
            for (ViewPort vp : viewPorts) {
                vp.setOutputFrameBuffer(null);
            }
            viewPorts.get(viewPorts.size() - 1).removeProcessor(this);
        }

        viewPorts.addAll(Arrays.asList(vps));
        viewPorts.get(viewPorts.size() - 1).addProcessor(this);

        this.attachAsMain = overrideMainFramebuffer;
    }

    @Override
    public void initialize(RenderManager rm, ViewPort vp) {
        if (this.rm == null) {
            // First time called in OGL thread
            this.rm = rm;
            reshapeInThread(1, 1);
        }
    }

    private void reshapeInThread(int width, int height) {
        byteBuf = BufferUtils.ensureLargeEnough(byteBuf, width * height * 4);
        intBuf = byteBuf.asIntBuffer();

        if (fb != null) {
            fb.dispose();
            fb = null;
        }

        fb = new FrameBuffer(width, height, 1);
        fb.setDepthTarget(FrameBuffer.FrameBufferTarget.newTarget(com.jme3.texture.Image.Format.Depth));
        fb.addColorTarget(FrameBuffer.FrameBufferTarget.newTarget(com.jme3.texture.Image.Format.RGB8));
        fb.setSrgb(srgb);

        if (attachAsMain) {
            rm.getRenderer().setMainFrameBufferOverride(fb);
        }

        synchronized (lock) {
            img = new BufferedImage(width, height, BufferedImage.TYPE_INT_BGR);
        }

        AffineTransform tx = AffineTransform.getScaleInstance(1, -1);
        tx.translate(0, -img.getHeight());
        transformOp = new AffineTransformOp(tx, AffineTransformOp.TYPE_NEAREST_NEIGHBOR);

        for (ViewPort vp : viewPorts) {
            if (!attachAsMain) {
                vp.setOutputFrameBuffer(fb);
            }
            vp.getCamera().resize(width, height, true);

            // NOTE: Hack alert. This is done ONLY for custom framebuffers.
            // Main framebuffer should use RenderManager.notifyReshape().
            for (SceneProcessor sp : vp.getProcessors()) {
                sp.reshape(vp, width, height);
            }
        }
    }

    @Override
    public boolean isInitialized() {
        return fb != null;
    }

    @Override
    public void preFrame(float tpf) {
        throw new UnsupportedOperationException("preFrame method not implemented.");
    }

    @Override
    public void postQueue(RenderQueue rq) {
        // This method is currently not implemented because it is part of a
        // framework or interface that requires a postQueue implementation.
        // It will throw an UnsupportedOperationException to indicate that
        // this method has not been implemented yet.
        throw new UnsupportedOperationException("postQueue method not implemented.");
    }

    @Override
    public void invalidate() {
        // For "PaintMode.OnDemand" only.
        repaintRequest.set(true);
    }

    void onFrameEnd() {
        if (reshapeNeeded.getAndSet(false)) {
            reshapeInThread(newWidth, newHeight);
        } else {
            if (!checkVisibilityState()) {
                return;
            }

            switch (paintMode) {
                case Accelerated:
                    drawFrameInThread();
                    break;
                case Repaint:
                    repaintInThread();
                    break;
                case OnRequest:
                    if (repaintRequest.getAndSet(false)) {
                        repaintInThread();
                    }
                    break;
            }
        }
    }

    @Override
    public void postFrame(FrameBuffer out) {
        if (!attachAsMain && out != fb) {
            throw new IllegalStateException("Why did you change the output framebuffer?");
        }
    }

    @Override
    public void reshape(ViewPort vp, int w, int h) {
        throw new UnsupportedOperationException("reshape method not implemented.");
    }

    @Override
    public void cleanup() {
        throw new UnsupportedOperationException("cleanup method not implemented.");
    }

    @Override
    public void setProfiler(AppProfiler profiler) {
        // not implemented
    }
}
