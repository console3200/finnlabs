package finnLabs;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Glamour \u2014 finnLabs Presentation Editor.
 * A slide-deck sibling to finnLabs.writer, built with the same spacious,
 * grouped-toolbar visual language and the same GSource/icons integration.
 */
public class Glamour extends JFrame {

    // =====================================================================
    // CONSTANTS
    // =====================================================================
    private static final int SLIDE_W = 960;
    private static final int SLIDE_H = 540;

    // --- Icon set (GSource/icons), same pattern as finnLabs.writer ---------
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

    // =====================================================================
    // DOCUMENT MODEL
    // =====================================================================
    abstract static class SlideElement implements Serializable {
        Rectangle bounds = new Rectangle(80, 80, 300, 100);
        abstract void paint(Graphics2D g2, boolean selected);
    }

    static class TextElement extends SlideElement {
        String text = "Click to edit text";
        String fontFamily = "SansSerif";
        int fontSize = 24;
        boolean bold = false, italic = false, underline = false;
        int align = 0; // 0=left, 1=center, 2=right
        int colorRGB = Color.BLACK.getRGB();

        @Override void paint(Graphics2D g2, boolean selected) {
            int style = (bold ? Font.BOLD : 0) | (italic ? Font.ITALIC : 0);
            Font f = new Font(fontFamily, style, fontSize);
            g2.setFont(f);
            g2.setColor(new Color(colorRGB, true));
            FontMetrics fm = g2.getFontMetrics(f);
            String[] lines = text.split("\n", -1);
            int lineHeight = fm.getHeight();
            int y = bounds.y + fm.getAscent();
            for (String line : lines) {
                int lw = fm.stringWidth(line);
                int x = bounds.x;
                if (align == 1) x = bounds.x + Math.max(0, (bounds.width - lw) / 2);
                else if (align == 2) x = bounds.x + Math.max(0, bounds.width - lw);
                g2.drawString(line, x, y);
                if (underline) g2.drawLine(x, y + 3, x + lw, y + 3);
                y += lineHeight;
            }
        }
    }

    static class ShapeElement extends SlideElement {
        String shapeType = "rectangle"; // rectangle | oval | line
        int fillRGB = new Color(66, 133, 244).getRGB();
        int borderRGB = new Color(20, 60, 140).getRGB();
        boolean hasFill = true;

        @Override void paint(Graphics2D g2, boolean selected) {
            g2.setStroke(new BasicStroke(2f));
            switch (shapeType) {
                case "oval":
                    if (hasFill) {
                        g2.setColor(new Color(fillRGB, true));
                        g2.fillOval(bounds.x, bounds.y, bounds.width, bounds.height);
                    }
                    g2.setColor(new Color(borderRGB, true));
                    g2.drawOval(bounds.x, bounds.y, bounds.width, bounds.height);
                    break;
                case "line":
                    g2.setColor(new Color(borderRGB, true));
                    g2.drawLine(bounds.x, bounds.y, bounds.x + bounds.width, bounds.y + bounds.height);
                    break;
                default:
                    if (hasFill) {
                        g2.setColor(new Color(fillRGB, true));
                        g2.fillRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
                    }
                    g2.setColor(new Color(borderRGB, true));
                    g2.drawRoundRect(bounds.x, bounds.y, bounds.width, bounds.height, 6, 6);
            }
        }
    }

    static class ImageElement extends SlideElement {
        byte[] imageBytes;
        transient Image image;

        @Override void paint(Graphics2D g2, boolean selected) {
            if (image != null) {
                g2.drawImage(image, bounds.x, bounds.y, bounds.width, bounds.height, null);
            } else {
                g2.setColor(new Color(200, 200, 200));
                g2.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                g2.setColor(Color.DARK_GRAY);
                g2.drawString("Image", bounds.x + 8, bounds.y + 18);
            }
        }

        void rehydrate() {
            if (imageBytes != null) {
                try { image = ImageIO.read(new ByteArrayInputStream(imageBytes)); } catch (Exception ignored) {}
            }
        }
    }

    static class Slide implements Serializable {
        int backgroundRGB = Color.WHITE.getRGB();
        List<SlideElement> elements = new ArrayList<>();
        String notes = "";
    }

    static class VersionSnapshot implements Serializable {
        final String label;
        final byte[] data;
        VersionSnapshot(String label, byte[] data) { this.label = label; this.data = data; }
        @Override public String toString() { return label; }
    }

    // =====================================================================
    // FIELDS
    // =====================================================================
    private List<Slide> slides = new ArrayList<>();
    private int currentSlide = 0;
    private SlideElement selectedElement;

    private SlideCanvas canvas;
    private JList<Slide> slideList;
    private DefaultListModel<Slide> slideListModel;
    private JPanel propertiesPanel;

    private JLabel slideStatusLabel;
    private JLabel saveStatusLabel;
    private JLabel zoomLabel;
    private JFileChooser fileChooser;

    private volatile boolean dirty = false;
    private int zoomPercent = 100;
    private boolean autoFitZoom = true;
    private JScrollPane canvasScrollPane;

    private final Deque<byte[]> undoStack = new ArrayDeque<>();
    private final Deque<byte[]> redoStack = new ArrayDeque<>();
    private final List<VersionSnapshot> versionHistory = new ArrayList<>();

    private javax.swing.Timer autosaveTimer;
    private javax.swing.Timer autoVersionTimer;

    private JTextArea inlineEditor;
    private TextElement editingElement;

    // =====================================================================
    // CONSTRUCTOR
    // =====================================================================
    public Glamour() {
        super("finnLabs : Glamour");
        configureSpaciousUI();
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1440, 920);
        setLocationRelativeTo(null);

        Slide first = new Slide();
        applyLayoutTo(first, "Title Slide");
        slides.add(first);

        slideStatusLabel = new JLabel();
        saveStatusLabel = new JLabel("All changes saved  ");
        zoomLabel = new JLabel("100%");

        setLayout(new BorderLayout());
        setJMenuBar(createMenuBar());

