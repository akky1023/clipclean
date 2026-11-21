package clipclean.windows;

import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.SIZE_T;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTR;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.win32.StdCallLibrary;

import clipclean.core.Settings;
import clipclean.core.UrlCleaner;

final class ClipboardMonitor {
    private static final int WM_CLIPBOARDUPDATE = 0x031D;
    private static final int WM_TIMER = 0x0113;
    private static final int CF_UNICODETEXT = 13;
    private static final int CF_BITMAP = 2;
    private static final int CF_DIB = 8;
    private static final int CF_HDROP = 15;
    private static final int CF_DIBV5 = 17;
    private static final int GMEM_MOVEABLE = 0x0002;
    private static final long MAX_BYTES = 512 * 1024;

    public interface ClipboardApi extends StdCallLibrary {
        ClipboardApi INSTANCE = Native.load("user32", ClipboardApi.class);
        boolean AddClipboardFormatListener(HWND window);
        boolean RemoveClipboardFormatListener(HWND window);
        int GetClipboardSequenceNumber();
        HWND GetClipboardOwner();
        boolean OpenClipboard(HWND window);
        boolean CloseClipboard();
        boolean EmptyClipboard();
        boolean IsClipboardFormatAvailable(int format);
        Pointer GetClipboardData(int format);
        Pointer SetClipboardData(int format, Pointer data);
        ULONG_PTR SetTimer(HWND window, ULONG_PTR id, int millis, Pointer callback);
        boolean KillTimer(HWND window, ULONG_PTR id);
    }

    public interface MemoryApi extends StdCallLibrary {
        MemoryApi INSTANCE = Native.load("kernel32", MemoryApi.class);
        Pointer GlobalAlloc(int flags, SIZE_T size);
        SIZE_T GlobalSize(Pointer handle);
        Pointer GlobalLock(Pointer handle);
        boolean GlobalUnlock(Pointer handle);
        Pointer GlobalFree(Pointer handle);
    }

    private final Supplier<Settings> settings;
    private final BooleanSupplier paused;
    private final Consumer<Throwable> onFailure;
    private final ClipboardApi clipboard = ClipboardApi.INSTANCE;
    private final MemoryApi memory = MemoryApi.INSTANCE;

    ClipboardMonitor(Supplier<Settings> settings, BooleanSupplier paused, Consumer<Throwable> onFailure) {
        this.settings = settings;
        this.paused = paused;
        this.onFailure = onFailure;
    }

    void start() {
        Thread thread = new Thread(() -> {
            try {
                watch();
            } catch (RuntimeException | LinkageError e) {
                onFailure.accept(e);
            }
        }, "Clipboard monitor");
        thread.setDaemon(true);
        thread.start();
    }

