package com.lftdetector;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.stage.DirectoryChooser;
import javafx.stage.Stage;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

/**
 * COVID-19 LFT detector — JavaFX UI (clinical dashboard).
 *
 * <p>Pipeline: select image → normalize.py strips EXIF → display the clean
 * JPEG. "Detect" runs detect.py Stage 1 (localize every cassette, draw boxes).
 * "Read All Results" runs Stage 2 (crop + classify EACH detected cassette).
 * Every detected cassette gets its own result card on the right; the ML logic
 * lives entirely in detect.py and is untouched by this UI.</p>
 */
public class LFTDetectorApp extends Application {

    // ----- configuration -----
    // The Python interpreter is resolved at startup (see resolvePythonExecutable):
    // it honours the LFT_PYTHON environment variable if set, otherwise falls back
    // to "python" on PATH — keeping the app portable across machines.
    private static final String PYTHON_EXECUTABLE = resolvePythonExecutable();
    private static final String DETECT_SCRIPT_REL = "python/detect.py";
    private static final String NORMALIZE_SCRIPT_REL = "python/normalize.py";
    private static final String MODEL_PATH_REL = "best.pt";
    // -------------------------

    private static final String[] IMAGE_EXTS = {".jpg", ".jpeg", ".png", ".bmp"};

    // Theme colours (kept in sync with app.css)
    private static final String ACCENT = "#16A47A";
    private static final String POSITIVE_COLOR = "#B5281A";   // muted crimson
    private static final String NEGATIVE_COLOR = "#1A7A52";   // deep medical green
    private static final String INVALID_COLOR  = "#A05A10";   // muted amber

    private final List<Path> imagePaths = new ArrayList<>();
    private final Map<Path, Path> normalizedCache = new HashMap<>();
    private int currentIndex = -1;
    private Path tempDir;

    private ImageView imageView;
    private Label folderLabel;
    private Label filenameLabel;
    private Label counterLabel;
    private Label statusLabel;
    private Button detectButton;
    private Button readResultButton;
    private Button prevButton;
    private Button nextButton;

    // The scrollable right-panel content, rebuilt on every render.
    private VBox detailsContainer;

    // Two-stage flow state.
    private Path lastDetectedImage;
    private DetectionResult lastResult;
    private int selectedDetection = 0;   // which detection card is expanded
    private double imageZoom = 1.0;       // viewer zoom (1.0 = fit-to-window)

    private DetectionService detectionService;
    private NormalizationService normalizationService;

