/*
 * Copyright (c) 1996, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package sun.awt.windows;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.*;
import java.awt.peer.*;

import java.beans.*;

import java.lang.reflect.*;

import java.util.*;
import java.util.List;
import java.util.logging.*;

import sun.awt.*;

import sun.java2d.pipe.Region;

public class WWindowPeer extends WPanelPeer implements WindowPeer {

    private static final Logger log = Logger.getLogger("sun.awt.windows.WWindowPeer");
    private static final Logger screenLog = Logger.getLogger("sun.awt.windows.screen.WWindowPeer");

    // we can't use WDialogPeer as blocker may be an instance of WPrintDialogPeer that
    // extends WWindowPeer, not WDialogPeer
    private WWindowPeer modalBlocker = null;

    private boolean isOpaque;

    private TranslucentWindowPainter painter;

    /*
     * A key used for storing a list of active windows in AppContext. The value
     * is a list of windows, sorted by the time of activation: later a window is
     * activated, greater its index is in the list.
     */
    private final static StringBuffer ACTIVE_WINDOWS_KEY =
        new StringBuffer("active_windows_list");

    /*
     * Listener for 'activeWindow' KFM property changes. It is added to each
     * AppContext KFM. See ActiveWindowListener inner class below.
     */
    private static PropertyChangeListener activeWindowListener =
        new ActiveWindowListener();

    /*
     * Contains all the AppContexts where activeWindowListener is added to.
     */
    private static Set<AppContext> trackedAppContexts = new HashSet<AppContext>();

    /**
     * Initialize JNI field IDs
     */
    private static native void initIDs();
    static {
        initIDs();
    }

    // WComponentPeer overrides

    protected void disposeImpl() {
        AppContext appContext = SunToolkit.targetToAppContext(target);
        synchronized (appContext) {
            List<WWindowPeer> l = (List<WWindowPeer>)appContext.get(ACTIVE_WINDOWS_KEY);
            if (l != null) {
                l.remove(this);
            }
        }

        // Remove ourself from the Map of DisplayChangeListeners
        GraphicsConfiguration gc = getGraphicsConfiguration();
        ((Win32GraphicsDevice)gc.getDevice()).removeDisplayChangedListener(this);

        synchronized (getStateLock()) {
            TranslucentWindowPainter currentPainter = painter;
            if (currentPainter != null) {
                currentPainter.flush();
                // don't set the current one to null here; reduces the chances of
                // MT issues (like NPEs)
            }
        }

        super.disposeImpl();
    }

    // WindowPeer implementation

    public void toFront() {
        updateFocusableWindowState();
        _toFront();
    }
    native void _toFront();
    public native void toBack();

    public native void setAlwaysOnTopNative(boolean value);
    public void setAlwaysOnTop(boolean value) {
        if ((value && ((Window)target).isVisible()) || !value) {
            setAlwaysOnTopNative(value);
        }
    }

    public void updateAlwaysOnTopState() {
        setAlwaysOnTop(((Window)target).isAlwaysOnTop());
    }

    public void updateFocusableWindowState() {
        setFocusableWindow(((Window)target).isFocusableWindow());
    }
    native void setFocusableWindow(boolean value);

    // FramePeer & DialogPeer partial shared implementation

    public void setTitle(String title) {
        // allow a null title to pass as an empty string.
        if (title == null) {
            title = new String("");
        }
        _setTitle(title);
    }
    native void _setTitle(String title);

    public void setResizable(boolean resizable) {
        _setResizable(resizable);
    }
    public native void _setResizable(boolean resizable);

    // Toolkit & peer internals

    WWindowPeer(Window target) {
        super(target);
    }

    void initialize() {
        super.initialize();

        updateInsets(insets_);

        Font f = ((Window)target).getFont();
        if (f == null) {
            f = defaultFont;
            ((Window)target).setFont(f);
            setFont(f);
        }
        // Express our interest in display changes
        GraphicsConfiguration gc = getGraphicsConfiguration();
        ((Win32GraphicsDevice)gc.getDevice()).addDisplayChangedListener(this);

        AppContext appContext = AppContext.getAppContext();
        synchronized (appContext) {
            if (!trackedAppContexts.contains(appContext)) {
                KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
                kfm.addPropertyChangeListener("activeWindow", activeWindowListener);
                trackedAppContexts.add(appContext);
            }
        }

        updateIconImages();

        Shape shape = AWTAccessor.getWindowAccessor().getShape((Window)target);
        if (shape != null) {
            applyShape(Region.getInstance(shape, null));
        }

        float opacity = AWTAccessor.getWindowAccessor().getOpacity((Window)target);
        if (opacity < 1.0f) {
            setOpacity(opacity);
        }

        synchronized (getStateLock()) {
            // default value of a boolean field is 'false', so set isOpaque to
            // true here explicitly
            this.isOpaque = true;
            Color bgColor = ((Window)target).getBackground();
            setOpaque((bgColor == null) || (bgColor.getAlpha() == 255));
        }
    }

    native void createAwtWindow(WComponentPeer parent);
    void create(WComponentPeer parent) {
        createAwtWindow(parent);
    }

    // should be overriden in WDialogPeer
    protected void realShow() {
        super.show();
    }

    public void show() {
        updateFocusableWindowState();

        boolean alwaysOnTop = ((Window)target).isAlwaysOnTop();

        // Fix for 4868278.
        // If we create a window with a specific GraphicsConfig, and then move it with
        // setLocation() or setBounds() to another one before its peer has been created,
        // then calling Window.getGraphicsConfig() returns wrong config. That may lead
        // to some problems like wrong-placed tooltips. It is caused by calling
        // super.displayChanged() in WWindowPeer.displayChanged() regardless of whether
        // GraphicsDevice was really changed, or not. So we need to track it here.
        updateGC();
        resetTargetGC();

        realShow();
        updateMinimumSize();

        if (((Window)target).isAlwaysOnTopSupported() && alwaysOnTop) {
            setAlwaysOnTop(alwaysOnTop);
        }

        synchronized (getStateLock()) {
            if (!isOpaque) {
                updateWindow(true);
            }
        }
    }

    // Synchronize the insets members (here & in helper) with actual window
    // state.
    native void updateInsets(Insets i);

    static native int getSysMinWidth();
    static native int getSysMinHeight();
    static native int getSysIconWidth();
    static native int getSysIconHeight();
    static native int getSysSmIconWidth();
    static native int getSysSmIconHeight();
    /**windows/classes/sun/awt/windows/
     * Creates native icon from specified raster data and updates
     * icon for window and all descendant windows that inherit icon.
     * Raster data should be passed in the ARGB form.
     * Note that raster data format was changed to provide support
     * for XP icons with alpha-channel
     */
    native void setIconImagesData(int[] iconRaster, int w, int h,
                                  int[] smallIconRaster, int smw, int smh);

    synchronized native void reshapeFrame(int x, int y, int width, int height);
    public boolean requestWindowFocus() {
        // Win32 window doesn't need this
        return false;
    }

    public boolean focusAllowedFor() {
        Window target = (Window)this.target;
        if (!target.isVisible() ||
            !target.isEnabled() ||
            !target.isFocusable())
        {
            return false;
        }

        if (isModalBlocked()) {
            return false;
        }

        return true;
    }

    public void updateMinimumSize() {
        Dimension minimumSize = null;
        if (((Component)target).isMinimumSizeSet()) {
            minimumSize = ((Component)target).getMinimumSize();
        }
        if (minimumSize != null) {
            int msw = getSysMinWidth();
            int msh = getSysMinHeight();
            int w = (minimumSize.width >= msw) ? minimumSize.width : msw;
            int h = (minimumSize.height >= msh) ? minimumSize.height : msh;
            setMinSize(w, h);
        } else {
            setMinSize(0, 0);
        }
    }

    public void updateIconImages() {
        java.util.List<Image> imageList = ((Window)target).getIconImages();
        if (imageList == null || imageList.size() == 0) {
            setIconImagesData(null, 0, 0, null, 0, 0);
        } else {
            int w = getSysIconWidth();
            int h = getSysIconHeight();
            int smw = getSysSmIconWidth();
            int smh = getSysSmIconHeight();
            DataBufferInt iconData = SunToolkit.getScaledIconData(imageList,
                                                                  w, h);
            DataBufferInt iconSmData = SunToolkit.getScaledIconData(imageList,
                                                                    smw, smh);
            if (iconData != null && iconSmData != null) {
                setIconImagesData(iconData.getData(), w, h,
                                  iconSmData.getData(), smw, smh);
            } else {
                setIconImagesData(null, 0, 0, null, 0, 0);
            }
        }
    }

    native void setMinSize(int width, int height);

