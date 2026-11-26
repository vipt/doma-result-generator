/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.*;
import io.github.domaresult.core.TableGenerator.*;
import io.github.domaresult.eclipse.TableWorkspaceUpdater;
import io.github.domaresult.eclipse.TableWorkspaceUpdater.*;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.eclipse.compare.CompareUI;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.*;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.viewers.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.ScrolledComposite;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.osgi.service.prefs.Preferences;

final class TableGenerationDialog extends TitleAreaDialog {
    private static final int PREVIEW = IDialogConstants.CLIENT_ID + 1;
    private static final int COMPARE = IDialogConstants.CLIENT_ID + 2;
    private static final int DETAILS = IDialogConstants.CLIENT_ID + 3;
    private final IProject project;
    private final List<IPackageFragmentRoot> roots;
    private final TableWorkspaceUpdater updater = new TableWorkspaceUpdater();
    private final Preferences settings;
    private final Set<TableGenerator.Table> selected = new HashSet<>();
    private List<TableGenerator.Table> tables = List.of();
    private ProposedFilesModel filesModel;
    private ProposedFilesPanel proposedFiles;
    private TableConnectionPanel connection;
    private Text schema, entityPackage, daoPackage, mappings, templates, filter;
    private Combo javaFolder, sqlFolder;
    private Button views, listener, qualified;
    private CheckboxTableViewer tableViewer;
    private Label count;
    private TabFolder tabs;
    private String errorDetails;
    private boolean oracleDriver;
    private final AtomicReference<JdbcErrorDetails.Stage> stage = new AtomicReference<>(JdbcErrorDetails.Stage.CONNECTING);

