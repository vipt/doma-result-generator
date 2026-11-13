/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.EntityGenerator;
import io.github.domaresult.core.GenerationException;
import io.github.domaresult.core.JavaNames;
import io.github.domaresult.core.JdbcDriverLoader;
import io.github.domaresult.core.JdbcErrorDetails;
import io.github.domaresult.core.JdbcMetadataReader;
import io.github.domaresult.core.MetadataException;
import io.github.domaresult.core.SqlInput;
import io.github.domaresult.core.SourceUpdatePlan;
import io.github.domaresult.core.TypeNameMappings;
import io.github.domaresult.eclipse.WorkspaceClassUpdater;
import io.github.domaresult.merge.EntityMerger;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.compare.CompareUI;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jface.dialogs.IDialogConstants;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.dialogs.TitleAreaDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.events.ModifyListener;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.TabFolder;
import org.eclipse.swt.widgets.TabItem;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IWorkbenchWindow;
import org.eclipse.ui.ide.IDE;

final class GenerateDialog extends TitleAreaDialog {
    private static final int PREVIEW = IDialogConstants.CLIENT_ID + 1;
    private static final int COMPARE = IDialogConstants.CLIENT_ID + 2;
    private static final int ERROR_DETAILS = IDialogConstants.CLIENT_ID + 3;
    private final IWorkbenchWindow window;
    private final IFile sqlFile;
    private final List<IPackageFragmentRoot> roots;
    private final String initialSql;
    private Text jars, driverClass, jdbcUrl, user, password, packageName, className, typeMappingsFile;
    private Combo sourceFolder;
    private StyledText sql, preview;
    private TabFolder tabs;
    private String generated;
    private String lastJdbcError;
    private ICompilationUnit previewTarget;
    private SourceUpdatePlan updatePlan;
    private final WorkspaceClassUpdater updater = new WorkspaceClassUpdater();