/*
 * ---- MODALITY SUPPORT ----
 */

    /**
     * Some modality-related code here because WFileDialogPeer, WPrintDialogPeer and
     *   WPageDialogPeer are descendants of WWindowPeer, not WDialogPeer
     */

    public boolean isModalBlocked() {
        return modalBlocker != null;
    }

    public void setModalBlocked(Dialog dialog, boolean blocked) {
        synchronized (((Component)getTarget()).getTreeLock()) // State lock should always be after awtLock
        {
            // use WWindowPeer instead of WDialogPeer because of FileDialogs and PrintDialogs
            WWindowPeer blockerPeer = (WWindowPeer)dialog.getPeer();
            if (blocked)
            {
                modalBlocker = blockerPeer;
                // handle native dialogs separately, as they may have not
                // got HWND yet; modalEnable/modalDisable is called from
                // their setHWnd() methods
                if (blockerPeer instanceof WFileDialogPeer) {
                    ((WFileDialogPeer)blockerPeer).blockWindow(this);
                } else if (blockerPeer instanceof WPrintDialogPeer) {
                    ((WPrintDialogPeer)blockerPeer).blockWindow(this);
                } else {
                    modalDisable(dialog, blockerPeer.getHWnd());
                }
            } else {
                modalBlocker = null;
                if (blockerPeer instanceof WFileDialogPeer) {
                    ((WFileDialogPeer)blockerPeer).unblockWindow(this);
                } else if (blockerPeer instanceof WPrintDialogPeer) {
                    ((WPrintDialogPeer)blockerPeer).unblockWindow(this);
                } else {
                    modalEnable(dialog);
                }
            }
        }
    }

    native void modalDisable(Dialog blocker, long blockerHWnd);
    native void modalEnable(Dialog blocker);

    /*
     * Returns all the ever active windows from the current AppContext.
     * The list is sorted by the time of activation, so the latest
     * active window is always at the end.
     */
    public static long[] getActiveWindowHandles() {
        AppContext appContext = AppContext.getAppContext();
        if (appContext == null) return null;
        synchronized (appContext) {
            List<WWindowPeer> l = (List<WWindowPeer>)appContext.get(ACTIVE_WINDOWS_KEY);
            if (l == null) {
                return null;
            }
            long[] result = new long[l.size()];
            for (int j = 0; j < l.size(); j++) {
                result[j] = l.get(j).getHWnd();
            }
            return result;
        }
    }

