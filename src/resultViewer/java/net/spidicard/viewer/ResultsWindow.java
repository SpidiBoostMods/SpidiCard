package net.spidicard.viewer;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.InputStream;
import java.io.DataInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Standalone result viewer by SpidiBoost; no Minecraft or Fabric dependencies. */
public final class ResultsWindow {
    private static final Color BACKGROUND = new Color(18, 20, 29);
    private static final Color SURFACE = new Color(26, 29, 41);
    private static final Color TEXT = new Color(231, 234, 244);
    private static final Color MUTED = new Color(147, 155, 180);
    private static final Color ACCENT = new Color(160, 137, 255);
    private static JFrame window;

    public static void main(String[] args) throws Exception {
        Path file = readResultPath(args, System.in);
        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        String contents = visibleContents(Files.readString(file, StandardCharsets.UTF_8));
        SwingUtilities.invokeAndWait(() -> show(file, contents));
        // Close this exact viewer, never another application's document.
        Thread control = new Thread(() -> {
            try (var input = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                while (true) {
                    String line = input.readLine();
                    if (line == null || line.equals("close")) { close(); return; }
                }
            } catch (Exception error) { close(); }
        }, "SpidiCard-viewer-control");
        control.setDaemon(true);
        control.start();
    }

    public static Path readResultPath(String[] args, InputStream input) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected the result file path");
        String path = args[0].equals("--stdin") ? new DataInputStream(input).readUTF() : args[0];
        return Path.of(path).toAbsolutePath().normalize();
    }

    public static String visibleContents(String contents) {
        return contents.replaceAll("(?m)^\\h*- grief #\\d+\\h*(?:\\R|$)", "");
    }

    private static void show(Path file, String contents) {
        var root = new JPanel(new BorderLayout(0, 22));
        root.setBackground(BACKGROUND);
        root.setBorder(new EmptyBorder(26, 30, 22, 30));
        var header = new JPanel(new BorderLayout(20, 0));
        header.setOpaque(false);
        var title = new JPanel(new GridLayout(2, 1, 0, 4));
        title.setOpaque(false);
        title.add(label("SpidiCard", 30, Font.BOLD, TEXT));
        title.add(label("Результаты проверки ключ-карт", 14, Font.PLAIN, MUTED));
        header.add(title, BorderLayout.WEST);
        long griefs = contents.lines().filter(line -> line.matches(".*- grief #\\d+$")).count();
        long players = contents.lines().map(line -> line.replaceFirst("\\s*- grief #\\d+$", "").trim())
                .filter(line -> !line.isEmpty()).mapToLong(line -> line.split("\\s+").length).sum();
        var counts = new JPanel(new GridLayout(1, griefs > 0 ? 2 : 1, 12, 0));
        counts.setOpaque(false);
        counts.add(stat(Long.toString(players), "совпадений"));
        if (griefs > 0) counts.add(stat(Long.toString(griefs), "грифов"));
        header.add(counts, BorderLayout.EAST);
        root.add(header, BorderLayout.NORTH);

        var text = new JTextArea(contents);
        text.setName("spidicard-results");
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(true);
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 15));
        text.setBackground(SURFACE);
        text.setForeground(TEXT);
        text.setCaretColor(ACCENT);
        text.setSelectionColor(new Color(77, 61, 129));
        text.setSelectedTextColor(Color.WHITE);
        text.setBorder(new EmptyBorder(18, 20, 18, 20));
        text.setCaretPosition(0);
        installCopyActions(text);
        var scroll = new JScrollPane(text);
        scroll.setBorder(BorderFactory.createLineBorder(new Color(44, 48, 65)));
        scroll.getViewport().setBackground(SURFACE);
        scroll.getVerticalScrollBar().setUnitIncrement(24);
        scroll.getVerticalScrollBar().setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() {
                thumbColor = new Color(73, 77, 99); trackColor = SURFACE;
            }
            @Override protected JButton createDecreaseButton(int direction) { return hidden(); }
            @Override protected JButton createIncreaseButton(int direction) { return hidden(); }
            private JButton hidden() { var b = new JButton(); b.setPreferredSize(new Dimension(0, 0)); return b; }
        });
        root.add(scroll, BorderLayout.CENTER);

        var footer = new JPanel(new BorderLayout(0, 14));
        footer.setOpaque(false);
        var path = new JTextField(file.toString());
        path.setEditable(false);
        path.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        path.setBackground(BACKGROUND);
        path.setForeground(MUTED);
        path.setBorder(null);
        path.setToolTipText(file.toString());
        installCopyActions(path);
        footer.add(path, BorderLayout.NORTH);
        var row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        var attribution = label("SpidiBoost", 13, Font.BOLD, MUTED);
        row.add(attribution, BorderLayout.WEST);
        var actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        var folder = button("Открыть папку", SURFACE, TEXT);
        folder.addActionListener(event -> {
            try { Desktop.getDesktop().open(file.getParent().toFile()); }
            catch (Exception error) { attribution.setText("Папка: " + file.getParent()); }
        });
        var copy = button("Копировать всё", ACCENT, BACKGROUND);
        copy.addActionListener(event -> {
            copyAll(text);
            copy.setText("Скопировано");
            var reset = new Timer(1800, e -> copy.setText("Копировать всё"));
            reset.setRepeats(false); reset.start();
        });
        actions.add(folder); actions.add(copy);
        row.add(actions, BorderLayout.EAST);
        footer.add(row, BorderLayout.CENTER);
        root.add(footer, BorderLayout.SOUTH);
        window = new JFrame("spidicard.txt — SpidiCard");
        window.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        window.setContentPane(root);
        window.setMinimumSize(new Dimension(660, 380));
        window.setSize(960, 620);
        window.setLocationByPlatform(true);
        window.setVisible(true);
        window.toFront();
        text.requestFocusInWindow();
        System.out.println("SPIDICARD_VIEWER_READY");
    }

    private static void copyAll(JTextComponent text) {
        int start = text.getSelectionStart(), end = text.getSelectionEnd();
        text.selectAll(); text.copy(); text.select(start, end);
    }

    private static void installCopyActions(JTextComponent text) {
        var selected = new AbstractAction("Копировать выделенное") {
            @Override public void actionPerformed(ActionEvent event) { text.copy(); }
        };
        var all = new AbstractAction("Копировать всё") {
            @Override public void actionPerformed(ActionEvent event) { copyAll(text); }
        };
        var selectAll = new AbstractAction("Выделить всё") {
            @Override public void actionPerformed(ActionEvent event) { text.selectAll(); }
        };
        text.getActionMap().put("spidicard-copy", selected);
        text.getActionMap().put("spidicard-select-all", selectAll);
        // Metal's default text bindings use Ctrl even on macOS. Bind both explicitly.
        for (int modifier : new int[]{InputEvent.CTRL_DOWN_MASK, InputEvent.META_DOWN_MASK}) {
            text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, modifier), "spidicard-copy");
            text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, modifier), "spidicard-select-all");
        }
        var popup = new JPopupMenu();
        var copySelection = popup.add(selected);
        copySelection.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_C,
                Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()));
        popup.add(all);
        popup.addSeparator();
        popup.add(selectAll);
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override public void popupMenuWillBecomeVisible(PopupMenuEvent event) {
                copySelection.setEnabled(text.getSelectionStart() != text.getSelectionEnd());
            }
            @Override public void popupMenuWillBecomeInvisible(PopupMenuEvent event) {}
            @Override public void popupMenuCanceled(PopupMenuEvent event) {}
        });
        text.setComponentPopupMenu(popup);
    }

    private static JLabel label(String text, int size, int style, Color color) {
        var label = new JLabel(text);
        label.setFont(new Font(Font.SANS_SERIF, style, size)); label.setForeground(color); return label;
    }
    private static JPanel stat(String count, String name) {
        var panel = new JPanel(new GridLayout(2, 1, 0, 2));
        panel.setBackground(SURFACE);
        panel.setBorder(new EmptyBorder(8, 16, 8, 16));
        panel.add(label(count, 22, Font.BOLD, ACCENT));
        panel.add(label(name, 12, Font.PLAIN, MUTED));
        return panel;
    }
    private static JButton button(String text, Color background, Color foreground) {
        var button = new JButton(text);
        button.setBackground(background); button.setForeground(foreground);
        button.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        button.setBorder(new EmptyBorder(12, 18, 12, 18));
        button.setFocusPainted(false);
        button.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        return button;
    }
    private static void close() { SwingUtilities.invokeLater(() -> { window.dispose(); System.exit(0); }); }
}
