/* SPDX-License-Identifier: Apache-2.0 */
package io.github.domaresult.ui;

import io.github.domaresult.core.*;
import io.github.domaresult.core.JdbcDriverCatalog.*;
import io.github.domaresult.eclipse.JdbcDownloadNetwork;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Platform;
import org.eclipse.jface.dialogs.*;
import org.eclipse.jface.window.Window;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Point;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.*;
import org.osgi.framework.FrameworkUtil;

/** An explicit, cancellable download updates connection fields only after every JAR is verified. */
final class JdbcDriverDownloadDialog extends TitleAreaDialog {
    record Selection(List<Path> jars, String driverClass) { String paths() { return String.join(";", jars.stream().map(Path::toString).toList()); } }
    private final String currentJars;
    private final Driver initial;
    private final JdbcDriverDownloads downloads;
    private Combo database, version;
    private Text driverClass, urlExample, artifacts;
    private Label notes;
    private Link license, website;
    private Button keepExtras;
    private Spinner timeout;
    private Selection selection;

    static Selection open(Shell parent, String jars, String driverClass, String url) {
        var dialog = new JdbcDriverDownloadDialog(parent, jars, driverClass, url);
        return dialog.open() == Window.OK ? dialog.selection : null;
    }
    JdbcDriverDownloadDialog(Shell parent, String jars, String driverClass, String url) {
        super(parent); currentJars = jars; initial = JdbcDriverCatalog.infer(url, driverClass);
        Path cache = Platform.getStateLocation(FrameworkUtil.getBundle(JdbcDriverDownloadDialog.class)).append("jdbc-drivers").toFile().toPath();
        downloads = new JdbcDriverDownloads(cache, JdbcDownloadNetwork::open);
        setShellStyle(getShellStyle() | SWT.RESIZE);
    }
    @Override protected Control createDialogArea(Composite parent) {
        setTitle("Download JDBC driver");
        setMessage("Select a database and catalog version. Verified JARs are cached in this workspace and reused by other projects.");
        Composite area = (Composite) super.createDialogArea(parent);
        Composite body = new Composite(area, SWT.NONE); body.setLayout(new GridLayout(2, false)); body.setLayoutData(new GridData(SWT.FILL, SWT.FILL, true, true));
        new Label(body, SWT.NONE).setText("Database"); database = new Combo(body, SWT.READ_ONLY);
        database.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); database.setItems(JdbcDriverCatalog.drivers().stream().map(Driver::name).toArray(String[]::new)); database.select(JdbcDriverCatalog.drivers().indexOf(initial));
        new Label(body, SWT.NONE).setText("Driver version"); version = new Combo(body, SWT.READ_ONLY); version.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        driverClass = readOnly(body, "Driver class"); urlExample = readOnly(body, "JDBC URL format");
        new Label(body, SWT.NONE).setText("JARs to download"); artifacts = new Text(body, SWT.BORDER | SWT.MULTI | SWT.READ_ONLY | SWT.V_SCROLL);
        GridData artifactsData = new GridData(SWT.FILL, SWT.FILL, true, true); artifactsData.heightHint = 95; artifacts.setLayoutData(artifactsData);
        new Label(body, SWT.NONE).setText("License"); license = new Link(body, SWT.NONE); license.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        license.addListener(SWT.Selection, e -> Program.launch(chosenDriver().licenseUrl().toString()));
        new Label(body, SWT.NONE).setText("Vendor"); website = new Link(body, SWT.NONE); website.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false));
        website.setText("<a>Open vendor documentation</a>"); website.addListener(SWT.Selection, e -> Program.launch(chosenDriver().website().toString()));
        new Label(body, SWT.NONE).setText("Download timeout (seconds)"); timeout = new Spinner(body, SWT.BORDER); timeout.setMinimum(5); timeout.setMaximum(600); timeout.setSelection(120);
        keepExtras = new Button(body, SWT.CHECK); keepExtras.setText("Keep additional JARs already listed (such as wallet/authentication libraries)"); keepExtras.setSelection(true);
        keepExtras.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        notes = new Label(body, SWT.WRAP); GridData notesData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1); notesData.widthHint = 700; notes.setLayoutData(notesData);
        Label explanation = new Label(body, SWT.WRAP);
        explanation.setText("Source: Maven Central over HTTPS. Size, SHA-256 and JAR contents are checked. JARs and driver class are applied after success. The JDBC URL, username and password are never sent to the repository. Changing the driver class clears the password as usual. Optional wallet/SSO, Azure/Windows authentication and Db2 license libraries may need manual JARs. Vendor/dependency licenses apply.");
        GridData explanationData = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1); explanationData.widthHint = 700; explanation.setLayoutData(explanationData);
        database.addListener(SWT.Selection, e -> refreshVersions()); version.addListener(SWT.Selection, e -> refreshRelease()); refreshVersions(); return area;
    }
    private Text readOnly(Composite parent, String label) {
        new Label(parent, SWT.NONE).setText(label); Text text = new Text(parent, SWT.BORDER | SWT.READ_ONLY); text.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false)); return text;
    }
    private Driver chosenDriver() { return JdbcDriverCatalog.drivers().get(database.getSelectionIndex()); }
    private Release chosenRelease() { return chosenDriver().releases().get(version.getSelectionIndex()); }
    private void refreshVersions() { version.setItems(chosenDriver().releases().stream().map(Release::label).toArray(String[]::new)); version.select(0); refreshRelease(); }
    private void refreshRelease() {
        Driver driver = chosenDriver(); Release release = chosenRelease(); driverClass.setText(driver.driverClass()); urlExample.setText(driver.urlExample());
        artifacts.setText(String.join("\n", release.jars().stream().map(jar -> jar.group() + ":" + jar.artifact() + ":" + jar.version()).toList()));
        license.setText("<a>" + driver.license() + "</a>"); notes.setText(release.notes() + "\nCache: " + downloads.cache()); notes.getParent().layout(); setErrorMessage(null);
    }
    @Override protected void createButtonsForButtonBar(Composite parent) {
        createButton(parent, IDialogConstants.OK_ID, "Download and use", true); createButton(parent, IDialogConstants.CANCEL_ID, "Cancel", false);
    }
    @Override protected void okPressed() {
        Driver driver = chosenDriver(); Release release = chosenRelease(); int seconds = timeout.getSelection(); boolean extras = keepExtras.getSelection();
        AtomicReference<List<Path>> downloaded = new AtomicReference<>();
        try {
            ProgressMonitorDialog progress = new ProgressMonitorDialog(getShell());
            progress.run(true, true, monitor -> {
                monitor.beginTask("Downloading/checking JDBC JARs...", (int) ((release.bytes() + 1023) / 1024)); int[] worked = {0};
                try {
                    downloaded.set(PreviewExecution.DEFAULT.run(cancel -> downloads.download(driver, release, cancel, update -> {
                        monitor.subTask((update.cached() ? "Cached: " : "Downloading: ") + update.file());
                        int units = (int) ((update.completedBytes() + 1023) / 1024); monitor.worked(Math.max(0, units - worked[0])); worked[0] = units;
                    }), monitor::isCanceled, Duration.ofSeconds(seconds)));
                } catch (Exception | LinkageError e) { throw new InvocationTargetException(e); }
                finally { monitor.done(); }
            });
            if (progress.getProgressMonitor().isCanceled()) throw new CancellationException();
            selection = new Selection(JdbcDriverCatalog.classpath(driver, release, downloaded.get(), currentJars, extras), driver.driverClass()); super.okPressed();
        } catch (Exception e) {
            Throwable failure = e;
            while ((failure instanceof InvocationTargetException || failure instanceof ExecutionException) && failure.getCause() != null) failure = failure.getCause();
            if (failure instanceof CancellationException || failure instanceof InterruptedException) setMessage("Download canceled. Connection fields were not changed. Completed verified JARs may be reused next time.");
            else if (failure instanceof TimeoutException) setErrorMessage("Download timed out. Check Eclipse Network Connections/proxy settings, then retry or use Browse.");
            else if (failure instanceof GenerationException) setErrorMessage(failure.getMessage());
            else setErrorMessage("Cannot download the JDBC driver. Check internet/proxy access, Eclipse runtime TLS certificates and cache permissions, then retry or use Browse. Connection fields were not changed.");
        }
    }
    @Override protected Point getInitialSize() { return new Point(880, 700); }
}