/*
 * ----DISPLAY CHANGE SUPPORT----
 */

    /*
     * Called from native code when we have been dragged onto another screen.
     */
    void draggedToNewScreen() {
        SunToolkit.executeOnEventHandlerThread((Component)target,new Runnable()
        {
            public void run() {
                displayChanged();
            }
        });
    }


    /*
     * Called from WCanvasPeer.displayChanged().
     * Override to do nothing - Window and WWindowPeer GC must never be set to
     * null!
     */
    void clearLocalGC() {}

    public void updateGC() {
        int scrn = getScreenImOn();
        if (screenLog.isLoggable(Level.FINER)) {
            log.log(Level.FINER, "Screen number: " + scrn);
        }

        // get current GD
        Win32GraphicsDevice oldDev = (Win32GraphicsDevice)winGraphicsConfig
                                     .getDevice();

        Win32GraphicsDevice newDev;
        GraphicsDevice devs[] = GraphicsEnvironment
            .getLocalGraphicsEnvironment()
            .getScreenDevices();
        // Occasionally during device addition/removal getScreenImOn can return
        // a non-existing screen number. Use the default device in this case.
        if (scrn >= devs.length) {
            newDev = (Win32GraphicsDevice)GraphicsEnvironment
                .getLocalGraphicsEnvironment().getDefaultScreenDevice();
        } else {
            newDev = (Win32GraphicsDevice)devs[scrn];
        }

        // Set winGraphicsConfig to the default GC for the monitor this Window
        // is now mostly on.
        winGraphicsConfig = (Win32GraphicsConfig)newDev
                            .getDefaultConfiguration();
        if (screenLog.isLoggable(Level.FINE)) {
            if (winGraphicsConfig == null) {
                screenLog.log(Level.FINE, "Assertion (winGraphicsConfig != null) failed");
            }
        }

        // if on a different display, take off old GD and put on new GD
        if (oldDev != newDev) {
            oldDev.removeDisplayChangedListener(this);
            newDev.addDisplayChangedListener(this);
        }
    }

    /*
     * From the DisplayChangedListener interface
     *
     * This method handles a display change - either when the display settings
     * are changed, or when the window has been dragged onto a different
     * display.
     */
    public void displayChanged() {
        updateGC();
        super.displayChanged();
    }

    private native int getScreenImOn();