        JPanel toolbarRow = new JPanel(new BorderLayout());
        toolbarRow.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0, 0, 0, 40)));
        toolbarRow.add(createToolBar(), BorderLayout.CENTER);
        toolbarRow.add(createPresentButtonPanel(), BorderLayout.EAST);
        add(toolbarRow, BorderLayout.NORTH);

        JPanel centerContainer = new JPanel(new BorderLayout(8, 0));
        JComponent slidePanel = createSlidePanel();
        JComponent propsPanel = createPropertiesPanel();
        centerContainer.add(slidePanel, BorderLayout.WEST);
        centerContainer.add(buildCanvasArea(), BorderLayout.CENTER);
        centerContainer.add(propsPanel, BorderLayout.EAST);
        add(centerContainer, BorderLayout.CENTER);

        add(createStatusBar(), BorderLayout.SOUTH);

        refreshSlideList();
        slideList.setSelectedIndex(0);
        updateStatus();

        autosaveTimer = new javax.swing.Timer(2500, e -> { if (dirty) performAutosave(); });
        autosaveTimer.start();
        autoVersionTimer = new javax.swing.Timer(180_000, e ->
                saveVersionSnapshot("Auto \u2014 " + new SimpleDateFormat("h:mm a").format(new Date())));
        autoVersionTimer.start();
    }

    // =====================================================================
    // SPACING / LAYOUT HELPERS (same approach as finnLabs.writer)
    // =====================================================================
    private void configureSpaciousUI() {
        // Spacing and menu/tooltip styling now come from the WhiteSur look and feel.
    }

    private JButton makeToolButton(String glyphOrText, String iconFile, String tooltip) {
        ImageIcon icon = iconFile != null ? loadIcon(iconFile, TOOLBAR_ICON_SIZE) : null;
        JButton btn = icon != null ? new JButton(icon) : new JButton(glyphOrText);
        if (icon != null) btn.setToolTipText(tooltip + " (" + glyphOrText + ")");
        else { btn.setFont(new Font("SansSerif", Font.PLAIN, 13)); btn.setToolTipText(tooltip); }
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(6, 10, 6, 10));
        btn.setPreferredSize(new Dimension(icon != null ? 38 : Math.max(38, btn.getPreferredSize().width + 14), 34));
        return btn;
    }

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
    // MENU BAR
    // =====================================================================
    private JMenuBar createMenuBar() {
        JMenuBar menuBar = new JMenuBar();

        // ---- File ----
        JMenu fileMenu = new JMenu("File");
        JMenuItem newPresItem = new JMenuItem("New Presentation", loadIcon("description.png", 16));
        JMenuItem openItem = new JMenuItem("Open...", loadIcon("fileopen.png", 16));
        JMenuItem saveItem = new JMenuItem("Save As...", loadIcon("fileSave.png", 16));
        JMenuItem saveVersionItem = new JMenuItem("Save Version Snapshot", loadIcon("backup.png", 16));
        JMenuItem versionHistoryItem = new JMenuItem("Version History...", loadIcon("restorePage.png", 16));

        JMenu exportMenu = new JMenu("Export As");
        JMenuItem exportPdf = new JMenuItem("PDF (.pdf)");
        JMenuItem exportPptx = new JMenuItem("PPTX (.pptx)");
        JMenuItem exportPngCurrent = new JMenuItem("PNG \u2014 Current Slide");
        JMenuItem exportPngAllItem = new JMenuItem("PNG \u2014 All Slides...", loadIcon("filePng.png", 16));
        exportPdf.addActionListener(e -> exportSimulated("pdf"));
        exportPptx.addActionListener(e -> exportSimulated("pptx"));
        exportPngCurrent.addActionListener(e -> exportPngCurrentSlide());
        exportPngAllItem.addActionListener(e -> exportPngAll());
        exportMenu.add(exportPdf);
        exportMenu.add(exportPptx);
        exportMenu.addSeparator();
        exportMenu.add(exportPngCurrent);
        exportMenu.add(exportPngAllItem);

        JMenuItem exitItem = new JMenuItem("Exit", loadIcon("close.png", 16));

        newPresItem.addActionListener(e -> {
            pushUndo();
            slides.clear();
            Slide s = new Slide();
            applyLayoutTo(s, "Title Slide");
            slides.add(s);
            currentSlide = 0;
            selectedElement = null;
            refreshSlideList();
            slideList.setSelectedIndex(0);
            canvas.repaint();
            updatePropertiesPanel();
            updateStatus();
        });
        openItem.addActionListener(e -> openFile());
        saveItem.addActionListener(e -> saveFile());
        saveVersionItem.addActionListener(e -> {
            saveVersionSnapshot("Manual \u2014 " + new SimpleDateFormat("MMM d, h:mm a").format(new Date()));
            JOptionPane.showMessageDialog(this, "Snapshot saved.", "Version History", JOptionPane.INFORMATION_MESSAGE);
        });
        versionHistoryItem.addActionListener(e -> showVersionHistory());
        exitItem.addActionListener(e -> System.exit(0));

        fileMenu.add(newPresItem);
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
        undoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK));
        redoItem.setAccelerator(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK));
        undoItem.addActionListener(e -> undo());
        redoItem.addActionListener(e -> redo());
        JMenuItem deleteElItem = new JMenuItem("Delete Selected Element", loadIcon("delete.png", 16));
        deleteElItem.addActionListener(e -> deleteSelectedElement());
        editMenu.add(undoItem);
        editMenu.add(redoItem);
        editMenu.addSeparator();
        editMenu.add(deleteElItem);

        // ---- Insert ----
        JMenu insertMenu = new JMenu("Insert");
        JMenuItem insTextItem = new JMenuItem("Text Box", loadIcon("description.png", 16));
        JMenuItem insRectItem = new JMenuItem("Rectangle");
        JMenuItem insOvalItem = new JMenuItem("Oval");
        JMenuItem insLineItem = new JMenuItem("Line");
        JMenuItem insImageItem = new JMenuItem("Image...", loadIcon("attachFile.png", 16));
        insTextItem.addActionListener(e -> addTextBox());
        insRectItem.addActionListener(e -> addShape("rectangle"));
        insOvalItem.addActionListener(e -> addShape("oval"));
        insLineItem.addActionListener(e -> addShape("line"));
        insImageItem.addActionListener(e -> insertImage());
        insertMenu.add(insTextItem);
        insertMenu.add(insRectItem);
        insertMenu.add(insOvalItem);
        insertMenu.add(insLineItem);
        insertMenu.addSeparator();
        insertMenu.add(insImageItem);

        // ---- Slide ----
        JMenu slideMenu = new JMenu("Slide");
        JMenuItem newSlideItem = new JMenuItem("New Slide");
        JMenuItem dupSlideItem = new JMenuItem("Duplicate Slide", loadIcon("fileCopy.png", 16));
        JMenuItem delSlideItem = new JMenuItem("Delete Slide", loadIcon("delete.png", 16));
        newSlideItem.addActionListener(e -> newSlide("Title + Content"));
        dupSlideItem.addActionListener(e -> duplicateSlide());
        delSlideItem.addActionListener(e -> deleteSlide());
        JMenu layoutMenu = new JMenu("Layout");
        for (String layout : new String[]{"Title Slide", "Title + Content", "Blank"}) {
            JMenuItem item = new JMenuItem(layout);
            item.addActionListener(e -> applyLayout(layout));
            layoutMenu.add(item);
        }
        JMenuItem bgItem = new JMenuItem("Slide Background Color...");
        bgItem.addActionListener(e -> setSlideBackground());
        slideMenu.add(newSlideItem);
        slideMenu.add(dupSlideItem);
        slideMenu.add(delSlideItem);
        slideMenu.addSeparator();
        slideMenu.add(layoutMenu);
        slideMenu.add(bgItem);

        // ---- Format ----
        JMenu formatMenu = new JMenu("Format");
        JMenuItem boldItem = new JMenuItem("Bold");
        JMenuItem italicItem = new JMenuItem("Italic");
        JMenuItem underlineItem = new JMenuItem("Underline");
        boldItem.addActionListener(e -> toggleBold());
        italicItem.addActionListener(e -> toggleItalic());
        underlineItem.addActionListener(e -> toggleUnderline());
        JMenuItem alLeftItem = new JMenuItem("Align Left");
        JMenuItem alCenterItem = new JMenuItem("Align Center");
        JMenuItem alRightItem = new JMenuItem("Align Right");
        alLeftItem.addActionListener(e -> setTextAlign(0));
        alCenterItem.addActionListener(e -> setTextAlign(1));
        alRightItem.addActionListener(e -> setTextAlign(2));
        JMenuItem frontItem = new JMenuItem("Bring to Front");
        JMenuItem backItem = new JMenuItem("Send to Back");
        frontItem.addActionListener(e -> bringToFront());
        backItem.addActionListener(e -> sendToBack());
        JMenuItem textColorItem = new JMenuItem("Text Color...");
        JMenuItem fillColorItem = new JMenuItem("Fill Color...");
        textColorItem.addActionListener(e -> applyTextColor());
        fillColorItem.addActionListener(e -> applyFillColor());
        formatMenu.add(boldItem);
        formatMenu.add(italicItem);
        formatMenu.add(underlineItem);
        formatMenu.addSeparator();
        formatMenu.add(alLeftItem);
        formatMenu.add(alCenterItem);
        formatMenu.add(alRightItem);
        formatMenu.addSeparator();
        formatMenu.add(textColorItem);
        formatMenu.add(fillColorItem);
        formatMenu.addSeparator();
        formatMenu.add(frontItem);
        formatMenu.add(backItem);

        // ---- View ----
        JMenu viewMenu = new JMenu("View");
        JMenuItem zoomInItem = new JMenuItem("Zoom In", loadIcon("zoomIn.png", 16));
        JMenuItem zoomOutItem = new JMenuItem("Zoom Out");
        JMenuItem zoomResetItem = new JMenuItem("Reset Zoom");
        JMenuItem zoomFitItem = new JMenuItem("Fit to Window");
        zoomInItem.addActionListener(e -> setZoom(Math.min(200, zoomPercent + 10)));
        zoomOutItem.addActionListener(e -> setZoom(Math.max(50, zoomPercent - 10)));
        zoomResetItem.addActionListener(e -> setZoom(100));
        zoomFitItem.addActionListener(e -> { autoFitZoom = true; fitZoomToWindow(); });
        viewMenu.add(zoomInItem);
        viewMenu.add(zoomOutItem);
        viewMenu.add(zoomResetItem);
        viewMenu.add(zoomFitItem);

        // ---- Presentation ----
        JMenu presentMenu = new JMenu("Presentation");
        JMenuItem startBeginItem = new JMenuItem("Start From Beginning", loadIcon("start.png", 16));
        JMenuItem startCurrentItem = new JMenuItem("Start From Current Slide");
        startBeginItem.addActionListener(e -> startPresentation(true));
        startCurrentItem.addActionListener(e -> startPresentation(false));
        presentMenu.add(startBeginItem);
        presentMenu.add(startCurrentItem);

        // ---- Help ----
        JMenu helpMenu = new JMenu("Help");
        JMenuItem docItem = new JMenuItem("User Guide");
        JMenuItem shortcutItem = new JMenuItem("Keyboard Shortcuts");
        JMenuItem aboutItem = new JMenuItem("About Glamour");
        docItem.addActionListener(e -> showHelpDialog("User Guide",
                "Glamour \u2014 finnLabs Presentation Editor:\n\n" +
                "1. Build slides from the left panel \u2014 New, Duplicate, Delete.\n" +
                "2. Insert text boxes, shapes, and images from the toolbar or Insert menu.\n" +
                "3. Drag elements to move them, drag a corner handle to resize.\n" +
                "4. Double-click a text box to edit its text in place.\n" +
                "5. Use the Design panel on the right for colors, layouts, and arrangement.\n" +
                "6. Autosave keeps a local backup as you work.\n" +
                "7. Version History \u2014 snapshot and restore earlier versions.\n" +
                "8. Present \u2014 runs a fullscreen slideshow (arrow keys to navigate, Esc to exit).\n" +
                "9. Export to PDF, PPTX, or PNG images."));
        shortcutItem.addActionListener(e -> showHelpDialog("Keyboard Shortcuts",
                "CTRL + Z : Undo\nCTRL + Y : Redo\nDouble-click text : Edit in place\n" +
                "\u2190 / \u2192 : Navigate slides while presenting\nEsc : Exit presentation\n"));
        aboutItem.addActionListener(e -> showHelpDialog("About",
                "Glamour v1.0 \u2014 finnLabs Presentation Editor\nCreated with Java Swing.\nPowered by finnLabs."));
        helpMenu.add(docItem);
        helpMenu.add(shortcutItem);
        helpMenu.addSeparator();
        helpMenu.add(aboutItem);

        menuBar.add(fileMenu);
        menuBar.add(editMenu);
        menuBar.add(insertMenu);
        menuBar.add(slideMenu);
        menuBar.add(formatMenu);
        menuBar.add(viewMenu);
        menuBar.add(presentMenu);
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

        JButton btnNewSlide = makeToolButton("New Slide", null, "Add a new slide after this one");
        btnNewSlide.addActionListener(e -> newSlide("Title + Content"));
        JButton btnDup = makeToolButton("Duplicate", "fileCopy.png", "Duplicate current slide");
        btnDup.addActionListener(e -> duplicateSlide());
        JButton btnDelSlide = makeToolButton("Delete Slide", "delete.png", "Delete current slide");
        btnDelSlide.addActionListener(e -> deleteSlide());

        JButton btnText = makeToolButton("Text Box", "description.png", "Insert a text box");
        btnText.addActionListener(e -> addTextBox());
        JButton btnRect = makeToolButton("Rectangle", null, "Insert a rectangle");
        btnRect.addActionListener(e -> addShape("rectangle"));
        JButton btnOval = makeToolButton("Oval", null, "Insert an oval");
        btnOval.addActionListener(e -> addShape("oval"));
        JButton btnLine = makeToolButton("Line", null, "Insert a line");
        btnLine.addActionListener(e -> addShape("line"));
        JButton btnImage = makeToolButton("Image", "attachFile.png", "Insert an image");
        btnImage.addActionListener(e -> insertImage());

        JButton btnBold = makeToolButton("B", null, "Bold");
        btnBold.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnBold.addActionListener(e -> toggleBold());
        JButton btnItalic = makeToolButton("I", null, "Italic");
        btnItalic.setFont(new Font("SansSerif", Font.ITALIC, 13));
        btnItalic.addActionListener(e -> toggleItalic());
        JButton btnUnderline = makeToolButton("U", null, "Underline");
        btnUnderline.addActionListener(e -> toggleUnderline());
        JButton btnTextColor = makeToolButton("Text Color", null, "Text color");
        btnTextColor.addActionListener(e -> applyTextColor());

        JButton btnAlignL = makeToolButton("\u2261L", null, "Align text left");
        JButton btnAlignC = makeToolButton("\u2261C", null, "Align text center");
        JButton btnAlignR = makeToolButton("\u2261R", null, "Align text right");
        btnAlignL.addActionListener(e -> setTextAlign(0));
        btnAlignC.addActionListener(e -> setTextAlign(1));
        btnAlignR.addActionListener(e -> setTextAlign(2));

        JButton btnFront = makeToolButton("Front", null, "Bring selection to front");
        JButton btnBack = makeToolButton("Back", null, "Send selection to back");
        btnFront.addActionListener(e -> bringToFront());
        btnBack.addActionListener(e -> sendToBack());

        JButton btnZoomOut = makeToolButton("\u2212", null, "Zoom out");
        JButton btnZoomIn = makeToolButton("+", "zoomIn.png", "Zoom in");
        btnZoomOut.addActionListener(e -> setZoom(Math.max(50, zoomPercent - 10)));
        btnZoomIn.addActionListener(e -> setZoom(Math.min(200, zoomPercent + 10)));
        zoomLabel.setPreferredSize(new Dimension(48, 34));
        zoomLabel.setHorizontalAlignment(SwingConstants.CENTER);

        toolBar.add(makeToolbarGroup("Slide", btnNewSlide, btnDup, btnDelSlide));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Insert", btnText, btnRect, btnOval, btnLine, btnImage));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Style", btnBold, btnItalic, btnUnderline, btnTextColor));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Align", btnAlignL, btnAlignC, btnAlignR));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Arrange", btnFront, btnBack));
        toolBar.add(toolbarDivider());
        toolBar.add(makeToolbarGroup("Zoom", btnZoomOut, zoomLabel, btnZoomIn));

        return toolBar;
    }

    private JPanel createPresentButtonPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 4));
        panel.setBorder(new EmptyBorder(6, 10, 6, 14));
        JButton presentBtn = new JButton("Present");
        ImageIcon startIcon = loadIcon("start.png", 16);
        if (startIcon != null) presentBtn.setIcon(startIcon);
        presentBtn.setBackground(new Color(26, 115, 232));
        presentBtn.setForeground(Color.WHITE);
        presentBtn.setFocusPainted(false);
        presentBtn.setMargin(new Insets(8, 16, 8, 16));
        presentBtn.setToolTipText("Start the slideshow from the current slide");
        presentBtn.addActionListener(e -> startPresentation(false));
        panel.add(presentBtn);
        return panel;
    }

    // =====================================================================
    // LEFT SIDEBAR: SLIDE PANEL
    // =====================================================================
    private JComponent createSlidePanel() {
        slideListModel = new DefaultListModel<>();
        slideList = new JList<>(slideListModel);
        slideList.setCellRenderer(new SlideCellRenderer());
        slideList.setFixedCellHeight(96);
        slideList.setBorder(new EmptyBorder(6, 6, 6, 6));
        slideList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                int idx = slideList.getSelectedIndex();
                if (idx >= 0 && idx < slides.size()) {
                    finishInlineEdit();
                    currentSlide = idx;
                    selectedElement = null;
                    canvas.repaint();
                    updatePropertiesPanel();
                    updateStatus();
                }
            }
        });

        JButton newBtn = new JButton("+ New Slide");
        newBtn.setMargin(new Insets(6, 10, 6, 10));
        newBtn.setFocusPainted(false);
        newBtn.addActionListener(e -> newSlide("Title + Content"));
        JButton dupBtn = makeToolButton("Duplicate", "fileCopy.png", "Duplicate current slide");
        dupBtn.addActionListener(e -> duplicateSlide());
        JButton delBtn = makeToolButton("Delete", "delete.png", "Delete current slide");
        delBtn.addActionListener(e -> deleteSlide());

        JPanel buttonRow = new JPanel(new GridLayout(1, 3, 4, 4));
        buttonRow.setBorder(new EmptyBorder(8, 8, 8, 8));
        buttonRow.add(newBtn);
        buttonRow.add(dupBtn);
        buttonRow.add(delBtn);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setPreferredSize(new Dimension(210, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Slides"), new EmptyBorder(2, 4, 4, 4)));
        JScrollPane scroll = new JScrollPane(slideList);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);
        panel.add(buttonRow, BorderLayout.SOUTH);
        return panel;
    }

    /** Renders each slide as a small live thumbnail in the slide list. */
    private class SlideCellRenderer extends JPanel implements ListCellRenderer<Slide> {
        private final JLabel thumbLabel = new JLabel();
        private final JLabel numberLabel = new JLabel();

        SlideCellRenderer() {
            setLayout(new BorderLayout(8, 0));
            setBorder(new EmptyBorder(8, 10, 8, 10));
            thumbLabel.setBorder(BorderFactory.createLineBorder(new Color(0, 0, 0, 60)));
            numberLabel.setFont(new Font("SansSerif", Font.PLAIN, 11));
            numberLabel.setForeground(new Color(120, 120, 120));
            JPanel left = new JPanel(new BorderLayout());
            left.setOpaque(false);
            left.add(numberLabel, BorderLayout.NORTH);
            add(left, BorderLayout.WEST);
            add(thumbLabel, BorderLayout.CENTER);
            setOpaque(true);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends Slide> list, Slide value, int index,
                                                        boolean isSelected, boolean cellHasFocus) {
            numberLabel.setText(String.valueOf(index + 1));
            thumbLabel.setIcon(new ImageIcon(renderSlideImage(value, 148, 84)));
            setBackground(isSelected ? new Color(26, 115, 232, 40) : list.getBackground());
            return this;
        }
    }

    // =====================================================================
    // CANVAS / STAGE LOOK
    // =====================================================================
    private JComponent buildCanvasArea() {
        canvas = new SlideCanvas();
        canvas.setBackground(Color.WHITE);

        StagePanel stage = new StagePanel(new BorderLayout());
        stage.add(canvas, BorderLayout.CENTER);

        ScrollableBackdrop backdrop = new ScrollableBackdrop(new GridBagLayout());
        backdrop.setBackground(new Color(60, 63, 65));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.weightx = 1;
        gbc.weighty = 0;
        gbc.insets = new Insets(36, 0, 48, 0);
        gbc.anchor = GridBagConstraints.NORTH;
        backdrop.add(stage, gbc);

        JScrollPane scrollPane = new JScrollPane(backdrop);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getViewport().setBackground(backdrop.getBackground());
        canvasScrollPane = scrollPane;
        scrollPane.getViewport().addComponentListener(new ComponentAdapter() {
            @Override public void componentResized(ComponentEvent e) {
                if (autoFitZoom) fitZoomToWindow();
            }
        });
        return scrollPane;
    }

    /** Recomputes zoom so the slide fills the available canvas area, preserving aspect ratio. */
    private void fitZoomToWindow() {
        if (canvasScrollPane == null) return;
        Dimension avail = canvasScrollPane.getViewport().getExtentSize();
        int availW = avail.width - 40;   // side breathing room to match stage margins
        int availH = avail.height - 84;  // matches the 36/48 top/bottom insets around the stage
        if (availW <= 0 || availH <= 0) return;
        double scale = Math.min(availW / (double) SLIDE_W, availH / (double) SLIDE_H);
        int percent = (int) Math.floor(scale * 100);
        percent = Math.max(50, Math.min(200, percent));
        setZoom(percent, false);
    }

    /** Panel that draws a soft drop shadow behind itself, framing the slide like a floating card. */
    static class StagePanel extends JPanel {
        StagePanel(LayoutManager lm) {
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
            g2.setColor(Color.WHITE);
            g2.fillRect(0, 0, w - shadow, h - shadow);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    static class ScrollableBackdrop extends JPanel implements Scrollable {
        ScrollableBackdrop(LayoutManager lm) { super(lm); }
        public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) { return 16; }
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) { return 120; }
        public boolean getScrollableTracksViewportWidth() { return true; }
        public boolean getScrollableTracksViewportHeight() { return false; }
    }

    // =====================================================================
    // EDITABLE SLIDE CANVAS
    // =====================================================================
    private class SlideCanvas extends JPanel {
        private Point dragStart;
        private Rectangle origBounds;
        private int handle = 0; // 0 none, 1 NW, 2 NE, 3 SW, 4 SE
        private boolean moving = false;

        SlideCanvas() {
            MouseAdapter ma = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent e) {
                    finishInlineEdit();
                    Point p = toSlideSpace(e.getPoint());
                    if (selectedElement != null) {
                        handle = hitHandle(selectedElement.bounds, p);
                        if (handle != 0) {
                            dragStart = p;
                            origBounds = new Rectangle(selectedElement.bounds);
                            pushUndo();
                            return;
                        }
                    }
                    handle = 0;
                    SlideElement hit = null;
                    List<SlideElement> els = currentSlideObj().elements;
                    for (int i = els.size() - 1; i >= 0; i--) {
                        if (els.get(i).bounds.contains(p)) { hit = els.get(i); break; }
                    }
                    selectedElement = hit;
                    updatePropertiesPanel();
                    repaint();
                    if (hit != null) {
                        moving = true;
                        dragStart = p;
                        origBounds = new Rectangle(hit.bounds);
                        pushUndo();
                    } else {
                        moving = false;
                    }
                }
                @Override public void mouseDragged(MouseEvent e) {
                    if (selectedElement == null || dragStart == null) return;
                    Point p = toSlideSpace(e.getPoint());
                    int dx = p.x - dragStart.x, dy = p.y - dragStart.y;
                    Rectangle b = selectedElement.bounds;
                    if (handle != 0) {
                        switch (handle) {
                            case 1:
                                b.setBounds(origBounds.x + dx, origBounds.y + dy,
                                        origBounds.width - dx, origBounds.height - dy);
                                break;
                            case 2:
                                b.setBounds(origBounds.x, origBounds.y + dy,
                                        origBounds.width + dx, origBounds.height - dy);
                                break;
                            case 3:
                                b.setBounds(origBounds.x + dx, origBounds.y,
                                        origBounds.width - dx, origBounds.height + dy);
                                break;
                            case 4:
                                b.setBounds(origBounds.x, origBounds.y,
                                        origBounds.width + dx, origBounds.height + dy);
                                break;
                            default:
                                break;
                        }
                        if (b.width < 20) b.width = 20;
                        if (b.height < 20) b.height = 20;
                    } else if (moving) {
                        b.x = origBounds.x + dx;
                        b.y = origBounds.y + dy;
                    }
                    markDirty();
                    repaint();
                }
                @Override public void mouseReleased(MouseEvent e) {
                    dragStart = null;
                    handle = 0;
                    moving = false;
                }
                @Override public void mouseClicked(MouseEvent e) {
                    if (e.getClickCount() == 2 && selectedElement instanceof TextElement) {
                        startInlineEdit((TextElement) selectedElement);
                    }
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private Point toSlideSpace(Point p) {
            double scale = zoomPercent / 100.0;
            return new Point((int) (p.x / scale), (int) (p.y / scale));
        }

        private int hitHandle(Rectangle b, Point p) {
            int r = 7;
            if (near(p, b.x, b.y, r)) return 1;
            if (near(p, b.x + b.width, b.y, r)) return 2;
            if (near(p, b.x, b.y + b.height, r)) return 3;
            if (near(p, b.x + b.width, b.y + b.height, r)) return 4;
            return 0;
        }
        private boolean near(Point p, int x, int y, int r) {
            return Math.abs(p.x - x) <= r && Math.abs(p.y - y) <= r;
        }

        @Override public Dimension getPreferredSize() {
            double scale = zoomPercent / 100.0;
            return new Dimension((int) (SLIDE_W * scale), (int) (SLIDE_H * scale));
        }

        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            double scale = zoomPercent / 100.0;
            g2.scale(scale, scale);

            Slide slide = currentSlideObj();
            g2.setColor(new Color(slide.backgroundRGB, true));
            g2.fillRect(0, 0, SLIDE_W, SLIDE_H);
            for (SlideElement el : slide.elements) {
                el.paint(g2, el == selectedElement);
            }
            if (selectedElement != null) {
                g2.setColor(new Color(26, 115, 232));
                g2.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
                        new float[]{4, 3}, 0));
                Rectangle b = selectedElement.bounds;
                g2.drawRect(b.x, b.y, b.width, b.height);
                g2.setStroke(new BasicStroke(1f));
                for (Point hp : handlePoints(b)) {
                    g2.setColor(Color.WHITE);
                    g2.fillRect(hp.x - 4, hp.y - 4, 8, 8);
                    g2.setColor(new Color(26, 115, 232));
                    g2.drawRect(hp.x - 4, hp.y - 4, 8, 8);
                }
            }
            g2.dispose();
        }

        private List<Point> handlePoints(Rectangle b) {
            return Arrays.asList(new Point(b.x, b.y), new Point(b.x + b.width, b.y),
                    new Point(b.x, b.y + b.height), new Point(b.x + b.width, b.y + b.height));
        }
    }

    // =====================================================================
    // INLINE TEXT EDITING
    // =====================================================================
    private void startInlineEdit(TextElement te) {
        editingElement = te;
        inlineEditor = new JTextArea(te.text);
        inlineEditor.setLineWrap(true);
        inlineEditor.setWrapStyleWord(true);
        int style = (te.bold ? Font.BOLD : 0) | (te.italic ? Font.ITALIC : 0);
        inlineEditor.setFont(new Font(te.fontFamily, style, te.fontSize));
        inlineEditor.setForeground(new Color(te.colorRGB, true));
        inlineEditor.setOpaque(false);
        inlineEditor.setBorder(BorderFactory.createDashedBorder(new Color(26, 115, 232)));

        double scale = zoomPercent / 100.0;
        Rectangle b = te.bounds;
        canvas.setLayout(null);
        inlineEditor.setBounds((int) (b.x * scale), (int) (b.y * scale),
                (int) (b.width * scale), (int) (b.height * scale));
        canvas.add(inlineEditor);
        canvas.setComponentZOrder(inlineEditor, 0);
        canvas.revalidate();
        canvas.repaint();
        inlineEditor.requestFocus();
        inlineEditor.addFocusListener(new FocusAdapter() {
            @Override public void focusLost(FocusEvent e) { finishInlineEdit(); }
        });
    }

    private void finishInlineEdit() {
        if (inlineEditor == null || editingElement == null) return;
        pushUndo();
        editingElement.text = inlineEditor.getText();
        canvas.remove(inlineEditor);
        inlineEditor = null;
        editingElement = null;
        markDirty();
        canvas.revalidate();
        canvas.repaint();
    }

    // =====================================================================
    // ELEMENT CREATION / MUTATION
    // =====================================================================
    private void addTextBox() {
        pushUndo();
        TextElement te = new TextElement();
        te.bounds = new Rectangle(SLIDE_W / 2 - 150, SLIDE_H / 2 - 30, 300, 60);
        currentSlideObj().elements.add(te);
        selectedElement = te;
        markDirty();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void addShape(String type) {
        pushUndo();
        ShapeElement se = new ShapeElement();
        se.shapeType = type;
        if ("line".equals(type)) {
            se.bounds = new Rectangle(SLIDE_W / 2 - 100, SLIDE_H / 2 - 30, 200, 60);
        } else {
            se.bounds = new Rectangle(SLIDE_W / 2 - 100, SLIDE_H / 2 - 60, 200, 120);
        }
        currentSlideObj().elements.add(se);
        selectedElement = se;
        markDirty();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void insertImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileFilter(new FileNameExtensionFilter("Image files", "png", "jpg", "jpeg", "gif"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            try {
                File f = chooser.getSelectedFile();
                byte[] bytes = java.nio.file.Files.readAllBytes(f.toPath());
                BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                if (img == null) throw new IOException("Unsupported or unreadable image file");
                pushUndo();
                ImageElement ie = new ImageElement();
                ie.imageBytes = bytes;
                ie.image = img;
                int w = Math.min(400, img.getWidth());
                int h = Math.max(1, (int) ((double) img.getHeight() * w / img.getWidth()));
                ie.bounds = new Rectangle(SLIDE_W / 2 - w / 2, SLIDE_H / 2 - h / 2, w, h);
                currentSlideObj().elements.add(ie);
                selectedElement = ie;
                markDirty();
                canvas.repaint();
                updatePropertiesPanel();
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Could not insert image: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void setSlideBackground() {
        Color c = JColorChooser.showDialog(this, "Slide Background", new Color(currentSlideObj().backgroundRGB));
        if (c != null) {
            pushUndo();
            currentSlideObj().backgroundRGB = c.getRGB();
            markDirty();
            canvas.repaint();
        }
    }

    private void applyLayoutTo(Slide s, String layout) {
        s.elements.clear();
        switch (layout) {
            case "Title Slide": {
                TextElement title = new TextElement();
                title.text = "Presentation Title";
                title.fontSize = 44;
                title.bold = true;
                title.align = 1;
                title.bounds = new Rectangle(80, 190, SLIDE_W - 160, 90);
                TextElement subtitle = new TextElement();
                subtitle.text = "Subtitle or author";
                subtitle.fontSize = 20;
                subtitle.align = 1;
                subtitle.bounds = new Rectangle(80, 290, SLIDE_W - 160, 50);
                s.elements.add(title);
                s.elements.add(subtitle);
                break;
            }
            case "Title + Content": {
                TextElement title = new TextElement();
                title.text = "Slide Title";
                title.fontSize = 32;
                title.bold = true;
                title.bounds = new Rectangle(60, 40, SLIDE_W - 120, 60);
                TextElement body = new TextElement();
                body.text = "Click to edit body text";
                body.fontSize = 18;
                body.bounds = new Rectangle(60, 130, SLIDE_W - 120, SLIDE_H - 180);
                s.elements.add(title);
                s.elements.add(body);
                break;
            }
            default:
                break; // Blank
        }
    }

    private void applyLayout(String layout) {
        pushUndo();
        applyLayoutTo(currentSlideObj(), layout);
        selectedElement = null;
        markDirty();
        refreshSlideList();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void bringToFront() {
        if (selectedElement == null) return;
        pushUndo();
        List<SlideElement> els = currentSlideObj().elements;
        els.remove(selectedElement);
        els.add(selectedElement);
        markDirty();
        canvas.repaint();
    }

    private void sendToBack() {
        if (selectedElement == null) return;
        pushUndo();
        List<SlideElement> els = currentSlideObj().elements;
        els.remove(selectedElement);
        els.add(0, selectedElement);
        markDirty();
        canvas.repaint();
    }

    private void deleteSelectedElement() {
        if (selectedElement == null) return;
        pushUndo();
        currentSlideObj().elements.remove(selectedElement);
        selectedElement = null;
        markDirty();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void toggleBold() {
        if (!(selectedElement instanceof TextElement)) return;
        pushUndo();
        TextElement te = (TextElement) selectedElement;
        te.bold = !te.bold;
        markDirty();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void toggleItalic() {
        if (!(selectedElement instanceof TextElement)) return;
        pushUndo();
        TextElement te = (TextElement) selectedElement;
        te.italic = !te.italic;
        markDirty();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void toggleUnderline() {
        if (!(selectedElement instanceof TextElement)) return;
        pushUndo();
        TextElement te = (TextElement) selectedElement;
        te.underline = !te.underline;
        markDirty();
        canvas.repaint();
        updatePropertiesPanel();
    }

    private void setTextAlign(int align) {
        if (!(selectedElement instanceof TextElement)) return;
        pushUndo();
        ((TextElement) selectedElement).align = align;
        markDirty();
        canvas.repaint();
    }

    private void setFontFamily(String family) {
        if (!(selectedElement instanceof TextElement) || family == null) return;
        pushUndo();
        ((TextElement) selectedElement).fontFamily = family;
        markDirty();
        canvas.repaint();
    }

    private void setFontSize(int size) {
        if (!(selectedElement instanceof TextElement)) return;
        pushUndo();
        ((TextElement) selectedElement).fontSize = size;
        markDirty();
        canvas.repaint();
    }

    private void applyTextColor() {
        if (!(selectedElement instanceof TextElement)) return;
        Color c = JColorChooser.showDialog(this, "Text Color", new Color(((TextElement) selectedElement).colorRGB));
        if (c != null) {
            pushUndo();
            ((TextElement) selectedElement).colorRGB = c.getRGB();
            markDirty();
            canvas.repaint();
        }
    }

    private void applyFillColor() {
        if (!(selectedElement instanceof ShapeElement)) return;
        Color c = JColorChooser.showDialog(this, "Fill Color", new Color(((ShapeElement) selectedElement).fillRGB));
        if (c != null) {
            pushUndo();
            ((ShapeElement) selectedElement).fillRGB = c.getRGB();
            markDirty();
            canvas.repaint();
        }
    }

    private void applyBorderColor() {
        if (!(selectedElement instanceof ShapeElement)) return;
        Color c = JColorChooser.showDialog(this, "Border Color", new Color(((ShapeElement) selectedElement).borderRGB));
        if (c != null) {
            pushUndo();
            ((ShapeElement) selectedElement).borderRGB = c.getRGB();
            markDirty();
            canvas.repaint();
        }
    }

    // =====================================================================
    // SLIDE MANAGEMENT
    // =====================================================================
    private Slide currentSlideObj() {
        return slides.get(currentSlide);
    }

    private void newSlide(String layout) {
        pushUndo();
        Slide s = new Slide();
        applyLayoutTo(s, layout);
        int insertAt = slides.isEmpty() ? 0 : currentSlide + 1;
        slides.add(insertAt, s);
        currentSlide = insertAt;
        selectedElement = null;
        markDirty();
        refreshSlideList();
        slideList.setSelectedIndex(currentSlide);
        canvas.repaint();
        updatePropertiesPanel();
        updateStatus();
    }

    private void duplicateSlide() {
        if (slides.isEmpty()) return;
        pushUndo();
        byte[] data = serializeSlides(Collections.singletonList(currentSlideObj()));
        List<Slide> copy = deserializeSlides(data);
        if (copy == null || copy.isEmpty()) return;
        Slide dup = copy.get(0);
        slides.add(currentSlide + 1, dup);
        currentSlide = currentSlide + 1;
        selectedElement = null;
        markDirty();
        refreshSlideList();
        slideList.setSelectedIndex(currentSlide);
        canvas.repaint();
        updatePropertiesPanel();
        updateStatus();
    }

    private void deleteSlide() {
        if (slides.size() <= 1) {
            JOptionPane.showMessageDialog(this, "A presentation needs at least one slide.",
                    "Delete Slide", JOptionPane.WARNING_MESSAGE);
            return;
        }
        pushUndo();
        slides.remove(currentSlide);
        currentSlide = Math.max(0, Math.min(currentSlide, slides.size() - 1));
        selectedElement = null;
        markDirty();
        refreshSlideList();
        slideList.setSelectedIndex(currentSlide);
        canvas.repaint();
        updatePropertiesPanel();
        updateStatus();
    }

    private void refreshSlideList() {
        slideListModel.clear();
        for (Slide s : slides) slideListModel.addElement(s);
    }

    // =====================================================================
    // ZOOM / STATUS
    // =====================================================================
    private void setZoom(int newPercent) {
        setZoom(newPercent, true);
    }

    private void setZoom(int newPercent, boolean userInitiated) {
        if (userInitiated) autoFitZoom = false;
        zoomPercent = newPercent;
        zoomLabel.setText(zoomPercent + "%");
        canvas.revalidate();
        canvas.repaint();
    }

    private void markDirty() {
        dirty = true;
        if (saveStatusLabel != null) saveStatusLabel.setText("\u25CF Unsaved changes  ");
    }

    private void updateStatus() {
        if (slideStatusLabel != null) {
            slideStatusLabel.setText(" Slide " + (currentSlide + 1) + " of " + slides.size() + "  ");
        }
    }

    private JPanel createStatusBar() {
        JPanel bar = new JPanel(new BorderLayout());
        bar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(0, 0, 0, 40)),
                new EmptyBorder(8, 16, 8, 16)));
        slideStatusLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        slideStatusLabel.setFont(slideStatusLabel.getFont().deriveFont(12f));
        saveStatusLabel.setBorder(new EmptyBorder(2, 4, 2, 4));
        saveStatusLabel.setFont(saveStatusLabel.getFont().deriveFont(12f));
        bar.add(slideStatusLabel, BorderLayout.WEST);
        bar.add(saveStatusLabel, BorderLayout.EAST);
        return bar;
    }

    // =====================================================================
    // RIGHT SIDEBAR: DESIGN / PROPERTIES PANEL
    // =====================================================================
    private JComponent createPropertiesPanel() {
        propertiesPanel = new JPanel();
        propertiesPanel.setLayout(new BoxLayout(propertiesPanel, BoxLayout.Y_AXIS));
        propertiesPanel.setBorder(new EmptyBorder(12, 12, 12, 12));

        JScrollPane scroll = new JScrollPane(propertiesPanel);
        scroll.setBorder(null);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setPreferredSize(new Dimension(260, 0));
        wrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder("Design"), new EmptyBorder(2, 4, 4, 4)));
        wrapper.add(scroll, BorderLayout.CENTER);

        updatePropertiesPanel();
        return wrapper;
    }

    private void updatePropertiesPanel() {
        if (propertiesPanel == null) return;
        propertiesPanel.removeAll();

        propertiesPanel.add(sectionLabel("Slide"));
        JButton bgBtn = new JButton("Background Color\u2026");
        bgBtn.setFocusPainted(false);
        bgBtn.addActionListener(e -> setSlideBackground());
        propertiesPanel.add(pad(bgBtn));

        JPanel layoutRow = new JPanel(new GridLayout(3, 1, 4, 4));
        layoutRow.setAlignmentX(Component.LEFT_ALIGNMENT);
        layoutRow.setMaximumSize(new Dimension(230, 112));
        layoutRow.setOpaque(false);
        for (String layout : new String[]{"Title Slide", "Title + Content", "Blank"}) {
            JButton b = new JButton(layout);
            b.setFocusPainted(false);
            b.addActionListener(e -> applyLayout(layout));
            layoutRow.add(b);
        }
        propertiesPanel.add(pad(layoutRow));
        propertiesPanel.add(Box.createVerticalStrut(10));

        if (selectedElement instanceof TextElement) {
            TextElement te = (TextElement) selectedElement;
            propertiesPanel.add(sectionLabel("Text"));

            JComboBox<String> fontBox = new JComboBox<>(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames());
            fontBox.setSelectedItem(te.fontFamily);
            fontBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            fontBox.setMaximumSize(new Dimension(230, 30));
            fontBox.addActionListener(e -> setFontFamily((String) fontBox.getSelectedItem()));
            propertiesPanel.add(pad(fontBox));

            JComboBox<Integer> sizeBox = new JComboBox<>(
                    new Integer[]{10, 12, 14, 16, 18, 20, 24, 28, 32, 36, 44, 54, 66, 80});
            sizeBox.setSelectedItem(te.fontSize);
            sizeBox.setAlignmentX(Component.LEFT_ALIGNMENT);
            sizeBox.setMaximumSize(new Dimension(230, 30));
            sizeBox.addActionListener(e -> setFontSize((Integer) sizeBox.getSelectedItem()));
            propertiesPanel.add(pad(sizeBox));

            JPanel styleRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            styleRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            styleRow.setOpaque(false);
            JToggleButton boldT = new JToggleButton("B", te.bold);
            JToggleButton italicT = new JToggleButton("I", te.italic);
            JToggleButton underlineT = new JToggleButton("U", te.underline);
            boldT.addActionListener(e -> toggleBold());
            italicT.addActionListener(e -> toggleItalic());
            underlineT.addActionListener(e -> toggleUnderline());
            styleRow.add(boldT);
            styleRow.add(italicT);
            styleRow.add(underlineT);
            propertiesPanel.add(pad(styleRow));

            JPanel alignRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            alignRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            alignRow.setOpaque(false);
            JButton alL = new JButton("\u2261L"), alC = new JButton("\u2261C"), alR = new JButton("\u2261R");
            alL.addActionListener(e -> { setTextAlign(0); updatePropertiesPanel(); });
            alC.addActionListener(e -> { setTextAlign(1); updatePropertiesPanel(); });
            alR.addActionListener(e -> { setTextAlign(2); updatePropertiesPanel(); });
            alignRow.add(alL);
            alignRow.add(alC);
            alignRow.add(alR);
            propertiesPanel.add(pad(alignRow));

            JButton colorBtn = new JButton("Text Color\u2026");
            colorBtn.setFocusPainted(false);
            colorBtn.addActionListener(e -> applyTextColor());
            propertiesPanel.add(pad(colorBtn));

        } else if (selectedElement instanceof ShapeElement) {
            propertiesPanel.add(sectionLabel("Shape"));
            JButton fillBtn = new JButton("Fill Color\u2026");
            fillBtn.setFocusPainted(false);
            fillBtn.addActionListener(e -> applyFillColor());
            propertiesPanel.add(pad(fillBtn));
            JButton borderBtn = new JButton("Border Color\u2026");
            borderBtn.setFocusPainted(false);
            borderBtn.addActionListener(e -> applyBorderColor());
            propertiesPanel.add(pad(borderBtn));

        } else if (selectedElement instanceof ImageElement) {
            propertiesPanel.add(sectionLabel("Image"));
            JLabel info = new JLabel("<html>Drag a corner handle<br>to resize the image.</html>");
            info.setAlignmentX(Component.LEFT_ALIGNMENT);
            propertiesPanel.add(pad(info));
        }

        if (selectedElement != null) {
            propertiesPanel.add(Box.createVerticalStrut(10));
            propertiesPanel.add(sectionLabel("Arrange"));
            JPanel arrangeRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            arrangeRow.setAlignmentX(Component.LEFT_ALIGNMENT);
            arrangeRow.setOpaque(false);
            JButton frontBtn = new JButton("Bring to Front");
            JButton backBtn = new JButton("Send to Back");
            JButton delBtn = new JButton("Delete");
            frontBtn.setFocusPainted(false);
            backBtn.setFocusPainted(false);
            delBtn.setFocusPainted(false);
            ImageIcon delIcon = loadIcon("delete.png", 14);
            if (delIcon != null) delBtn.setIcon(delIcon);
            frontBtn.addActionListener(e -> bringToFront());
            backBtn.addActionListener(e -> sendToBack());
            delBtn.addActionListener(e -> deleteSelectedElement());
            arrangeRow.add(frontBtn);
            arrangeRow.add(backBtn);
            arrangeRow.add(delBtn);
            propertiesPanel.add(pad(arrangeRow));
        }

        propertiesPanel.revalidate();
        propertiesPanel.repaint();
    }

    private JLabel sectionLabel(String text) {
        JLabel l = new JLabel(text.toUpperCase());
        l.setFont(new Font("SansSerif", Font.PLAIN, 10));
        l.setForeground(new Color(140, 140, 140));
        l.setAlignmentX(Component.LEFT_ALIGNMENT);
        l.setBorder(new EmptyBorder(4, 2, 6, 2));
        return l;
    }

    private JComponent pad(JComponent c) {
        c.setAlignmentX(Component.LEFT_ALIGNMENT);
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.setBorder(new EmptyBorder(0, 0, 8, 0));
        p.setAlignmentX(Component.LEFT_ALIGNMENT);
        p.add(c, BorderLayout.CENTER);
        return p;
    }

    // =====================================================================
    // THUMBNAIL / OFFSCREEN RENDERING (shared by list, PNG export, present mode)
    // =====================================================================
    private BufferedImage renderSlideImage(Slide slide, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        double sx = w / (double) SLIDE_W, sy = h / (double) SLIDE_H;
        g2.scale(sx, sy);
        g2.setColor(new Color(slide.backgroundRGB, true));
        g2.fillRect(0, 0, SLIDE_W, SLIDE_H);
        for (SlideElement el : slide.elements) el.paint(g2, false);
        g2.dispose();
        return img;
    }

    // =====================================================================
    // SAVE / OPEN
    // =====================================================================
    private void saveFile() {
        finishInlineEdit();
        if (fileChooser == null) fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("Glamour Presentation (*.glam)", "glam"));
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String path = file.getAbsolutePath();
            if (!path.toLowerCase(Locale.ROOT).endsWith(".glam")) path += ".glam";
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(path))) {
                oos.writeObject(slides);
                dirty = false;
                saveStatusLabel.setText("All changes saved  ");
                JOptionPane.showMessageDialog(this, "Presentation saved!", "Glamour", JOptionPane.INFORMATION_MESSAGE);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "Error saving file: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void openFile() {
        finishInlineEdit();
        if (fileChooser == null) fileChooser = new JFileChooser();
        fileChooser.setFileFilter(new FileNameExtensionFilter("Glamour Presentation (*.glam)", "glam"));
        if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            loadPresentation(fileChooser.getSelectedFile());
        }
    }

    /** Loads a .glam presentation into this window. Split out of openFile()
     *  so it can be reused both by the file chooser and by callers that
     *  already have a File in hand (e.g. the File Explorer's "open with"
     *  routing). */
    @SuppressWarnings("unchecked")
    private void loadPresentation(File file) {
        finishInlineEdit();
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            List<Slide> loaded = (List<Slide>) ois.readObject();
            rehydrateImages(loaded);
            slides = loaded;
            currentSlide = 0;
            selectedElement = null;
            refreshSlideList();
            slideList.setSelectedIndex(0);
            canvas.repaint();
            updatePropertiesPanel();
            updateStatus();
        } catch (Exception ex) {
            JOptionPane.showMessageDialog(this, "Error opening file: " + ex.getMessage(),
                    "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =====================================================================
    // EXPORT
    // =====================================================================
    private void exportPngCurrentSlide() {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String path = file.getAbsolutePath();
            if (!path.toLowerCase(Locale.ROOT).endsWith(".png")) path += ".png";
            try {
                BufferedImage img = renderSlideImage(currentSlideObj(), SLIDE_W, SLIDE_H);
                ImageIO.write(img, "png", new File(path));
                JOptionPane.showMessageDialog(this, "Slide exported as PNG!", "Export Complete",
                        JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private void exportPngAll() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("Choose a folder to export slide images");
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            try {
                for (int i = 0; i < slides.size(); i++) {
                    BufferedImage img = renderSlideImage(slides.get(i), SLIDE_W, SLIDE_H);
                    ImageIO.write(img, "png", new File(dir, "slide-" + (i + 1) + ".png"));
                }
                JOptionPane.showMessageDialog(this, "Exported " + slides.size() + " slide image(s).",
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /** Lightweight demo exporter \u2014 zips a simple XML outline plus a PNG per slide,
     *  in the same spirit as finnLabs.writer's simulated PDF/DOCX exporters. */
    private void exportSimulated(String format) {
        if (fileChooser == null) fileChooser = new JFileChooser();
        if (fileChooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            String path = file.getAbsolutePath();
            if (!path.toLowerCase(Locale.ROOT).endsWith("." + format)) path += "." + format;
            try (ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(path))) {
                zos.putNextEntry(new ZipEntry("presentation.xml"));
                StringBuilder sb = new StringBuilder();
                sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<presentation format=\"")
                        .append(format).append("\">\n");
                for (int i = 0; i < slides.size(); i++) {
                    sb.append("  <slide index=\"").append(i + 1).append("\">\n");
                    for (SlideElement el : slides.get(i).elements) {
                        if (el instanceof TextElement) {
                            sb.append("    <text>").append(escapeXml(((TextElement) el).text)).append("</text>\n");
                        }
                    }
                    sb.append("  </slide>\n");
                }
                sb.append("</presentation>\n");
                zos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
                for (int i = 0; i < slides.size(); i++) {
                    zos.putNextEntry(new ZipEntry("slide-" + (i + 1) + ".png"));
                    BufferedImage img = renderSlideImage(slides.get(i), SLIDE_W, SLIDE_H);
                    ImageIO.write(img, "png", zos);
                    zos.closeEntry();
                }
                JOptionPane.showMessageDialog(this, "Exported to " + format.toUpperCase() + "! (finnLabs demo exporter)",
                        "Export Complete", JOptionPane.INFORMATION_MESSAGE);
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "Export failed: " + ex.getMessage(),
                        "Error", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    private static String escapeXml(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void showHelpDialog(String title, String message) {
        JOptionPane.showMessageDialog(this, message, title, JOptionPane.INFORMATION_MESSAGE);
    }

    // =====================================================================
    // AUTOSAVE
    // =====================================================================
    private void performAutosave() {
        dirty = false;
        saveStatusLabel.setText("Saving\u2026  ");
        final List<Slide> snapshot = slides;
        SwingWorker<Void, Void> worker = new SwingWorker<>() {
            @Override protected Void doInBackground() {
                try {
                    File dir = new File(System.getProperty("user.home"), ".finnlabs");
                    if (!dir.exists()) dir.mkdirs();
                    try (ObjectOutputStream oos =
                                 new ObjectOutputStream(new FileOutputStream(new File(dir, "glamour-autosave.glam")))) {
                        oos.writeObject(snapshot);
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
    private void saveVersionSnapshot(String label) {
        byte[] data = serializeSlides(slides);
        if (data != null) {
            versionHistory.add(0, new VersionSnapshot(label, data));
            if (versionHistory.size() > 25) versionHistory.remove(versionHistory.size() - 1);
        }
    }

    private void showVersionHistory() {
        if (versionHistory.isEmpty()) {
            JOptionPane.showMessageDialog(this,
                    "No saved versions yet. Use File > Save Version Snapshot to create one.",
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
        List<Slide> restored = deserializeSlides(vs.data);
        if (restored != null) {
            slides = restored;
            currentSlide = Math.max(0, Math.min(currentSlide, slides.size() - 1));
            selectedElement = null;
            refreshSlideList();
            slideList.setSelectedIndex(currentSlide);
            canvas.repaint();
            updatePropertiesPanel();
            updateStatus();
            JOptionPane.showMessageDialog(this, "Restored: " + vs.label, "Version Restored",
                    JOptionPane.INFORMATION_MESSAGE);
        } else {
            JOptionPane.showMessageDialog(this, "Restore failed.", "Error", JOptionPane.ERROR_MESSAGE);
        }
    }

    // =====================================================================
    // UNDO / REDO (memento-style snapshots of the slide list)
    // =====================================================================
    private byte[] serializeSlides(List<Slide> list) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(bos)) {
            oos.writeObject(list);
            return bos.toByteArray();
        } catch (IOException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private List<Slide> deserializeSlides(byte[] data) {
        if (data == null) return null;
        try (ObjectInputStream ois = new ObjectInputStream(new ByteArrayInputStream(data))) {
            List<Slide> list = (List<Slide>) ois.readObject();
            rehydrateImages(list);
            return list;
        } catch (Exception e) {
            return null;
        }
    }

    private void rehydrateImages(List<Slide> list) {
        for (Slide s : list) {
            for (SlideElement el : s.elements) {
                if (el instanceof ImageElement) ((ImageElement) el).rehydrate();
            }
        }
    }

    private void pushUndo() {
        byte[] snap = serializeSlides(slides);
        if (snap != null) {
            undoStack.push(snap);
            while (undoStack.size() > 50) undoStack.removeLast();
            redoStack.clear();
        }
    }

    private void undo() {
        finishInlineEdit();
        if (undoStack.isEmpty()) return;
        byte[] cur = serializeSlides(slides);
        List<Slide> prev = deserializeSlides(undoStack.pop());
        if (prev != null) {
            if (cur != null) redoStack.push(cur);
            slides = prev;
            currentSlide = Math.max(0, Math.min(currentSlide, slides.size() - 1));
            selectedElement = null;
            refreshSlideList();
            slideList.setSelectedIndex(currentSlide);
            canvas.repaint();
            updatePropertiesPanel();
            updateStatus();
            markDirty();
        }
    }

    private void redo() {
        finishInlineEdit();
        if (redoStack.isEmpty()) return;
        byte[] cur = serializeSlides(slides);
        List<Slide> next = deserializeSlides(redoStack.pop());
        if (next != null) {
            if (cur != null) undoStack.push(cur);
            slides = next;
            currentSlide = Math.max(0, Math.min(currentSlide, slides.size() - 1));
            selectedElement = null;
            refreshSlideList();
            slideList.setSelectedIndex(currentSlide);
            canvas.repaint();
            updatePropertiesPanel();
            updateStatus();
            markDirty();
        }
    }

    // =====================================================================
    // PRESENT MODE (fullscreen slideshow)
    // =====================================================================
    private void startPresentation(boolean fromBeginning) {
        finishInlineEdit();
        if (slides.isEmpty()) return;
        final int[] idxHolder = {fromBeginning ? 0 : currentSlide};

        JFrame presentFrame = new JFrame();
        presentFrame.setUndecorated(true);
        presentFrame.setBackground(Color.BLACK);

        JPanel presentPanel = new JPanel() {
            @Override protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                Slide s = slides.get(Math.min(idxHolder[0], slides.size() - 1));
                double scale = Math.min(getWidth() / (double) SLIDE_W, getHeight() / (double) SLIDE_H);
                int w = (int) (SLIDE_W * scale), h = (int) (SLIDE_H * scale);
                int ox = (getWidth() - w) / 2, oy = (getHeight() - h) / 2;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.translate(ox, oy);
                g2.scale(scale, scale);
                g2.setColor(new Color(s.backgroundRGB, true));
                g2.fillRect(0, 0, SLIDE_W, SLIDE_H);
                for (SlideElement el : s.elements) el.paint(g2, false);
                g2.dispose();
            }
        };
        presentPanel.setBackground(Color.BLACK);
        presentFrame.add(presentPanel);

        GraphicsDevice gd = GraphicsEnvironment.getLocalGraphicsEnvironment().getDefaultScreenDevice();
        presentFrame.addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) {
                int code = e.getKeyCode();
                if (code == KeyEvent.VK_ESCAPE) {
                    if (gd.getFullScreenWindow() == presentFrame) gd.setFullScreenWindow(null);
                    presentFrame.dispose();
                } else if (code == KeyEvent.VK_RIGHT || code == KeyEvent.VK_SPACE || code == KeyEvent.VK_DOWN
                        || code == KeyEvent.VK_PAGE_DOWN) {
                    if (idxHolder[0] < slides.size() - 1) { idxHolder[0]++; presentPanel.repaint(); }
                } else if (code == KeyEvent.VK_LEFT || code == KeyEvent.VK_UP || code == KeyEvent.VK_PAGE_UP) {
                    if (idxHolder[0] > 0) { idxHolder[0]--; presentPanel.repaint(); }
                }
            }
        });
        presentFrame.setFocusable(true);

        if (gd.isFullScreenSupported()) {
            presentFrame.setResizable(false);
            gd.setFullScreenWindow(presentFrame);
        } else {
            presentFrame.setExtendedState(JFrame.MAXIMIZED_BOTH);
            presentFrame.setVisible(true);
        }
        presentFrame.requestFocus();
    }

    // =====================================================================
    // MAIN
    // =====================================================================
    public void launch(){
        SwingUtilities.invokeLater(() -> {
            try {
                whitesur.WhiteSur.ensureInstalled();
            } catch (Exception ignored) {}
            // NOTE: previously this constructed a *second* Glamour() and showed
            // that one instead of `this` - the instance launch() was called on
            // was built and then silently discarded. Fixed to show `this`.
            setVisible(true);
        });
    }

    /** Same as {@link #launch()}, but also loads the given .glam presentation
     *  once the window is up. Used by properties.AppRegistry / FileManager's
     *  "open with" routing. */
    public void launch(File toOpen) {
        SwingUtilities.invokeLater(() -> {
            try {
                whitesur.WhiteSur.ensureInstalled();
            } catch (Exception ignored) {}
            setVisible(true);
            if (toOpen != null && toOpen.isFile()) {
                loadPresentation(toOpen);
            }
        });
    }

    /** Standalone entry point so <code>app/glamour.app</code> can run this as its own process. */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new Glamour().launch());
    }
}