    private void watch() {
        User32 user = User32.INSTANCE;
        HWND window = user.CreateWindowEx(0, "STATIC", "ClipClean", 0,
                0, 0, 0, 0, User32.HWND_MESSAGE, null, null, null);
        if (window == null) throw new IllegalStateException("クリップボード監視用のウィンドウを作れません");
        int lastSequence = clipboard.GetClipboardSequenceNumber();
        int protectedFormat = user.RegisterClipboardFormat("ExcludeClipboardContentFromMonitorProcessing");
        int passwordFormat = user.RegisterClipboardFormat("Clipboard Viewer Ignore");
        ULONG_PTR retryTimer = new ULONG_PTR(1);
        try {
            if (!clipboard.AddClipboardFormatListener(window)) {
                throw new IllegalStateException("クリップボードの変更を監視できません");
            }
            MSG event = new MSG();
            int received;
            int retries = 0;
            while ((received = user.GetMessage(event, null, 0, 0)) > 0) {
                if (event.message != WM_CLIPBOARDUPDATE && event.message != WM_TIMER) {
                    user.TranslateMessage(event);
                    user.DispatchMessage(event);
                    continue;
                }
                if (event.message == WM_CLIPBOARDUPDATE) retries = 0;
                clipboard.KillTimer(window, retryTimer);
                int sequence = clipboard.GetClipboardSequenceNumber();
                if (sequence == lastSequence) continue;
                if (paused.getAsBoolean()) {
                    lastSequence = sequence;
                    continue;
                }
                if (!clipboard.OpenClipboard(window)) {
                    if (++retries <= 5) clipboard.SetTimer(window, retryTimer, 30, null);
                    continue;
                }
                try {
                    // Observe the sequence under the same lock used to read and replace the text.
                    lastSequence = clipboard.GetClipboardSequenceNumber();
                    if (window.equals(clipboard.GetClipboardOwner()) || paused.getAsBoolean()
                            || hasNonTextData(protectedFormat, passwordFormat)) continue;
                    String input = readText();
                    if (input == null) continue;
                    UrlCleaner.Result result = UrlCleaner.clean(input, settings.get());
                    if (result.changed()) replaceText(input, result.text());
                } finally {
                    clipboard.CloseClipboard();
                }
            }
            if (received < 0) throw new IllegalStateException("クリップボードの監視が停止しました");
        } finally {
            clipboard.KillTimer(window, retryTimer);
            clipboard.RemoveClipboardFormatListener(window);
            user.DestroyWindow(window);
        }
    }

    private boolean hasNonTextData(int protectedFormat, int passwordFormat) {
        return (protectedFormat != 0 && clipboard.IsClipboardFormatAvailable(protectedFormat))
                || (passwordFormat != 0 && clipboard.IsClipboardFormatAvailable(passwordFormat))
                || clipboard.IsClipboardFormatAvailable(CF_BITMAP)
                || clipboard.IsClipboardFormatAvailable(CF_DIB)
                || clipboard.IsClipboardFormatAvailable(CF_DIBV5)
                || clipboard.IsClipboardFormatAvailable(CF_HDROP);
    }

    private String readText() {
        if (!clipboard.IsClipboardFormatAvailable(CF_UNICODETEXT)) return null;
        Pointer handle = clipboard.GetClipboardData(CF_UNICODETEXT);
        if (handle == null) return null;
        long size = memory.GlobalSize(handle).longValue();
        if (size < 2 || size > MAX_BYTES) return null;
        Pointer data = memory.GlobalLock(handle);
        if (data == null) return null;
        byte[] bytes;
        try {
            bytes = data.getByteArray(0, (int) size);
        } finally {
            memory.GlobalUnlock(handle);
        }
        for (int end = 0; end + 1 < bytes.length; end += 2) {
            if (bytes[end] == 0 && bytes[end + 1] == 0) {
                return new String(bytes, 0, end, StandardCharsets.UTF_16LE);
            }
        }
        return null;
    }

    private Pointer allocateText(String text) {
        byte[] bytes = (text + '\0').getBytes(StandardCharsets.UTF_16LE);
        Pointer handle = memory.GlobalAlloc(GMEM_MOVEABLE, new SIZE_T(bytes.length));
        if (handle == null) return null;
        Pointer data = memory.GlobalLock(handle);
        if (data == null) {
            memory.GlobalFree(handle);
            return null;
        }
        try {
            data.write(0, bytes, 0, bytes.length);
        } finally {
            memory.GlobalUnlock(handle);
        }
        return handle;
    }

    private void replaceText(String input, String output) {
        Pointer replacement = allocateText(output);
        if (replacement == null) return;
        Pointer backup = allocateText(input);
        try {
            if (backup == null || !clipboard.EmptyClipboard()) return;
            if (clipboard.SetClipboardData(CF_UNICODETEXT, replacement) != null) {
                replacement = null; // Windows owns the handle after a successful transfer.
            } else if (clipboard.SetClipboardData(CF_UNICODETEXT, backup) != null) {
                backup = null;
            }
        } finally {
            if (replacement != null) memory.GlobalFree(replacement);
            if (backup != null) memory.GlobalFree(backup);
        }
    }
}