/*
 * ----END DISPLAY CHANGE SUPPORT----
 */

     public void grab() {
         nativeGrab();
     }

     public void ungrab() {
         nativeUngrab();
     }
     private native void nativeGrab();
     private native void nativeUngrab();

     private final boolean hasWarningWindow() {
         return ((Window)target).getWarningString() != null;
     }

     boolean isTargetUndecorated() {
         return true;
     }

     // These are the peer bounds. They get updated at:
     //    1. the WWindowPeer.setBounds() method.
     //    2. the native code (on WM_SIZE/WM_MOVE)
     private volatile int sysX = 0;
     private volatile int sysY = 0;
     private volatile int sysW = 0;
     private volatile int sysH = 0;

     Rectangle constrainBounds(int x, int y, int width, int height) {
         GraphicsConfiguration gc = this.winGraphicsConfig;

         // We don't restrict the setBounds() operation if the code is trusted.
         if (!hasWarningWindow() || gc == null) {
             return new Rectangle(x, y, width, height);
         }

         int newX = x;
         int newY = y;
         int newW = width;
         int newH = height;

         Rectangle sB = gc.getBounds();
         Insets sIn = Toolkit.getDefaultToolkit().getScreenInsets(gc);

         int screenW = sB.width - sIn.left - sIn.right;
         int screenH = sB.height - sIn.top - sIn.bottom;

         // If it's undecorated or is not currently visible
         if (!AWTAccessor.getComponentAccessor().isVisible_NoClientCode(
                     (Component)target) || isTargetUndecorated())
         {
             // Now check each point is within the visible part of the screen
             int screenX = sB.x + sIn.left;
             int screenY = sB.y + sIn.top;

             // First make sure the size is within the visible part of the screen
             if (newW > screenW) {
                 newW = screenW;
             }
             if (newH > screenH) {
                 newH = screenH;
             }

             // Tweak the location if needed
             if (newX < screenX) {
                 newX = screenX;
             } else if (newX + newW > screenX + screenW) {
                 newX = screenX + screenW - newW;
             }
             if (newY < screenY) {
                 newY = screenY;
             } else if (newY + newH > screenY + screenH) {
                 newY = screenY + screenH - newH;
             }
         } else {
             int maxW = Math.max(screenW, sysW);
             int maxH = Math.max(screenH, sysH);

             // Make sure the size is withing the visible part of the screen
             // OR less that the current size of the window.
             if (newW > maxW) {
                 newW = maxW;
             }
             if (newH > maxH) {
                 newH = maxH;
             }
         }

         return new Rectangle(newX, newY, newW, newH);
     }

     public native void repositionSecurityWarning();

     @Override
     public void setBounds(int x, int y, int width, int height, int op) {
         Rectangle newBounds = constrainBounds(x, y, width, height);

         sysX = newBounds.x;
         sysY = newBounds.y;
         sysW = newBounds.width;
         sysH = newBounds.height;

         super.setBounds(newBounds.x, newBounds.y, newBounds.width, newBounds.height, op);
     }

    @Override
    public void print(Graphics g) {
        // We assume we print the whole frame,
        // so we expect no clip was set previously
        Shape shape = AWTAccessor.getWindowAccessor().getShape((Window)target);
        if (shape != null) {
            g.setClip(shape);
        }
        super.print(g);
    }

    private void replaceSurfaceDataRecursively(Component c) {
        if (c instanceof Container) {
            for (Component child : ((Container)c).getComponents()) {
                replaceSurfaceDataRecursively(child);
            }
        }
        ComponentPeer cp = c.getPeer();
        if (cp instanceof WComponentPeer) {
            ((WComponentPeer)cp).replaceSurfaceDataLater();
        }
    }

    @Override
    public Graphics getGraphics() {
        synchronized (getStateLock()) {
            if (!isOpaque) {
                return painter.getBackBuffer(false).getGraphics();
            }
        }
        return super.getGraphics();
    }

    @Override
    public void setBackground(Color c) {
        super.setBackground(c);
        synchronized (getStateLock()) {
            if (!isOpaque && ((Window)target).isVisible()) {
                updateWindow(true);
            }
        }
    }

    private native void setOpacity(int iOpacity);

    public void setOpacity(float opacity) {
        if (!((SunToolkit)((Window)target).getToolkit()).
            isWindowOpacitySupported())
        {
            return;
        }

        replaceSurfaceDataRecursively((Component)getTarget());

        final int maxOpacity = 0xff;
        int iOpacity = (int)(opacity * maxOpacity);
        if (iOpacity < 0) {
            iOpacity = 0;
        }
        if (iOpacity > maxOpacity) {
            iOpacity = maxOpacity;
        }

        setOpacity(iOpacity);

        synchronized (getStateLock()) {
            if (!isOpaque && ((Window)target).isVisible()) {
                updateWindow(true);
            }
        }
    }

    private native void setOpaqueImpl(boolean isOpaque);

    public void setOpaque(boolean isOpaque) {
        synchronized (getStateLock()) {
            if (this.isOpaque == isOpaque) {
                return;
            }
        }

        Window target = (Window)getTarget();

        SunToolkit sunToolkit = (SunToolkit)target.getToolkit();
        if (!sunToolkit.isWindowTranslucencySupported() ||
            !sunToolkit.isTranslucencyCapable(target.getGraphicsConfiguration()))
        {
            return;
        }

        boolean isVistaOS = Win32GraphicsEnvironment.isVistaOS();

        if (!isVistaOS) {
            // non-Vista OS: only replace the surface data if the opacity
            // status changed (see WComponentPeer.isAccelCapable() for more)
            replaceSurfaceDataRecursively(target);
        }

        synchronized (getStateLock()) {
            this.isOpaque = isOpaque;
            setOpaqueImpl(isOpaque);
            if (isOpaque) {
                TranslucentWindowPainter currentPainter = painter;
                if (currentPainter != null) {
                    currentPainter.flush();
                    painter = null;
                }
            } else {
                painter = TranslucentWindowPainter.createInstance(this);
            }
        }

        if (isVistaOS) {
            // On Vista: setting the window non-opaque makes the window look
            // rectangular, though still catching the mouse clicks within
            // its shape only. To restore the correct visual appearance
            // of the window (i.e. w/ the correct shape) we have to reset
            // the shape.
            Shape shape = AWTAccessor.getWindowAccessor().getShape(target);
            if (shape != null) {
                AWTAccessor.getWindowAccessor().setShape(target, shape);
            }
        }

        if (((Window)target).isVisible()) {
            updateWindow(true);
        }
    }

    public native void updateWindowImpl(int[] data, int width, int height);

    public void updateWindow() {
        updateWindow(false);
    }

    private void updateWindow(boolean repaint) {
        Window w = (Window)target;
        synchronized (getStateLock()) {
            if (isOpaque || !w.isVisible() ||
                (w.getWidth() <= 0) || (w.getHeight() <= 0))
            {
                return;
            }
            TranslucentWindowPainter currentPainter = painter;
            if (currentPainter != null) {
                currentPainter.updateWindow(repaint);
            } else if (log.isLoggable(Level.FINER)) {
                log.log(Level.FINER,
                        "Translucent window painter is null in updateWindow");
            }
        }
    }

    /*
     * Static inner class, listens for 'activeWindow' KFM property changes and
     * updates the list of active windows per AppContext, so the latest active
     * window is always at the end of the list. The list is stored in AppContext.
     */
    private static class ActiveWindowListener implements PropertyChangeListener {
        public void propertyChange(PropertyChangeEvent e) {
            Window w = (Window)e.getNewValue();
            if (w == null) {
                return;
            }
            AppContext appContext = SunToolkit.targetToAppContext(w);
            synchronized (appContext) {
                List<WWindowPeer> l = (List<WWindowPeer>)appContext.get(ACTIVE_WINDOWS_KEY);
                if (l == null) {
                    l = new LinkedList<WWindowPeer>();
                    appContext.put(ACTIVE_WINDOWS_KEY, l);
                }
                WWindowPeer wp = (WWindowPeer)w.getPeer();
                // add/move wp to the end of the list
                l.remove(wp);
                l.add(wp);
            }
        }
    }
}