    @Override
    public void start(Stage stage) {
        Path detectScript = Paths.get(DETECT_SCRIPT_REL).toAbsolutePath();
        Path normalizeScript = Paths.get(NORMALIZE_SCRIPT_REL).toAbsolutePath();
        Path modelPath = Paths.get(MODEL_PATH_REL).toAbsolutePath();
        detectionService = new DetectionService(PYTHON_EXECUTABLE, detectScript, modelPath);
        normalizationService = new NormalizationService(PYTHON_EXECUTABLE, normalizeScript);

        try {
            tempDir = Files.createTempDirectory("lft-ui-");
            tempDir.toFile().deleteOnExit();
        } catch (IOException e) {
            tempDir = Paths.get(System.getProperty("java.io.tmpdir"), "lft-ui");
            tempDir.toFile().mkdirs();
        }

        BorderPane root = new BorderPane();
        root.getStyleClass().add("root-pane");
        root.setTop(buildHeader(stage));
        root.setCenter(buildCenter());
        root.setBottom(buildBottomBar());

        Scene scene = new Scene(root, 1180, 760);
        scene.getStylesheets().add(getClass().getResource("/app.css").toExternalForm());

        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.LEFT) {
                navigate(-1);
                e.consume();
            } else if (e.getCode() == KeyCode.RIGHT) {
                navigate(1);
                e.consume();
            } else if (e.getCode() == KeyCode.ENTER) {
                runDetection();
                e.consume();
            }
        });

        stage.setTitle("COVID-19 LFT Detector");
        stage.setScene(scene);
        stage.setMinWidth(980);
        stage.setMinHeight(640);
        stage.show();

        updateUiState();
    }

    // ==================== Header ====================

    private HBox buildHeader(Stage stage) {
        StackPane logo = new StackPane();
        logo.getStyleClass().add("logo-circle");

        // COVID virus icon: central circle with radiating spikes (each capped
        // by a small dot — the classic spike-protein silhouette).
        javafx.scene.Group virus = new javafx.scene.Group();
        double coreR = 5, spikeInner = 7.5, spikeOuter = 11, tipR = 1.6;
        javafx.scene.shape.Circle core = new javafx.scene.shape.Circle(0, 0, coreR);
        core.setFill(Color.web(ACCENT));
        virus.getChildren().add(core);
        int spikes = 8;
        for (int i = 0; i < spikes; i++) {
            double angle = 2 * Math.PI * i / spikes;
            double x1 = Math.cos(angle) * spikeInner, y1 = Math.sin(angle) * spikeInner;
            double x2 = Math.cos(angle) * spikeOuter, y2 = Math.sin(angle) * spikeOuter;
            javafx.scene.shape.Line spike = new javafx.scene.shape.Line(x1, y1, x2, y2);
            spike.setStroke(Color.web(ACCENT));
            spike.setStrokeWidth(1.6);
            spike.setStrokeLineCap(javafx.scene.shape.StrokeLineCap.ROUND);
            virus.getChildren().add(spike);
            javafx.scene.shape.Circle tip = new javafx.scene.shape.Circle(x2, y2, tipR);
            tip.setFill(Color.web(ACCENT));
            virus.getChildren().add(tip);
        }
        logo.getChildren().add(virus);

        Label title = new Label("COVID-19 LFT Detector");
        title.getStyleClass().add("app-title");
        Label subtitle = new Label("AI lateral-flow test analysis");
        subtitle.getStyleClass().add("app-subtitle");
        VBox titleBox = new VBox(1, title, subtitle);
        titleBox.setAlignment(Pos.CENTER_LEFT);

        HBox titleSide = new HBox(12, logo, titleBox);
        titleSide.setAlignment(Pos.CENTER_LEFT);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        folderLabel = new Label("No folder selected");
        folderLabel.getStyleClass().add("folder-text");

        Button browseButton = new Button("Browse");
        browseButton.getStyleClass().add("browse-button");
        browseButton.setOnAction(e -> chooseFolder(stage));

        SVGPath folderIcon = new SVGPath();
        folderIcon.setContent("M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z");
        folderIcon.setFill(Color.TRANSPARENT);
        folderIcon.setStroke(Color.web("#AEAEB2"));
        folderIcon.setStrokeWidth(1.6);

        HBox folderPill = new HBox(8, folderIcon, folderLabel, separatorDot(), browseButton);
        folderPill.setAlignment(Pos.CENTER_LEFT);
        folderPill.getStyleClass().add("folder-pill");

        HBox header = new HBox(16, titleSide, spacer, folderPill);
        header.setAlignment(Pos.CENTER_LEFT);
        header.getStyleClass().add("app-header");
        return header;
    }

    private Label separatorDot() {
        Label l = new Label("|");
        l.getStyleClass().add("sep-dot");
        return l;
    }

    // ==================== Center: viewer + results ====================

    private HBox buildCenter() {
        // ----- image viewer card -----
        imageView = new ImageView();
        imageView.setPreserveRatio(true);   // never distort the cassette

        StackPane imageStack = new StackPane(imageView);
        imageStack.getStyleClass().add("image-stack");
        imageStack.setMinSize(360, 360);

        // Fit-to-window: bind the fit box to the panel so the WHOLE image is
        // always visible, aspect ratio preserved, never cropped.
        imageView.fitWidthProperty().bind(imageStack.widthProperty().subtract(36));
        imageView.fitHeightProperty().bind(imageStack.heightProperty().subtract(36));

        // Clip so a zoomed image never spills past the rounded card corners.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(imageStack.widthProperty());
        clip.heightProperty().bind(imageStack.heightProperty());
        clip.setArcWidth(28);
        clip.setArcHeight(28);
        imageStack.setClip(clip);

        // Optional zoom: scroll to zoom, double-click to reset to fit.
        imageStack.setOnScroll((ScrollEvent e) -> {
            if (imageView.getImage() == null) return;
            double factor = e.getDeltaY() > 0 ? 1.1 : 1.0 / 1.1;
            imageZoom = Math.max(1.0, Math.min(5.0, imageZoom * factor));
            imageView.setScaleX(imageZoom);
            imageView.setScaleY(imageZoom);
            e.consume();
        });
        imageStack.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) resetZoom();
        });

        filenameLabel = new Label("—");
        filenameLabel.getStyleClass().add("image-chip");
        StackPane.setAlignment(filenameLabel, Pos.BOTTOM_LEFT);
        StackPane.setMargin(filenameLabel, new Insets(0, 0, 14, 14));

        Label zoomHint = new Label("scroll to zoom · double-click to reset");
        zoomHint.getStyleClass().add("image-chip");
        StackPane.setAlignment(zoomHint, Pos.BOTTOM_RIGHT);
        StackPane.setMargin(zoomHint, new Insets(0, 14, 14, 0));

        imageStack.getChildren().addAll(filenameLabel, zoomHint);

        // ----- action buttons under the viewer -----
        detectButton = new Button("Detect");
        detectButton.getStyleClass().add("secondary-button");
        detectButton.setMaxWidth(Double.MAX_VALUE);
        detectButton.setOnAction(e -> runDetection());

        readResultButton = new Button("Read All Results");
        readResultButton.getStyleClass().add("primary-button");
        readResultButton.setMaxWidth(Double.MAX_VALUE);
        readResultButton.setOnAction(e -> runClassification());

        HBox.setHgrow(detectButton, Priority.ALWAYS);
        HBox.setHgrow(readResultButton, Priority.ALWAYS);
        HBox actionRow = new HBox(12, detectButton, readResultButton);

        VBox leftColumn = new VBox(14, imageStack, actionRow);
        VBox.setVgrow(imageStack, Priority.ALWAYS);

        // ----- right results panel -----
        VBox rightPanel = buildResultsPanel();

        HBox center = new HBox(20, leftColumn, rightPanel);
        center.setPadding(new Insets(20, 28, 16, 28));
        HBox.setHgrow(leftColumn, Priority.ALWAYS);
        return center;
    }

    private VBox buildResultsPanel() {
        Label heading = new Label("Analysis");
        heading.getStyleClass().add("panel-title");

        detailsContainer = new VBox(14);
        detailsContainer.getStyleClass().add("details-panel");
        renderEmpty("Press Detect to find the test cassette(s) in this image.");

        ScrollPane scroll = new ScrollPane(detailsContainer);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("details-scroll");
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        VBox.setVgrow(scroll, Priority.ALWAYS);

        VBox panel = new VBox(12, heading, scroll);
        panel.getStyleClass().add("results-panel");
        panel.setPrefWidth(370);
        panel.setMinWidth(330);
        return panel;
    }

    // ==================== Bottom navigation ====================

    private HBox buildBottomBar() {
        prevButton = new Button("◀  Previous Image");
        nextButton = new Button("Next Image  ▶");
        prevButton.getStyleClass().add("nav-button");
        nextButton.getStyleClass().add("nav-button");
        prevButton.setMinWidth(160);
        nextButton.setMinWidth(160);
        prevButton.setOnAction(e -> navigate(-1));
        nextButton.setOnAction(e -> navigate(1));

        counterLabel = new Label("No images");
        counterLabel.getStyleClass().add("image-counter");
        statusLabel = new Label("Choose a folder to begin");
        statusLabel.getStyleClass().add("status-label");

        VBox centerBox = new VBox(2, counterLabel, statusLabel);
        centerBox.setAlignment(Pos.CENTER);

        Region spaceL = new Region();
        Region spaceR = new Region();
        HBox.setHgrow(spaceL, Priority.ALWAYS);
        HBox.setHgrow(spaceR, Priority.ALWAYS);

        HBox bar = new HBox(16, prevButton, spaceL, centerBox, spaceR, nextButton);
        bar.setAlignment(Pos.CENTER);
        bar.getStyleClass().add("bottom-bar");
        return bar;
    }

    // ==================== Folder + navigation ====================

    private void chooseFolder(Stage stage) {
        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Choose folder of test images");
        File chosen = dc.showDialog(stage);
        if (chosen == null) return;
        loadFolder(chosen.toPath());
    }

    private void loadFolder(Path folder) {
        imagePaths.clear();
        normalizedCache.clear();
        try (Stream<Path> stream = Files.list(folder)) {
            stream.filter(this::isImage)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString().toLowerCase(Locale.ROOT)))
                    .forEach(imagePaths::add);
        } catch (Exception e) {
            showAlert("Failed to read folder: " + e.getMessage());
            return;
        }

        folderLabel.setText(shortFolderName(folder));

        if (imagePaths.isEmpty()) {
            currentIndex = -1;
            statusLabel.setText("No images found in folder");
        } else {
            currentIndex = 0;
            statusLabel.setText("Loaded " + imagePaths.size() + " images");
        }
        showCurrent();
    }

    private boolean isImage(Path p) {
        if (!Files.isRegularFile(p)) return false;
        String name = p.getFileName().toString().toLowerCase(Locale.ROOT);
        for (String ext : IMAGE_EXTS) {
            if (name.endsWith(ext)) return true;
        }
        return false;
    }

    private String shortFolderName(Path folder) {
        Path parent = folder.getParent();
        if (parent == null) return folder.toString();
        return parent.getFileName() + "/" + folder.getFileName() + "/";
    }

    private void navigate(int delta) {
        if (imagePaths.isEmpty()) return;
        currentIndex = Math.floorMod(currentIndex + delta, imagePaths.size());
        showCurrent();
    }

    /**
     * Show the current image. If a normalized copy is cached, swap to it
     * instantly; otherwise keep the previous image visible until normalization
     * finishes (never flash the un-normalized original). Prefetches neighbours.
     */
    private void showCurrent() {
        lastResult = null;
        lastDetectedImage = null;
        selectedDetection = 0;
        renderEmpty("Press Detect to find the test cassette(s) in this image.");

        if (currentIndex < 0 || imagePaths.isEmpty()) {
            clearViewerImage();
            filenameLabel.setText("—");
            counterLabel.setText("No images");
            updateUiState();
            return;
        }
        Path original = imagePaths.get(currentIndex);
        filenameLabel.setText(original.getFileName().toString());
        counterLabel.setText("Image " + (currentIndex + 1) + " of " + imagePaths.size());
        updateUiState();

        Path cached = normalizedCache.get(original);
        if (cached != null && Files.exists(cached)) {
            setViewerImage(cached);
            statusLabel.setText("Ready — " + imagePaths.size() + " images loaded");
            prefetchNeighbours();
            return;
        }

        statusLabel.setText("Loading…");
        int capturedIndex = currentIndex;
        ensureNormalized(original, () -> {
            if (capturedIndex == currentIndex) {
                Path n = normalizedCache.get(original);
                if (n != null && Files.exists(n)) {
                    setViewerImage(n);
                }
                statusLabel.setText("Ready — " + imagePaths.size() + " images loaded");
                prefetchNeighbours();
            }
        });
    }

    private void ensureNormalized(Path original, Runnable onDone) {
        if (normalizedCache.containsKey(original)) {
            if (onDone != null) Platform.runLater(onDone);
            return;
        }
        Path normOut = tempDir.resolve("norm_" + System.currentTimeMillis() + "_"
                + safeName(original.getFileName().toString()));
        var task = normalizationService.normalize(original, normOut);
        task.setOnSucceeded(ev -> {
            Path result = task.getValue();
            if (result != null && Files.exists(result)) {
                normalizedCache.put(original, result);
            }
            if (onDone != null) onDone.run();
        });
        task.setOnFailed(ev -> {
            if (onDone != null) onDone.run();
        });
        Thread t = new Thread(task, "normalize-task");
        t.setDaemon(true);
        t.start();
    }

    private void prefetchNeighbours() {
        if (imagePaths.size() < 2) return;
        int next = Math.floorMod(currentIndex + 1, imagePaths.size());
        int prev = Math.floorMod(currentIndex - 1, imagePaths.size());
        if (next != currentIndex) ensureNormalized(imagePaths.get(next), null);
        if (prev != currentIndex && prev != next) ensureNormalized(imagePaths.get(prev), null);
    }

    private static String safeName(String name) {
        String base = name.toLowerCase(Locale.ROOT);
        int dot = base.lastIndexOf('.');
        if (dot >= 0) base = base.substring(0, dot);
        base = base.replaceAll("[^a-z0-9]+", "_");
        if (base.length() > 40) base = base.substring(0, 40);
        return base + ".jpg";
    }

    private void updateUiState() {
        boolean hasImage = currentIndex >= 0 && !imagePaths.isEmpty();
        detectButton.setDisable(!hasImage);
        prevButton.setDisable(!hasImage);
        nextButton.setDisable(!hasImage);
        boolean hasDetections = lastResult != null && lastResult.detections != null
                && !lastResult.detections.isEmpty();
        readResultButton.setDisable(!hasDetections);
    }

    // ==================== Viewer helpers ====================

    private void setViewerImage(Path p) {
        resetZoom();
        imageView.setImage(null);
        imageView.setImage(new Image(p.toUri().toString(), false));
    }

    private void clearViewerImage() {
        resetZoom();
        imageView.setImage(null);
    }

    private void resetZoom() {
        imageZoom = 1.0;
        if (imageView != null) {
            imageView.setScaleX(1.0);
            imageView.setScaleY(1.0);
        }
    }

    // ==================== Detection (Stage 1) ====================

    private void runDetection() {
        if (currentIndex < 0 || imagePaths.isEmpty()) return;
        Path original = imagePaths.get(currentIndex);
        Path normalized = normalizedCache.get(original);
        if (normalized == null || !Files.exists(normalized)) {
            statusLabel.setText("Image not ready yet — wait a moment");
            return;
        }

        Path outImg = tempDir.resolve("annotated_" + System.currentTimeMillis() + ".jpg");

        detectButton.setDisable(true);
        readResultButton.setDisable(true);
        lastResult = null;
        lastDetectedImage = null;
        selectedDetection = 0;
        statusLabel.setText("Detecting…");

        // Stage 1 only: localize every cassette, draw boxes. No crop path → no classify.
        var task = detectionService.detect(normalized, outImg);
        task.setOnSucceeded(ev -> {
            DetectionResult result = task.getValue();
            if (!result.ok) {
                statusLabel.setText("Detection failed");
                renderError(result.error);
            } else if (result.detections == null || result.detections.isEmpty()) {
                statusLabel.setText("No cassette detected");
                renderEmpty("No cassette found in this image. Try another image or angle.");
            } else {
                lastResult = result;
                lastDetectedImage = normalized;
                selectedDetection = 0;
                statusLabel.setText("Detected " + result.detections.size() + " cassette(s) — press Read All Results");
                if (result.annotated_image != null) {
                    Path annotated = Paths.get(result.annotated_image);
                    if (Files.exists(annotated)) setViewerImage(annotated);
                }
                renderResult();
            }
            updateUiState();
        });
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            statusLabel.setText("Detection error");
            renderError(err == null ? "unknown" : err.getMessage());
            updateUiState();
        });

        Thread t = new Thread(task, "detection-task");
        t.setDaemon(true);
        t.start();
    }

    // ==================== Classification (Stage 2) ====================

    private void runClassification() {
        if (lastDetectedImage == null || !Files.exists(lastDetectedImage)) {
            statusLabel.setText("Run Detect first");
            return;
        }

        Path outImg = tempDir.resolve("annotated2_" + System.currentTimeMillis() + ".jpg");
        Path cropImg = tempDir.resolve("crop_" + System.currentTimeMillis() + ".jpg");

        readResultButton.setDisable(true);
        detectButton.setDisable(true);
        statusLabel.setText("Reading results…");

        // Pass the crop output path → detect.py classifies EACH detection.
        var task = detectionService.detect(lastDetectedImage, outImg, cropImg);
        task.setOnSucceeded(ev -> {
            DetectionResult result = task.getValue();
            if (!result.ok) {
                statusLabel.setText("Classification failed");
                renderError(result.error);
            } else {
                lastResult = result;
                int n = result.detections == null ? 0 : result.detections.size();
                if (selectedDetection < 0 || selectedDetection >= n) selectedDetection = 0;
                statusLabel.setText(summaryStatus(result));
                // Show the selected detection's crop so the cassette is clearly visible.
                if (n > 0) {
                    DetectionResult.Detection d = result.detections.get(selectedDetection);
                    if (d.crop_image != null) {
                        Path crop = Paths.get(d.crop_image);
                        if (Files.exists(crop)) setViewerImage(crop);
                    } else if (result.annotated_image != null) {
                        Path ann = Paths.get(result.annotated_image);
                        if (Files.exists(ann)) setViewerImage(ann);
                    }
                }
                renderResult();
            }
            updateUiState();
        });
        task.setOnFailed(ev -> {
            Throwable err = task.getException();
            statusLabel.setText("Classification error");
            renderError(err == null ? "unknown" : err.getMessage());
            updateUiState();
        });

        Thread t = new Thread(task, "classify-task");
        t.setDaemon(true);
        t.start();
    }

    private String summaryStatus(DetectionResult result) {
        if (result.detections == null || result.detections.isEmpty()) return "No results";
        if (result.detections.size() == 1) {
            DetectionResult.Classification c = result.detections.get(0).classification;
            if (c != null && c.error == null && c.label != null) {
                return "Result: " + c.label.toUpperCase(Locale.ROOT);
            }
            return "Result ready";
        }
        return result.detections.size() + " results ready — tap a card to review";
    }

    // ==================== Results rendering ====================

    private void renderEmpty(String message) {
        if (detailsContainer == null) return;
        Label hint = new Label(message);
        hint.getStyleClass().add("hint-text");
        hint.setWrapText(true);
        VBox box = new VBox(hint);
        box.getStyleClass().add("placeholder-card");
        detailsContainer.getChildren().setAll(box);
    }

    private void renderError(String message) {
        if (detailsContainer == null) return;
        Label heading = new Label("Error");
        heading.getStyleClass().add("error-title");
        Label detail = new Label(message == null ? "unknown error" : message);
        detail.getStyleClass().add("hint-text");
        detail.setWrapText(true);
        VBox box = new VBox(6, heading, detail);
        box.getStyleClass().add("placeholder-card");
        detailsContainer.getChildren().setAll(box);
    }

    /** Build the summary card + one card per detection. */
    private void renderResult() {
        if (detailsContainer == null) return;
        detailsContainer.getChildren().clear();
        if (lastResult == null || lastResult.detections == null || lastResult.detections.isEmpty()) {
            renderEmpty("Press Detect to find the test cassette(s) in this image.");
            return;
        }
        List<DetectionResult.Detection> dets = lastResult.detections;
        if (selectedDetection < 0 || selectedDetection >= dets.size()) selectedDetection = 0;

        detailsContainer.getChildren().add(buildSummaryCard(dets));
        for (int i = 0; i < dets.size(); i++) {
            detailsContainer.getChildren().add(buildDetectionCard(dets.get(i), i));
        }
    }

    /** "Detection Summary" card with overall counts. */
    private VBox buildSummaryCard(List<DetectionResult.Detection> dets) {
        Label heading = new Label("Detection Summary");
        heading.getStyleClass().add("panel-heading");

        boolean anyClassified = dets.stream().anyMatch(d -> d.classification != null);

        VBox card = new VBox(8, heading);
        card.getStyleClass().add("summary-card");

        Label count = new Label(dets.size() + (dets.size() == 1 ? " cassette detected" : " cassettes detected"));
        count.getStyleClass().add("summary-count");
        card.getChildren().add(count);

        if (!anyClassified) {
            Label note = new Label("Press “Read All Results” to classify each one.");
            note.getStyleClass().add("hint-text");
            note.setWrapText(true);
            card.getChildren().add(note);
            return card;
        }

        int pos = 0, neg = 0, inv = 0, unk = 0;
        for (DetectionResult.Detection d : dets) {
            String label = (d.classification != null && d.classification.error == null)
                    ? d.classification.label : null;
            if (label == null) { unk++; continue; }
            switch (label.toLowerCase(Locale.ROOT)) {
                case "positive": pos++; break;
                case "negative": neg++; break;
                case "invalid":  inv++; break;
                default: unk++;
            }
        }
        FlowPane tallies = new FlowPane(10, 6);
        if (pos > 0) tallies.getChildren().add(tally(pos, "Positive", POSITIVE_COLOR));
        if (neg > 0) tallies.getChildren().add(tally(neg, "Negative", NEGATIVE_COLOR));
        if (inv > 0) tallies.getChildren().add(tally(inv, "Invalid", INVALID_COLOR));
        if (unk > 0) tallies.getChildren().add(tally(unk, "Unread", "#6B7F75"));
        card.getChildren().add(tallies);
        return card;
    }

    private HBox tally(int n, String label, String color) {
        Label dot = new Label("●");
        dot.setStyle("-fx-text-fill: " + color + "; -fx-font-size: 11;");
        Label text = new Label(n + " " + label);
        text.getStyleClass().add("tally-text");
        HBox row = new HBox(5, dot, text);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** One card per detected cassette. Selected card is highlighted + expanded. */
    private VBox buildDetectionCard(DetectionResult.Detection d, int idx) {
        boolean selected = (idx == selectedDetection);
        DetectionResult.Classification c = d.classification;

        // Header: "Detection N" + result badge
        Label title = new Label("Detection " + (idx + 1));
        title.getStyleClass().add("det-title");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        HBox header = new HBox(8, title, grow);
        header.setAlignment(Pos.CENTER_LEFT);
        if (c != null && c.error == null && c.label != null) {
            header.getChildren().add(badge(c.label));
        } else if (c != null && c.error != null) {
            header.getChildren().add(badge("error"));
        }

        VBox card = new VBox(10, header);
        card.getStyleClass().add("detection-card");
        if (selected) card.getStyleClass().add("detection-card-selected");
        card.setOnMouseClicked(e -> selectDetection(idx));

        // Crop thumbnail — constrain both axes so the full cassette is always visible
        if (d.crop_image != null) {
            Path crop = Paths.get(d.crop_image);
            if (Files.exists(crop)) {
                ImageView thumb = new ImageView(new Image(crop.toUri().toString(), false));
                thumb.setPreserveRatio(true);
                thumb.setSmooth(true);
                thumb.setFitWidth(selected ? 316 : 200);
                thumb.setFitHeight(selected ? 180 : 110);
                StackPane frame = new StackPane(thumb);
                frame.getStyleClass().add("thumb-frame");
                card.getChildren().add(frame);
            }
        }

        // Always show detection confidence (localization quality).
        card.getChildren().add(compactMetric("Detection confidence",
                String.format(Locale.ROOT, "%.0f%%", d.confidence * 100)));

        if (selected) {
            card.getChildren().add(buildSelectedDetail(d, c));
        } else if (c == null) {
            Label hint = new Label("Not yet classified");
            hint.getStyleClass().add("muted-text");
            card.getChildren().add(hint);
        }
        return card;
    }

    /** Expanded detail shown inside the selected card. */
    private VBox buildSelectedDetail(DetectionResult.Detection d, DetectionResult.Classification c) {
        VBox box = new VBox(6);

        if (c != null && c.error == null && c.label != null) {
            Label conf = new Label(String.format(Locale.ROOT, "Result confidence  %.1f%%", c.confidence * 100));
            conf.getStyleClass().add("metric-strong");
            box.getChildren().add(conf);

            if (c.probs != null) {
                Label probHeading = new Label("Class probabilities");
                probHeading.getStyleClass().add("metric-label");
                VBox.setMargin(probHeading, new Insets(6, 0, 0, 0));
                box.getChildren().add(probHeading);
                for (String cls : new String[]{"positive", "negative", "invalid"}) {
                    Double p = c.probs.get(cls);
                    if (p == null) continue;
                    box.getChildren().add(probBar(cls, p));
                }
            }
            if (c.decision_source != null) {
                Label src = new Label("How: " + c.decision_source);
                src.getStyleClass().add("hint-text");
                src.setWrapText(true);
                VBox.setMargin(src, new Insets(6, 0, 0, 0));
                box.getChildren().add(src);
            }
            if (c.num_lines != null) {
                StringBuilder ln = new StringBuilder("Lines counted: ").append(c.num_lines);
                if (c.cnn_label != null) ln.append("   ·   CNN: ").append(c.cnn_label);
                if (c.classifier_backend != null) ln.append("   ·   model: ").append(c.classifier_backend);
                Label lines = new Label(ln.toString());
                lines.getStyleClass().add("hint-text");
                lines.setWrapText(true);
                box.getChildren().add(lines);

                // Surface the discrepancy when the line counter finds no lines but the
                // verdict is not invalid — the CNN overrode a potential invalid signal.
                // The user should verify the image or re-run the test.
                if (c.num_lines == 0 && !"invalid".equalsIgnoreCase(c.label)) {
                    Label warn = new Label("Warning: no lines detected by the classical counter — verify image quality and test validity.");
                    warn.getStyleClass().add("hint-text");
                    warn.setWrapText(true);
                    warn.setStyle("-fx-text-fill: " + INVALID_COLOR + ";");
                    VBox.setMargin(warn, new Insets(4, 0, 0, 0));
                    box.getChildren().add(warn);
                }
            }
        } else if (c != null && c.error != null) {
            Label warn = new Label("Classifier: " + c.error);
            warn.getStyleClass().add("hint-text");
            warn.setWrapText(true);
            warn.setStyle("-fx-text-fill: " + INVALID_COLOR + ";");
            box.getChildren().add(warn);
        } else {
            Label hint = new Label("Press “Read All Results” to classify this cassette.");
            hint.getStyleClass().add("hint-text");
            hint.setWrapText(true);
            box.getChildren().add(hint);
        }

        // Localization details
        javafx.scene.control.Separator sep = new javafx.scene.control.Separator();
        VBox.setMargin(sep, new Insets(6, 0, 4, 0));
        box.getChildren().add(sep);
        box.getChildren().add(compactMetric("Rotation",
                String.format(Locale.ROOT, "%.1f°", d.rotation_deg)));
        box.getChildren().add(compactMetric("Box size",
                String.format(Locale.ROOT, "%.0f × %.0f px", d.bbox_width, d.bbox_height)));
        return box;
    }

    /** A labelled mini progress bar for one class probability. */
    private HBox probBar(String cls, double p) {
        Label name = new Label(cls);
        name.getStyleClass().add("prob-name");
        name.setMinWidth(58);

        Region fill = new Region();
        fill.getStyleClass().add("prob-fill");
        fill.setStyle("-fx-background-color: " + verdictColor(cls) + ";");
        fill.setPrefWidth(Math.max(2, 150 * Math.max(0, Math.min(1, p))));
        fill.setMinHeight(8);
        fill.setPrefHeight(8);
        StackPane track = new StackPane(fill);
        track.getStyleClass().add("prob-track");
        StackPane.setAlignment(fill, Pos.CENTER_LEFT);
        HBox.setHgrow(track, Priority.ALWAYS);

        Label pct = new Label(String.format(Locale.ROOT, "%.0f%%", p * 100));
        pct.getStyleClass().add("prob-pct");
        pct.setMinWidth(38);

        HBox row = new HBox(8, name, track, pct);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    private Label badge(String label) {
        Label b = new Label(label.toUpperCase(Locale.ROOT));
        b.getStyleClass().addAll("badge", badgeClass(label));
        return b;
    }

    private HBox compactMetric(String label, String value) {
        Label l = new Label(label);
        l.getStyleClass().add("metric-label");
        Region grow = new Region();
        HBox.setHgrow(grow, Priority.ALWAYS);
        Label v = new Label(value);
        v.getStyleClass().add("metric-value");
        HBox row = new HBox(6, l, grow, v);
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** Highlight + expand the chosen detection; show its crop in the main viewer when available. */
    private void selectDetection(int idx) {
        if (lastResult == null || lastResult.detections == null) return;
        if (idx < 0 || idx >= lastResult.detections.size()) return;
        selectedDetection = idx;
        DetectionResult.Detection d = lastResult.detections.get(idx);
        if (d.crop_image != null) {
            Path crop = Paths.get(d.crop_image);
            if (Files.exists(crop)) {
                setViewerImage(crop);
            }
        } else if (lastResult.annotated_image != null) {
            Path ann = Paths.get(lastResult.annotated_image);
            if (Files.exists(ann)) setViewerImage(ann);
        }
        renderResult();
    }

    private String verdictColor(String label) {
        if (label == null) return "#AEAEB2";
        switch (label.toLowerCase(Locale.ROOT)) {
            case "positive": return POSITIVE_COLOR;
            case "negative": return NEGATIVE_COLOR;
            case "invalid":  return INVALID_COLOR;
            default:         return "#AEAEB2";
        }
    }

    private String badgeClass(String label) {
        if (label == null) return "badge-unknown";
        switch (label.toLowerCase(Locale.ROOT)) {
            case "positive": return "badge-positive";
            case "negative": return "badge-negative";
            case "invalid":  return "badge-invalid";
            default:         return "badge-unknown";
        }
    }

    // ==================== Misc ====================

    private void showAlert(String msg) {
        Platform.runLater(() -> {
            Alert a = new Alert(Alert.AlertType.WARNING, msg);
            a.setHeaderText(null);
            a.showAndWait();
        });
    }

    private static String resolvePythonExecutable() {
        String env = System.getenv("LFT_PYTHON");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        return "python";
    }

    public static void main(String[] args) {
        launch(args);
    }
}
