package net.spidicard.verification;

import net.spidicard.ResultViewer;
import net.spidicard.viewer.ResultsWindow;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.ActionEvent;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.file.*;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.imageio.ImageIO;

public final class ViewerProbe {
    static Process process(ResultViewer viewer) throws Exception {
        var field = ResultViewer.class.getDeclaredField("previous"); field.setAccessible(true);
        return (Process) field.get(viewer);
    }
    static Process waitReady(ResultViewer viewer, Path game, long expectedReady) throws Exception {
        long deadline = System.nanoTime()+15_000_000_000L;
        Path log = game.resolve("logs/spidicard-viewer.log");
        while (System.nanoTime()<deadline) {
            Process p=process(viewer);
            if(p!=null && p.isAlive() && Files.exists(log)
                    && Files.readAllLines(log).stream().filter("SPIDICARD_VIEWER_READY"::equals).count()>=expectedReady) return p;
            Thread.sleep(50);
        }
        throw new AssertionError("Viewer not ready: "+game);
    }
    static void close(Process p) throws Exception {
        p.getOutputStream().write("close\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        p.getOutputStream().flush();
        if(!p.waitFor(5,java.util.concurrent.TimeUnit.SECONDS)||p.exitValue()!=0) throw new AssertionError("Viewer did not close cleanly");
    }
    static JTextArea findText(Container parent) {
        for(Component c:parent.getComponents()) {
            if(c instanceof JTextArea t) return t;
            if(c instanceof Container nested) { JTextArea t=findText(nested); if(t!=null)return t; }
        } return null;
    }
    static void shortcut(JTextArea area, int modifier, int key) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            Object binding=area.getInputMap().get(KeyStroke.getKeyStroke(key,modifier));
            Action action=binding==null?null:area.getActionMap().get(binding);
            if(action==null)throw new AssertionError("Missing keyboard binding");
            action.actionPerformed(new ActionEvent(area,ActionEvent.ACTION_PERFORMED,"test-key-binding"));
        });
    }
    static void expectClipboard(String expected) throws Exception {
        String actual=(String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
        if(!expected.equals(actual))throw new AssertionError("Clipboard did not contain the requested visible selection");
    }
    static void select(JTextArea area, int start, int end) throws Exception {
        SwingUtilities.invokeAndWait(()-> area.select(start,end));
    }
    static void popupAction(JTextArea area, int index) throws Exception {
        SwingUtilities.invokeAndWait(()-> {
            var popup=area.getComponentPopupMenu();
            popup.show(area,100,28);
            if(!popup.isVisible())throw new AssertionError("Popup is not visible");
            var item=(JMenuItem)popup.getComponent(index);
            if(!item.isEnabled())throw new AssertionError("Copy action disabled despite selection");
            item.doClick();popup.setVisible(false);
        });
    }
    static void verifyInteractions(JTextArea area, String expected) throws Exception {
        var clipboard=Toolkit.getDefaultToolkit().getSystemClipboard();
        var previous=clipboard.getContents(null);
        try {
            select(area,0,9);
            clipboard.setContents(new StringSelection("SPIDICARD_PENDING"),null);
            shortcut(area,InputEvent.META_DOWN_MASK,KeyEvent.VK_C);expectClipboard("ShadowFox");
            clipboard.setContents(new StringSelection("SPIDICARD_PENDING"),null);
            shortcut(area,InputEvent.CTRL_DOWN_MASK,KeyEvent.VK_C);expectClipboard("ShadowFox");
            clipboard.setContents(new StringSelection("SPIDICARD_PENDING"),null);
            popupAction(area,0);expectClipboard("ShadowFox");
            select(area,0,9);popupAction(area,1);expectClipboard(expected);
            if(!"ShadowFox".equals(area.getSelectedText()))throw new AssertionError("Copy all destroyed selection");
            for(int modifier:new int[]{InputEvent.META_DOWN_MASK,InputEvent.CTRL_DOWN_MASK}) {
                select(area,0,9);shortcut(area,modifier,KeyEvent.VK_A);
                shortcut(area,modifier,KeyEvent.VK_C);expectClipboard(expected);
            }
            select(area,0,0);
            SwingUtilities.invokeAndWait(()-> {
                var popup=area.getComponentPopupMenu();popup.show(area,100,28);
                if(popup.getComponent(0).isEnabled())throw new AssertionError("Empty selection copy should be disabled");
                popup.setVisible(false);
            });
        } finally {if(previous!=null)clipboard.setContents(previous,null);}
        for(String[] example:new String[][]{
                {"- grief #1\n- grief #2\n",""},
                {"Nick - grief #1\r\n- grief #2\r\nOther - grief #56","Nick - grief #1\r\nOther - grief #56"},
                {"",""},{"Nick Other","Nick Other"},{"Nick - grief #1\n","Nick - grief #1\n"}}) {
            if(!example[1].equals(ResultsWindow.visibleContents(example[0])))
                throw new AssertionError("Filtering failed for an empty/start/full/CRLF result");
        }
    }
    public static void main(String[] args) throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath(); Files.createDirectories(root);
        if(args.length>1 && (args[1].equals("render")||args[1].equals("interaction"))) {
            Path file=root.resolve("spidicard.txt");
            String text="ShadowFox NorthWind - grief #1\nRainfall - grief #2\n- grief #3\nSkyHunter Redstone EchoCraft - grief #4\n";
            String expected="ShadowFox NorthWind - grief #1\nRainfall - grief #2\nSkyHunter Redstone EchoCraft - grief #4\n";
            Files.writeString(file,text);
            var input=new PipedInputStream(); var keepAlive=new PipedOutputStream(input); System.setIn(input);
            ResultsWindow.main(new String[]{file.toString()});
            JFrame[] frameRef=new JFrame[1];JTextArea[] areaRef=new JTextArea[1];
            try {
            SwingUtilities.invokeAndWait(()-> {
                try {
                    JFrame frame=(JFrame)java.util.Arrays.stream(Frame.getFrames()).filter(Frame::isShowing).findFirst().orElseThrow();
                    JTextArea area=findText(frame); if(!expected.equals(area.getText()))throw new AssertionError("GUI did not hide only empty griefs");
                    if(!text.equals(Files.readString(file)))throw new AssertionError("Viewer changed original result file");
                    frameRef[0]=frame;areaRef[0]=area;
                    var image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);
                    Graphics2D graphics=image.createGraphics(); frame.paintAll(graphics); graphics.dispose();
                    ImageIO.write(image,"png",root.resolve("window-preview.png").toFile());
                    Files.writeString(root.resolve("gui-verification.txt"),"PASS: actual Swing window hides empty grief #3, preserves matching griefs #1/#2/#4 and leaves original UTF-8 file intact\n");
                } catch(Exception e) {throw new RuntimeException(e);}
            });
            if(args[1].equals("interaction")) {
                verifyInteractions(areaRef[0],expected);
                Files.writeString(root.resolve("interaction-verification.txt"),
                        "PASS: Cmd+C binding copies selected nickname to system clipboard\n"
                        +"PASS: Ctrl+C copies selection (Windows binding)\n"
                        +"PASS: visible context menu Copy selection action copies selected nickname\n"
                        +"PASS: popup Copy all copies only visible griefs and preserves selection\n"
                        +"PASS: Cmd+A/Ctrl+A and copy bindings copy entire filtered result; empty selection copy disabled\nPASS: empty/full/start/CRLF result filtering\n"
                        +"ALL INTERACTION CHECKS PASSED\n");
                System.out.println("ALL INTERACTION CHECKS PASSED");
            }
            } finally { keepAlive.write("close\n".getBytes()); keepAlive.flush(); }
            return;
        }
        var failures=new CopyOnWriteArrayList<Throwable>();
        Path one=root.resolve("Инстанс 1 & ' $ (проверка)"); Path two=root.resolve("Другой экземпляр");
        Files.createDirectories(one); Files.createDirectories(two);
        Path first=one.resolve("spidicard.txt"), second=two.resolve("spidicard.txt");
        Files.writeString(first,"Alpha Beta"); Files.writeString(second,"Other - grief #1");
        var a=new ResultViewer(); var b=new ResultViewer();
        a.reopen(first,failures::add); Process old=waitReady(a,one,1);
        b.reopen(second,failures::add); Process other=waitReady(b,two,1);
        Files.writeString(first,"Fresh - grief #56"); a.reopen(first,failures::add);
        Process fresh=waitReady(a,one,2);
        if(old.isAlive()||fresh.pid()==old.pid()||!other.isAlive()) throw new AssertionError("Instance/window isolation failed");
        close(fresh); close(other);
        a.reopen(first,failures::add); close(waitReady(a,one,3));
        if(!failures.isEmpty())throw new AssertionError(failures.toString());
        Files.writeString(root.resolve("viewer-verification.txt"),String.join("\n",List.of(
                "PASS: separate native Java viewer opened with spaces, Cyrillic, quotes, shell metacharacters",
                "PASS: reopening closes exact previous viewer and creates a fresh process",
                "PASS: second Minecraft instance viewer remains open and uses its own result path",
                "PASS: reopening after manual close works; clean close exits with code 0",
                "ALL VIEWER CHECKS PASSED"))+"\n");
        System.out.println("ALL VIEWER CHECKS PASSED");
    }
}
