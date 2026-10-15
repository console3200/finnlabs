package finnLabs;


import Console.FocusState;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.*;
import javax.swing.text.rtf.RTFEditorKit;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.io.*;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class writer extends JFrame {

    // Fixed key objects used as custom attribute markers inside the StyledDocument
    private static final Object HYPERLINK_ATTR = new Object();
    private static final Object OUTLINE_LEVEL_ATTR = new Object();
    private static final int PAGE_CONTENT_WIDTH = 760;

    // --- Icon set (GSource/icons) ------------------------------------------------
    // Icons live in a GSource/icons folder relative to the working directory the app
    // is launched from (e.g. run from ~/Console so it resolves ~/Console/GSource/icons).
    // Every lookup is defensive: if a file is missing, callers fall back to a plain
    // text/glyph button instead of failing, since this icon pack is a general system
    // set rather than a purpose-built word-processor set.
    private static final String ICON_DIR = "GSource" + File.separator + "icons" + File.separator;
    private static final int TOOLBAR_ICON_SIZE = 20;
    private static final Map<String, ImageIcon> iconCache = new HashMap<>();

    private static ImageIcon loadIcon(String filename, int size) {
        String key = filename + "@" + size;
        if (iconCache.containsKey(key)) return iconCache.get(key);
        ImageIcon result = null;
        try {
            File f = new File(ICON_DIR + filename);
            if (f.exists()) {
                ImageIcon raw = new ImageIcon(f.getAbsolutePath());
                if (raw.getIconWidth() > 0) {
                    Image scaled = raw.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH);
                    result = new ImageIcon(scaled);
                }
            }
        } catch (Exception ignored) {}
        iconCache.put(key, result);
        return result;
    }

    private JTextPane textPane;
    private final JLabel wordCountLabel;
    private final JLabel saveStatusLabel;
    private final JLabel zoomLabel;
    private final UndoManager undoManager;
    private final JPanel searchPanel;
    private final JTextField searchField;
    private final JTextField replaceField;
    private JFileChooser fileChooser;

    private JPanel topContainer;
    private JPanel statusBar;
    private JComponent outlineSidebar;
    private JComponent commentsSidebarPanel;
    private PagePanel pagePanel;

    private DefaultListModel<OutlineEntry> outlineListModel;
    private JList<OutlineEntry> outlineList;
    private DefaultListModel<Comment> commentListModel;
    private JList<Comment> commentList;

    private final List<Comment> comments = new ArrayList<>();
    private final List<VersionSnapshot> versionHistory = new ArrayList<>();
    private final List<int[]> misspelledRanges = new ArrayList<>();
    private final List<Object> misspelledTags = new ArrayList<>();

    private final Highlighter.HighlightPainter squigglyPainter = new SquigglyUnderlinePainter(new Color(220, 50, 47));
    private final Highlighter.HighlightPainter commentHighlightPainter =
            new DefaultHighlighter.DefaultHighlightPainter(new Color(255, 244, 176));

    private javax.swing.Timer spellDebounce;
    private javax.swing.Timer autosaveTimer;
    private javax.swing.Timer autoVersionTimer;

    private volatile boolean dirty = false;
    private boolean focusMode = false;
    private boolean outlineVisible = true;
    private boolean commentsVisible = false;
    private int zoomPercent = 100;

    private final Set<String> personalDictionary = new HashSet<>();
    private final Set<String> baseDictionary = new HashSet<>(Arrays.asList(
            "the","be","to","of","and","a","in","that","have","i","it","for","not","on","with","he","as","you","do",
            "at","this","but","his","by","from","they","we","say","her","she","or","an","will","my","one","all",
            "would","there","their","what","so","up","out","if","about","who","get","which","go","me","when","make",
            "can","like","time","no","just","him","know","take","people","into","year","your","good","some","could",
            "them","see","other","than","then","now","look","only","come","its","over","think","also","back","after",
            "use","two","how","our","work","first","well","way","even","new","want","because","any","these","give",
            "day","most","us","is","are","was","were","been","being","has","had","did","doing","done","should",
            "shall","might","must","may","cannot","wont","dont","doesnt","isnt","arent","wasnt","werent","cant",
            "couldnt","wouldnt","shouldnt","im","ive","id","ill","youre","youve","youll","hes","shes","its","were",
            "theyre","theyve","theyll","thats","whats","whos","heres","theres","lets","please","thanks","thank",
            "hello","hi","yes","okay","ok","sure","great","nice","good","bad","happy","sad","love","hate","need",
            "want","help","find","found","made","made","tell","told","ask","asked","call","called","try","tried",
            "keep","kept","let","begin","began","seem","seemed","talk","turn","turned","start","started","show",
            "showed","hear","heard","play","played","run","ran","move","moved","live","lived","believe","believed",
            "bring","brought","happen","happened","write","wrote","provide","provided","sit","sat","stand","stood",
            "lose","lost","pay","paid","meet","met","include","included","continue","continued","set","learn",
            "learned","change","changed","lead","led","understand","understood","watch","watched","follow",
            "followed","stop","stopped","create","created","speak","spoke","read","allow","allowed","add","added",
            "spend","spent","grow","grew","open","opened","walk","walked","win","won","offer","offered","remember",
            "remembered","love","consider","considered","appear","appeared","buy","bought","wait","waited","serve",
            "served","die","died","send","sent","expect","expected","build","built","stay","stayed","fall","fell",
            "cut","reach","reached","kill","killed","remain","remained","word","world","life","hand","part","child",
            "eye","woman","man","place","case","week","company","system","program","question","government","number",
            "night","point","home","water","room","mother","area","money","story","fact","month","lot","right",
            "study","book","job","word","business","issue","side","kind","head","house","service","friend","father",
            "power","hour","game","line","end","member","law","car","city","community","name","president","team",
            "minute","idea","body","information","back","parent","face","others","level","office","door","health",
            "person","art","war","history","party","result","change","morning","reason","research","girl","guy",
            "moment","air","teacher","force","education","writer","finnlabs","document","text","editor","java",
            "format","export","comment","outline","zoom","autosave","version","hyperlink","table","image","emoji"
    ));

    public writer() {
        super("finnLabs : Writer");
        configureSpaciousUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1440, 920);
        setLocationRelativeTo(null);

        // --- Editor initialization -------------------------------------------------
        textPane = new JTextPane() {
            @Override
            public Dimension getPreferredSize() {
                Dimension d = super.getPreferredSize();
                return new Dimension(PAGE_CONTENT_WIDTH, d.height);
            }
        };
        textPane.setFont(new Font("Serif", Font.PLAIN, 16));
        textPane.setMargin(new Insets(0, 0, 0, 0));

        undoManager = new UndoManager();
        textPane.getDocument().addUndoableEditListener(undoManager);
        attachDocumentListener();

        installMouseHandlers();

        // --- Status bar --------------------------------------------------------
        wordCountLabel = new JLabel(" Words: 0  |  Characters: 0  |  ~1 min read ");
        saveStatusLabel = new JLabel("All changes saved  ");
        zoomLabel = new JLabel("100%");

        // --- Search / Replace bar -----------------------------------------------
        searchField = new JTextField(15);
        replaceField = new JTextField(15);
        searchPanel = createSearchPanel();
        searchPanel.setVisible(false);

        // --- Debounce timers -----------------------------------------------------
        spellDebounce = new javax.swing.Timer(500, e -> {
            runLiveSpellCheckPass();
            refreshOutline();
        });
        spellDebounce.setRepeats(false);

        autosaveTimer = new javax.swing.Timer(2500, e -> { if (dirty) performAutosave(); });
        autosaveTimer.start();

        autoVersionTimer = new javax.swing.Timer(180_000, e ->
                saveVersionSnapshot("Auto \u2014 " + new SimpleDateFormat("h:mm a").format(new Date())));
        autoVersionTimer.start();

        // --- Layout assembly -------------------------------------------------------
        setLayout(new BorderLayout());
        setJMenuBar(createMenuBar());

        JPanel toolbarRow = new JPanel(new BorderLayout());
        toolbarRow.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0, 0, 0, 40)));
        toolbarRow.add(createToolBar(), BorderLayout.CENTER);
        toolbarRow.add(createShareButtonPanel(), BorderLayout.EAST);

        topContainer = new JPanel(new BorderLayout());
        topContainer.add(toolbarRow, BorderLayout.NORTH);
        topContainer.add(searchPanel, BorderLayout.SOUTH);
        add(topContainer, BorderLayout.NORTH);

        JPanel centerContainer = new JPanel(new BorderLayout(8, 0));
        centerContainer.setBorder(new EmptyBorder(0, 0, 0, 0));
        outlineSidebar = createOutlineSidebar();
        commentsSidebarPanel = createCommentsSidebar();
        commentsSidebarPanel.setVisible(false);
        centerContainer.add(outlineSidebar, BorderLayout.WEST);
        centerContainer.add(buildCanvas(), BorderLayout.CENTER);
        centerContainer.add(commentsSidebarPanel, BorderLayout.EAST);
        add(centerContainer, BorderLayout.CENTER);

        statusBar = createStatusBar();
        add(statusBar, BorderLayout.SOUTH);

        // Apply default theme
        applyTheme("Modern Dark");
        updateStatus();
    }

    // =====================================================================
    // SPACING / LAYOUT HELPERS
    // =====================================================================
    /** Sets generous default padding/margins across every Swing menu & button so the
     *  whole app breathes, without having to hand-tune each individual component. */
    private void configureSpaciousUI() {
        // Spacing and menu/tooltip styling now come from the WhiteSur look and feel.
    }

    /** Builds a toolbar button that prefers an icon from GSource/icons and falls back
     *  to a text/glyph label when that icon isn't present on disk, always with a
     *  tooltip and generous, consistent padding so buttons don't feel cramped. */
    private JButton makeToolButton(String glyphOrText, String iconFile, String tooltip) {
        ImageIcon icon = iconFile != null ? loadIcon(iconFile, TOOLBAR_ICON_SIZE) : null;
        JButton btn = icon != null ? new JButton(icon) : new JButton(glyphOrText);
        if (icon == null) btn.setFont(new Font("SansSerif", Font.PLAIN, 13));
        btn.setToolTipText(tooltip);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(6, 10, 6, 10));
        btn.setPreferredSize(new Dimension(icon != null ? 38 : Math.max(38, btn.getPreferredSize().width + 14), 34));
        return btn;
    }

    /** Wraps a swing Action (e.g. StyledEditorKit.BoldAction) as a spacious toolbar
     *  button, keeping its built-in behaviour but giving it room and a tooltip. */
    private JButton makeActionButton(Action action, String label, Font font, String tooltip) {
        JButton btn = new JButton(action);
        btn.setText(label);
        if (font != null) btn.setFont(font);
        btn.setToolTipText(tooltip);
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(6, 12, 6, 12));
        btn.setPreferredSize(new Dimension(Math.max(38, btn.getPreferredSize().width + 10), 34));
        return btn;
    }

    /** A labelled cluster of controls with breathing room around and between them,
     *  used to visually group the toolbar into "Format", "Insert", "View" etc. */
    private JPanel makeToolbarGroup(String label, JComponent... controls) {
        JPanel group = new JPanel();
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setOpaque(false);
        group.setBorder(new EmptyBorder(2, 10, 2, 10));

        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
        row.setOpaque(false);
        for (JComponent c : controls) row.add(c);

        if (label != null) {
            JLabel caption = new JLabel(label.toUpperCase());
            caption.setFont(new Font("SansSerif", Font.PLAIN, 9));
            caption.setForeground(new Color(140, 140, 140));
            caption.setBorder(new EmptyBorder(0, 4, 1, 0));
            group.add(caption);
        }
        group.add(row);
        return group;
    }

    private JComponent toolbarDivider() {
        JSeparator sep = new JSeparator(SwingConstants.VERTICAL);
        sep.setPreferredSize(new Dimension(1, 32));
        sep.setMaximumSize(new Dimension(1, 32));
        return sep;
    }

    // =====================================================================
    // CANVAS / PAGE LOOK
    // =====================================================================
    private JComponent buildCanvas() {
        pagePanel = new PagePanel(new BorderLayout());
        pagePanel.setBorder(new EmptyBorder(56, 68, 56, 68));
        pagePanel.add(textPane, BorderLayout.CENTER);

        ScrollableBackdrop backdrop = new ScrollableBackdrop(new GridBagLayout());
        backdrop.setBackground(new Color(60, 63, 65));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(36, 0, 48, 0);
        gbc.anchor = GridBagConstraints.NORTH;
        backdrop.add(pagePanel, gbc);

        JScrollPane scrollPane = new JScrollPane(backdrop);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setBackground(backdrop.getBackground());
        return scrollPane;
    }

    /** Panel that draws a soft drop shadow behind itself, giving a floating "page" look. */
    static class PagePanel extends JPanel {
        PagePanel(LayoutManager lm) {
            super(lm);
            setOpaque(false);
        }
        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth(), h = getHeight();
            int shadow = 10;
            for (int i = shadow; i > 0; i--) {
                g2.setColor(new Color(0, 0, 0, 8));
                g2.fillRoundRect(shadow - i, shadow - i, w - shadow, h - shadow, 4, 4);
            }
            g2.setColor(getBackground());
            g2.fillRect(0, 0, w - shadow, h - shadow);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Wrapper panel whose width tracks the viewport (so its GridBagLayout child can be centered)
     *  while its height stays natural, so vertical scrolling still works. */
    static class ScrollableBackdrop extends JPanel implements Scrollable {
        ScrollableBackdrop(LayoutManager lm) { super(lm); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 120; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // =====================================================================
    // DOCUMENT CHANGE PLUMBING
    // =====================================================================
    private void attachDocumentListener() {
        textPane.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onDocChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onDocChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onDocChanged(); }
        });
    }

    private void onDocChanged() {
        updateStatus();
        markDirty();
        if (spellDebounce != null) spellDebounce.restart();
    }

    private void markDirty() {
        dirty = true;
        if (saveStatusLabel != null) saveStatusLabel.setText("\u25CF Unsaved changes  ");
    }

    // =====================================================================
    // SEARCH & REPLACE BAR
    // =====================================================================
    private JPanel createSearchPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 8));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Find & Replace"),
                new EmptyBorder(4, 10, 8, 10)));

        searchField.setPreferredSize(new Dimension(200, 30));
        replaceField.setPreferredSize(new Dimension(200, 30));

        JButton btnFindNext = new JButton("Find Next");
        JButton btnReplace = new JButton("Replace");
        JButton btnReplaceAll = new JButton("Replace All");
        JButton btnClose = makeToolButton("\u2715", "close.png", "Close find & replace");
        for (JButton b : new JButton[]{btnFindNext, btnReplace, btnReplaceAll}) {
            b.setMargin(new Insets(6, 12, 6, 12));
        }

        btnFindNext.addActionListener(e -> findNextWord(searchField.getText()));
        btnReplace.addActionListener(e -> replaceCurrentWord(searchField.getText(), replaceField.getText()));
        btnReplaceAll.addActionListener(e -> replaceAllWords(searchField.getText(), replaceField.getText()));
        btnClose.addActionListener(e -> {
            textPane.getHighlighter().removeAllHighlights();
            runLiveSpellCheckPass();
            searchPanel.setVisible(false);
        });

        panel.add(new JLabel("Find:"));
        panel.add(searchField);
        panel.add(btnFindNext);
        panel.add(Box.createHorizontalStrut(10));
        panel.add(new JLabel("Replace:"));
        panel.add(replaceField);
        panel.add(btnReplace);
        panel.add(btnReplaceAll);
        panel.add(Box.createHorizontalStrut(10));
        panel.add(btnClose);

        return panel;
    }

    private void findNextWord(String query) {
        if (query.isEmpty()) return;
        String content = textPane.getText();
        int caretPos = textPane.getCaretPosition();
        int idx = content.indexOf(query, caretPos);
        if (idx == -1) idx = content.indexOf(query, 0);
        if (idx != -1) {
            textPane.select(idx, idx + query.length());
            textPane.requestFocus();
        } else {
            JOptionPane.showMessageDialog(this, "Match not found.", "Search", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    private void replaceCurrentWord(String target, String replacement) {
        if (textPane.getSelectedText() != null && textPane.getSelectedText().equals(target)) {
            textPane.replaceSelection(replacement);
        }
        findNextWord(target);
    }

    private void replaceAllWords(String target, String replacement) {
        if (target.isEmpty()) return;
        String content = textPane.getText();
        textPane.setText(content.replace(target, replacement));
    }

    // =====================================================================
    // MENU BAR
    // =====================================================================
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // ---- File ----
        JMenu fileMenu = new JMenu("File");
        JMenuItem newItem = new JMenuItem("New", loadIcon("description.png", 16));
        JMenuItem openItem = new JMenuItem("Open...", loadIcon("fileopen.png", 16));
        JMenuItem saveItem = new JMenuItem("Save As...", loadIcon("fileSave.png", 16));
        JMenuItem saveVersionItem = new JMenuItem("Save Version Snapshot", loadIcon("backup.png", 16));
        JMenuItem versionHistoryItem = new JMenuItem("Version History...", loadIcon("restorePage.png", 16));

        JMenu exportMenu = new JMenu("Export As");
        String[] formats = {"pdf", "docx", "txt", "rtf", "epub", "md"};
        for (String fmt : formats) {
            JMenuItem item = new JMenuItem(fmt.toUpperCase() + " (" + fmt + ")");
            item.addActionListener(e -> exportFile(fmt));
            exportMenu.add(item);
        }

        JMenuItem exitItem = new JMenuItem("Exit", loadIcon("close.png", 16));
        newItem.addActionListener(e -> {
            textPane.setText("");
            comments.clear();
            commentListModel.clear();
            refreshOutline();
        });
        openItem.addActionListener(e -> openFile());
        saveItem.addActionListener(e -> saveFile());
        saveVersionItem.addActionListener(e -> {
            saveVersionSnapshot("Manual \u2014 " + new SimpleDateFormat("MMM d, h:mm a").format(new Date()));
            JOptionPane.showMessageDialog(this, "Snapshot saved.", "Version History", JOptionPane.INFORMATION_MESSAGE);
        });
        versionHistoryItem.addActionListener(e -> showVersionHistory());
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newItem);
        fileMenu.add(openItem);
        fileMenu.add(saveItem);
        fileMenu.addSeparator();
        fileMenu.add(saveVersionItem);
        fileMenu.add(versionHistoryItem);
        fileMenu.addSeparator();
        fileMenu.add(exportMenu);
        fileMenu.addSeparator();
        fileMenu.add(exitItem);

        // ---- Edit ----
        JMenu editMenu = new JMenu("Edit");
        JMenuItem undoItem = new JMenuItem("Undo", loadIcon("undo.png", 16));
        JMenuItem redoItem = new JMenuItem("Redo");
        JMenuItem findItem = new JMenuItem("Find & Replace");
        JMenuItem commentItem = new JMenuItem("Add Comment");

        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        findItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_F, InputEvent.CTRL_DOWN_MASK));

        undoItem.addActionListener(e -> { if (undoManager.canUndo()) undoManager.undo(); });
        redoItem.addActionListener(e -> { if (undoManager.canRedo()) undoManager.redo(); });
        findItem.addActionListener(e -> {
            searchPanel.setVisible(!searchPanel.isVisible());
            if (searchPanel.isVisible()) searchField.requestFocus();
        });
        commentItem.addActionListener(e -> addComment());

        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(findItem);
        editMenu.add(commentItem);

        // ---- Insert ----
        JMenu insertMenu = new JMenu("Insert");
        JMenuItem insertImageItem = new JMenuItem("Image...", loadIcon("attachFile.png", 16));
        JMenuItem insertTableItem = new JMenuItem("Table...");
        JMenuItem insertLinkItem = new JMenuItem("Link...", loadIcon("mediaLink.png", 16));
        JMenuItem insertHrItem = new JMenuItem("Horizontal Rule");
        JMenuItem insertEmojiItem = new JMenuItem("Emoji...");

        insertImageItem.addActionListener(e -> insertImage());
        insertTableItem.addActionListener(e -> insertTable());
        insertLinkItem.addActionListener(e -> insertHyperlink());
        insertHrItem.addActionListener(e -> insertHorizontalRule());
        insertEmojiItem.addActionListener(e -> insertEmojiPicker());

        insertMenu.add(insertImageItem);
        insertMenu.add(insertTableItem);
        insertMenu.add(insertLinkItem);
        insertMenu.add(insertHrItem);
        insertMenu.addSeparator();
        insertMenu.add(insertEmojiItem);

        // ---- Format ----
        JMenu formatMenu = new JMenu("Format");
        JMenu styleMenu = new JMenu("Paragraph Style");
        for (String style : new String[]{"Normal text", "Title", "Heading 1", "Heading 2", "Heading 3"}) {
            JMenuItem item = new JMenuItem(style);
            item.addActionListener(e -> applyParagraphStyle(style));
            styleMenu.add(item);
        }

        JMenu alignMenu = new JMenu("Align");
        JMenuItem alignLeft = new JMenuItem("Left");
        JMenuItem alignCenter = new JMenuItem("Center");
        JMenuItem alignRight = new JMenuItem("Right");
        JMenuItem alignJustify = new JMenuItem("Justify");
        alignLeft.addActionListener(e -> setAlignment(StyleConstants.ALIGN_LEFT));
        alignCenter.addActionListener(e -> setAlignment(StyleConstants.ALIGN_CENTER));
        alignRight.addActionListener(e -> setAlignment(StyleConstants.ALIGN_RIGHT));
        alignJustify.addActionListener(e -> setAlignment(StyleConstants.ALIGN_JUSTIFIED));
        alignMenu.add(alignLeft);
        alignMenu.add(alignCenter);
        alignMenu.add(alignRight);
        alignMenu.add(alignJustify);

        JMenu spacingMenu = new JMenu("Line Spacing");
        for (float sp : new float[]{0f, 0.3f, 0.7f, 1.2f}) {
            String label = sp == 0f ? "Single" : sp == 0.3f ? "1.15" : sp == 0.7f ? "1.5" : "Double";
            JMenuItem item = new JMenuItem(label);
            item.addActionListener(e -> setLineSpacing(sp));
            spacingMenu.add(item);
        }

        JMenuItem bulletItem = new JMenuItem("Bulleted List");
        JMenuItem numberedItem = new JMenuItem("Numbered List");
        bulletItem.addActionListener(e -> toggleListPrefix(false));
        numberedItem.addActionListener(e -> toggleListPrefix(true));

        formatMenu.add(styleMenu);
        formatMenu.add(alignMenu);
        formatMenu.add(spacingMenu);
        formatMenu.addSeparator();
        formatMenu.add(bulletItem);
        formatMenu.add(numberedItem);

        // ---- Tools ----
        JMenu toolsMenu = new JMenu("Tools");
        JMenuItem spellCheckItem = new JMenuItem("Recheck Spelling Now", loadIcon("refresh.png", 16));
        JMenuItem translateItem = new JMenuItem("Google Translate (Live Web)");
        JMenuItem compareItem = new JMenuItem("Compare Files...", loadIcon("fileCopy.png", 16));

        spellCheckItem.addActionListener(e -> {
            runLiveSpellCheckPass();
            String msg = misspelledRanges.isEmpty()
                    ? "No issues found. Nice writing!"
                    : misspelledRanges.size() + " possible spelling issue(s) underlined in red. Right-click a word for suggestions.";
            JOptionPane.showMessageDialog(this, msg, "finnLabs Spell Check", JOptionPane.INFORMATION_MESSAGE);
        });
        translateItem.addActionListener(e -> runGoogleTranslateWeb());
        compareItem.addActionListener(e -> runFileCompare());

        toolsMenu.add(spellCheckItem);
        toolsMenu.add(translateItem);
        toolsMenu.add(compareItem);

        // ---- View ----
        JMenu viewMenu = new JMenu("View");
        JCheckBoxMenuItem outlineToggle = new JCheckBoxMenuItem("Show Outline", outlineVisible);
        JCheckBoxMenuItem commentsToggle = new JCheckBoxMenuItem("Show Comments", commentsVisible);
        JMenuItem focusModeItem = new JMenuItem("Toggle Focus Mode");
        JMenuItem zoomInItem = new JMenuItem("Zoom In", loadIcon("zoomIn.png", 16));
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        JMenuItem zoomResetItem = new JMenuItem("Reset Zoom");

        outlineToggle.addActionListener(e -> {
            outlineVisible = outlineToggle.isSelected();
            outlineSidebar.setVisible(outlineVisible && !focusMode);
        });
        commentsToggle.addActionListener(e -> {
            commentsVisible = commentsToggle.isSelected();
            commentsSidebarPanel.setVisible(commentsVisible && !focusMode);
        });
        focusModeItem.addActionListener(e -> toggleFocusMode());
        zoomInItem.addActionListener(e -> setZoom(Math.min(200, zoomPercent + 10)));
        zoomOutItem.addActionListener(e -> setZoom(Math.max(50, zoomPercent - 10)));
        zoomResetItem.addActionListener(e -> setZoom(100));

        viewMenu.add(outlineToggle);
        viewMenu.add(commentsToggle);
        viewMenu.addSeparator();
        viewMenu.add(focusModeItem);
        viewMenu.addSeparator();
        viewMenu.add(zoomInItem);
        viewMenu.add(zoomOutItem);
        viewMenu.add(zoomResetItem);

        // ---- GUI Customization ----
        JMenu guiMenu = new JMenu("GUI Customization");
        JMenu themesMenu = new JMenu("Preset Themes");
        String[] presetThemes = {"Modern Dark", "Paper Light", "Cyberpunk Matrix", "Retro Amber", "Vaporwave Purple"};
        for (String theme : presetThemes) {
            JMenuItem tItem = new JMenuItem(theme);
            tItem.addActionListener(e -> applyTheme(theme));
            themesMenu.add(tItem);
        }

        JMenuItem customBg = new JMenuItem("Custom Canvas Color...", loadIcon("settings.png", 16));
        JMenuItem customFg = new JMenuItem("Custom Text Color...");
        JMenuItem customMargin = new JMenuItem("Adjust Page Margins...");

        customBg.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Canvas Color", textPane.getBackground());
            if (c != null) { textPane.setBackground(c); pagePanel.setBackground(c); pagePanel.repaint(); }
        });
        customFg.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Text Color", textPane.getForeground());
            if (c != null) textPane.setForeground(c);
        });
        customMargin.addActionListener(e -> {
            String val = JOptionPane.showInputDialog(this, "Enter Margin Size (px):", "60");
            if (val != null) {
                try {
                    int m = Integer.parseInt(val);
                    pagePanel.setBorder(new EmptyBorder(m, m, m, m));
                    pagePanel.revalidate();
                } catch (NumberFormatException ignored) {}
            }
        });

        guiMenu.add(themesMenu);
        guiMenu.addSeparator();
        guiMenu.add(customBg);
        guiMenu.add(customFg);
        guiMenu.add(customMargin);

        // ---- Help ----
        JMenu helpMenu = new JMenu("Help");
        JMenuItem docItem = new JMenuItem("User Guide");
        JMenuItem shortcutItem = new JMenuItem("Keyboard Shortcuts");
        JMenuItem aboutItem = new JMenuItem("About finnLabs : Writer");

        docItem.addActionListener(e -> showHelpDialog("User Guide",
                "finnLabs : Writer Features:\n\n" +
                "1. Live Web Translation: Uses Google's direct web engine (no credentials needed).\n" +
                "2. Rich Text Engine: Formatting, fonts, colors, superscripts, highlights.\n" +
                "3. Multi-Format Exporters: PDF, DOCX, RTF, EPUB, MD, TXT.\n" +
                "4. Theme Engine: Switch between Cyberpunk, Vaporwave, Dark, and Light modes.\n" +
                "5. Live spell check with red squiggly underlines \u2014 right-click a word for fixes.\n" +
                "6. Document Outline \u2014 apply a Heading style and it appears in the left sidebar.\n" +
                "7. Comments \u2014 select text, add a comment, see it in the right sidebar.\n" +
                "8. Autosave \u2014 your work is saved locally as you type.\n" +
                "9. Version History \u2014 snapshot and restore earlier versions.\n" +
                "10. Insert images, tables, links, horizontal rules, and emoji.\n" +
                "11. Share (simulated) \u2014 generates a shareable link for demo purposes."));

        shortcutItem.addActionListener(e -> showHelpDialog("Keyboard Shortcuts",
                "CTRL + Z : Undo\nCTRL + Y : Redo\nCTRL + F : Find & Replace\nCTRL + Click : Open link\n"));

        aboutItem.addActionListener(e -> showHelpDialog("About",
                "finnLabs : Writer v3.0 \u2014 \"Wild Edition\"\nCreated with Java Swing & Google Web Integration.\nPowered by finnLabs."));

        helpMenu.add(docItem);
        helpMenu.add(shortcutItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(insertMenu);
        menuBar.add(formatMenu);
        menuBar.add(toolsMenu);
        menuBar.add(viewMenu);
        menuBar.add(guiMenu);
        menuBar.add(helpMenu);

        return menuBar;
    }

    // =====================================================================
    // TOOLBAR
    // =====================================================================
    private JToolBar createToolBar() {
        JToolBar toolBar = new JToolBar();
        toolBar.setFloatable(false);
        toolBar.setLayout(new FlowLayout(FlowLayout.LEFT, 4, 4));
        toolBar.setBorder(new EmptyBorder(6, 10, 6, 10));

        // ---- File group (icons from GSource/icons where a match exists) ----
        JButton btnNew = makeToolButton("New", "description.png", "New document");
        btnNew.addActionListener(e -> {
            textPane.setText("");
            comments.clear();
            commentListModel.clear();
            refreshOutline();
        });
        JButton btnOpen = makeToolButton("Open", "fileopen.png", "Open a saved document");
        btnOpen.addActionListener(e -> openFile());
        JButton btnSave = makeToolButton("Save", "fileSave.png", "Save As\u2026");
        btnSave.addActionListener(e -> saveFile());
        JButton btnUndo = makeToolButton("Undo", "undo.png", "Undo (Ctrl+Z)");
        btnUndo.addActionListener(e -> { if (undoManager.canUndo()) undoManager.undo(); });
        JButton btnRedo = makeToolButton("Redo", null, "Redo (Ctrl+Y)");
        btnRedo.addActionListener(e -> { if (undoManager.canRedo()) undoManager.redo(); });

        // ---- Text style group ----
        JButton btnBold = makeActionButton(new StyledEditorKit.BoldAction(), "B",
                new Font("SansSerif", Font.BOLD, 13), "Bold (Ctrl+B)");
        JButton btnItalic = makeActionButton(new StyledEditorKit.ItalicAction(), "I",
                new Font("SansSerif", Font.ITALIC, 13), "Italic (Ctrl+I)");
        JButton btnUnderline = makeActionButton(new StyledEditorKit.UnderlineAction(), "U",
                new Font("SansSerif", Font.PLAIN, 13), "Underline (Ctrl+U)");
        JButton btnStrikethrough = makeToolButton("S", null, "Strikethrough");
        btnStrikethrough.addActionListener(e -> toggleStyle(StyleConstants.StrikeThrough));
        JButton btnSuperscript = makeToolButton("x\u00B2", null, "Superscript");
        btnSuperscript.addActionListener(e -> toggleStyle(StyleConstants.Superscript));
        JButton btnSubscript = makeToolButton("x\u2082", null, "Subscript");
        btnSubscript.addActionListener(e -> toggleStyle(StyleConstants.Subscript));

        // ---- Font group ----
        String[] fonts = GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames();
        JComboBox<String> fontBox = new JComboBox<>(fonts);
        fontBox.setSelectedItem("Serif");
        fontBox.setMaximumSize(new Dimension(160, 32));
        fontBox.setPreferredSize(new Dimension(160, 32));
        fontBox.setToolTipText("Font family");
        fontBox.addActionListener(e -> {
            String f = (String) fontBox.getSelectedItem();
            if (f != null) new StyledEditorKit.FontFamilyAction(f, f).actionPerformed(e);
        });

        Integer[] sizes = {10, 12, 14, 16, 18, 20, 24, 28, 36, 48, 72};
        JComboBox<Integer> sizeBox = new JComboBox<>(sizes);
        sizeBox.setSelectedItem(16);
        sizeBox.setMaximumSize(new Dimension(70, 32));
        sizeBox.setPreferredSize(new Dimension(70, 32));
        sizeBox.setToolTipText("Font size");
        sizeBox.addActionListener(e -> {
            Integer s = (Integer) sizeBox.getSelectedItem();
            if (s != null) new StyledEditorKit.FontSizeAction(s.toString(), s).actionPerformed(e);
        });

        JComboBox<String> styleBox = new JComboBox<>(new String[]{"Normal text", "Title", "Heading 1", "Heading 2", "Heading 3"});
        styleBox.setMaximumSize(new Dimension(150, 32));
        styleBox.setPreferredSize(new Dimension(150, 32));
        styleBox.setToolTipText("Paragraph style");
        styleBox.addActionListener(e -> applyParagraphStyle((String) styleBox.getSelectedItem()));

        // ---- Color group ----
        JButton btnTextColor = makeToolButton("Text Color", null, "Text color");
        btnTextColor.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Text Color", Color.BLACK);
            if (c != null) new StyledEditorKit.ForegroundAction("Color", c).actionPerformed(e);
        });
        JButton btnHighlight = makeToolButton("Highlight", null, "Highlight color");
        btnHighlight.addActionListener(e -> {
            Color c = JColorChooser.showDialog(this, "Highlight Color", Color.YELLOW);
            if (c != null) {
                SimpleAttributeSet sas = new SimpleAttributeSet();
                StyleConstants.setBackground(sas, c);
                textPane.setCharacterAttributes(sas, false);
            }
        });

        // ---- Paragraph group ----
        JButton btnAlignLeft = makeToolButton("\u2261L", null, "Align left");
        JButton btnAlignCenter = makeToolButton("\u2261C", null, "Align center");
        JButton btnAlignRight = makeToolButton("\u2261R", null, "Align right");
        btnAlignLeft.addActionListener(e -> setAlignment(StyleConstants.ALIGN_LEFT));
        btnAlignCenter.addActionListener(e -> setAlignment(StyleConstants.ALIGN_CENTER));
        btnAlignRight.addActionListener(e -> setAlignment(StyleConstants.ALIGN_RIGHT));

        JButton btnBullet = makeToolButton("\u2022 List", null, "Bulleted list");
        JButton btnNumbered = makeToolButton("1. List", null, "Numbered list");
        btnBullet.addActionListener(e -> toggleListPrefix(false));
        btnNumbered.addActionListener(e -> toggleListPrefix(true));

        // ---- Insert / comment group ----
        JButton btnInsertImage = makeToolButton("Image", "attachFile.png", "Insert image");
        btnInsertImage.addActionListener(e -> insertImage());
        JButton btnInsertLink = makeToolButton("Link", "mediaLink.png", "Insert link");
        btnInsertLink.addActionListener(e -> insertHyperlink());
        JButton btnComment = makeToolButton("\uD83D\uDCAC Comment", null, "Add a comment on the selected text");
        btnComment.addActionListener(e -> addComment());

        // ---- View / zoom group ----
        JButton btnSpellcheck = makeToolButton("Recheck", "refresh.png", "Recheck spelling now");
        btnSpellcheck.addActionListener(e -> runLiveSpellCheckPass());
        JButton btnZoomOut = makeToolButton("\u2212", null, "Zoom out");
        JButton btnZoomIn = makeToolButton("+", "zoomIn.png", "Zoom in");
        btnZoomOut.addActionListener(e -> setZoom(Math.max(50, zoomPercent - 10)));
        btnZoomIn.addActionListener(e -> setZoom(Math.min(200, zoomPercent + 10)));
        zoomLabel.setBorder(new EmptyBorder(0, 6, 0, 6));
        zoomLabel.setPreferredSize(new Dimension(44, 34));
        zoomLabel.setHorizontalAlignment(SwingConstants.CENTER);

        toolBar.add(makeToolbarGroup("File", btnNew, btnOpen, btnSave, btnUndo, btnRedo));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Style", btnBold, btnItalic, btnUnderline, btnStrikethrough, btnSuperscript, btnSubscript));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Font", fontBox, sizeBox, styleBox));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Color", btnTextColor, btnHighlight));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Paragraph", btnAlignLeft, btnAlignCenter, btnAlignRight, btnBullet, btnNumbered));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Insert", btnInsertImage, btnInsertLink, btnComment));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("View", btnSpellcheck, btnZoomOut, zoomLabel, btnZoomIn));

        return toolBar;
    }

    private JPanel createShareButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        panel.setBorder(new EmptyBorder(6, 10, 6, 14));
        JButton shareBtn = new JButton("Share");
        ImageIcon shareIcon = loadIcon("openInBrowser.png", 16);
        if (shareIcon != null) shareBtn.setIcon(shareIcon);
        shareBtn.setBackground(new Color(26, 115, 232));
        shareBtn.setForeground(Color.WHITE);
        shareBtn.setFocusPainted(false);
        shareBtn.setMargin(new Insets(8, 16, 8, 16));
        shareBtn.setToolTipText("Share this document (demo link)");
        shareBtn.addActionListener(e -> showShareDialog());
        panel.add(shareBtn);
        return panel;
    }

    private void toggleStyle(Object styleKey) {
        StyledDocument doc = textPane.getStyledDocument();
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        if (start == end) return;

        Element elem = doc.getCharacterElement(start);
        AttributeSet as = elem.getAttributes();
        boolean state = false;

        if (styleKey.equals(StyleConstants.StrikeThrough)) state = StyleConstants.isStrikeThrough(as);
        else if (styleKey.equals(StyleConstants.Superscript)) state = StyleConstants.isSuperscript(as);
        else if (styleKey.equals(StyleConstants.Subscript)) state = StyleConstants.isSubscript(as);

        SimpleAttributeSet sas = new SimpleAttributeSet();
        if (styleKey.equals(StyleConstants.StrikeThrough)) StyleConstants.setStrikeThrough(sas, !state);
        if (styleKey.equals(StyleConstants.Superscript)) StyleConstants.setSuperscript(sas, !state);
        if (styleKey.equals(StyleConstants.Subscript)) StyleConstants.setSubscript(sas, !state);

        doc.setCharacterAttributes(start, end - start, sas, false);
    }

    // =====================================================================
    // OUTLINE SIDEBAR
    // =====================================================================
    static class OutlineEntry {
        final int level;
        final String text;
        final int offset;
        OutlineEntry(int level, String text, int offset) { this.level = level; this.text = text; this.offset = offset; }
        @Override public String toString() {
            String indent = level > 0 ? "  ".repeat(level) : "";
            String prefix = level == 0 ? "\u2605 " : "";
            return indent + prefix + text;
        }
    }

    private JComponent createOutlineSidebar() {
        outlineListModel = new DefaultListModel<>();
        outlineList = new JList<>(outlineListModel);
        outlineList.setFont(new Font("SansSerif", Font.PLAIN, 13));
        outlineList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                OutlineEntry sel = outlineList.getSelectedValue();
                if (sel != null) {
                    int pos = Math.min(sel.offset, textPane.getDocument().getLength());
                    textPane.setCaretPosition(pos);
                    textPane.requestFocus();
                }
            }
        });

        outlineList.setBorder(new EmptyBorder(6, 8, 6, 8));
        outlineList.setFixedCellHeight(26);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(230, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Outline"),
                new EmptyBorder(2, 6, 6, 6)));
        JLabel hint = new JLabel("<html><i>Apply a Heading style<br>to build the outline.</i></html>");
        hint.setBorder(new EmptyBorder(4, 8, 8, 8));
        panel.add(hint, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(outlineList);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    private void refreshOutline() {
        outlineListModel.clear();
        StyledDocument doc = textPane.getStyledDocument();
        Element root = doc.getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element para = root.getElement(i);
            AttributeSet attrs = para.getAttributes();
            Object levelObj = attrs.getAttribute(OUTLINE_LEVEL_ATTR);
            if (levelObj instanceof Integer && (Integer) levelObj >= 0) {
                int level = (Integer) levelObj;
                try {
                    String text = doc.getText(para.getStartOffset(), para.getEndOffset() - para.getStartOffset()).trim();
                    if (!text.isEmpty()) {
                        outlineListModel.addElement(new OutlineEntry(level, text, para.getStartOffset()));
                    }
                } catch (BadLocationException ignored) {}
            }
        }
    }

    private void applyParagraphStyle(String styleName) {
        StyledDocument doc = textPane.getStyledDocument();
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        if (start == end) { int caret = textPane.getCaretPosition(); start = caret; end = caret; }

        int parStart = doc.getParagraphElement(start).getStartOffset();
        int parEnd = doc.getParagraphElement(Math.max(end - 1, start)).getEndOffset();

        SimpleAttributeSet charAttrs = new SimpleAttributeSet();
        SimpleAttributeSet paraAttrs = new SimpleAttributeSet();
        int fontSize;
        boolean bold;
        int level;
        switch (styleName) {
            case "Title": fontSize = 28; bold = true; level = 0; break;
            case "Heading 1": fontSize = 24; bold = true; level = 1; break;
            case "Heading 2": fontSize = 20; bold = true; level = 2; break;
            case "Heading 3": fontSize = 18; bold = true; level = 3; break;
            default: fontSize = 16; bold = false; level = -1; break;
        }
        StyleConstants.setFontSize(charAttrs, fontSize);
        StyleConstants.setBold(charAttrs, bold);
        paraAttrs.addAttribute(OUTLINE_LEVEL_ATTR, level);

        doc.setCharacterAttributes(parStart, parEnd - parStart, charAttrs, false);
        doc.setParagraphAttributes(parStart, parEnd - parStart, paraAttrs, false);
        refreshOutline();
        markDirty();
    }

    // =====================================================================
    // COMMENTS SIDEBAR
    // =====================================================================
    static class Comment {
        Position startPos, endPos;
        String anchorText;
        String text;
        String author = "You";
        String timestamp;
        Object highlightTag;
        @Override public String toString() {
            String snippet = anchorText.length() > 26 ? anchorText.substring(0, 26) + "\u2026" : anchorText;
            return "<html><b>\"" + escape(snippet) + "\"</b><br>" + escape(text) + "<br><small>" + timestamp + "</small></html>";
        }
        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }

    private JComponent createCommentsSidebar() {
        commentListModel = new DefaultListModel<>();
        commentList = new JList<>(commentListModel);
        commentList.setFont(new Font("SansSerif", Font.PLAIN, 12));
        commentList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                Comment c = commentList.getSelectedValue();
                if (c != null) {
                    textPane.setCaretPosition(c.startPos.getOffset());
                    textPane.moveCaretPosition(c.endPos.getOffset());
                    textPane.requestFocus();
                }
            }
        });

        commentList.setBorder(new EmptyBorder(6, 8, 6, 8));

        JButton deleteBtn = new JButton("Delete Selected Comment");
        ImageIcon deleteIcon = loadIcon("delete.png", 16);
        if (deleteIcon != null) deleteBtn.setIcon(deleteIcon);
        deleteBtn.setMargin(new Insets(8, 10, 8, 10));
        deleteBtn.addActionListener(e -> deleteSelectedComment());

        JPanel deleteRow = new JPanel(new BorderLayout());
        deleteRow.setBorder(new EmptyBorder(6, 6, 6, 6));
        deleteRow.add(deleteBtn, BorderLayout.CENTER);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(260, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Comments"),
                new EmptyBorder(2, 6, 6, 6)));
        JScrollPane scroll = new JScrollPane(commentList);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(deleteRow, BorderLayout.SOUTH);
        return panel;
    }

    private void addComment() {
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        if (start == end) {
            JOptionPane.showMessageDialog(this, "Select some text first to attach a comment.", "Add Comment", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        String commentText = JOptionPane.showInputDialog(this, "Comment:", "Add Comment", JOptionPane.PLAIN_MESSAGE);
        if (commentText == null || commentText.trim().isEmpty()) return;

        try {
            StyledDocument doc = textPane.getStyledDocument();
            Comment c = new Comment();
            c.startPos = doc.createPosition(start);
            c.endPos = doc.createPosition(end);
            c.anchorText = textPane.getSelectedText();
            c.text = commentText.trim();
            c.timestamp = new SimpleDateFormat("MMM d, h:mm a").format(new Date());
            c.highlightTag = textPane.getHighlighter().addHighlight(start, end, commentHighlightPainter);
            comments.add(c);
            commentListModel.addElement(c);
            commentsVisible = true;
            commentsSidebarPanel.setVisible(!focusMode);
            markDirty();
        } catch (BadLocationException ignored) {}
    }

    private void deleteSelectedComment() {
        Comment c = commentList.getSelectedValue();
        if (c == null) return;
        textPane.getHighlighter().removeHighlight(c.highlightTag);
        comments.remove(c);
        commentListModel.removeElement(c);
    }

    // =====================================================================
    // LIVE SPELL CHECK
    // =====================================================================
    static class SquigglyUnderlinePainter implements Highlighter.HighlightPainter {
        private final Color color;
        SquigglyUnderlinePainter(Color color) { this.color = color; }
        @Override
        public void paint(Graphics g, int p0, int p1, Shape bounds, JTextComponent c) {
            try {
                Rectangle2D r0 = c.modelToView2D(p0);
                Rectangle2D r1 = c.modelToView2D(p1);
                if (r0 == null || r1 == null) return;
                g.setColor(color);
                int y = (int) (r0.getY() + r0.getHeight()) - 2;
                int x = (int) r0.getX();
                int endX = (int) r1.getX();
                int period = 4;
                boolean up = true;
                for (int i = x; i < endX; ) {
                    int next = Math.min(i + period, endX);
                    g.drawLine(i, up ? y : y + 2, next, up ? y + 2 : y);
                    up = !up;
                    i = next;
                }
            } catch (BadLocationException ignored) {}
        }
    }

    private void runLiveSpellCheckPass() {
        Highlighter hl = textPane.getHighlighter();
        for (Object tag : misspelledTags) hl.removeHighlight(tag);
        misspelledTags.clear();
        misspelledRanges.clear();

        String text = textPane.getText();
        Matcher m = Pattern.compile("[A-Za-z']+").matcher(text);
        while (m.find()) {
            String word = m.group();
            String clean = word.replaceAll("[^a-zA-Z]", "").toLowerCase();
            if (clean.length() < 2) continue;
            if (!baseDictionary.contains(clean) && !personalDictionary.contains(clean)) {
                try {
                    Object tag = hl.addHighlight(m.start(), m.end(), squigglyPainter);
                    misspelledTags.add(tag);
                    misspelledRanges.add(new int[]{m.start(), m.end()});
                } catch (BadLocationException ignored) {}
            }
        }
    }

    private int[] findMisspelledRangeAt(int offset) {
        for (int[] range : misspelledRanges) {
            if (offset >= range[0] && offset <= range[1]) return range;
        }
        return null;
    }

    private List<String> suggestCorrections(String word) {
        List<String> candidates = new ArrayList<>(baseDictionary);
        candidates.addAll(personalDictionary);
        candidates.sort(Comparator.comparingInt(c -> levenshtein(word, c)));
        List<String> result = new ArrayList<>();
        for (String c : candidates) {
            if (levenshtein(word, c) <= 2) result.add(c);
            if (result.size() >= 4) break;
        }
        return result;
    }

    private int levenshtein(String a, String b) {
        int[][] dp = new int[a.length() + 1][b.length() + 1];
        for (int i = 0; i <= a.length(); i++) dp[i][0] = i;
        for (int j = 0; j <= b.length(); j++) dp[0][j] = j;
        for (int i = 1; i <= a.length(); i++) {
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                dp[i][j] = Math.min(Math.min(dp[i - 1][j] + 1, dp[i][j - 1] + 1), dp[i - 1][j - 1] + cost);
            }
        }
        return dp[a.length()][b.length()];
    }

    // =====================================================================
    // MOUSE HANDLING: right-click spelling menu, comment/cut/copy/paste, links
    // =====================================================================
    private void installMouseHandlers() {
        textPane.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { maybeShowPopup(e); }
            @Override public void mouseClicked(MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1 && (e.isControlDown() || e.isMetaDown())) {
                    int pos = textPane.viewToModel2D(e.getPoint());
                    openLinkAt(pos);
                }
            }
        });
        textPane.addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                int pos = textPane.viewToModel2D(e.getPoint());
                if (pos < 0 || pos >= Math.max(1, textPane.getDocument().getLength())) return;
                AttributeSet as = textPane.getStyledDocument().getCharacterElement(pos).getAttributes();
                textPane.setCursor(Cursor.getPredefinedCursor(
                        as.getAttribute(HYPERLINK_ATTR) != null ? Cursor.HAND_CURSOR : Cursor.TEXT_CURSOR));
            }
        });
    }

    private void openLinkAt(int pos) {
        if (pos < 0 || pos >= Math.max(1, textPane.getDocument().getLength())) return;
        AttributeSet as = textPane.getStyledDocument().getCharacterElement(pos).getAttributes();
        Object url = as.getAttribute(HYPERLINK_ATTR);
        if (url != null) {
            try {
                Desktop.getDesktop().browse(new URI(url.toString()));
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not open link: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void maybeShowPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        int pos = textPane.viewToModel2D(e.getPoint());
        JPopupMenu menu = new JPopupMenu();
        final int[] misspelledRange = findMisspelledRangeAt(pos);

        if (misspelledRange != null) {
            try {
                String word = textPane.getText(misspelledRange[0], misspelledRange[1] - misspelledRange[0]);
                List<String> suggestions = suggestCorrections(word.toLowerCase());
                if (suggestions.isEmpty()) {
                    JMenuItem none = new JMenuItem("No suggestions");
                    none.setEnabled(false);
                    menu.add(none);
                } else {
                    for (String s : suggestions) {
                        JMenuItem item = new JMenuItem(s);
                        item.addActionListener(ev -> {
                            try {
                                textPane.getStyledDocument().remove(misspelledRange[0], misspelledRange[1] - misspelledRange[0]);
                                textPane.getStyledDocument().insertString(misspelledRange[0], s, null);
                            } catch (BadLocationException ignored) {}
                        });
                        menu.add(item);
                    }
                }
                menu.addSeparator();
                JMenuItem addDict = new JMenuItem("Add \"" + word + "\" to dictionary");
                addDict.addActionListener(ev -> {
                    personalDictionary.add(word.toLowerCase());
                    runLiveSpellCheckPass();
                });
                menu.add(addDict);
                JMenuItem ignore = new JMenuItem("Ignore");
                ignore.addActionListener(ev -> {
                    Highlighter hl = textPane.getHighlighter();
                    int idx = misspelledRanges.indexOf(misspelledRange);
                    if (idx >= 0) {
                        hl.removeHighlight(misspelledTags.get(idx));
                        misspelledTags.remove(idx);
                        misspelledRanges.remove(idx);
                    }
                });
                menu.add(ignore);
                menu.addSeparator();
            } catch (BadLocationException ignored) {}
        }

        JMenuItem cut = new JMenuItem("Cut");
        JMenuItem copy = new JMenuItem("Copy");
        JMenuItem paste = new JMenuItem("Paste");
        JMenuItem comment = new JMenuItem("Add Comment");
        cut.addActionListener(ev -> textPane.cut());
        copy.addActionListener(ev -> textPane.copy());
        paste.addActionListener(ev -> textPane.paste());
        comment.addActionListener(ev -> addComment());
        menu.add(cut);
        menu.add(copy);
        menu.add(paste);
        menu.addSeparator();
        menu.add(comment);
        menu.show(textPane, e.getX(), e.getY());
    }

    // =====================================================================
    // ALIGNMENT / LISTS / LINE SPACING
    // =====================================================================
    private void setAlignment(int align) {
        StyledDocument doc = textPane.getStyledDocument();
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setAlignment(sas, align);
        doc.setParagraphAttributes(start, Math.max(end - start, 1), sas, false);
        markDirty();
    }

    private void setLineSpacing(float spacing) {
        StyledDocument doc = textPane.getStyledDocument();
        int start = textPane.getSelectionStart();
        int end = textPane.getSelectionEnd();
        if (start == end) {
            Element para = doc.getParagraphElement(start);
            start = para.getStartOffset();
            end = para.getEndOffset();
        }
        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setLineSpacing(sas, spacing);
        doc.setParagraphAttributes(start, Math.max(end - start, 1), sas, false);
        markDirty();
    }

    private void toggleListPrefix(boolean numbered) {
        try {
            StyledDocument doc = textPane.getStyledDocument();
            int start = textPane.getSelectionStart();
            int end = textPane.getSelectionEnd();
            Element root = doc.getDefaultRootElement();
            int startLine = root.getElementIndex(start);
            int endLine = root.getElementIndex(Math.max(end - 1, start));
            int counter = 1;
            for (int i = startLine; i <= endLine; i++) {
                Element line = root.getElement(i);
                int lineStart = line.getStartOffset();
                int lineEnd = Math.min(line.getEndOffset(), doc.getLength());
                String lineText = doc.getText(lineStart, lineEnd - lineStart);
                String stripped = lineText.replaceFirst("^(\u2022\\s|\\d+\\.\\s)", "");
                if (!lineText.equals(stripped)) {
                    doc.remove(lineStart, lineText.length() - stripped.length());
                } else {
                    String prefix = numbered ? (counter + ". ") : "\u2022 ";
                    doc.insertString(lineStart, prefix, null);
                }
                counter++;
            }
            markDirty();
        } catch (BadLocationException ignored) {}
    }

    // =====================================================================
    // INSERT: image, table, hyperlink, horizontal rule, emoji
    // =====================================================================
    private void insertImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Image files", "png", "jpg", "jpeg", "gif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                ImageIcon icon = new ImageIcon(chooser.getSelectedFile().getAbsolutePath());
                int maxWidth = PAGE_CONTENT_WIDTH - 20;
                if (icon.getIconWidth() > maxWidth) {
                    int newHeight = (int) ((double) icon.getIconHeight() * maxWidth / icon.getIconWidth());
                    Image scaled = icon.getImage().getScaledInstance(maxWidth, newHeight, Image.SCALE_SMOOTH);
                    icon = new ImageIcon(scaled);
                }
                textPane.insertIcon(icon);
                markDirty();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not insert image: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void insertTable() {
        JPanel dialogPanel = new JPanel(new GridLayout(2, 2, 5, 5));
        JTextField rowsField = new JTextField("3");
        JTextField colsField = new JTextField("3");
        dialogPanel.add(new JLabel("Rows:"));
        dialogPanel.add(rowsField);
        dialogPanel.add(new JLabel("Columns:"));
        dialogPanel.add(colsField);
        int result = JOptionPane.showConfirmDialog(this, dialogPanel, "Insert Table", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;
        try {
            int rows = Math.max(1, Math.min(20, Integer.parseInt(rowsField.getText().trim())));
            int cols = Math.max(1, Math.min(10, Integer.parseInt(colsField.getText().trim())));
            JPanel table = new JPanel(new GridLayout(rows, cols, 1, 1));
            table.setBackground(new Color(160, 160, 160));
            table.setBorder(BorderFactory.createLineBorder(new Color(160, 160, 160), 1));
            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    JTextField cell = new JTextField();
                    cell.setBorder(new EmptyBorder(4, 6, 4, 6));
                    table.add(cell);
                }
            }
            table.setPreferredSize(new Dimension(Math.min(PAGE_CONTENT_WIDTH - 20, cols * 120), rows * 30));
            textPane.insertComponent(table);
            textPane.getStyledDocument().insertString(textPane.getCaretPosition(), "\n", null);
            markDirty();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Invalid table size.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void insertHyperlink() {
        JPanel panel = new JPanel(new GridLayout(2, 2, 5, 5));
        String preset = textPane.getSelectedText() != null ? textPane.getSelectedText() : "";
        JTextField textField = new JTextField(preset);
        JTextField urlField = new JTextField("https://");
        panel.add(new JLabel("Display text:"));
        panel.add(textField);
        panel.add(new JLabel("URL:"));
        panel.add(urlField);
        int result = JOptionPane.showConfirmDialog(this, panel, "Insert Link", JOptionPane.OK_CANCEL_OPTION);
        if (result != JOptionPane.OK_OPTION) return;
        String display = textField.getText().trim();
        String url = urlField.getText().trim();
        if (display.isEmpty() || url.isEmpty()) return;

        SimpleAttributeSet sas = new SimpleAttributeSet();
        StyleConstants.setForeground(sas, new Color(26, 115, 232));
        StyleConstants.setUnderline(sas, true);
        sas.addAttribute(HYPERLINK_ATTR, url);
        try {
            StyledDocument doc = textPane.getStyledDocument();
            int caret = textPane.getCaretPosition();
            if (textPane.getSelectedText() != null && textPane.getSelectedText().equals(display)) {
                caret = textPane.getSelectionStart();
                doc.remove(caret, textPane.getSelectionEnd() - caret);
            }
            doc.insertString(caret, display, sas);
            markDirty();
        } catch (BadLocationException ignored) {}
    }

    private void insertHorizontalRule() {
        try {
            StyledDocument doc = textPane.getStyledDocument();
            int pos = textPane.getCaretPosition();
            doc.insertString(pos, "\n", null);
            textPane.setCaretPosition(pos + 1);
            JSeparator sep = new JSeparator(SwingConstants.HORIZONTAL);
            sep.setPreferredSize(new Dimension(PAGE_CONTENT_WIDTH - 20, 2));
            textPane.insertComponent(sep);
            doc.insertString(textPane.getCaretPosition(), "\n", null);
            markDirty();
        } catch (BadLocationException ignored) {}
    }

    private void insertEmojiPicker() {
        String[] emojis = {"\uD83D\uDE00", "\uD83D\uDE02", "\uD83D\uDE0D", "\uD83D\uDC4D", "\uD83C\uDF89", "\uD83D\uDD25",
                "\u2764\uFE0F", "\u2705", "\u2B50", "\uD83D\uDCA1", "\uD83D\uDCCC", "\uD83D\uDE80",
                "\uD83D\uDE22", "\uD83E\uDD14", "\uD83D\uDC40", "\u2728"};
        JDialog dialog = new JDialog(this, "Insert Emoji", true);
        JPanel grid = new JPanel(new GridLayout(4, 4, 4, 4));
        for (String em : emojis) {
            JButton b = new JButton(em);
            b.setFont(new Font("SansSerif", Font.PLAIN, 20));
            b.addActionListener(ev -> {
                try {
                    textPane.getStyledDocument().insertString(textPane.getCaretPosition(), em, null);
                } catch (BadLocationException ignored) {}
                dialog.dispose();
            });
            grid.add(b);
        }
        dialog.add(grid);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // =====================================================================
    // SHARE (simulated)
    // =====================================================================
    private void showShareDialog() {
        String fakeId = UUID.randomUUID().toString().substring(0, 8);
        String link = "https://finnlabs.app/d/" + fakeId;

        JTextField linkField = new JTextField(link);
        linkField.setEditable(false);
        JButton copyBtn = new JButton("Copy link");
        copyBtn.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(link), null);
            copyBtn.setText("Copied!");
        });
        JPanel linkRow = new JPanel(new BorderLayout(6, 6));
        linkRow.add(linkField, BorderLayout.CENTER);
        linkRow.add(copyBtn, BorderLayout.EAST);

        JComboBox<String> access = new JComboBox<>(new String[]{"Viewer", "Commenter", "Editor"});
        JPanel accessRow = new JPanel(new FlowLayout(FlowLayout.LEFT));
        accessRow.add(new JLabel("Anyone with the link:"));
        accessRow.add(access);

        JLabel note = new JLabel("<html><i>This is a local demo \u2014 sharing is simulated,<br>the link isn't actually live.</i></html>");

        JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.add(linkRow, BorderLayout.NORTH);
        panel.add(accessRow, BorderLayout.CENTER);
        panel.add(note, BorderLayout.SOUTH);
        panel.setPreferredSize(new Dimension(420, 110));

        JOptionPane.showMessageDialog(this, panel, "Share \"finnLabs Document\"", JOptionPane.PLAIN_MESSAGE);
    }

    // =====================================================================
    // AUTOSAVE
    // =====================================================================
    private void performAutosave() {
        dirty = false;
        saveStatusLabel.setText("Saving\u2026  ");
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                try {
                    File dir = new File(System.getProperty("user.home"), ".finnlabs");
                    if (!dir.exists()) dir.mkdirs();
                    File f = new File(dir, "autosave.dat");
                    try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(f))) {
                        oos.writeObject(textPane.getDocument());
                    }
                } catch (Exception ignored) {}
                return null;
            }
            @Override protected void done() {
                saveStatusLabel.setText("All changes saved  ");
            }
        };
        worker.execute();
    }

    // =====================================================================
    // VERSION HISTORY
    // =====================================================================
    static class VersionSnapshot {
        final String label;
        final byte[] data;
        VersionSnapshot(String label, byte[] data) { this.label = label; this.data = data; }
        @Override public String toString() { return label; }
    }

    private void saveVersionSnapshot(String label) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            try (ObjectOutputStream oos = new ObjectOutputStream(bos)) {
                oos.writeObject(textPane.getStyledDocument());
            }
            versionHistory.add(0, new VersionSnapshot(label, bos.toByteArray()));
            if (versionHistory.size() > 25) versionHistory.remove(versionHistory.size() - 1);
        } catch (Exception ignored) {}
    }

    private void showVersionHistory() {
        if (versionHistory.isEmpty()) {
            JOptionPane.showMessageDialog(this, "No saved versions yet. Use File > Save Version Snapshot to create one.",
                    "Version History", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JList<VersionSnapshot> list = new JList<>(versionHistory.toArray(new VersionSnapshot[0]));
        list.setSelectedIndex(0);
        int result = JOptionPane.showConfirmDialog(this, new JScrollPane(list),
                "Version History \u2014 select a version to restore", JOptionPane.OK_CANCEL_OPTION);
        if (result == JOptionPane.OK_OPTION) {
            VersionSnapshot sel = list.getSelectedValue();
            if (sel != null) restoreVersion(sel);
        }
    }

    private void restoreVersion(VersionSnapshot vs) {
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(vs.data))) {
            StyledDocument doc = (StyledDocument) ois.readObject();
            textPane.setDocument(doc);
            textPane.getDocument().addUndoableEditListener(undoManager);
            attachDocumentListener();
            refreshOutline();
            runLiveSpellCheckPass();
            updateStatus();
            JOptionPane.showMessageDialog(this, "Restored: " + vs.label, "Version Restored", JOptionPane.INFORMATION_MESSAGE);
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Restore failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =====================================================================
    // ZOOM
    // =====================================================================
    private void setZoom(int newPercent) {
        double factor = (double) newPercent / zoomPercent;
        scaleAllFonts(factor);
        zoomPercent = newPercent;
        zoomLabel.setText(zoomPercent + "%");
    }

    private void scaleAllFonts(double factor) {
        StyledDocument doc = textPane.getStyledDocument();
        Element root = doc.getDefaultRootElement();
        for (int i = 0; i < root.getElementCount(); i++) {
            Element para = root.getElement(i);
            for (int j = 0; j < para.getElementCount(); j++) {
                Element run = para.getElement(j);
                AttributeSet as = run.getAttributes();
                int size = StyleConstants.getFontSize(as);
                int newSize = Math.max(6, (int) Math.round(size * factor));
                SimpleAttributeSet sas = new SimpleAttributeSet();
                StyleConstants.setFontSize(sas, newSize);
                doc.setCharacterAttributes(run.getStartOffset(), run.getEndOffset() - run.getStartOffset(), sas, false);
            }
        }
    }

    // =====================================================================
    // FOCUS MODE
    // =====================================================================
    private void toggleFocusMode() {
        focusMode = !focusMode;
        FocusState.writerFocus = focusMode;
        topContainer.setVisible(!focusMode);
        statusBar.setVisible(!focusMode);
        outlineSidebar.setVisible(!focusMode && outlineVisible);
        commentsSidebarPanel.setVisible(!focusMode && commentsVisible);
    }

    // =====================================================================
    // GOOGLE TRANSLATE (LIVE WEB)
    // =====================================================================
    private void runGoogleTranslateWeb() {
        Map<String, String> langMap = new LinkedHashMap<>();
        langMap.put("Spanish", "es");
        langMap.put("French", "fr");
        langMap.put("German", "de");
        langMap.put("Hindi", "hi");
        langMap.put("Italian", "it");
        langMap.put("Japanese", "ja");
        langMap.put("Portuguese", "pt");
        langMap.put("Russian", "ru");

        String selectedLang = (String) JOptionPane.showInputDialog(this, "Select Target Language:",
                "finnLabs Translate (Method 1)", JOptionPane.QUESTION_MESSAGE, null,
                langMap.keySet().toArray(), "Spanish");

        if (selectedLang != null) {
            String textToTranslate = textPane.getSelectedText();
            if (textToTranslate == null || textToTranslate.trim().isEmpty()) {
                textToTranslate = textPane.getText();
            }
            if (textToTranslate.trim().isEmpty()) {
                JOptionPane.showMessageDialog(this, "Document is empty!", "Translate Warning", JOptionPane.WARNING_MESSAGE);
                return;
            }
            String code = langMap.get(selectedLang);
            String translated = translateGoogleFree(textToTranslate, code);

            JTextArea area = new JTextArea(translated, 15, 45);
            area.setLineWrap(true);
            area.setWrapStyleWord(true);
            JOptionPane.showMessageDialog(this, new JScrollPane(area),
                    "Live Translation (" + selectedLang + ")", JOptionPane.PLAIN_MESSAGE);
        }
    }

    private String translateGoogleFree(String text, String targetLangCode) {
        try {
            String encodedText = URLEncoder.encode(text, StandardCharsets.UTF_8);
            String url = "https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl="
                    + targetLangCode + "&dt=t&q=" + encodedText;

            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("User-Agent", "Mozilla/5.0")
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            String body = response.body();

            StringBuilder result = new StringBuilder();
            int idx = 0;
            while ((idx = body.indexOf("[\"", idx)) != -1) {
                int endQuote = body.indexOf("\",\"", idx + 2);
                if (endQuote != -1) {
                    result.append(body, idx + 2, endQuote);
                    idx = endQuote;
                } else {
                    break;
                }
            }
            return result.length() > 0 ? result.toString().replace("\\n", "\n") : "Translation output unreadable.";
        } catch (Exception e) {
            return "Translation Error: " + e.getMessage();
        }
    }

    // =====================================================================
    // THEMES
    // =====================================================================
    private void applyTheme(String themeName) {
        switch (themeName) {
            case "Cyberpunk Matrix":
                textPane.setBackground(new Color(13, 13, 13));
                textPane.setForeground(new Color(0, 255, 102));
                textPane.setCaretColor(new Color(0, 255, 102));
                textPane.setFont(new Font("Monospaced", Font.PLAIN, 16));
                break;
            case "Vaporwave Purple":
                textPane.setBackground(new Color(40, 42, 54));
                textPane.setForeground(new Color(248, 248, 242));
                textPane.setCaretColor(new Color(255, 121, 198));
                textPane.setFont(new Font("SansSerif", Font.PLAIN, 16));
                break;
            case "Retro Amber":
                textPane.setBackground(new Color(28, 16, 11));
                textPane.setForeground(new Color(255, 176, 0));
                textPane.setCaretColor(new Color(255, 176, 0));
                textPane.setFont(new Font("Monospaced", Font.PLAIN, 16));
                break;
            case "Paper Light":
                textPane.setBackground(new Color(250, 250, 250));
                textPane.setForeground(new Color(34, 34, 34));
                textPane.setCaretColor(Color.BLACK);
                textPane.setFont(new Font("Serif", Font.PLAIN, 16));
                break;
            case "Modern Dark":
            default:
                textPane.setBackground(new Color(30, 30, 30));
                textPane.setForeground(new Color(212, 212, 212));
                textPane.setCaretColor(Color.WHITE);
                textPane.setFont(new Font("SansSerif", Font.PLAIN, 16));
                break;
        }
        if (pagePanel != null) {
            pagePanel.setBackground(textPane.getBackground());
            pagePanel.repaint();
        }
    }

    // =====================================================================
    // FILE COMPARE / EXPORT / SAVE / OPEN
    // =====================================================================
    private void runFileCompare() {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File secondFile = fileChooser.getSelectedFile();
            try {
                String content1 = textPane.getText();
                String content2 = new String(java.nio.file.Files.readAllBytes(secondFile.toPath()), StandardCharsets.UTF_8);

                JTextArea area1 = new JTextArea(content1, 20, 30);
                JTextArea area2 = new JTextArea(content2, 20, 30);

                JPanel diffPanel = new JPanel(new GridLayout(1, 2, 10, 10));
                diffPanel.add(new JScrollPane(area1));
                diffPanel.add(new JScrollPane(area2));

                JDialog dialog = new JDialog(this, "File Comparison - Active vs " + secondFile.getName(), true);
                dialog.add(diffPanel);
                dialog.setSize(850, 500);
                dialog.setLocationRelativeTo(this);
                dialog.setVisible(true);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Error comparing files: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportFile(String format) {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String path = file.getAbsolutePath();
            if (!path.endsWith("." + format)) path += "." + format;
            File targetFile = new File(path);

            try {
                if (format.equals("txt")) {
                    try (FileWriter fw = new FileWriter(targetFile)) { fw.write(textPane.getText()); }
                } else if (format.equals("rtf")) {
                    RTFEditorKit rtfKit = new RTFEditorKit();
                    try (FileOutputStream out = new FileOutputStream(targetFile)) {
                        rtfKit.write(out, textPane.getDocument(), 0, textPane.getDocument().getLength());
                    }
                } else if (format.equals("md")) {
                    try (FileWriter fw = new FileWriter(targetFile)) {
                        fw.write("# finnLabs : Document\n\n" + textPane.getText());
                    }
                } else {
                    try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(targetFile))) {
                        zos.putNextEntry(new ZipEntry("content.xml"));
                        String docData = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<document format=\"" + format + "\">\n<body>\n" +
                                textPane.getText() + "\n</body>\n</document>";
                        zos.write(docData.getBytes(StandardCharsets.UTF_8));
                        zos.closeEntry();
                    }
                }
                JOptionPane.showMessageDialog(this, "Exported successfully to " + format.toUpperCase() + "!", "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void showHelpDialog(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    private void updateStatus() {
        String text = textPane.getText().trim();
        int charCount = text.length();
        int wordCount = text.isEmpty() ? 0 : text.split("\\s+").length;
        int readingMinutes = Math.max(1, (int) Math.ceil(wordCount / 200.0));
        wordCountLabel.setText(String.format(" Words: %d  |  Characters: %d  |  ~%d min read ", wordCount, charCount, readingMinutes));
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0, 0, 0, 40)),
                new EmptyBorder(8, 16, 8, 16)));
        wordCountLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        wordCountLabel.setFont(wordCountLabel.getFont().deriveFont(12f));
        saveStatusLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        saveStatusLabel.setFont(saveStatusLabel.getFont().deriveFont(12f));
        bar.add(wordCountLabel, BorderLayout.WEST);
        bar.add(saveStatusLabel, BorderLayout.EAST);
        return bar;
    }

    private void saveFile() {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(file))) {
                oos.writeObject(textPane.getDocument());
                dirty = false;
                saveStatusLabel.setText("All changes saved  ");
                JOptionPane.showMessageDialog(this, "File saved successfully!", "finnLabs : Writer", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error saving file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void openFile() {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadDocument(fileChooser.getSelectedFile());
        }
    }

    /** Loads a saved writer document (a serialized StyledDocument) into this
     *  window. Split out of openFile() so it can be reused both by the file
     *  chooser and by callers that already have a File in hand (e.g. the File
     *  Explorer's "open with" routing). */
    private void loadDocument(File file) {
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            StyledDocument doc = (StyledDocument) ois.readObject();
            textPane.setDocument(doc);
            textPane.getDocument().addUndoableEditListener(undoManager);
            attachDocumentListener();
            refreshOutline();
            runLiveSpellCheckPass();
            updateStatus();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error opening file: " + ex.getMessage(), "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    public void launch() {
        SwingUtilities.invokeLater(() -> {
            try {
                whitesur.WhiteSur.ensureInstalled();
            } catch (Exception ignored) {}
            // NOTE: previously this constructed a *second* writer() and showed
            // that one instead of `this` - the instance launch() was called on
            // was built and then silently discarded. Fixed to show `this`.
            setVisible(true);
        });
    }

    /** Same as {@link #launch()}, but also loads the given saved document
     *  once the window is up. Used by properties.AppRegistry / FileManager's
     *  "open with" routing. */
    public void launch(File toOpen) {
        SwingUtilities.invokeLater(() -> {
            try {
                whitesur.WhiteSur.ensureInstalled();
            } catch (Exception ignored) {}
            setVisible(true);
            if (toOpen != null && toOpen.isFile()) {
                loadDocument(toOpen);
            }
        });
    }

    @Override public void dispose() {
        FocusState.writerFocus = false;   // closing Writer ends its Focus Mode
        super.dispose();
    }
}
