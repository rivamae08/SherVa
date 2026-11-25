import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicTabbedPaneUI;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class StudioClient extends JFrame {
    private static final String APPROVE = "APPROVE";
    private static final String CHANGES = "CHANGES";
    private static final int REQUIRED_APPROVALS = 2;

    private static final class Proto {
        static final int DEFAULT_PORT = 5050;
        static String build(String type, String... fields) {
            StringBuilder sb = new StringBuilder(type);
            for (String f : fields) sb.append('|').append(URLEncoder.encode(f == null ? "" : f, StandardCharsets.UTF_8));
            return sb.toString();
        }
        static String[] parse(String line) {
            String[] parts = line.split("\\|", -1);
            for (int i = 1; i < parts.length; i++) {
                try { parts[i] = URLDecoder.decode(parts[i], StandardCharsets.UTF_8); }
                catch (IllegalArgumentException e) { parts[i] = ""; }
            }
            return parts;
        }
        static String arg(String[] p, int i) { return i < p.length ? p[i].trim() : ""; }
    }

    private static final Color BLUSH = new Color(0xFFF0F5);
    private static final Color PETAL = new Color(0xFCE4EC);
    private static final Color SOFT = new Color(0xF8BBD0);
    private static final Color ROSE = new Color(0xEC407A);
    private static final Color DEEP = new Color(0xAD1457);
    private static final Color INK = new Color(0x4A1A2C);
    private static final Color MUTED = new Color(0xB0668A);
    private static final Color PAPER = new Color(0xFFF8FB);

    private final PinkField hostField = new PinkField("localhost", 11);
    private final PinkField portField = new PinkField(String.valueOf(Proto.DEFAULT_PORT), 5);
    private final PinkField nickField = new PinkField("", 10);
    private final PinkButton connectBtn = new PinkButton("Connect", false);
    private final JLabel statusLabel = new JLabel(" Not connected. Enter the server details above.");

    private final JTextPane chatPane = new JTextPane();
    private final PinkField input = new PinkField("", 30);
    private final PinkButton sendBtn = new PinkButton("Send", true);
    private final DefaultListModel<String> usersModel = new DefaultListModel<>();
    private final JLabel usersTitle = titleLabel("Writers online");

    private final DefaultTableModel ideasModel = model("ID", "Author", "Category", "Title", "Status");
    private final JTable ideasTable = new JTable(ideasModel);
    private final JTextArea ideaDetail = readOnlyArea();
    private final DefaultTableModel reqModel = model("ID", "Feedback request");
    private final JTable reqTable = new JTable(reqModel) {
    @Override public String getToolTipText(java.awt.event.MouseEvent e) { return rowTip(this, e); }
    };
    private final DefaultTableModel replyModel = model("ID", "From", "Time", "Feedback");
    private final JTable replyTable = new JTable(replyModel) {
        @Override public String getToolTipText(java.awt.event.MouseEvent e) { return rowTip(this, e); }
    };

    private final DefaultTableModel revModel = model("Rev", "Idea", "Ver", "Author", "Status", "Votes");
    private final JTable revTable = new JTable(revModel);
    private final JTextArea revDetail = readOnlyArea();

    private final List<JButton> featureButtons = new ArrayList<>();

    private final Map<Integer, String[]> ideaData = new HashMap<>();
    private final Map<Integer, String[]> revData = new HashMap<>();
    private final List<String[]> reqData = new ArrayList<>();
    private final List<String[]> replyData = new ArrayList<>();

    private volatile Socket socket;
    private volatile PrintWriter out;
    private volatile boolean intentional;
    private boolean connecting, joined;
    private String nick = "";
    private final ExecutorService sender = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "inkloop-sender");
        t.setDaemon(true);
        return t;
    });

    public StudioClient() {
        super("Sherva - THINK. WRITE. DESIGN.");
        buildUi();
        applyState();
        addWindowListener(new WindowAdapter() {
        @Override public void windowClosing(WindowEvent e) {
                disconnect();
                dispose();
                System.exit(0);
            }
        });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        setSize(1100, 780);
        setMinimumSize(new Dimension(960, 640));
        setLocationRelativeTo(null);
    }

    private static void smooth(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static final class PinkButton extends JButton {
        private final boolean primary;
        private boolean danger;

        PinkButton danger() { danger = true; repaint(); return this; }
        PinkButton compact() {
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            setBorder(new EmptyBorder(4, 12, 4, 12));
            return this;
        }
        PinkButton(String text, boolean primary) {
            super(text);
            this.primary = primary;
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setOpaque(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            setBorder(new EmptyBorder(8, 18, 8, 18));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            ButtonModel m = getModel();
            Color fill, fg, line;
            if (!isEnabled()) { fill = new Color(0xF3DCE6); fg = new Color(0xC9A3B5); line = fill; }
            else if (danger) {
                fill = m.isPressed() ? new Color(0xFFB4AB) : m.isRollover() ? new Color(0xFFDAD6) : Color.WHITE;
                fg = new Color(0xB3261E); line = fg;
            }
            else if (primary) {
                fill = m.isPressed() ? new Color(0x880E4F) : m.isRollover() ? ROSE : DEEP;
                fg = Color.WHITE; line = fill;
            } else {
                fill = m.isPressed() ? SOFT : m.isRollover() ? PETAL : Color.WHITE;
                fg = DEEP; line = ROSE;
            }
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.setColor(line);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            g2.setColor(fg);
            g2.drawString(getText(), (getWidth() - fm.stringWidth(getText())) / 2,
                    (getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            g2.dispose();
        }
    }

    private static final class PinkField extends JTextField {
        PinkField(String text, int cols) {
            super(text, cols);
            setOpaque(false);
            setForeground(INK);
            setCaretColor(DEEP);
            setSelectionColor(SOFT);
            setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));
            setBorder(new EmptyBorder(7, 14, 7, 14));
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(isEnabled() ? Color.WHITE : new Color(0xF6E9EF));
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, getHeight(), getHeight());
            g2.setColor(hasFocus() ? ROSE : SOFT);
            g2.setStroke(new BasicStroke(hasFocus() ? 2f : 1f));
            g2.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, getHeight(), getHeight());
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class RoundedPanel extends JPanel {
        private final Color fill, line;
        private final int arc;
        RoundedPanel(LayoutManager lm, Color fill, Color line, int arc) {
            super(lm);
            this.fill = fill; this.line = line; this.arc = arc;
            setOpaque(false);
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            if (line != null) {
                g2.setColor(line);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, arc, arc);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static final class HeaderPanel extends JPanel {
        HeaderPanel(LayoutManager lm) { super(lm); setOpaque(true); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            int w = getWidth(), h = getHeight();
            g2.setPaint(new GradientPaint(0, 0, new Color(0xAD1457), w, h, new Color(0xF48FB1)));
            g2.fillRect(0, 0, w, h);
            g2.setColor(new Color(255, 255, 255, 30));
            g2.fill(new Ellipse2D.Double(w - 160, -60, 170, 170));
            g2.fill(new Ellipse2D.Double(w * 0.45, h - 40, 90, 90));
            g2.setColor(new Color(255, 255, 255, 22));
            g2.fill(new Ellipse2D.Double(w * 0.30, -35, 80, 80));
            g2.fill(new Ellipse2D.Double(w - 300, h - 30, 60, 60));
            g2.dispose();
        }
    }

    private static final class Logo extends JComponent {
        Logo() { setPreferredSize(new Dimension(52, 52)); }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(new Color(255, 255, 255, 235));
            g2.fillOval(2, 2, 48, 48);
            Path2D heart = new Path2D.Double();
            for (int i = 0; i <= 80; i++) {
                double t = Math.PI * 2 * i / 80;
                double x = 16 * Math.pow(Math.sin(t), 3);
                double y = -(13 * Math.cos(t) - 5 * Math.cos(2 * t) - 2 * Math.cos(3 * t) - Math.cos(4 * t));
                double px = 26 + x * 0.95, py = 26 + y * 0.95 - 1;
                if (i == 0) heart.moveTo(px, py); else heart.lineTo(px, py);
            }
            heart.closePath();
            g2.setPaint(new GradientPaint(0, 8, ROSE, 0, 44, DEEP));
            g2.fill(heart);
            g2.dispose();
        }
    }

    private static final class PinkTabUI extends BasicTabbedPaneUI {
        @Override protected void installDefaults() {
            super.installDefaults();
            tabAreaInsets = new Insets(2, 2, 6, 2);
            contentBorderInsets = new Insets(0, 0, 0, 0);
            tabInsets = new Insets(5, 20, 5, 20);
            selectedTabPadInsets = new Insets(0, 0, 0, 0);
        }
        @Override protected void paintTabBackground(Graphics g, int tp, int idx, int x, int y, int w, int h, boolean sel) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(sel ? ROSE : PETAL);
            g2.fillRoundRect(x + 3, y + 2, w - 6, h - 4, h, h);
            if (!sel) { g2.setColor(SOFT); g2.drawRoundRect(x + 3, y + 2, w - 7, h - 5, h, h); }
            g2.dispose();
        }
        @Override protected void paintText(Graphics g, int tp, Font font, FontMetrics fm, int idx,
            String title, Rectangle tr, boolean sel) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setFont(font);
            g2.setColor(sel ? Color.WHITE : DEEP);
            g2.drawString(title, tr.x, tr.y + fm.getAscent());
            g2.dispose();
        }
        @Override protected void paintTabBorder(Graphics g, int tp, int idx, int x, int y, int w, int h, boolean sel) { }
        @Override protected void paintContentBorder(Graphics g, int tp, int sel) { }
        @Override protected void paintFocusIndicator(Graphics g, int tp, Rectangle[] r, int idx, Rectangle ir, Rectangle tr, boolean sel) { }
        @Override protected int getTabLabelShiftX(int tp, int idx, boolean sel) { return 0; }
        @Override protected int getTabLabelShiftY(int tp, int idx, boolean sel) { return 0; }
    }

    private static final class StripeRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            setBorder(new EmptyBorder(0, 10, 0, 10));
            setForeground(INK);
            setBackground(sel ? SOFT : (r % 2 == 0 ? Color.WHITE : PETAL));
            return this;
        }
    }

    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, false, false, r, c);
            setOpaque(true);
            setBackground(ROSE);
            setForeground(Color.WHITE);
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
            setBorder(new EmptyBorder(0, 10, 0, 10));
            return this;
        }
    }

    private static final class PillRenderer extends DefaultTableCellRenderer {
        private Color rowBg = Color.WHITE, pill = PETAL;
        @Override public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean f, int r, int c) {
            super.getTableCellRendererComponent(t, v, sel, false, r, c);
            String s = String.valueOf(v);
            Color fg = DEEP;
            pill = PETAL;
            if (s.equals("Approved")) { fg = new Color(0x1B6E32); pill = new Color(0xDDF3E3); }
            else if (s.equals("Needs Changes")) { fg = new Color(0xB3261E); pill = new Color(0xFFDAD6); }
            else if (s.equals("In Review") || s.equals("Pending")) { fg = new Color(0x9A5B00); pill = new Color(0xFFEBC8); }
            else if (s.equals("Draft")) { fg = MUTED; pill = new Color(0xF3E3EA); }
            rowBg = sel ? SOFT : (r % 2 == 0 ? Color.WHITE : PETAL);
            setHorizontalAlignment(CENTER);
            setFont(new Font(Font.SANS_SERIF, Font.BOLD, 11));
            setForeground(fg);
            setOpaque(false);
            return this;
        }
        @Override protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            smooth(g2);
            g2.setColor(rowBg);
            g2.fillRect(0, 0, getWidth(), getHeight());
            FontMetrics fm = getFontMetrics(getFont());
            int pw = Math.min(getWidth() - 8, fm.stringWidth(getText()) + 24), ph = 20;
            g2.setColor(pill);
            g2.fillRoundRect((getWidth() - pw) / 2, (getHeight() - ph) / 2, pw, ph, ph, ph);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    private static DefaultTableModel model(String... cols) {
        return new DefaultTableModel(cols, 0) {
            @Override public boolean isCellEditable(int r, int c) { return false; }
        };
    }

    private static JTextArea readOnlyArea() {
        JTextArea a = new JTextArea();
        a.setEditable(false);
        a.setLineWrap(true);
        a.setWrapStyleWord(true);
        a.setBackground(PAPER);
        a.setForeground(INK);
        a.setSelectionColor(SOFT);
        a.setFont(new Font(Font.SERIF, Font.PLAIN, 15));
        a.setMargin(new Insets(8, 10, 8, 10));
        return a;
    }

    private static JLabel titleLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SERIF, Font.BOLD | Font.ITALIC, 17));
        l.setForeground(DEEP);
        return l;
    }

    private static JScrollPane scroll(Component c) {
        JScrollPane sp = new JScrollPane(c);
        sp.setBorder(BorderFactory.createLineBorder(PETAL));
        sp.getViewport().setBackground(c instanceof JTable ? Color.WHITE : PAPER);
        return sp;
    }

    private static RoundedPanel card(JLabel title, JComponent body) {
        RoundedPanel p = new RoundedPanel(new BorderLayout(0, 8), Color.WHITE, SOFT, 22);
        p.setBorder(new EmptyBorder(10, 14, 12, 14));
        p.add(title, BorderLayout.NORTH);
        p.add(body, BorderLayout.CENTER);
        return p;
    }

    private static RoundedPanel card(String title, JComponent body) { return card(titleLabel(title), body); }

    private static JPanel clear(LayoutManager lm) {
        JPanel p = new JPanel(lm);
        p.setOpaque(false);
        return p;
    }

    private PinkButton featureButton(String text, boolean primary, Runnable action) {
        PinkButton b = new PinkButton(text, primary);
        b.addActionListener(e -> action.run());
        featureButtons.add(b);
        return b;
    }

    private final List<Object[]> selectionButtons = new ArrayList<>();

    private PinkButton tableButton(String text, boolean primary, boolean danger, JTable table, Runnable action) {
        PinkButton b = featureButton(text, primary, action);
        if (danger) b.danger();
        selectionButtons.add(new Object[]{b, table});
        return b;
    }

    private void updateSelectionButtons() {
        for (Object[] o : selectionButtons) {
            ((JButton) o[0]).setEnabled(joined && ((JTable) o[1]).getSelectedRow() >= 0);
        }
    }

    private static String rowTip(JTable t, java.awt.event.MouseEvent e) {
        int r = t.rowAtPoint(e.getPoint());
        if (r < 0) return null;
        String v = String.valueOf(t.getValueAt(r, t.getColumnCount() - 1));
        return "<html><body style='width:300px'>" + v.replace("&", "&amp;").replace("<", "&lt;").replace("\n", "<br>") + "</body></html>";
    }

    private static JLabel hintLabel(String text) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SERIF, Font.ITALIC, 13));
        l.setForeground(MUTED);
        return l;
    }

    private static JPanel sectionHeader(String title, JComponent... buttons) {
        JLabel l = new JLabel(title);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        l.setForeground(DEEP);
        JPanel right = clear(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        for (JComponent b : buttons) right.add(b);
        JPanel h = clear(new BorderLayout(8, 0));
        h.add(l, BorderLayout.WEST);
        h.add(right, BorderLayout.EAST);
        return h;
    }

    private void styleTable(JTable t, int statusCol) {
        t.setRowHeight(32);
        t.setShowGrid(false);
        t.setIntercellSpacing(new Dimension(0, 0));
        t.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        t.setFillsViewportHeight(true);
        t.setForeground(INK);
        t.setSelectionBackground(SOFT);
        t.setSelectionForeground(INK);
        t.setFont(new Font(Font.SERIF, Font.PLAIN, 14));
        t.setDefaultRenderer(Object.class, new StripeRenderer());
        JTableHeader h = t.getTableHeader();
        h.setReorderingAllowed(false);
        h.setPreferredSize(new Dimension(0, 30));
        h.setDefaultRenderer(new HeaderRenderer());
        if (statusCol >= 0) t.getColumnModel().getColumn(statusCol).setCellRenderer(new PillRenderer());
    }

    private void buildUi() {
        getContentPane().setBackground(BLUSH);
        setLayout(new BorderLayout());

        HeaderPanel header = new HeaderPanel(new BorderLayout(16, 0));
        header.setBorder(new EmptyBorder(14, 18, 14, 18));

        JLabel brand = new JLabel("Sherva");
        brand.setFont(new Font(Font.SERIF, Font.BOLD | Font.ITALIC, 32));
        brand.setForeground(Color.WHITE);
        JLabel tag = new JLabel("THINK. WRITE. DESIGN.");
        tag.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        tag.setForeground(new Color(0xFFE4EE));
        JPanel brandText = clear(new GridLayout(2, 1, 0, 0));
        brandText.add(brand);
        brandText.add(tag);
        JPanel brandBox = clear(new FlowLayout(FlowLayout.LEFT, 12, 0));
        brandBox.add(new Logo());
        brandBox.add(brandText);
        header.add(brandBox, BorderLayout.WEST);

        RoundedPanel pill = new RoundedPanel(new FlowLayout(FlowLayout.LEFT, 8, 6),
                new Color(255, 255, 255, 60), new Color(255, 255, 255, 130), 40);
        pill.add(whiteLabel("Server IP"));
        pill.add(hostField);
        pill.add(whiteLabel("Port"));
        pill.add(portField);
        pill.add(whiteLabel("Pen name"));
        pill.add(nickField);
        pill.add(connectBtn);
        connectBtn.addActionListener(e -> { if (joined) disconnect(); else connect(); });
        nickField.addActionListener(e -> { if (!joined) connect(); });
        JPanel pillWrap = clear(new GridBagLayout());
        pillWrap.add(pill);
        header.add(pillWrap, BorderLayout.EAST);
        add(header, BorderLayout.NORTH);

        JTabbedPane tabs = new JTabbedPane();
        tabs.setUI(new PinkTabUI());
        tabs.setOpaque(false);
        tabs.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        tabs.addTab("Studio Chat", buildChatTab());
        tabs.addTab("Idea Board", buildBoardTab());
        tabs.addTab("Revisions", buildRevisionTab());
        for (JTable t : new JTable[]{ideasTable, reqTable, replyTable, revTable}) {
            t.getSelectionModel().addListSelectionListener(e -> updateSelectionButtons());
            ((DefaultTableModel) t.getModel()).addTableModelListener(e -> SwingUtilities.invokeLater(this::updateSelectionButtons));
        }

        JList<String> users = new JList<>(usersModel);
        users.setOpaque(false);
        users.setSelectionModel(new DefaultListSelectionModel() {
            @Override public void setSelectionInterval(int a, int b) { }
        });
        users.setCellRenderer((list, value, idx, sel, focus) -> {
            boolean me = value.equals(nick);
            JLabel l = new JLabel("<html><font color='#EC407A'>&#9679;</font>&nbsp; "
                    + (me ? "<b>" + value + "</b> <font color='#B0668A'>(you)</font>" : value) + "</html>");
            l.setFont(new Font(Font.SERIF, Font.PLAIN, 15));
            l.setForeground(INK);
            l.setBorder(new EmptyBorder(4, 4, 4, 4));
            return l;
        });
        JScrollPane usersScroll = new JScrollPane(users);
        usersScroll.setBorder(null);
        usersScroll.setOpaque(false);
        usersScroll.getViewport().setOpaque(false);
        RoundedPanel side = card(usersTitle, usersScroll);
        side.setPreferredSize(new Dimension(190, 100));

        JPanel center = clear(new BorderLayout(12, 0));
        center.setBorder(new EmptyBorder(12, 14, 8, 14));
        center.add(tabs, BorderLayout.CENTER);
        center.add(side, BorderLayout.EAST);
        add(center, BorderLayout.CENTER);

        statusLabel.setForeground(DEEP);
        statusLabel.setFont(new Font(Font.SERIF, Font.ITALIC, 13));
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBackground(PETAL);
        bar.setBorder(new EmptyBorder(6, 16, 6, 16));
        bar.add(statusLabel);
        add(bar, BorderLayout.SOUTH);
        updateUsersTitle();
    }

    private static JLabel whiteLabel(String t) {
        JLabel l = new JLabel(t);
        l.setForeground(Color.WHITE);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        return l;
    }

    private JPanel buildChatTab() {
        chatPane.setEditable(false);
        chatPane.setBackground(PAPER);
        chatPane.setMargin(new Insets(10, 12, 10, 12));
        chatPane.setSelectionColor(SOFT);

        JPanel bottom = clear(new BorderLayout(8, 0));
        bottom.add(input, BorderLayout.CENTER);
        bottom.add(sendBtn, BorderLayout.EAST);
        input.addActionListener(e -> sendChat());
        sendBtn.addActionListener(e -> sendChat());

        JPanel p = clear(new BorderLayout(0, 10));
        p.setBorder(new EmptyBorder(6, 0, 0, 0));
        p.add(card("Studio chat", scroll(chatPane)), BorderLayout.CENTER);
        p.add(bottom, BorderLayout.SOUTH);
        return p;
    }

    private JPanel buildBoardTab() {
        styleTable(ideasTable, 4);
        styleTable(reqTable, -1);
        styleTable(replyTable, -1);
        ideasTable.getColumnModel().getColumn(0).setMaxWidth(45);
        ideasTable.getColumnModel().getColumn(4).setMaxWidth(130);
        reqTable.getColumnModel().getColumn(0).setMaxWidth(45);
        ideasTable.setRowHeight(28);
        reqTable.setRowHeight(28);
        replyTable.setRowHeight(28);
        replyTable.getColumnModel().getColumn(0).setMaxWidth(45);
        replyTable.getColumnModel().getColumn(1).setMaxWidth(90);
        replyTable.getColumnModel().getColumn(2).setMinWidth(64);
        replyTable.getColumnModel().getColumn(2).setMaxWidth(70);
        ideasTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) { refreshIdeaDetail(); refreshFeedback(); }
        });
        reqTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshReplies();
        });
        attachRowMenu(ideasTable, this::editIdea, this::deleteIdea);
        attachRowMenu(reqTable, this::editRequest, this::deleteRequest);
        attachRowMenu(replyTable, this::editReply, this::deleteReply);

        JPanel buttons = clear(new FlowLayout(FlowLayout.LEFT, 6, 0));
        buttons.add(featureButton("+ New Idea Card", true, this::newIdea));
        buttons.add(tableButton("Request Feedback", false, false, ideasTable, this::requestFeedback));
        buttons.add(tableButton("Submit Revision", false, false, ideasTable, this::submitRevision));
        buttons.add(hintLabel("Tip: double-click a row to edit, right-click for more."));
        JPanel ideasBody = clear(new BorderLayout(0, 8));
        ideasBody.add(scroll(ideasTable), BorderLayout.CENTER);
        ideasBody.add(buttons, BorderLayout.SOUTH);
        RoundedPanel boardCard = card("Idea board", ideasBody);
        boardCard.setPreferredSize(new Dimension(100, 250));

        JPanel ideaBtns = clear(new FlowLayout(FlowLayout.LEFT, 6, 0));
        ideaBtns.add(tableButton("Edit Idea", true, false, ideasTable, this::editIdea));
        ideaBtns.add(tableButton("Delete Idea", false, true, ideasTable, this::deleteIdea));
        JPanel ideaBody = clear(new BorderLayout(0, 8));
        ideaBody.add(scroll(ideaDetail), BorderLayout.CENTER);
        ideaBody.add(ideaBtns, BorderLayout.SOUTH);

        JPanel reqBox = clear(new BorderLayout(0, 4));
        reqBox.add(sectionHeader("Feedback requests",
                tableButton("Edit", false, false, reqTable, this::editRequest).compact(),
                tableButton("Delete", false, true, reqTable, this::deleteRequest).compact()), BorderLayout.NORTH);
        reqBox.add(scroll(reqTable), BorderLayout.CENTER);

        JPanel replyBox = clear(new BorderLayout(0, 4));
        replyBox.add(sectionHeader("Replies",
                tableButton("Reply", true, false, reqTable, this::replyToRequest).compact(),
                tableButton("Edit", false, false, replyTable, this::editReply).compact(),
                tableButton("Delete", false, true, replyTable, this::deleteReply).compact()), BorderLayout.NORTH);
        replyBox.add(scroll(replyTable), BorderLayout.CENTER);

        JPanel fbBody = clear(new GridLayout(2, 1, 0, 8));
        fbBody.add(reqBox);
        fbBody.add(replyBox);

        JPanel bottom = clear(new GridLayout(1, 2, 12, 0));
        bottom.add(card("Idea card", ideaBody));
        bottom.add(card("Feedback on this idea", fbBody));

        JPanel p = clear(new BorderLayout(0, 12));
        p.setBorder(new EmptyBorder(6, 0, 0, 0));
        p.add(boardCard, BorderLayout.NORTH);
        p.add(bottom, BorderLayout.CENTER);
        return p;
    }

    private void attachRowMenu(JTable table, Runnable edit, Runnable delete) {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem editItem = new JMenuItem("Edit");
        JMenuItem deleteItem = new JMenuItem("Delete");
        deleteItem.setForeground(new Color(0xB3261E));
        editItem.addActionListener(e -> edit.run());
        deleteItem.addActionListener(e -> delete.run());
        menu.add(editItem);
        menu.add(deleteItem);
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            private void select(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row >= 0) table.setRowSelectionInterval(row, row);
                else table.clearSelection();
            }
            private void popup(java.awt.event.MouseEvent e) {
                if (!e.isPopupTrigger() || !joined) return;
                select(e);
                if (table.getSelectedRow() >= 0) menu.show(table, e.getX(), e.getY());
            }
            @Override public void mousePressed(java.awt.event.MouseEvent e) { popup(e); }
            @Override public void mouseReleased(java.awt.event.MouseEvent e) { popup(e); }
            @Override public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e) && joined) {
                    select(e);
                    if (table.getSelectedRow() >= 0) edit.run();
                }
            }
        });
    }

    private JPanel buildRevisionTab() {
        styleTable(revTable, 4);
        attachRowMenu(revTable, this::editRevision, this::deleteRevision);
        revTable.getColumnModel().getColumn(0).setMaxWidth(50);
        revTable.getColumnModel().getColumn(2).setMaxWidth(50);
        revTable.getColumnModel().getColumn(4).setMaxWidth(130);
        revTable.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) refreshRevDetail();
        });

        JLabel hint = new JLabel("Approved after " + REQUIRED_APPROVALS + " approvals; any change request blocks approval.");
        hint.setFont(new Font(Font.SERIF, Font.ITALIC, 13));
        hint.setForeground(MUTED);
        JPanel buttons = clear(new FlowLayout(FlowLayout.LEFT, 8, 0));
        buttons.add(tableButton("Approve", true, false, revTable, () -> vote(APPROVE)));
        buttons.add(tableButton("Request Changes", false, false, revTable, () -> vote(CHANGES)));
        buttons.add(tableButton("Edit Revision", false, false, revTable, this::editRevision));
        buttons.add(tableButton("Delete Revision", false, true, revTable, this::deleteRevision));
        buttons.add(hint);
        JPanel body = clear(new BorderLayout(0, 8));
        body.add(scroll(revTable), BorderLayout.CENTER);
        body.add(buttons, BorderLayout.SOUTH);

        JPanel p = clear(new GridLayout(2, 1, 0, 12));
        p.setBorder(new EmptyBorder(6, 0, 0, 0));
        p.add(card("Revisions awaiting review", body));
        p.add(card("Revision note and reviewer votes", scroll(revDetail)));
        return p;
    }

    private void applyState() {
        boolean open = socket != null || connecting;
        hostField.setEnabled(!open);
        portField.setEnabled(!open);
        nickField.setEnabled(!open);
        connectBtn.setText(joined ? "Disconnect" : (connecting ? "Connecting..." : "Connect"));
        connectBtn.setEnabled(joined || !open);
        input.setEnabled(joined);
        sendBtn.setEnabled(joined);
        for (JButton b : featureButtons) b.setEnabled(joined);
        updateSelectionButtons();
        updateUsersTitle();
    }

    private void updateUsersTitle() {
        usersTitle.setText("Online (" + usersModel.getSize() + ")");
    }

    private void status(String msg) { statusLabel.setText(" " + msg); }

    private void showError(String msg) {
        status(msg.replace('\n', ' '));
        JOptionPane.showMessageDialog(this, msg, "Sherva", JOptionPane.WARNING_MESSAGE);
    }

    private void chatAdd(String text, SimpleAttributeSet attrs) throws BadLocationException {
        StyledDocument d = chatPane.getStyledDocument();
        d.insertString(d.getLength(), text, attrs);
    }

    private SimpleAttributeSet style(int size, boolean bold, boolean italic, Color fg, Color bg) {
        SimpleAttributeSet a = new SimpleAttributeSet();
        StyleConstants.setFontFamily(a, Font.SERIF);
        StyleConstants.setFontSize(a, size);
        StyleConstants.setBold(a, bold);
        StyleConstants.setItalic(a, italic);
        StyleConstants.setForeground(a, fg);
        if (bg != null) StyleConstants.setBackground(a, bg);
        return a;
    }

    private void paragraph(int start, int align) {
        StyledDocument d = chatPane.getStyledDocument();
        SimpleAttributeSet p = new SimpleAttributeSet();
        StyleConstants.setAlignment(p, align);
        StyleConstants.setSpaceAbove(p, 5);
        StyleConstants.setSpaceBelow(p, 5);
        d.setParagraphAttributes(start, d.getLength() - start, p, false);
        chatPane.setCaretPosition(d.getLength());
    }

    private void chatMessage(String time, String who, String text) {
        boolean mine = who.equals(nick);
        int start = chatPane.getStyledDocument().getLength();
        try {
            chatAdd(time + "  ", style(11, false, false, MUTED, null));
            chatAdd(who + "  ", style(14, true, false, mine ? DEEP : new Color(0x8E24AA), null));
            chatAdd(" " + text + " ", style(15, false, false, INK, mine ? SOFT : PETAL));
            chatAdd("\n", style(15, false, false, INK, null));
        } catch (BadLocationException ignored) { }
        paragraph(start, mine ? StyleConstants.ALIGN_RIGHT : StyleConstants.ALIGN_LEFT);
    }

    private void chatNote(String text, Color color) {
        int start = chatPane.getStyledDocument().getLength();
        try {
            chatAdd(text + "\n", style(13, false, true, color, null));
        } catch (BadLocationException ignored) { }
        paragraph(start, StyleConstants.ALIGN_CENTER);
    }

    private void chatInfo(String text) { chatNote(text, MUTED); }
    private void chatError(String text) { chatNote("! " + text, new Color(0xC62828)); }

    private void connect() {
        String host = hostField.getText().trim();
        String portText = portField.getText().trim();
        String name = nickField.getText().trim();
        if (host.isEmpty()) { showError("Enter the server IP address (or localhost)."); return; }
        int port;
        try {
            port = Integer.parseInt(portText);
            if (port < 1 || port > 65535) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            showError("Port must be a number between 1 and 65535.");
            return;
        }
        if (name.isEmpty()) { showError("Choose a pen name (nickname) first."); return; }

        connecting = true;
        status("Connecting to " + host + ":" + port + " ...");
        applyState();

        Thread t = new Thread(() -> {
            Socket s = new Socket();
            BufferedReader reader;
            try {
                s.connect(new InetSocketAddress(host, port), 4000);
                reader = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                out = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                socket = s;
                intentional = false;
            } catch (IOException | RuntimeException e) {
                try { s.close(); } catch (IOException ignored) { }
                SwingUtilities.invokeLater(() -> {
                    connecting = false;
                    applyState();
                    showError("Could not connect to " + host + ":" + port
                            + ".\nCheck the IP address and port, and make sure the server is running.\n(" + e.getMessage() + ")");
                });
                return;
            }
            out.println(Proto.build("JOIN", name));
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    final String l = line;
                    SwingUtilities.invokeLater(() -> handleLine(l));
                }
            } catch (IOException ignored) {
            } finally {
                try { s.close(); } catch (IOException ignored) { }
                SwingUtilities.invokeLater(() -> onClosed(s));
            }
        }, "inkloop-reader");
        t.setDaemon(true);
        t.start();
    }

    private void disconnect() {
        Socket s = socket;
        PrintWriter w = out;
        if (s == null) return;
        intentional = true;
        sender.execute(() -> {
            if (w != null) w.println(Proto.build("MSG", "/exit"));
            try { s.close(); } catch (IOException ignored) { }
        });
    }

    private void closeSocketNow() {
        Socket s = socket;
        if (s != null) {
            sender.execute(() -> { try { s.close(); } catch (IOException ignored) { } });
        }
    }

    private void onClosed(Socket s) {
        if (s != socket) return;
        boolean wasJoined = joined;
        socket = null;
        out = null;
        joined = false;
        connecting = false;
        usersModel.clear();
        applyState();
        if (wasJoined && !intentional) {
            chatError("Connection to the server was lost. Press Connect to rejoin.");
            showError("Connection to the server was lost.\nYour chat history stays on screen; press Connect to rejoin.");
        } else {
            status("Disconnected.");
        }
    }

    private void send(String line) {
        PrintWriter w = out;
        if (w == null || !joined) { status("Not connected."); return; }
        sender.execute(() -> {
            w.println(line);
            if (w.checkError()) {
                SwingUtilities.invokeLater(() -> status("Send failed - the connection may be lost."));
            }
        });
    }

    private void sendChat() {
        String text = input.getText().trim();
        if (text.isEmpty()) { status("A blank message cannot be sent."); return; }
        send(Proto.build("MSG", text));
        input.setText("");
    }

    private void handleLine(String line) {
        String[] p = Proto.parse(line);
        switch (p[0]) {
            case "JOINED":
                nick = Proto.arg(p, 1);
                joined = true;
                connecting = false;
                nickField.setText(nick);
                applyState();
                chatInfo("Welcome to the studio, " + nick + "! Type /help for commands.");
                status("Connected as " + nick + ".");
                break;
            case "NICK":
                nick = Proto.arg(p, 1);
                nickField.setText(nick);
                status("You are now " + nick + ".");
                break;
            case "ERROR": {
                String msg = Proto.arg(p, 1);
                if (!joined) {
                    intentional = true;
                    showError(msg);
                    closeSocketNow();
                } else {
                    chatError(msg);
                    status(msg);
                }
                break;
            }
            case "INFO":
                chatInfo(Proto.arg(p, 1));
                break;
            case "CHAT":
                chatMessage(Proto.arg(p, 1), Proto.arg(p, 2), Proto.arg(p, 3));
                break;
            case "USERS": {
                usersModel.clear();
                String csv = Proto.arg(p, 1);
                if (!csv.isEmpty()) for (String n : csv.split(",")) usersModel.addElement(n);
                updateUsersTitle();
                break;
            }
            case "BYE":
                intentional = true;
                closeSocketNow();
                break;
            case "CLEAR":
                ideasModel.setRowCount(0);
                reqModel.setRowCount(0);
                revModel.setRowCount(0);
                ideaData.clear(); revData.clear(); reqData.clear(); replyData.clear();
                replyModel.setRowCount(0);
                ideaDetail.setText(""); revDetail.setText("");
                break;
            case "IDEA":
                if (p.length >= 7) {
                    int id = toInt(p[1]);
                    ideaData.put(id, p);
                    upsert(ideasModel, new Object[]{id, p[2], p[3], p[4], p[6]});
                    refreshIdeaDetail();
                }
                break;
            case "FBREQ":
                if (p.length >= 5) { replaceById(reqData, p); refreshFeedback(); }
                break;
            case "FBREPLY":
                if (p.length >= 6) { replaceById(replyData, p); refreshReplies(); }
                break;
            case "REV":
                if (p.length >= 11) {
                    int id = toInt(p[1]);
                    revData.put(id, p);
                    upsert(revModel, new Object[]{id, p[3], "v" + p[4], p[5], p[7],
                            p[8] + " approve / " + p[9] + " changes"});
                    refreshRevDetail();
                }
                break;
            default:
                break;
        }
    }

    private static int toInt(String s) {
        try { return Integer.parseInt(s); } catch (NumberFormatException e) { return -1; }
    }

    private static void replaceById(List<String[]> list, String[] row) {
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i)[1].equals(row[1])) { list.set(i, row); return; }
        }
        list.add(row);
    }

    private static void upsert(DefaultTableModel m, Object[] row) {
        for (int r = 0; r < m.getRowCount(); r++) {
            if (m.getValueAt(r, 0).equals(row[0])) {
                for (int c = 0; c < row.length; c++) m.setValueAt(row[c], r, c);
                return;
            }
        }
        m.addRow(row);
    }

    private static int selectedId(JTable t, DefaultTableModel m) {
        int row = t.getSelectedRow();
        return row < 0 ? -1 : (Integer) m.getValueAt(row, 0);
    }

    private void refreshIdeaDetail() {
        String[] d = ideaData.get(selectedId(ideasTable, ideasModel));
        if (d == null) { ideaDetail.setText("Select an idea card to read its pitch."); return; }
        ideaDetail.setText(d[4] + "\n" + d[3] + " by " + d[2] + "  -  " + d[6] + "\n\n"
                + (d[5].isEmpty() ? "(no pitch written)" : d[5]));
        ideaDetail.setCaretPosition(0);
    }

    private void refreshFeedback() {
        int ideaId = selectedId(ideasTable, ideasModel);
        int keep = selectedId(reqTable, reqModel);
        reqModel.setRowCount(0);
        for (String[] r : reqData) {
            if (toInt(r[2]) == ideaId) reqModel.addRow(new Object[]{toInt(r[1]), r[3] + ": " + r[4]});
        }
        for (int i = 0; i < reqModel.getRowCount(); i++) {
            if ((Integer) reqModel.getValueAt(i, 0) == keep) { reqTable.setRowSelectionInterval(i, i); break; }
        }
        refreshReplies();
    }

    private void refreshReplies() {
        int reqId = selectedId(reqTable, reqModel);
        int keep = selectedId(replyTable, replyModel);
        replyModel.setRowCount(0);
        if (reqId < 0) return;
        for (String[] r : replyData) {
            if (toInt(r[2]) == reqId) replyModel.addRow(new Object[]{toInt(r[1]), r[3], r[5], r[4]});
        }
        for (int i = 0; i < replyModel.getRowCount(); i++) {
            if ((Integer) replyModel.getValueAt(i, 0) == keep) { replyTable.setRowSelectionInterval(i, i); break; }
        }
    }

    private void refreshRevDetail() {
        String[] d = revData.get(selectedId(revTable, revModel));
        if (d == null) { revDetail.setText("Select a revision to see the author's note and votes."); return; }
        revDetail.setText("\"" + d[3] + "\" version " + d[4] + " by " + d[5] + "  -  " + d[7]
                + "\n\nWhat changed: " + d[6] + "\n\nVotes:\n" + (d[10].isEmpty() ? "(none yet)" : d[10]));
        revDetail.setCaretPosition(0);
    }

    private String askText(String title, String label, boolean required) {
        return askText(title, label, required, "");
    }

    private String askText(String title, String label, boolean required, String initial) {
        JTextArea area = new JTextArea(initial, 4, 30);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new Font(Font.SERIF, Font.PLAIN, 15));
        area.setSelectionColor(SOFT);
        JLabel l = new JLabel(label);
        l.setForeground(DEEP);
        JPanel p = new JPanel(new BorderLayout(0, 6));
        p.add(l, BorderLayout.NORTH);
        p.add(new JScrollPane(area), BorderLayout.CENTER);
        if (JOptionPane.showConfirmDialog(this, p, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return null;
        String text = area.getText().trim();
        if (required && text.isEmpty()) { status("That field cannot be blank."); return null; }
        return text;
    }

    private static final int IDEA_TITLE_MAX = 80, IDEA_PITCH_MAX = 400;

    private static void limitLength(javax.swing.text.JTextComponent c, int max) {
        ((javax.swing.text.AbstractDocument) c.getDocument()).setDocumentFilter(new javax.swing.text.DocumentFilter() {
            @Override public void insertString(FilterBypass fb, int off, String s, AttributeSet a) throws BadLocationException {
                replace(fb, off, 0, s, a);
            }
            @Override public void replace(FilterBypass fb, int off, int len, String s, AttributeSet a) throws BadLocationException {
                if (s == null) s = "";
                int room = max - (fb.getDocument().getLength() - len);
                if (s.length() > room) s = s.substring(0, Math.max(room, 0));
                if (s.isEmpty() && len == 0) return;
                super.replace(fb, off, len, s, a);
            }
        });
    }

    private static void onChange(javax.swing.text.JTextComponent c, Runnable r) {
        c.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { r.run(); }
        });
    }

    private static JLabel counter(javax.swing.text.JTextComponent c, int max) {
        JLabel l = new JLabel();
        l.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 11));
        Runnable update = () -> {
            int n = c.getDocument().getLength();
            l.setText(n + " / " + max);
            l.setForeground(n >= max ? new Color(0xB3261E) : MUTED);
        };
        onChange(c, update);
        update.run();
        return l;
    }

    private static JPanel labelRow(String text, JLabel right) {
        JLabel l = new JLabel(text);
        l.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        l.setForeground(DEEP);
        JPanel row = new JPanel(new BorderLayout());
        row.setOpaque(false);
        row.add(l, BorderLayout.WEST);
        if (right != null) row.add(right, BorderLayout.EAST);
        return row;
    }

    private String[] ideaDialog(String heading, String[] existing) {
        JComboBox<String> cat = new JComboBox<>(new String[]{"Poem", "Short Story", "Essay", "Script", "Song Lyrics", "Other"});
        cat.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 13));

        JTextField title = new JTextField(40);
        title.setFont(new Font(Font.SERIF, Font.PLAIN, 17));
        title.setMargin(new Insets(5, 8, 5, 8));
        limitLength(title, IDEA_TITLE_MAX);

        JTextArea pitch = new JTextArea(12, 50);
        pitch.setFont(new Font(Font.SERIF, Font.PLAIN, 16));
        pitch.setMargin(new Insets(10, 12, 10, 12));
        pitch.setLineWrap(true);
        pitch.setWrapStyleWord(true);
        limitLength(pitch, IDEA_PITCH_MAX);

        if (existing != null) {
            cat.setSelectedItem(existing[0]);
            title.setText(existing[1]);
            pitch.setText(existing[2]);
        }

        JLabel error = new JLabel(" ");
        error.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 12));
        error.setForeground(new Color(0xB3261E));
        onChange(title, () -> error.setText(" "));

        JPanel p = new JPanel(new GridBagLayout());
        p.setPreferredSize(new Dimension(640, 470));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0; g.weightx = 1; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(0, 0, 4, 0);

        g.gridy = 0; p.add(labelRow("Category", null), g);
        g.gridy = 1; g.fill = GridBagConstraints.NONE; g.anchor = GridBagConstraints.WEST; g.insets = new Insets(0, 0, 12, 0);
        p.add(cat, g);

        g.fill = GridBagConstraints.HORIZONTAL; g.anchor = GridBagConstraints.CENTER; g.insets = new Insets(0, 0, 4, 0);
        g.gridy = 2; p.add(labelRow("Title", counter(title, IDEA_TITLE_MAX)), g);
        g.gridy = 3; g.insets = new Insets(0, 0, 12, 0); p.add(title, g);

        g.gridy = 4; g.insets = new Insets(0, 0, 4, 0);
        p.add(labelRow("Your idea - what is it about? (write as much as you like, up to " + IDEA_PITCH_MAX + " characters)",
                counter(pitch, IDEA_PITCH_MAX)), g);
        g.gridy = 5; g.weighty = 1; g.fill = GridBagConstraints.BOTH; g.insets = new Insets(0, 0, 4, 0);
        JScrollPane pitchScroll = new JScrollPane(pitch);
        pitchScroll.setBorder(BorderFactory.createLineBorder(SOFT));
        p.add(pitchScroll, g);

        g.gridy = 6; g.weighty = 0; g.fill = GridBagConstraints.HORIZONTAL; g.insets = new Insets(0, 0, 0, 0);
        p.add(error, g);

        String confirmText = existing == null ? "Add to board" : "Save changes";
        JOptionPane pane = new JOptionPane(p, JOptionPane.PLAIN_MESSAGE, JOptionPane.DEFAULT_OPTION, null,
                new Object[]{confirmText, "Cancel"}, confirmText);
        JDialog dlg = pane.createDialog(this, heading);
        dlg.setResizable(true);
        dlg.setMinimumSize(new Dimension(520, 420));
        dlg.addWindowListener(new WindowAdapter() {
            @Override public void windowOpened(WindowEvent e) { title.requestFocusInWindow(); }
        });

        while (true) {
            pane.setValue(JOptionPane.UNINITIALIZED_VALUE);
            dlg.setVisible(true);
            if (!confirmText.equals(pane.getValue())) { dlg.dispose(); return null; }
            if (!title.getText().isBlank()) break;
            error.setText("Please give your idea a title.");
            title.requestFocusInWindow();
        }
        dlg.dispose();
        return new String[]{(String) cat.getSelectedItem(), title.getText().trim(), pitch.getText().trim()};
    }

    private void newIdea() {
        String[] v = ideaDialog("New Idea Card", null);
        if (v != null) send(Proto.build("IDEA", v[0], v[1], v[2]));
    }

    private boolean confirm(String message) {
        return JOptionPane.showConfirmDialog(this, message, "Sherva", JOptionPane.YES_NO_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.YES_OPTION;
    }

    private void editIdea() {
        if (!joined) return;
        int id = selectedId(ideasTable, ideasModel);
        if (id < 0) { status("Select an idea card first."); return; }
        String[] d = ideaData.get(id);
        if (d == null) return;
        String[] v = ideaDialog("Edit Idea Card", new String[]{d[3], d[4], d[5]});
        if (v != null) send(Proto.build("IDEAUPD", String.valueOf(id), v[0], v[1], v[2]));
    }

    private void deleteIdea() {
        if (!joined) return;
        int id = selectedId(ideasTable, ideasModel);
        if (id < 0) { status("Select an idea card first."); return; }
        if (ideaData.get(id) == null) return;
        if (confirm("Delete \"" + ideaData.get(id)[4] + "\" and all of its feedback and revisions?\nThis cannot be undone."))
            send(Proto.build("IDEADEL", String.valueOf(id)));
    }

    private String[] findRow(List<String[]> list, int id) {
        for (String[] r : list) if (toInt(r[1]) == id) return r;
        return null;
    }

    private void editRequest() {
        int id = selectedId(reqTable, reqModel);
        if (id < 0) { status("Select a feedback request first."); return; }
        String[] r = findRow(reqData, id);
        if (r == null) return;
        String q = askText("Edit Feedback Request", "What do you want readers to look at?", true, r[4]);
        if (q != null) send(Proto.build("FBREQUPD", String.valueOf(id), q));
    }

    private void deleteRequest() {
        int id = selectedId(reqTable, reqModel);
        if (id < 0) { status("Select a feedback request first."); return; }
        String[] r = findRow(reqData, id);
        if (r == null) return;
        if (confirm("Delete this feedback request and all replies to it?"))
            send(Proto.build("FBREQDEL", String.valueOf(id)));
    }

    private void editReply() {
        int id = selectedId(replyTable, replyModel);
        if (id < 0) { status("Select a reply first."); return; }
        String[] r = findRow(replyData, id);
        if (r == null) return;
        String c = askText("Edit Feedback", "Your feedback:", true, r[4]);
        if (c != null) send(Proto.build("FBREPLYUPD", String.valueOf(id), c));
    }

    private void deleteReply() {
        int id = selectedId(replyTable, replyModel);
        if (id < 0) { status("Select a reply first."); return; }
        String[] r = findRow(replyData, id);
        if (r == null) return;
        if (confirm("Delete your reply?")) send(Proto.build("FBREPLYDEL", String.valueOf(id)));
    }

    private void editRevision() {
        int id = selectedId(revTable, revModel);
        if (id < 0) { status("Select a revision first."); return; }
        String[] d = revData.get(id);
        if (d == null) return;
        if (!d[5].equals(nick)) { status("Only the author can edit this revision."); return; }
        String note = askText("Edit Revision", "What changed in this revision? (Editing clears existing votes.)", true, d[6]);
        if (note != null) send(Proto.build("REVUPD", String.valueOf(id), note));
    }

    private void deleteRevision() {
        int id = selectedId(revTable, revModel);
        if (id < 0) { status("Select a revision first."); return; }
        String[] d = revData.get(id);
        if (d == null) return;
        if (!d[5].equals(nick)) { status("Only the author can delete this revision."); return; }
        if (confirm("Delete version " + d[4] + " of \"" + d[3] + "\"?")) send(Proto.build("REVDEL", String.valueOf(id)));
    }

    private int ownIdeaSelected(String action) {
        int id = selectedId(ideasTable, ideasModel);
        if (id < 0) { status("Select an idea card first."); return -1; }
        if (!ideaData.get(id)[2].equals(nick)) { status("Only the idea's author can " + action + "."); return -1; }
        return id;
    }

    private void requestFeedback() {
        int id = ownIdeaSelected("request feedback");
        if (id < 0) return;
        String q = askText("Request Feedback", "What do you want readers to look at? (e.g. \"Is the ending too abrupt?\")", true);
        if (q != null) send(Proto.build("FBREQ", String.valueOf(id), q));
    }

    private void replyToRequest() {
        int id = selectedId(reqTable, reqModel);
        if (id < 0) { status("Select a feedback request first."); return; }
        String c = askText("Give Feedback", "Your feedback:", true);
        if (c != null) send(Proto.build("FBREPLY", String.valueOf(id), c));
    }

    private void submitRevision() {
        int id = ownIdeaSelected("submit a revision");
        if (id < 0) return;
        String note = askText("Submit Revision for Approval", "What changed in this revision?", true);
        if (note != null) send(Proto.build("REVSUBMIT", String.valueOf(id), note));
    }

    private void vote(String choice) {
        int id = selectedId(revTable, revModel);
        if (id < 0) { status("Select a revision first."); return; }
        if (revData.get(id)[5].equals(nick)) { status("You cannot vote on your own revision."); return; }
        String comment = "";
        if (choice.equals(CHANGES)) {
            String c = askText("Request Changes", "What should the writer change?", true);
            if (c == null) return;
            comment = c;
        }
        send(Proto.build("REVVOTE", String.valueOf(id), choice, comment));
    }

    private static void applyPinkDefaults() {
        UIManager.put("Panel.background", BLUSH);
        UIManager.put("OptionPane.background", BLUSH);
        UIManager.put("OptionPane.messageForeground", INK);
        UIManager.put("Label.foreground", INK);
        UIManager.put("Button.background", PETAL);
        UIManager.put("Button.select", SOFT);
        UIManager.put("Button.foreground", DEEP);
        UIManager.put("TextField.selectionBackground", SOFT);
        UIManager.put("TextField.selectionForeground", INK);
        UIManager.put("TextArea.selectionBackground", SOFT);
        UIManager.put("ComboBox.selectionBackground", SOFT);
        UIManager.put("ComboBox.selectionForeground", INK);
        UIManager.put("ScrollBar.thumb", SOFT);
        UIManager.put("ScrollBar.thumbShadow", ROSE);
        UIManager.put("ScrollBar.thumbHighlight", PETAL);
        UIManager.put("ScrollBar.track", PETAL);
        UIManager.put("ScrollBar.width", 12);
    }

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            try { UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); } catch (Exception ignored) { }
            applyPinkDefaults();
            new StudioClient().setVisible(true);
        });
    }
}
