package clipclean.windows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import com.sun.jna.platform.win32.KnownFolders;
import com.sun.jna.platform.win32.Shell32Util;

final class Startup {
    private static final String CREATE_LINK =
            "$ErrorActionPreference = 'Stop'; "
            + "$shortcut = (New-Object -ComObject WScript.Shell).CreateShortcut($env:CLIPCLEAN_LINK); "
            + "$shortcut.TargetPath = $env:CLIPCLEAN_EXE; "
            + "$shortcut.WorkingDirectory = [System.IO.Path]::GetDirectoryName($env:CLIPCLEAN_EXE); "
            + "$shortcut.Description = 'ClipClean'; $shortcut.Save()";

    private final Path executable = ProcessHandle.current().info().command().map(Path::of)
            .filter(path -> path.getFileName().toString().equalsIgnoreCase("ClipClean.exe")).orElse(null);
    private final Path shortcut = Path.of(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Startup))
            .resolve("ClipClean.lnk");

    boolean available() { return executable != null; }

    boolean registered() { return Files.isRegularFile(shortcut); }

    void setEnabled(boolean enabled) throws IOException, InterruptedException {
        if (!available()) throw new IOException("ポータブル版の ClipClean.exe から起動してください");
        if (!enabled) {
            Files.deleteIfExists(shortcut);
            return;
        }

        createShortcut(shortcut, executable);
    }

    private static void createShortcut(Path shortcut, Path executable) throws IOException, InterruptedException {
        Path powershell = Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
        ProcessBuilder builder = new ProcessBuilder(powershell.toString(), "-NoProfile", "-NonInteractive",
                "-WindowStyle", "Hidden", "-Command", CREATE_LINK)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        builder.environment().put("CLIPCLEAN_LINK", shortcut.toString());
        builder.environment().put("CLIPCLEAN_EXE", executable.toString());
        Process process = builder.start();
        try {
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0 || !Files.isRegularFile(shortcut)) {
                throw new IOException("スタートアップのショートカットを作成できませんでした");
            }
        } finally {
            if (process.isAlive()) process.destroyForcibly();
        }
    }
}