    GenerateDialog(Shell parent, IWorkbenchWindow window, IFile file,
            List<IPackageFragmentRoot> roots, String initialSql) {
        super(parent);
        this.window = window;
        this.sqlFile = file;
        this.roots = roots;
        this.initialSql = initialSql;
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        setTitle("Generate Doma Result Class");
        setMessage("Configure JDBC, check the metadata SQL, then preview the result class.");
        Composite area = (Composite) super.createDialogArea(parent);
        tabs = new TabFolder(area, SWT.NONE);
        tabs.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        Composite config = page("Configuration", 3);
        jars = field(config, "Driver JARs", "", false);
        Button browse = new Button(config, SWT.PUSH);
        browse.setText("Browse...");
        browse.addListener(SWT.Selection, e -> {
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN | SWT.MULTI);
            dialog.setFilterExtensions(new String[] { "*.jar" });
            if (dialog.open() != null) {
                jars.setText(String.join(";", Arrays.stream(dialog.getFileNames())
                        .map(name -> new File(dialog.getFilterPath(), name).getAbsolutePath()).toList()));
            }
        });
        note(config, "Multiple driver/dependency JARs: separate paths with semicolons.");
        driverClass = fullField(config, "Driver class", "", false);
        jdbcUrl = fullField(config, "JDBC URL", "", false);
        user = fullField(config, "Username", "", false);
        password = fullField(config, "Password", "", true);
        note(config, "Connection settings are used for this dialog only and are not saved.");
        IFile defaults = sqlFile.getProject().getFile("name.properties");
        String defaultFile = defaults.exists() && defaults.getLocation() != null ? defaults.getLocation().toOSString() : "";
        typeMappingsFile = field(config, "Type mappings (optional)", defaultFile, false);
        Button browseMappings = new Button(config, SWT.PUSH);
        browseMappings.setText("Browse...");
        browseMappings.addListener(SWT.Selection, e -> {
            FileDialog dialog = new FileDialog(getShell(), SWT.OPEN);
            dialog.setFilterExtensions(new String[] { "*.properties", "*" });
            String selected = dialog.open();
            if (selected != null) typeMappingsFile.setText(selected);
        });
        note(config, "UTF-8 name.properties rules match generated camelCase property names; the first match overrides JDBC types.");
        new Label(config, SWT.NONE).setText("Source folder");
        sourceFolder = new Combo(config, SWT.READ_ONLY);
        sourceFolder.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        roots.forEach(root -> sourceFolder.add(root.getPath().toPortableString()));
        sourceFolder.select(0);
        packageName = fullField(config, "Package", "com.example.dto", false);
        String base = JavaNames.fieldName(sqlFile.getName().replaceFirst("\\.sql$", ""));
        className = fullField(config, "Class name", Character.toUpperCase(base.charAt(0)) + base.substring(1) + "Result", false);
        Composite sqlPage = page("Metadata SQL", 1);
        note(sqlPage, "Simple %expand directives are converted to * or alias.* on Preview. Bind test literals can remain. Replace conditions/loops with concrete SQL. The original file is unchanged.");
        sql = new StyledText(sqlPage, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL);
        sql.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        sql.setText(initialSql);
        Composite previewPage = page("Proposed Java", 1);
        preview = new StyledText(previewPage, SWT.BORDER | SWT.MULTI | SWT.H_SCROLL | SWT.V_SCROLL | SWT.READ_ONLY);
        preview.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        ModifyListener invalidate = e -> invalidatePreview();
        for (Text input : List.of(jars, driverClass, jdbcUrl, user, password, packageName, className, typeMappingsFile)) input.addModifyListener(invalidate);
        sql.addModifyListener(invalidate);
        sourceFolder.addModifyListener(invalidate);
        return area;
    }

    private Composite page(String name, int columns) {
        TabItem tab = new TabItem(tabs, SWT.NONE);
        tab.setText(name);
        Composite body = new Composite(tabs, SWT.NONE);
        body.setLayout(new GridLayout(columns, false));
        tab.setControl(body);
        return body;
    }

    private Text field(Composite body, String label, String value, boolean secret) {
        new Label(body, SWT.NONE).setText(label);
        Text text = new Text(body, SWT.BORDER | (secret ? SWT.PASSWORD : SWT.NONE));
        text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        text.setText(value);
        return text;
    }

    private Text fullField(Composite body, String label, String value, boolean secret) {
        Text text = field(body, label, value, secret);
        ((GridData) text.getLayoutData()).horizontalSpan = 2;
        return text;
    }

    private void note(Composite body, String message) {
        Label label = new Label(body, SWT.WRAP);
        label.setText(message);
        GridData data = new GridData(SWT.FILL, SWT.CENTER, true, false, ((GridLayout) body.getLayout()).numColumns, 1);
        data.widthHint = 640;
        label.setLayoutData(data);
    }

    private void invalidatePreview() {
        generated = null;
        previewTarget = null;
        updatePlan = null;
        lastJdbcError = null;
        if (getButton(IDialogConstants.OK_ID) != null) {
            getButton(IDialogConstants.OK_ID).setEnabled(false);
            getButton(IDialogConstants.OK_ID).setText("Generate");
            getButton(COMPARE).setEnabled(false);
            getButton(ERROR_DETAILS).setEnabled(false);
        }
        if (preview != null) preview.setText("");
        setErrorMessage(null);
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, PREVIEW, "Preview", true);
        createButton(parent, ERROR_DETAILS, "Error details...", false).setEnabled(false);
        createButton(parent, COMPARE, "Compare changes...", false).setEnabled(false);
        createButton(parent, IDialogConstants.OK_ID, "Generate", false).setEnabled(false);
        createButton(parent, IDialogConstants.CANCEL_ID, IDialogConstants.CANCEL_LABEL, false);
    }

    @Override
    protected void buttonPressed(int id) {
        if (id == PREVIEW) loadPreview();
        else if (id == COMPARE) showChanges();
        else if (id == ERROR_DETAILS && lastJdbcError != null) MessageDialog.openError(getShell(), "JDBC error details", lastJdbcError);
        else super.buttonPressed(id);
    }

    private void loadPreview() {
        invalidatePreview();
        AtomicReference<JdbcErrorDetails.Stage> stage = new AtomicReference<>(JdbcErrorDetails.Stage.LOADING_DRIVER);
        try {
            String pkg = packageName.getText().trim();
            String name = className.getText().trim();
            JavaNames.validateTarget(pkg, name);
            ICompilationUnit target = roots.get(sourceFolder.getSelectionIndex()).getPackageFragment(pkg).getCompilationUnit(name + ".java");
            boolean existing = target.exists();
            String before;
            long stamp;
            if (existing) {
                var snapshot = updater.snapshot(target);
                stamp = snapshot.modificationStamp();
                before = snapshot.source();
            } else { before = null; stamp = IResource.NULL_STAMP; }
            if (driverClass.getText().isBlank() || jdbcUrl.getText().isBlank()) {
                throw new IllegalArgumentException("Enter the JDBC driver class and JDBC URL.");
            }
            String query = SqlInput.validate(sql.getText());
            if (!query.equals(sql.getText())) sql.setText(query);
            List<Path> paths = Arrays.stream(jars.getText().split(";"))
                    .filter(path -> !path.isBlank()).map(String::trim).map(Path::of).toList();
            String driver = driverClass.getText().trim();
            String url = jdbcUrl.getText().trim();
            String username = user.getText();
            String secret = password.getText();
            Path mappingPath = typeMappingsFile.getText().isBlank() ? null : Path.of(typeMappingsFile.getText().trim());
            AtomicReference<String> result = new AtomicReference<>();
            AtomicReference<List<String>> notes = new AtomicReference<>(List.of());
            new ProgressMonitorDialog(getShell()).run(true, false, monitor -> {
                monitor.beginTask("Reading SELECT column metadata...", -1);
                try {
                    TypeNameMappings mappings;
                    try {
                        mappings = mappingPath == null ? TypeNameMappings.empty() : TypeNameMappings.read(mappingPath);
                    } catch (IOException e) {
                        throw new GenerationException("Cannot read the type mappings file. Check its path, permissions and UTF-8 encoding.");
                    }
                    try (JdbcDriverLoader loader = new JdbcDriverLoader(paths)) {
                        Thread thread = Thread.currentThread();
                        ClassLoader previous = thread.getContextClassLoader();
                        try {
                            thread.setContextClassLoader(loader);
                            stage.set(JdbcErrorDetails.Stage.CONNECTING);
                            try (var connection = loader.connect(driver, url, username, secret)) {
                                String fresh = new EntityGenerator().generate(pkg, name,
                                        new JdbcMetadataReader().read(connection, query, pkg + "." + name, mappings, stage::set));
                                result.set(fresh);
                                stage.set(JdbcErrorDetails.Stage.CLOSING_CONNECTION);
                            }
                        } finally { thread.setContextClassLoader(previous); }
                        stage.set(JdbcErrorDetails.Stage.CLOSING_DRIVER);
                    }
                    if (existing) {
                        EntityMerger.Result merged = new EntityMerger().merge(before, result.get(), target.getJavaProject().getOptions(true));
                        if (!before.equals(merged.source())) updater.validate(target, merged.source(), monitor);
                        result.set(merged.source());
                        notes.set(merged.notes());
                    }
                } catch (Exception | LinkageError e) {
                    throw new InvocationTargetException(e);
                } finally { monitor.done(); }
            });
            generated = result.get();
            previewTarget = target;
            updatePlan = existing ? new SourceUpdatePlan(before, generated, stamp) : null;
            preview.setText(generated);
            tabs.setSelection(2);
            getButton(IDialogConstants.OK_ID).setText(existing ? "Update" : "Generate");
            boolean changes = !existing || updatePlan.hasChanges();
            getButton(COMPARE).setEnabled(existing && changes);
            if (existing && changes) {
                setMessage((notes.get().isEmpty() ? "" : String.join(" ", notes.get()) + " ")
                        + "Review the comparison, then select Update. Custom members and comments are retained.");
                showChanges();
            } else {
                getButton(IDialogConstants.OK_ID).setEnabled(changes);
                setMessage(existing ? "No generated-member changes are needed."
                        : "Review the proposed class, then select Generate.");
            }
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause();
            // Driver messages may include connection credentials: show no raw driver text.
            if (cause instanceof GenerationException || cause instanceof MetadataException) setErrorMessage(cause.getMessage());
            else {
                JdbcErrorDetails.Info info = JdbcErrorDetails.describe(cause, stage.get(), driverClass.getText().trim().startsWith("oracle."));
                lastJdbcError = info.details();
                setErrorMessage(info.summary());
                getButton(ERROR_DETAILS).setEnabled(true);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            setErrorMessage("Metadata lookup interrupted.");
        } catch (IllegalArgumentException e) {
            setErrorMessage(e.getMessage());
        } catch (CoreException e) {
            setErrorMessage("Cannot read the target Java class. Check its saved state, encoding and permissions.");
        }
    }

    private void showChanges() {
        if (updatePlan == null || !updatePlan.hasChanges()) return;
        SourceCompareInput comparison = new SourceCompareInput(previewTarget.getElementName(), updatePlan.before(), updatePlan.after());
        CompareUI.openCompareDialog(comparison);
        getButton(IDialogConstants.OK_ID).setEnabled(comparison.getCompareResult() != null);
    }

    @Override
    protected void okPressed() {
        if (generated == null || previewTarget == null) return;
        try {
            ICompilationUnit unit;
            if (updatePlan != null) {
                updater.apply(previewTarget, updatePlan);
                unit = previewTarget;
            } else {
                IPackageFragmentRoot root = roots.get(sourceFolder.getSelectionIndex());
                var pkg = root.createPackageFragment(packageName.getText().trim(), true, new NullProgressMonitor());
                String fileName = className.getText().trim() + ".java";
                if (pkg.getCompilationUnit(fileName).exists()) {
                    throw new GenerationException("The class was created after Preview. Refresh Preview to review an update.");
                }
                unit = pkg.createCompilationUnit(fileName, generated, false, new NullProgressMonitor());
            }
            try {
                IDE.openEditor(window.getActivePage(), (IFile) unit.getResource());
            } catch (Exception e) {
                MessageDialog.openInformation(getShell(), "Doma Result Generator", "Class created at "
                        + unit.getPath().toPortableString() + ". Open it from the Package Explorer.");
            }
            super.okPressed();
        } catch (GenerationException e) {
            setErrorMessage(e.getMessage());
            getButton(IDialogConstants.OK_ID).setEnabled(false);
        } catch (Exception e) {
            setErrorMessage("Cannot save class (" + e.getClass().getSimpleName() + "). Check the source folder and file permissions.");
        }
    }

    @Override
    protected Point getInitialSize() { return new Point(900, 720); }

    @Override
    public boolean close() {
        if (password != null && !password.isDisposed()) password.setText("");
        return super.close();
    }
}