    TableGenerationDialog(Shell parent, IProject project, List<IPackageFragmentRoot> roots) {
        super(parent); this.project = project; this.roots = List.copyOf(roots);
        settings = InstanceScope.INSTANCE.getNode(ConnectionSettingsStore.PLUGIN).node("table-generation").node(ConnectionSettingsStore.hash(project.getName()));
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    @Override protected Control createDialogArea(Composite parent) {
        setTitle("Generate Doma DAO, Entity and SQL");
        setMessage("Choose a connection, load tables, then preview official doma-gen output. Files are saved only after comparison.");
        Composite area = (Composite) super.createDialogArea(parent);
        tabs = new TabFolder(area, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite config = configPage();
        connection = new TableConnectionPanel(config, project, this::connectionChanged,
                message -> setMessage(message, IMessageProvider.WARNING));
        connection.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        schema = fullField(config, "Schema (JDBC pattern; blank = DB default)", settings.get("schema", ""));
        views = check(config, "Include views", settings.getBoolean("views", false));
        javaFolder = folders(config, "Java source folder", settings.get("javaFolder", ""), false);
        sqlFolder = folders(config, "SQL resource folder", settings.get("sqlFolder", ""), true);
        entityPackage = fullField(config, "Entity package", settings.get("entityPackage", "com.example.entity"));
        daoPackage = fullField(config, "DAO package", settings.get("daoPackage", "com.example.dao"));
        listener = check(config, "Generate EntityListener", settings.getBoolean("listener", false));
        qualified = check(config, "Include schema in @Table", settings.getBoolean("qualified", false));
        IFile defaultMappings = project.getFile("name.properties");
        String defaultPath = defaultMappings.exists() && defaultMappings.getLocation() != null ? defaultMappings.getLocation().toOSString() : "";
        mappings = pathField(config, "name.properties (optional)", settings.get("mappings", defaultPath), false, this::editMappings);
        templates = pathField(config, "doma-gen template directory (optional)", settings.get("templates", ""), true);
        Composite templateActions = new Composite(config, SWT.NONE);
        templateActions.setLayout(new GridLayout(2, false));
        templateActions.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1));
        Button editTemplates = new Button(templateActions, SWT.PUSH); editTemplates.setText("Edit templates...");
        editTemplates.addListener(SWT.Selection, e -> editTemplates());
        Label editNote = new Label(templateActions, SWT.WRAP);
        editNote.setText("Edit FTL files or copy missing defaults. Save the template, then Preview again.");
        editNote.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        note(config, "The directory can contain your existing entity.ftl, dao.ftl, entityListener.ftl and *.sql.ftl. Templates run locally with doma-gen. Default entities have private fields and public accessors; a custom entity.ftl controls its own visibility.");
        note(config, "DAO and SQL generation follows doma-gen: primary keys produce selectById.sql; a version column also produces selectByIdAndVersion.sql. Tables without primary keys have no default SELECT SQL. No database data is changed.");
        for (Text text : List.of(entityPackage, daoPackage, mappings, templates)) text.addModifyListener(e -> clearPreview());
        for (Combo combo : List.of(javaFolder, sqlFolder)) combo.addModifyListener(e -> clearPreview());
        for (Button button : List.of(listener, qualified)) button.addListener(SWT.Selection, e -> clearPreview());
        schema.addModifyListener(e -> connectionChanged());
        views.addListener(SWT.Selection, e -> connectionChanged());
        createTablesPage(); createPreviewPage();
        return area;
    }
    private Composite configPage() {
        TabItem tab = new TabItem(tabs, SWT.NONE); tab.setText("Configuration");
        ScrolledComposite scroll = new ScrolledComposite(tabs, SWT.V_SCROLL);
        scroll.setExpandHorizontal(true); scroll.setExpandVertical(true); scroll.setShowFocusedControl(true);
        Composite body = new Composite(scroll, SWT.NONE); body.setLayout(new GridLayout(3, false));
        scroll.setContent(body);
        scroll.addListener(SWT.Resize, e -> scroll.setMinSize(body.computeSize(scroll.getClientArea().width, SWT.DEFAULT)));
        tab.setControl(scroll); return body;
    }
    private Composite page(String title, int columns) {
        TabItem tab = new TabItem(tabs, SWT.NONE); tab.setText(title);
        Composite body = new Composite(tabs, SWT.NONE); body.setLayout(new GridLayout(columns, false));
        tab.setControl(body); return body;
    }
    private void createTablesPage() {
        Composite body = page("Tables", 2);
        Button load = new Button(body, SWT.PUSH); load.setText("Load tables"); load.addListener(SWT.Selection, e -> loadTables());
        count = new Label(body, SWT.NONE); count.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        new Label(body, SWT.NONE).setText("Filter");
        filter = new Text(body, SWT.BORDER | SWT.SEARCH);
        filter.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        tableViewer = CheckboxTableViewer.newCheckList(body, SWT.BORDER | SWT.V_SCROLL | SWT.H_SCROLL | SWT.MULTI);
        tableViewer.getControl().setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true, 2, 1));
        tableViewer.setContentProvider(ArrayContentProvider.getInstance());
        tableViewer.setLabelProvider(new LabelProvider() { @Override public String getText(Object value) { return ((TableGenerator.Table) value).label(); } });
        tableViewer.addFilter(new ViewerFilter() {
            @Override public boolean select(Viewer viewer, Object parent, Object element) {
                return ((TableGenerator.Table) element).label().toLowerCase(Locale.ROOT).contains(filter.getText().toLowerCase(Locale.ROOT));
            }
        });
        tableViewer.addCheckStateListener(e -> {
            if (e.getChecked()) selected.add((TableGenerator.Table) e.getElement()); else selected.remove(e.getElement());
            clearPreview(); updateCount();
        });
        filter.addModifyListener(e -> { tableViewer.refresh(); tableViewer.setCheckedElements(selected.toArray()); });
        Button all = new Button(body, SWT.PUSH); all.setText("Select shown"); all.addListener(SWT.Selection, e -> {
            for (TableItem item : tableViewer.getTable().getItems()) selected.add((TableGenerator.Table) item.getData());
            tableViewer.setCheckedElements(selected.toArray()); clearPreview(); updateCount();
        });
        Button none = new Button(body, SWT.PUSH); none.setText("Clear selection"); none.addListener(SWT.Selection, e -> {
            selected.clear(); tableViewer.setAllChecked(false); clearPreview(); updateCount();
        });
        updateCount();
    }
    private void createPreviewPage() {
        Composite body = page("Proposed files", 1);
        proposedFiles = new ProposedFilesPanel(body, this::updateButtons);
        proposedFiles.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
    }
    private Text fullField(Composite body, String title, String initial) {
        Text field = field(body, title, initial); ((GridData) field.getLayoutData()).horizontalSpan = 2; return field;
    }
    private Text field(Composite body, String title, String initial) {
        new Label(body, SWT.NONE).setText(title); Text field = new Text(body, SWT.BORDER);
        field.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); field.setText(initial); return field;
    }
    private Text pathField(Composite body, String title, String initial, boolean directory) {
        return pathField(body, title, initial, directory, null);
    }
    private Text pathField(Composite body, String title, String initial, boolean directory, Runnable edit) {
        Text field = field(body, title, initial);
        Composite actions = new Composite(body, SWT.NONE);
        GridLayout layout = new GridLayout(2, false); layout.marginWidth = layout.marginHeight = 0; actions.setLayout(layout);
        Button browse = new Button(actions, SWT.PUSH); browse.setText("Browse...");
        browse.addListener(SWT.Selection, e -> {
            String path;
            if (directory) path = new DirectoryDialog(getShell()).open();
            else { FileDialog dialog = new FileDialog(getShell()); dialog.setFilterExtensions(new String[]{"*.properties", "*"}); path = dialog.open(); }
            if (path != null) field.setText(path);
        });
        if (edit != null) { Button button = new Button(actions, SWT.PUSH); button.setText("Edit..."); button.addListener(SWT.Selection, e -> edit.run()); }
        return field;
    }
    private Combo folders(Composite parent, String title, String initial, boolean resource) {
        new Label(parent, SWT.NONE).setText(title);
        Combo combo = new Combo(parent, SWT.READ_ONLY); combo.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        int chosen = 0;
        for (int i = 0; i < roots.size(); i++) {
            String path = roots.get(i).getPath().toPortableString(); combo.add(path);
            if (path.equals(initial) || initial.isEmpty() && path.endsWith(resource ? "/resources" : "/java")) chosen = i;
        }
        combo.select(chosen); return combo;
    }
    private static Button check(Composite parent, String title, boolean value) {
        Button button = new Button(parent, SWT.CHECK); button.setText(title); button.setSelection(value);
        button.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1)); return button;
    }
    private static void note(Composite parent, String text) {
        Label note = new Label(parent, SWT.WRAP); note.setText(text);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, 3, 1); data.widthHint = 700; note.setLayoutData(data);
    }
    private void connectionChanged() {
        tables = List.of(); selected.clear();
        if (tableViewer != null) { tableViewer.setInput(tables); updateCount(); }
        clearPreview();
    }
    private void updateCount() { if (count != null) count.setText(tables.size() + " tables/views; " + selected.size() + " selected"); }
    private void clearPreview() {
        filesModel = null; errorDetails = null;
        if (proposedFiles != null) proposedFiles.setModel(null);
        if (getButton(COMPARE) != null) { getButton(COMPARE).setEnabled(false); getButton(IDialogConstants.OK_ID).setEnabled(false); getButton(DETAILS).setEnabled(false); }
        setErrorMessage(null);
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, PREVIEW, "Preview", true);
        createButton(parent, DETAILS, "Error details...", false).setEnabled(false);
        createButton(parent, COMPARE, "Compare checked changes...", false).setEnabled(false);
        createButton(parent, IDialogConstants.OK_ID, "Generate checked files", false).setEnabled(false);
        createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
    }
    @Override protected void buttonPressed(int id) {
        if (id == PREVIEW) preview();
        else if (id == COMPARE) compare();
        else if (id == DETAILS && errorDetails != null) MessageDialog.openError(getShell(), "Error details", errorDetails);
        else super.buttonPressed(id);
    }
    private void loadTables() {
        connectionChanged();
        try {
            var spec = connection.spec(); String schemaName = schema.getText().trim(); boolean includeViews = views.getSelection();
            List<TableGenerator.Table> result = runDb("Loading tables...", spec, cancel -> {
                try { return spec.run(cancel, db -> { stage.set(JdbcErrorDetails.Stage.READING_TABLES); return new TableGenerator().tables(db, spec.url(), schemaName, includeViews, cancel); }); }
                catch (Exception e) { throw new CompletionException(e); }
            });
            if (result == null) return;
            tables = result; tableViewer.setInput(tables); updateCount(); tabs.setSelection(1);
            setMessage(tables.isEmpty() ? "No tables found. Check the schema pattern and metadata privileges." : "Select tables and choose Preview.");
        } catch (Exception e) { showError(e); }
    }
    private void preview() {
        clearPreview();
        try {
            if (selected.isEmpty()) { tabs.setSelection(1); throw new GenerationException("Load tables and select at least one table."); }
            Options options = new Options(entityPackage.getText().trim(), daoPackage.getText().trim(), listener.getSelection(), qualified.getSelection(), path(mappings), path(templates));
            var spec = connection.spec(); String schemaName = schema.getText().trim(); boolean includeViews = views.getSelection();
            Set<TableGenerator.Table> chosen = Set.copyOf(selected);
            IContainer javaRoot = (IContainer) roots.get(javaFolder.getSelectionIndex()).getResource();
            IContainer sqlRoot = (IContainer) roots.get(sqlFolder.getSelectionIndex()).getResource();
            List<Artifact> output = runDb("Generating table previews with doma-gen...", spec, cancel -> {
                try { return spec.run(cancel, db -> { stage.set(JdbcErrorDetails.Stage.READING_TABLES); return new TableGenerator().generate(db, spec.url(), schemaName, includeViews, chosen, options, cancel); }); }
                catch (Exception e) { throw new CompletionException(e); }
            });
            if (output == null) return;
            List<ProposedFilesModel.Destination> pending = new ArrayList<>();
            for (Artifact artifact : output) {
                IContainer root = artifact.kind() == Kind.SQL ? sqlRoot : javaRoot;
                IFile file = root.getFile(new org.eclipse.core.runtime.Path(artifact.path()));
                pending.add(new ProposedFilesModel.Destination(artifact, file, root));
            }
            stage.set(JdbcErrorDetails.Stage.MERGING_CLASS);
            filesModel = runWork("Preparing checked file previews...", spec.timeout(), cancel -> new ProposedFilesModel(updater, pending, cancel::check));
            proposedFiles.setModel(filesModel); tabs.setSelection(2);
            setMessage("Check files in the left tree and choose Merge/Overwrite. Unchecked files are skipped. Compare checked changes before generating.");
        } catch (Exception e) { showError(e); }
    }
    private static Path path(Text input) { return input.getText().isBlank() ? null : Path.of(input.getText().trim()); }
    private void editMappings() {
        clearPreview();
        try {
            Path path = NameMappingsEditorDialog.edit(getShell(), project, mappings.getText());
            if (path != null) mappings.setText(path.toString());
            setMessage("Select Preview again to use saved type mapping changes. Generated files have not been changed.");
        } catch (IllegalArgumentException e) { setErrorMessage(e.getMessage()); }
    }
    private void editTemplates() {
        clearPreview();
        try {
            Path directory = path(templates);
            if (directory == null) {
                if (project.getLocation() == null) throw new GenerationException("Select a local template directory first.");
                directory = project.getLocation().append("doma-gen-templates").toFile().toPath();
            }
            TemplateEditorDialog dialog = new TemplateEditorDialog(getShell(), directory);
            dialog.open();
            if (java.nio.file.Files.isDirectory(dialog.directory())) templates.setText(dialog.directory().toString());
            setMessage("Select Preview again to use saved template changes. Generated files have not been changed.");
        } catch (IllegalArgumentException e) { setErrorMessage(e.getMessage()); }
    }
    private <T> T runDb(String title, TableConnectionPanel.Spec spec, Function<PreviewExecution.Cancellation, T> work) throws Exception {
        stage.set(JdbcErrorDetails.Stage.CONNECTING);
        oracleDriver = spec.driver().toLowerCase(Locale.ROOT).contains("oracle");
        return runWork(title, spec.timeout(), work);
    }
    private <T> T runWork(String title, int timeout, Function<PreviewExecution.Cancellation, T> work) throws Exception {
        AtomicReference<T> result = new AtomicReference<>();
        ProgressMonitorDialog progress = new ProgressMonitorDialog(getShell());
        progress.run(true, true, monitor -> {
            monitor.beginTask(title, IProgressMonitor.UNKNOWN);
            try { result.set(PreviewExecution.DEFAULT.run(work::apply, monitor::isCanceled, Duration.ofSeconds(timeout))); }
            catch (Exception | LinkageError e) { throw new InvocationTargetException(e); }
            finally { monitor.done(); }
        });
        if (progress.getProgressMonitor().isCanceled()) throw new CancellationException();
        return result.get();
    }
    private void updateButtons() {
        if (getButton(COMPARE) == null) return;
        boolean changes = filesModel != null && filesModel.hasChanges();
        getButton(COMPARE).setEnabled(changes); getButton(IDialogConstants.OK_ID).setEnabled(filesModel != null && filesModel.canApply());
        if (filesModel != null && filesModel.checkedErrors() > 0) setErrorMessage("Checked files have conflicts/errors. Resolve them, uncheck those files, or choose Overwrite and review the differences.");
        else setErrorMessage(null);
    }
    private void compare() {
        if (filesModel == null || !filesModel.hasChanges()) return;
        long revision = filesModel.revision();
        var comparison = new TableCompareInput(filesModel.selectedPlans());
        CompareUI.openCompareDialog(comparison);
        if (comparison.getCompareResult() != null) filesModel.markCompared(revision);
        updateButtons();
    }
    @Override protected void okPressed() {
        if (filesModel == null || !filesModel.canApply()) return;
        try {
            List<Plan> plans = filesModel.selectedPlans();
            updater.apply(plans, new NullProgressMonitor());
            long changed = plans.stream().filter(Plan::changed).count();
            MessageDialog.openInformation(getShell(), "Doma table generator", changed + " files generated/updated.");
            super.okPressed();
        } catch (IOException e) {
            MessageDialog.openWarning(getShell(), "Generation baseline", "Files were saved, but workspace generation baselines could not be saved. Check workspace permissions before the next merge.");
            super.okPressed();
        } catch (CoreException | GenerationException e) { filesModel.invalidateReview(); updateButtons(); showError(e); }
    }
    private void showError(Throwable error) {
        while ((error instanceof InvocationTargetException || error instanceof ExecutionException || error instanceof CompletionException) && error.getCause() != null) error = error.getCause();
        if (error instanceof CancellationException || error instanceof InterruptedException || error instanceof OperationCanceledException) {
            setMessage("Operation canceled. No generated files were saved."); return;
        }
        if (error instanceof TimeoutException) { setErrorMessage("Database operation timed out. Check the connection and driver timeouts, then retry."); return; }
        if (error instanceof GenerationException || error instanceof IllegalArgumentException) { setErrorMessage(error.getMessage()); return; }
        var info = JdbcErrorDetails.describe(error, stage.get(), oracleDriver);
        errorDetails = info.details(); setErrorMessage(info.summary()); getButton(DETAILS).setEnabled(true);
    }
    @Override public boolean close() {
        if (connection != null && !connection.isDisposed()) {
            try {
                settings.put("schema", schema.getText()); settings.putBoolean("views", views.getSelection());
                settings.put("entityPackage", entityPackage.getText()); settings.put("daoPackage", daoPackage.getText());
                settings.put("javaFolder", javaFolder.getText()); settings.put("sqlFolder", sqlFolder.getText());
                settings.putBoolean("listener", listener.getSelection()); settings.putBoolean("qualified", qualified.getSelection());
                settings.put("mappings", mappings.getText()); settings.put("templates", templates.getText()); settings.flush();
            } catch (org.osgi.service.prefs.BackingStoreException e) { MessageDialog.openWarning(getShell(), "Table settings", "Table generation defaults could not be saved."); }
            connection.saveOnClose();
        }
        return super.close();
    }
    @Override protected Point getInitialSize() { return new Point(1000, 820); }
}
