package com.tyron.builder.compiler.resource;

import android.content.Context;
import android.util.Log;

import com.tyron.builder.BuildModule;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogUtils;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.model.Project;
import com.tyron.builder.parser.FileManager;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.tools.Diagnostic;

public class AAPT2Compiler {

    /* ---------------------------------------------------------------------- */
    /*  Constants                                                             */
    /* ---------------------------------------------------------------------- */
    private static final Pattern MANIFEST_PACKAGE =
            Pattern.compile("\\s*(package)\\s*(=)\\s*(\")([a-zA-Z0-9.]+)(\")");
    private static final String TAG = AAPT2Compiler.class.getSimpleName();
    private static final String AAPT2_FILE_NAME = "aapt2";

    /* Log-level constants for log(...) helper */
    private static final int LOG_LEVEL_ERROR   = 0;
    private static final int LOG_LEVEL_WARNING = 1;
    private static final int LOG_LEVEL_INFO    = 2;

    /* ---------------------------------------------------------------------- */
    /*  Fields                                                                */
    /* ---------------------------------------------------------------------- */
    private final Project  mProject;
    private final ILogger  mLogger;
    private final List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();

    /* ---------------------------------------------------------------------- */
    /*  Constructor                                                           */
    /* ---------------------------------------------------------------------- */
    public AAPT2Compiler(ILogger log, Project project) {
        mLogger = log;
        mProject = project;
    }

    /* ---------------------------------------------------------------------- */
    /*  Public API                                                            */
    /* ---------------------------------------------------------------------- */
    public void run() throws IOException, CompilationFailedException {
        long start = System.currentTimeMillis();

        compileProject();
        link();

        Log.d(TAG, "Resource compilation took " + (System.currentTimeMillis() - start) + " ms");
    }

    /* ---------------------------------------------------------------------- */
    /*  Compilation & Linking                                                 */
    /* ---------------------------------------------------------------------- */
    private void compileProject() throws IOException, CompilationFailedException {

        mLogger.debug("Compiling project resources.");

        FileManager.deleteDir(getOutputPath());
        FileManager.deleteDir(new File(mProject.getBuildDirectory(), "gen"));

        File projectOut = createNewFile(getOutputPath(), "project.zip");

        List<String> args = Arrays.asList(
                "compile",
                "--dir", mProject.getResourceDirectory().getAbsolutePath(),
                "-o",    projectOut.getAbsolutePath()
        );

        runAapt2(args, "project");
        compileLibraries();
    }

    private void compileLibraries() throws IOException, CompilationFailedException {

        mLogger.debug("Compiling libraries.");

        for (File libJar : mProject.getLibraries()) {
            File parent = libJar.getParentFile();
            if (parent == null) continue;

            File resFolder = new File(parent, "res");
            if (!resFolder.isDirectory()) continue;

            Log.d(TAG, "Compiling library " + parent.getName());

            File out = createNewFile(getOutputPath(), parent.getName() + ".zip");

            List<String> args = Arrays.asList(
                    "compile",
                    "--dir", resFolder.getAbsolutePath(),
                    "-o",    out.getAbsolutePath()
            );

            runAapt2(args, "library " + parent.getName());
        }
    }

    private void link() throws IOException, CompilationFailedException {

        mLogger.debug("Linking resources");

        List<String> args = new ArrayList<>();
        args.add("link");
        args.add("-I"); args.add(BuildModule.getAndroidJar().getAbsolutePath());
        args.add("--allow-reserved-package-id");
        args.add("--no-version-vectors");
        args.add("--no-version-transitions");
        args.add("--auto-add-overlay");
        args.add("--min-sdk-version"); args.add(String.valueOf(mProject.getMinSdk()));
        args.add("--target-sdk-version"); args.add(String.valueOf(mProject.getTargetSdk()));

        File[] resources = getOutputPath().listFiles();
        if (resources != null) {
            for (File f : resources) {
                if (f.isFile() && f.getName().endsWith(".zip")) {
                    args.add("-R"); args.add(f.getAbsolutePath());
                }
            }
        }

        File gen = new File(mProject.getBuildDirectory(), "gen");
        FileUtils.forceMkdir(gen);
        args.add("--java"); args.add(gen.getAbsolutePath());

        args.add("--manifest"); args.add(mProject.getManifestFile().getAbsolutePath());

        File resApk = new File(getOutputPath().getParentFile(), "generated.apk.res");
        args.add("-o"); args.add(resApk.getAbsolutePath());

        File rTxt = new File(getOutputPath(), "R.txt");
        Files.deleteIfExists(rTxt.toPath());
        FileUtils.touch(rTxt);
        args.add("--output-text-symbols"); args.add(rTxt.getAbsolutePath());

        runAapt2(args, "link");
    }

