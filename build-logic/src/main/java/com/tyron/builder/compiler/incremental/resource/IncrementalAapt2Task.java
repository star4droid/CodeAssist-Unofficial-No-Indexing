package com.tyron.builder.compiler.incremental.resource;

import android.content.Context;
import android.util.Log;

import com.tyron.builder.compiler.BuildType;
import com.tyron.builder.compiler.Task;
import com.tyron.builder.exception.CompilationFailedException;
import com.tyron.builder.log.ILogger;
import com.tyron.builder.log.LogUtils;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.builder.project.api.AndroidModule;
import com.tyron.builder.project.api.Module;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.filefilter.FileFilterUtils;
import org.apache.commons.io.filefilter.TrueFileFilter;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.tools.Diagnostic;

public class IncrementalAapt2Task extends Task<AndroidModule> {

    private static final String TAG = "IncrementalAapt2Task";

    /* Log-level constants for log(...) helper */
    private static final int LOG_LEVEL_ERROR   = 0;
    private static final int LOG_LEVEL_WARNING = 1;
    private static final int LOG_LEVEL_INFO    = 2;

    private final boolean mGenerateProtoFormat;
    private final List<DiagnosticWrapper> mDiagnostics = new ArrayList<>();

    public IncrementalAapt2Task(
            Project project, AndroidModule module, ILogger logger, boolean generateProtoFormat) {
        super(project, module, logger);
        mGenerateProtoFormat = generateProtoFormat;
    }

    @Override
    public String getName() {
        return TAG;
    }

    @Override
    public void prepare(BuildType type) throws IOException {}

    /* ---------------------------------------------------------------------- */
    /*  Main entry point                                                      */
    /* ---------------------------------------------------------------------- */
    public void run() throws IOException, CompilationFailedException {
        Map<String, List<File>> filesToCompile =
                getFiles(getModule(), getOutputDirectory(getModule()));
        List<File> librariesToCompile = getLibraries();

        compileProject(filesToCompile);
        compileLibraries(librariesToCompile);
        link();
        updateJavaFiles();
    }

    /* ---------------------------------------------------------------------- */
    /*  Compilation helpers                                                   */
    /* ---------------------------------------------------------------------- */
    private void updateJavaFiles() {
        File genFolder = new File(getModule().getBuildDirectory(), "gen");
        if (genFolder.exists()) {
            FileUtils.iterateFiles(
                            genFolder,
                            FileFilterUtils.suffixFileFilter(".java"),
                            TrueFileFilter.INSTANCE)
                    .forEachRemaining(getModule()::addResourceClass);
        }
    }

    private void compileProject(Map<String, List<File>> files)
            throws IOException, CompilationFailedException {
        List<String> args = new ArrayList<>();
        args.add("compile"); // aapt2 sub-command

        for (String resourceType : files.keySet()) {
            List<File> list = files.get(resourceType);
            if (list != null && !list.isEmpty()) {
                for (File f : list) {
                    args.add(f.getAbsolutePath());
                }
            }
        }

        args.add("-o");
        File outputCompiled = new File(getModule().getBuildDirectory(), "bin/res/compiled");
        if (!outputCompiled.exists() && !outputCompiled.mkdirs()) {
            throw new IOException("Failed to create compiled directory");
        }
        args.add(outputCompiled.getAbsolutePath());

        runAapt2(args, "compile-project");
        copyMapToDir(files);
    }

    private void compileLibraries(List<File> libraries)
            throws IOException, CompilationFailedException {
        Log.d(TAG, "Compiling libraries.");

        File output = new File(getModule().getBuildDirectory(), "bin/res");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Failed to create resource output directory");
        }

