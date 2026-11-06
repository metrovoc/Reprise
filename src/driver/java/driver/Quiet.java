package driver;

import com.mojang.logging.LogUtils;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.system.JNI;
import org.lwjgl.system.Platform;
import org.lwjgl.system.macosx.ObjCRuntime;
import org.slf4j.Logger;

/**
 * Keeps a scripted client from acting on the machine of whoever is at it. Call {@link #tick} on every client tick, from
 * any driver. It uses reflection so that it compiles and runs unchanged on every game version: copy it, never trim it.
 *
 * A game that believes its window has focus moves the system cursor when it grabs or releases the mouse, speaks the
 * first-run narrator prompt, and switches the system's input source back to ASCII outside text boxes. A window that
 * never had focus still believes it has, since GLFW only reports changes.
 */
public final class Quiet {
    private static final Logger LOG = LogUtils.getLogger();
    private static boolean warned;

    private Quiet() {
    }

    public static void tick(Object minecraft) {
        try {
            Object window = minecraft.getClass().getMethod("getWindow").invoke(minecraft);
            long handle = handle(window);
            yieldFocus(handle);
            if (GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == 0) {
                // the game keeps this belief on its window or, on older versions, on itself
                boolean corrected = clear(window, "focused") | clear(minecraft, "windowActive");
                if (!corrected && !warned) {
                    warned = true;
                    LOG.warn("[quiet] found no focus state to correct on this version: add its name to Quiet.java");
                }
            }
            clearInputMethodRequest(minecraft);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long handle(Object window) throws ReflectiveOperationException {
        for (Method method : window.getClass().getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == long.class
                    && (method.getName().equals("handle") || method.getName().equals("getWindow")
                            || method.getName().equals("getHandle"))) {
                return (long) method.invoke(window);
            }
        }
        throw new NoSuchMethodException("no window handle on " + window.getClass());
    }

    /** Hiding the app and showing it again without activating it hands focus to the app that had it before. */
    private static void yieldFocus(long handle) {
        if (Platform.get() != Platform.MACOSX || GLFW.glfwGetWindowAttrib(handle, GLFW.GLFW_FOCUSED) == 0) {
            return;
        }
        long send = ObjCRuntime.getLibrary().getFunctionAddress("objc_msgSend");
        long app = JNI.invokePPP(ObjCRuntime.objc_getClass("NSApplication"), ObjCRuntime.sel_getUid("sharedApplication"), send);
        JNI.invokePPPP(app, ObjCRuntime.sel_getUid("hide:"), 0L, send);
        JNI.invokePPV(app, ObjCRuntime.sel_getUid("unhideWithoutActivation"), send);
    }

    /** What the game would restore when a scripted text box opens. Versions from before this state have nothing to do. */
    private static void clearInputMethodRequest(Object minecraft) throws ReflectiveOperationException {
        Method manager;
        try {
            manager = minecraft.getClass().getMethod("textInputManager");
        } catch (NoSuchMethodException e) {
            return;
        }
        clear(manager.invoke(minecraft), "imeRequested");
    }

    private static boolean clear(Object owner, String name) throws IllegalAccessException {
        try {
            Field field = owner.getClass().getDeclaredField(name);
            field.setAccessible(true);
            field.setBoolean(owner, false);
            return true;
        } catch (NoSuchFieldException e) {
            return false;
        }
    }
}
