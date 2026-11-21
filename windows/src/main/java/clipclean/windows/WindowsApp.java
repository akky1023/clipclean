package clipclean.windows;

import java.awt.AWTException;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.RenderingHints;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.function.UnaryOperator;

import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.filechooser.FileNameExtensionFilter;

import clipclean.core.Rules;
import clipclean.core.Rules.Rule;
import clipclean.core.Rules.Site;
import clipclean.core.Settings;
import clipclean.core.SettingsJson;

public final class WindowsApp {
    private final Path settingsFile;
    private final Startup startup = new Startup();
    private volatile Settings settings = Settings.defaults();
    private volatile boolean paused;
    private JFrame window;
    private TrayIcon tray;
    private MenuItem pauseAction;
    private int selectedTab;
    private boolean changingStartup;
    private JCheckBox login;

    private WindowsApp(Path settingsFile) {
        this.settingsFile = settingsFile;
    }

    public static void main(String[] args) {
        if (GraphicsEnvironment.isHeadless()) return;
        String local = System.getenv("LOCALAPPDATA");
        Path directory = Path.of(local == null ? System.getProperty("user.home") : local, "ClipClean");
        try {
            Files.createDirectories(directory);
            // Keep the channel alive for the whole process.
            FileChannel lock = FileChannel.open(directory.resolve("instance.lock"),
                    StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            if (lock.tryLock() == null) {
                lock.close();
                return;
            }
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                try {
                    lock.close();
                } catch (IOException ignored) {
                    // The process also releases the file lock on exit.
                }
            }));
            SwingUtilities.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    new WindowsApp(directory.resolve("settings.json")).start();
                } catch (Exception | LinkageError e) {
                    JOptionPane.showMessageDialog(null, e.getMessage(), "ClipClean", JOptionPane.ERROR_MESSAGE);
                    System.exit(1);
                }
            });
        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, e.getMessage(), "ClipClean", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void start() throws AWTException {
        if (Files.isRegularFile(settingsFile)) {
            try {
                settings = readSettings(settingsFile);
            } catch (IOException | IllegalArgumentException e) {
                error("保存した設定を読み込めません。初期設定で起動します。", e);
            }
        }
        PopupMenu menu = new PopupMenu();
        MenuItem edit = new MenuItem("設定");
        edit.addActionListener(event -> SwingUtilities.invokeLater(this::showSettings));
        pauseAction = new MenuItem("一時停止");
        pauseAction.addActionListener(event -> SwingUtilities.invokeLater(() -> {
            paused = !paused;
            pauseAction.setLabel(paused ? "再開" : "一時停止");
            tray.setToolTip(paused ? "ClipClean（一時停止中）" : "ClipClean");
        }));
        MenuItem quit = new MenuItem("終了");
        quit.addActionListener(event -> System.exit(0));
        menu.add(edit);
        menu.add(pauseAction);
        menu.addSeparator();
        menu.add(quit);

        tray = new TrayIcon(icon(), "ClipClean", menu);
        tray.setImageAutoSize(true);
        tray.addActionListener(event -> SwingUtilities.invokeLater(this::showSettings));
        SystemTray.getSystemTray().add(tray);
        new ClipboardMonitor(() -> settings, () -> paused, failure -> SwingUtilities.invokeLater(() -> {
            pauseAction.setEnabled(false);
            error("クリップボードの監視を開始できませんでした。", failure);
        })).start();

        // Refresh an existing registration if the extracted folder was moved.
        if (startup.available() && startup.registered()) changeStartup(true);
    }

    private void showSettings() {
        if (window == null) {
            window = new JFrame("ClipClean の設定");
            window.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
            window.setIconImage(icon());
            rebuild();
            window.pack();
            window.setMinimumSize(window.getSize());
            window.setLocationByPlatform(true);
        }
        window.setVisible(true);
        window.toFront();
    }

    private void rebuild() {
        JPanel content = new JPanel(new BorderLayout(0, 12));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));

        JPanel general = verticalPanel();
        JCheckBox embedded = new JCheckBox("文章や複数行に含まれるURLも整理する", settings.embeddedUrls());
        embedded.addActionListener(event -> update(current -> current.withEmbeddedUrls(embedded.isSelected()), embedded));
        general.add(embedded);
        login = new JCheckBox("Windows ログイン時に自動起動", startup.registered());
        login.setEnabled(startup.available() && !changingStartup);
        login.addActionListener(event -> changeStartup(login.isSelected()));
        general.add(login);
        content.add(general, BorderLayout.NORTH);

        JTabbedPane sites = new JTabbedPane();
        for (Site site : Site.values()) {
            JPanel panel = verticalPanel();
            panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
            panel.add(new JLabel("削除するパラメータ"));
            for (Rule rule : Rules.forSite(site)) {
                JCheckBox row = new JCheckBox(rule.label(), settings.removes(site, rule));
                row.addActionListener(event -> update(current -> current.withRemoved(site, rule, row.isSelected()), row));
                panel.add(row);
            }
            JCheckBox unknown = new JCheckBox("リストにないパラメータも削除する", settings.removesUnknown(site));
            unknown.addActionListener(event -> update(current -> current.withRemovedUnknown(site, unknown.isSelected()), unknown));
            panel.add(unknown);
            if (site == Site.YOUTUBE) panel.add(new JLabel("動画IDとプレイリスト自体のIDは常に保持"));
            sites.addTab(site.label(), new JScrollPane(panel));
        }
        sites.setSelectedIndex(selectedTab);
        sites.addChangeListener(event -> selectedTab = sites.getSelectedIndex());
        content.add(sites, BorderLayout.CENTER);

        JPanel files = new JPanel();
        JButton export = new JButton("エクスポート");
        export.addActionListener(event -> exportSettings());
        JButton importFile = new JButton("インポート");
        importFile.addActionListener(event -> importSettings());
        files.add(export);
        files.add(importFile);
        content.add(files, BorderLayout.SOUTH);
        window.setContentPane(content);
        window.revalidate();
        window.repaint();
    }

    private static JPanel verticalPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        return panel;
    }

    private void update(UnaryOperator<Settings> change, JCheckBox row) {
        try {
            save(change.apply(settings));
        } catch (IOException e) {
            row.setSelected(!row.isSelected());
            error("設定を保存できませんでした。", e);
        }
    }

    private void save(Settings next) throws IOException {
        writeSettings(settingsFile, next);
        settings = next;
    }

    private static void writeSettings(Path path, Settings value) throws IOException {
        Path temporary = Files.createTempFile(path.toAbsolutePath().getParent(), ".clipclean-", ".json");
        try {
            Files.writeString(temporary, SettingsJson.encode(value), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static Settings readSettings(Path path) throws IOException {
        try (InputStream stream = Files.newInputStream(path)) {
            byte[] data = stream.readNBytes(SettingsJson.MAX_BYTES + 1);
            if (data.length > SettingsJson.MAX_BYTES) throw new IOException("設定ファイルが大きすぎます");
            return SettingsJson.decode(StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(data)).toString());
        }
    }

    private JFileChooser jsonChooser() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("JSON 設定ファイル", "json"));
        return chooser;
    }

    private void exportSettings() {
        JFileChooser chooser = jsonChooser();
        chooser.setSelectedFile(new java.io.File("clipclean-settings.json"));
        if (chooser.showSaveDialog(window) != JFileChooser.APPROVE_OPTION) return;
        Path output = chooser.getSelectedFile().toPath();
        if (Files.exists(output) && JOptionPane.showConfirmDialog(window,
                "既存のファイルを上書きしますか？", "設定をエクスポート",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            writeSettings(output, settings);
        } catch (IOException e) {
            error("設定をエクスポートできませんでした。", e);
        }
    }

    private void importSettings() {
        JFileChooser chooser = jsonChooser();
        if (chooser.showOpenDialog(window) != JFileChooser.APPROVE_OPTION) return;
        try {
            Settings next = readSettings(chooser.getSelectedFile().toPath());
            if (JOptionPane.showConfirmDialog(window, "現在のURL整理設定を置き換えますか？",
                    "設定をインポート", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
                save(next);
                rebuild();
            }
        } catch (IOException | IllegalArgumentException e) {
            error("設定をインポートできませんでした。", e);
        }
    }

    private void changeStartup(boolean enabled) {
        changingStartup = true;
        if (login != null) login.setEnabled(false);
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                startup.setEnabled(enabled);
                return null;
            }
            @Override protected void done() {
                try {
                    get();
                } catch (Exception e) {
                    error("ログイン時の自動起動を設定できませんでした。", e.getCause());
                } finally {
                    changingStartup = false;
                    if (login != null) {
                        login.setSelected(startup.registered());
                        login.setEnabled(startup.available());
                    }
                }
            }
        }.execute();
    }

    private void error(String text, Throwable cause) {
        String detail = cause == null ? "" : "\n" + cause.getMessage();
        JOptionPane.showMessageDialog(window, text + detail, "ClipClean", JOptionPane.ERROR_MESSAGE);
    }

    private static BufferedImage icon() {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(28, 92, 106));
        g.fillRoundRect(0, 0, 32, 32, 8, 8);
        g.setColor(Color.WHITE);
        g.setStroke(new java.awt.BasicStroke(2.5f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
        g.drawRoundRect(9, 8, 14, 17, 3, 3);
        g.drawLine(13, 12, 19, 12);
        g.drawLine(13, 18, 15, 20);
        g.drawLine(15, 20, 19, 16);
        g.dispose();
        return image;
    }
}