        for (File libJar : libraries) {
            File parent = libJar.getParentFile();
            if (parent == null) continue;

            File resDir = new File(parent, "res");
            if (!resDir.isDirectory()) continue;

            List<String> args = Arrays.asList(
                    "compile",
                    "--dir", resDir.getAbsolutePath(),
                    "-o",    createNewFile(output, parent.getName() + ".zip").getAbsolutePath()
            );

            runAapt2(args, "library " + parent.getName());
        }
    }

    private void link() throws IOException, CompilationFailedException {
        Log.d(TAG, "Linking resources");

        List<String> args = new ArrayList<>();
        args.add("link");
        args.add("-I");
        args.add(getModule().getBootstrapJarFile().getAbsolutePath());
        File files = new File(getOutputPath(), "compiled");
        args.add("--allow-reserved-package-id");
        args.add("--no-version-vectors");
        args.add("--no-version-transitions");
        args.add("--auto-add-overlay");
        args.add("--min-sdk-version");
        args.add(String.valueOf(getModule().getMinSdk()));
        args.add("--target-sdk-version");
        args.add(String.valueOf(getModule().getTargetSdk()));
        args.add("--proguard");
        args.add(createNewFile(
                new File(getModule().getBuildDirectory(), "bin/res"),
                "generated-rules.txt").getAbsolutePath());

        /* Compiled library ZIPs */
        File[] libraryResources = getOutputPath().listFiles();
        if (libraryResources != null) {
            for (File f : libraryResources) {
                if (f.isFile() && f.getName().endsWith(".zip")) {
                    args.add("-R");
                    args.add(f.getAbsolutePath());
                }
            }
        }

        /* Compiled .flat files */
        File[] flatFiles = files.listFiles();
        if (flatFiles != null) {
            for (File f : flatFiles) {
                if (f.isFile() && f.getName().endsWith(".flat")) {
                    args.add("-R");
                    args.add(f.getAbsolutePath());
                }
            }
        }

        /* Java / R source output */
        File gen = new File(getModule().getBuildDirectory(), "gen");
        if (!gen.exists() && !gen.mkdirs()) {
            throw new CompilationFailedException("Failed to create gen folder");
        }
        args.add("--java");
        args.add(gen.getAbsolutePath());

        /* Manifest */
        File mergedManifest = new File(getModule().getBuildDirectory(), "bin/AndroidManifest.xml");
        if (!mergedManifest.exists()) {
            throw new IOException("Unable to get merged manifest file");
        }
        args.add("--manifest");
        args.add(mergedManifest.getAbsolutePath());

        /* Output APK/zip */
        if (mGenerateProtoFormat) {
            args.add("-o");
            args.add(getOutputPath().getParent() + "/proto-format.zip");
            args.add("--proto-format");
        } else {
            args.add("-o");
            args.add(getOutputPath().getParent() + "/generated.apk.res");
        }

        /* R.txt */
        File rTxt = new File(getOutputPath(), "R.txt");
        Files.deleteIfExists(rTxt.toPath());
        if (!rTxt.createNewFile()) {
            throw new IOException("Unable to create R.txt file");
        }
        args.add("--output-text-symbols");
        args.add(rTxt.getAbsolutePath());

        /* Library assets */
        for (File library : getModule().getLibraries()) {
            File parent = library.getParentFile();
            if (parent == null) continue;
            File assetsDir = new File(parent, "assets");
            if (assetsDir.exists()) {
                args.add("-A");
                args.add(assetsDir.getAbsolutePath());
            }
        }

        /* Module assets */
        if (getModule().getAssetsDirectory().exists()) {
            args.add("-A");
            args.add(getModule().getAssetsDirectory().getAbsolutePath());
        }

        runAapt2(args, "link");
    }

    /* ---------------------------------------------------------------------- */
    /*  AAPT2 process wrapper                                                 */
    /* ---------------------------------------------------------------------- */
    private void runAapt2(List<String> args, String task) throws CompilationFailedException {
        File aapt2;
        try {
            aapt2 = getAapt2Binary();
        } catch (IOException e) {
            throw new CompilationFailedException("Unable to obtain aapt2 binary", e);
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(aapt2.getAbsolutePath());
        cmd.addAll(cmd);

        getLogger().debug("Running AAPT2 " + task + ": " + cmd);

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
            throw new CompilationFailedException("AAPT2 " + task + " execution failed", e);
        }

        LogUtils.log(mDiagnostics, getLogger());
        if (exit != 0) {
            throw new CompilationFailedException("AAPT2 " + task + " failed (exit code " + exit + ")");
        }
    }

    /* ---------------------------------------------------------------------- */
    /*  AAPT2 binary discovery / installation                                 */
    /* ---------------------------------------------------------------------- */
    private File getAapt2Binary() throws IOException {
    File target = new File(BuildModule.getContext().getFilesDir(), "aapt2");

    if (target.exists() && target.canExecute()) return target;

    // copy from assets
    try (InputStream in  = BuildModule.getContext().getAssets().open("aapt2");
         FileOutputStream out = new FileOutputStream(target)) {
        IOUtils.copy(in, out);
    }

    // make it executable for everyone
    if (!target.setExecutable(true, false))
        throw new IOException("Cannot mark aapt2 as executable");

    // extra safety: try chmod 755 via shell
    try {
        Runtime.getRuntime().exec("chmod 755 " + target.getAbsolutePath()).waitFor();
    } catch (Exception ignore) {}

    if (!target.canExecute())
        throw new IOException("aapt2 still not executable");

    return target;
}

    /* ---------------------------------------------------------------------- */
    /*  Logging helpers                                                       */
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

    /* ---------------------------------------------------------------------- */
    /*  Rest of the utility methods (unchanged)                               */
    /* ---------------------------------------------------------------------- */

    private List<File> getLibraries() throws IOException {
        File resDir = new File(getModule().getBuildDirectory(), "bin/res");
        if (!resDir.exists() && !resDir.mkdirs()) {
            throw new IOException("Failed to create resource directory");
        }

        List<File> libraries = new ArrayList<>();
        for (File library : getModule().getLibraries()) {
            File parent = library.getParentFile();
            if (parent != null) {
                if (!new File(parent, "res").exists()) continue;
                File check = new File(resDir, parent.getName() + ".zip");
                if (!check.exists()) libraries.add(library);
            }
        }
        return libraries;
    }

    private File createNewFile(File parent, String name) throws IOException {
        File f = new File(parent, name);
        if (!parent.exists() && !parent.mkdirs()) {
            throw new IOException("Unable to create directories");
        }
        if (!f.exists() && !f.createNewFile()) {
            throw new IOException("Unable to create file " + name);
        }
        return f;
    }

    public static File getOutputDirectory(Module module) throws IOException {
        File intermediate = new File(module.getBuildDirectory(), "intermediate");
        if (!intermediate.exists() && !intermediate.mkdirs()) {
            throw new IOException("Failed to create intermediate directory");
        }
        File resources = new File(intermediate, "resources");
        if (!resources.exists() && !resources.mkdirs()) {
            throw new IOException("Failed to create resource directory");
        }
        return resources;
    }

    private File getOutputPath() throws IOException {
        File file = new File(getModule().getBuildDirectory(), "bin/res");
        if (!file.exists() && !file.mkdirs()) {
            throw new IOException("Failed to get resource directory");
        }
        return file;
    }

    /* --------------  Static utility methods kept verbatim  ---------------- */
    public static Map<String, List<File>> getFiles(AndroidModule module, File cachedDirectory)
            throws IOException {
        Map<String, List<ResourceFile>> newFiles = findFiles(module.getAndroidResourcesDirectory());
        Map<String, List<ResourceFile>> oldFiles = findFiles(cachedDirectory);
        Map<String, List<File>> filesToCompile = new HashMap<>();

        for (String resourceType : newFiles.keySet()) {
            if (!oldFiles.containsKey(resourceType)) {
                List<ResourceFile> files = newFiles.get(resourceType);
                if (files != null) addToMapList(filesToCompile, resourceType, files);
                continue;
            }
            List<ResourceFile> newRes = newFiles.get(resourceType);
            List<ResourceFile> oldRes = oldFiles.get(resourceType);
            if (newRes == null) newRes = Collections.emptyList();
            if (oldRes == null) oldRes = Collections.emptyList();
            addToMapList(filesToCompile, resourceType, getModifiedFiles(newRes, oldRes));
        }

        for (String resourceType : oldFiles.keySet()) {
            if (!newFiles.containsKey(resourceType)) {
                List<ResourceFile> files = oldFiles.get(resourceType);
                if (files != null) {
                    for (File f : files) {
                        if (!f.delete()) throw new IOException("Failed to delete file " + f);
                    }
                }
            }
        }
        return filesToCompile;
    }

    public static void addToMapList(
            Map<String, List<File>> map, String key, List<ResourceFile> values) {
        List<File> list = map.computeIfAbsent(key, k -> new ArrayList<>());
        list.addAll(values);
    }

    private void copyMapToDir(Map<String, List<File>> map) throws IOException {
        File output = new File(getModule().getBuildDirectory(), "intermediate/resources");
        if (!output.exists() && !output.mkdirs()) {
            throw new IOException("Failed to create intermediate directory");
        }
        for (String type : map.keySet()) {
            File dir = new File(output, type);
            if (!dir.exists() && !dir.mkdir()) {
                throw new IOException("Failed to create output directory for " + dir);
            }
            for (File f : map.get(type)) {
                File dest = new File(dir, f.getName());
                if (dest.exists()) FileUtils.deleteQuietly(dest);
                FileUtils.copyFileToDirectory(f, dir, false);
            }
        }
    }

    public static List<ResourceFile> getModifiedFiles(
            List<ResourceFile> newFiles, List<ResourceFile> oldFiles) throws IOException {
        List<ResourceFile> modified = new ArrayList<>();
        for (ResourceFile nf : newFiles) {
            if (!oldFiles.contains(nf)) {
                modified.add(nf);
            } else {
                File of = oldFiles.get(oldFiles.indexOf(nf));
                if (contentModified(nf, of)) {
                    modified.add(nf);
                    if (!of.delete()) throw new IOException("Failed to delete " + of.getName());
                }
                oldFiles.remove(of);
            }
        }
        for (ResourceFile rf : oldFiles) {
            if (!rf.delete()) throw new IOException("Failed to delete old file " + rf);
        }
        return modified;
    }

    private static boolean contentModified(File newFile, File oldFile) {
        if (!oldFile.exists() || !newFile.exists()) return true;
        if (newFile.length() != oldFile.length()) return true;
        return newFile.lastModified() > oldFile.lastModified();
    }

    public static Map<String, List<ResourceFile>> findFiles(File file) {
        File[] children = file.listFiles();
        if (children == null) return Collections.emptyMap();
        Map<String, List<ResourceFile>> map = new HashMap<>();
        for (File child : children) {
            if (!child.isDirectory()) continue;
            File[] files = child.listFiles();
            List<File> list = (files == null) ? Collections.emptyList() : Arrays.asList(files);
            map.put(child.getName(),
                    list.stream().map(ResourceFile::fromFile).collect(Collectors.toList()));
        }
        return map;
    }
}
 