    /* ---------------------------------------------------------------------- */
    /*  AAPT2 process handling                                                */
    /* ---------------------------------------------------------------------- */
    private void runAapt2(List<String> args, String task) throws CompilationFailedException {
    File aapt2;
    try {
        aapt2 = getAapt2Binary();
    } catch (IOException e) {
        throw new CompilationFailedException("Unable to obtain aapt2 binary: " + e.getMessage(), e);
    }

    List<String> cmd = new ArrayList<>();
    cmd.add(aapt2.getAbsolutePath());
    cmd.addAll(args);

    mLogger.debug("Running AAPT2 " + task + ": " + cmd);

    ProcessBuilder pb = new ProcessBuilder(cmd);
    pb.redirectErrorStream(true);

    clearLogs();
    int exit;

    try {
        Process p = pb.start();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(p.getInputStream(), Charset.defaultCharset()))) {

            String line;
            while ((line = br.readLine()) != null) {
                log(LOG_LEVEL_ERROR, null, -1, line);
            }
        }
        exit = p.waitFor();
    } catch (IOException | InterruptedException e) {
        throw new CompilationFailedException("AAPT2 execution failed: " + e.getMessage(), e);
    }

    LogUtils.log(mDiagnostics, mLogger);

    if (exit != 0) {
        throw new CompilationFailedException("AAPT2 " + task + " failed (exit code " + exit + ")");
    }
}



    /* ---------------------------------------------------------------------- */
    /*  AAPT2 binary discovery / installation                                 */
    /* ---------------------------------------------------------------------- */
    private File getAapt2Binary() throws IOException {
        Context ctx   = BuildModule.getContext();
        File target   = new File(ctx.getFilesDir(), AAPT2_FILE_NAME);

        if (target.exists() && target.canExecute()) {
            return target;
        }

        /* Copy the plain executable from assets */
        try (InputStream in  = ctx.getAssets().open(AAPT2_FILE_NAME);
             FileOutputStream out = new FileOutputStream(target)) {
            IOUtils.copy(in, out);
        }

        /* Make it executable */
        if (!target.setExecutable(true, false)) {
            throw new IOException("Cannot mark aapt2 binary as executable");
        }
        return target;
    }

    /* ---------------------------------------------------------------------- */
    /*  Helpers                                                               */
    /* ---------------------------------------------------------------------- */
    private File getOutputPath() throws IOException {
        File dir = new File(mProject.getBuildDirectory(), "bin/res");
        FileUtils.forceMkdir(dir);
        return dir;
    }

    private File createNewFile(File parent, String name) throws IOException {
        File f = new File(parent, name);
        FileUtils.touch(f);
        return f;
    }

    /* ---------------------------------------------------------------------- */
    /*  Package name helpers (unchanged)                                      */
    /* ---------------------------------------------------------------------- */
    public static String getPackageName(File library) {
        String manifestString;
        try {
            manifestString = FileUtils.readFileToString(library, Charset.defaultCharset());
        } catch (IOException e) {
            return null;
        }
        Matcher matcher = MANIFEST_PACKAGE.matcher(manifestString);
        if (matcher.find()) {
            return matcher.group(4);
        }
        return null;
    }

    /* ---------------------------------------------------------------------- */
    /*  Logging helpers requested by the user                                 */
    /* ---------------------------------------------------------------------- */
    @SuppressWarnings({"unused", "SameParameterValue"})
    private void log(int level, String path, long line, String message) {
        DiagnosticWrapper wrapper = new DiagnosticWrapper();
        switch (level) {
            case LOG_LEVEL_ERROR:
                wrapper.setKind(Diagnostic.Kind.ERROR);
                break;
            case LOG_LEVEL_WARNING:
                wrapper.setKind(Diagnostic.Kind.WARNING);
                break;
            case LOG_LEVEL_INFO:
                wrapper.setKind(Diagnostic.Kind.NOTE);
                break;
            default:
                wrapper.setKind(Diagnostic.Kind.OTHER);
                break;
        }
        if (path != null) {
            wrapper.setSource(new File(path));
        }
        if (line != -1) {
            wrapper.setLineNumber(line);
            wrapper.setEndLine((int) line);
            wrapper.setStartLine((int) line);
        }
        wrapper.setMessage(message);
        mDiagnostics.add(wrapper);
    }

    private void clearLogs() {
        mDiagnostics.clear();
    }
}
