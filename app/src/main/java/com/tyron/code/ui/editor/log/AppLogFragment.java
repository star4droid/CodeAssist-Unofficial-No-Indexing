package com.tyron.code.ui.editor.log;

import static io.github.rosemoe.sora2.text.EditorUtil.getDefaultColorScheme;

//by Wadamzmail 
import androidx.core.content.ContextCompat;
import android.text.style.ForegroundColorSpan;


import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.style.ClickableSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.ColorInt;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.res.ResourcesCompat;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.tyron.builder.log.LogViewModel;
import com.tyron.builder.model.DiagnosticWrapper;
import com.tyron.builder.project.Project;
import com.tyron.code.ApplicationLoader;
import com.tyron.code.ui.editor.impl.FileEditorManagerImpl;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorFragment;
import com.tyron.code.ui.editor.impl.text.rosemoe.CodeEditorView;
import com.tyron.code.ui.editor.scheme.CompiledEditorScheme;
import com.tyron.code.ui.main.MainViewModel;
import com.tyron.code.ui.project.ProjectManager;
import com.tyron.code.ui.theme.ThemeRepository;
import com.tyron.code.util.ApkInstaller;
import com.tyron.common.SharedPreferenceKeys;
import com.tyron.editor.Caret;
import com.tyron.fileeditor.api.FileEditorManager;

import io.github.rosemoe.sora.langs.textmate.theme.TextMateColorScheme;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import io.github.rosemoe.sora2.text.EditorUtil;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.logging.Handler;

import javax.tools.Diagnostic;

import org.codeassist.unofficial.R;

public class AppLogFragment extends Fragment implements ProjectManager.OnProjectOpenListener {

    /* Only used in IDE Logs */
    private Handler mHandler;

    public static AppLogFragment newInstance(int id) {
        AppLogFragment fragment = new AppLogFragment();
        Bundle bundle = new Bundle();
        bundle.putInt("id", id);
        fragment.setArguments(bundle);
        return fragment;
    }

    private CodeEditorView mEditor;
    private FloatingActionButton copyText, actionFab;
    private View mRoot;
    private int id;
    private MainViewModel mMainViewModel;
    private LogViewModel mModel;
    private OnDiagnosticClickListener mListener;

    List<DiagnosticWrapper> diags = new ArrayList<>();
    List<ErrorItem> errors = new ArrayList<>();

    public AppLogFragment() {}

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        id = requireArguments().getInt("id");

        mModel = new ViewModelProvider(requireActivity()).get(LogViewModel.class);
        mMainViewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
    }

    @Override
    public View onCreateView(
            @NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        mRoot = inflater.inflate(R.layout.app_log_fragment, container, false);
        return mRoot;
    }

    @Override
    public void onViewCreated(@NonNull View view, Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        mEditor = view.findViewById(R.id.output_text);
        copyText = view.findViewById(R.id.copy_text);
        actionFab = view.findViewById(R.id.action_fab);
        actionFab.setVisibility(View.GONE);

        actionFab.setOnClickListener(
                v -> {
                    String output = mEditor.getText().toString().trim();

                    if (!output.isEmpty()) {
                        if (output.contains("INSTALL")
                                || output.contains("Generated APK has been saved")) {

                            Project project = ProjectManager.getInstance().getCurrentProject();
                            if (project != null) {
                                File mApkFile = new File(project.getRootFile(), "app/build/bin/signed.apk");
                                ApkInstaller.installApplication(requireContext(), mApkFile.getAbsolutePath());
                            }

                        } else if (output.contains("ERROR")
                                || output.contains("error")
                                || output.contains("failed")
                                || output.contains("Failed")) {

                            errors.clear();

                            for (DiagnosticWrapper diagnostic : diags) {
                                if (diagnostic != null
                                        && diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                                    String error = diagnostic.getMessage(Locale.getDefault());
                                    if (diagnostic.getSource() != null) {
                                        String label = diagnostic.getSource().getName();
                                        label = label + " : line:" + diagnostic.getLineNumber() + " : " + error;
                                        errors.add(new ErrorItem(label, diagnostic.getSource(), diagnostic));
                                    }
                                }
                            }

                            if (!errors.isEmpty()) {
                                ArrayAdapter<ErrorItem> adapter =
                                        new ArrayAdapter<ErrorItem>(
                                                requireContext(),
                                                android.R.layout.select_dialog_item,
                                                android.R.id.text1,
                                                errors) {
                                            @NonNull
                                            @Override
                                            public View getView(
                                                    int position,
                                                    @Nullable View convertView,
                                                    @NonNull ViewGroup parent) {
                                                View view = super.getView(position, convertView, parent);
                                                TextView textView = view.findViewById(android.R.id.text1);
                                                ErrorItem errorItem = getItem(position);
                                                textView.setPadding(12, 12, 12, 12);
                                                textView.setTextSize(12);
                                                textView.setTextColor(0xffcf6679);
                                                textView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                                                        R.drawable.ic_error, 0, 0, 0);
                                                return view;
                                            }
                                        };

                                new MaterialAlertDialogBuilder(requireContext())
                                        .setTitle(getString(R.string.errors_found, String.valueOf(errors.size())))
                                        .setAdapter(adapter, (dialog, which) -> {
                                            ErrorItem selectedErrorItem = errors.get(which);
                                            if (selectedErrorItem.getFile() != null && getContext() != null) {
                                                FileEditorManager manager = FileEditorManagerImpl.getInstance();
                                                manager.openFile(
                                                        requireContext(),
                                                        selectedErrorItem.getFile(),
                                                        it -> {
                                                            if (selectedErrorItem.getDiagnosticWrapper().getLineNumber() > 0
                                                                    && selectedErrorItem.getDiagnosticWrapper().getColumnNumber() > 0) {
                                                                Bundle bundle = new Bundle(it.getFragment().getArguments());
                                                                bundle.putInt(CodeEditorFragment.KEY_LINE,
                                                                        (int) selectedErrorItem.getDiagnosticWrapper().getLineNumber());
                                                                bundle.putInt(CodeEditorFragment.KEY_COLUMN,
                                                                        (int) selectedErrorItem.getDiagnosticWrapper().getColumnNumber());
                                                                it.getFragment().setArguments(bundle);
                                                                manager.openFileEditor(it);
                                                            }
                                                        });
                                            }
                                        })
                                        .show();
                            }
                        }
                    }
                });

        copyText.setOnClickListener(
                v -> {
                    Caret caret = mEditor.getCaret();
                    if (!(caret.getStartLine() == caret.getEndLine()
                            && caret.getStartColumn() == caret.getEndColumn())) {
                        CharSequence textToCopy =
                                mEditor.getContent().subSequence(caret.getStart(), caret.getEnd());
                        String content = textToCopy.toString().trim();
                        if (!content.isEmpty()) {
                            copyContent(content);
                        }
                    } else {
                        String content = mEditor.getText().toString().trim();
                        if (!content.isEmpty()) {
                            copyContent(content);
                        }
                    }
                });

        mEditor.setEditable(false);
        configureEditor(mEditor);

        if (mModel != null) {
            mModel.getLogs(id).observe(getViewLifecycleOwner(), this::process);
        }
    }

    private void copyContent(String content) {
        ClipboardManager clipboard =
                (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setText(content);
        Toast.makeText(requireContext(), R.string.copied_to_clipboard, Toast.LENGTH_LONG).show();
    }

    private void configureEditor(@NonNull CodeEditorView editor) {
        editor.setEditable(false);
        editor.setColorScheme(new CompiledEditorScheme(requireContext()));

        String key = EditorUtil.isDarkMode(requireContext())
                ? ThemeRepository.DEFAULT_NIGHT
                : ThemeRepository.DEFAULT_LIGHT;
        TextMateColorScheme scheme = ThemeRepository.getColorScheme(key);
        if (scheme == null) {
            scheme = getDefaultColorScheme(requireContext());
            ThemeRepository.putColorScheme(key, scheme);
        }
        editor.setColorScheme(scheme);

        // --- Apply desired underline colours for diagnostics ---
        EditorColorScheme colorScheme = editor.getColorScheme();
        colorScheme.setColor(EditorColorScheme.PROBLEM_ERROR,   Color.RED);
        colorScheme.setColor(EditorColorScheme.PROBLEM_WARNING, Color.YELLOW);
        // If your fork supports NOTE, uncomment next line
        // colorScheme.setColor(EditorColorScheme.PROBLEM_NOTE, Color.CYAN);

        editor.setTypefaceText(
                ResourcesCompat.getFont(requireContext(), R.font.jetbrains_mono_regular));
        editor.setEdgeEffectColor(Color.TRANSPARENT);
        editor.setInputType(
                EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                        | EditorInfo.TYPE_CLASS_TEXT
                        | EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE
                        | EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);

        SharedPreferences pref = ApplicationLoader.getDefaultPreferences();
        editor.setWordwrap(true);
        editor.setTextSize(Integer.parseInt(pref.getString(SharedPreferenceKeys.FONT_SIZE, "10")));
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
    }

    private void process(List<DiagnosticWrapper> texts) {
    new android.os.Handler(Looper.getMainLooper()).postDelayed(
            () -> {
                SpannableStringBuilder combinedText = new SpannableStringBuilder();

                if (texts != null) {
                    List<DiagnosticWrapper> diagnostics = new ArrayList<>(texts);
                    this.diags = diagnostics;

                    for (DiagnosticWrapper diagnostic : diagnostics) {
                        if (diagnostic == null) continue;

                        /* -------- KIND -------- */
                        String kindStr = diagnostic.getKind() == null
                                ? "OTHER" : diagnostic.getKind().name();
                        
                        int kindStart = combinedText.length();
                        if(!kindStr.equals("OTHER"))
                        combinedText.append(kindStr).append(": ");
                        combinedText.setSpan(
                                new ForegroundColorSpan(getColor(diagnostic.getKind())),
                                kindStart,
                                combinedText.length(),
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                        /* -------- MESSAGE -------- */
                        String msg = diagnostic.getMessage(Locale.getDefault());
                        int msgStart = combinedText.length();
                        combinedText.append(msg).append("\n");
                        combinedText.setSpan(
                                new ForegroundColorSpan(getColor(diagnostic.getKind())),
                                msgStart,
                                combinedText.length() - 1,   // skip '\n'
                                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

                        /* -------- FAB visibility -------- */
                        if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                            actionFab.setVisibility(View.VISIBLE);
                            actionFab.setImageResource(R.drawable.ic_error);
                        } else {
                            if (msg.contains("Generated APK has been saved")) {
                                actionFab.setVisibility(View.VISIBLE);
                                actionFab.setImageResource(R.drawable.apk_install);
                            } else {
                                actionFab.setVisibility(View.GONE);
                            }
                        }
                    }
                }

                mEditor.setText(combinedText);

                /* scroll to bottom */
                int lastLine = mEditor.getLineCount() - 1;
                mEditor.setSelection(Math.max(lastLine, 0), 0);
            },
            100);
}




    @Override
    public void onProjectOpen(Project project) {}

    @ColorInt
    private int getColor(Diagnostic.Kind kind) {
        if(kind==null) return Color.WHITE;
        switch (kind) {
            case ERROR:
                return Color.RED;
            case MANDATORY_WARNING:
            case WARNING:
                return Color.YELLOW;
            case NOTE:
                return Color.CYAN;
            default:
                return Color.WHITE;
        }
    }
    @ColorInt
    private int getColor(String kind) {
        if(kind==null) return Color.WHITE;
        switch (kind) {
            case "ERROR":
                return Color.RED;
            case "MANDATORY_WARNING":
            case "WARNING":
                return Color.YELLOW;
            case "NOTE":
                return Color.CYAN;
            default:
                return Color.WHITE;
        }
    }

    private void addDiagnosticSpan(SpannableStringBuilder sb, DiagnosticWrapper diagnostic) {
        if (diagnostic.getSource() == null || !diagnostic.getSource().exists()) {
            return;
        }

        ClickableSpan span =
                new ClickableSpan() {
                    @Override
                    public void onClick(@NonNull View view) {
                        if (mListener != null) {
                            mListener.onClick(diagnostic);
                        }
                    }

                    @Override
                    public void updateDrawState(@NonNull TextPaint ds) {
                        super.updateDrawState(ds);
                        ds.setColor(getColor(diagnostic.getKind()));
                        ds.setUnderlineText(false);
                    }
                };

        String label = diagnostic.getSource().getName() + ":" + diagnostic.getLineNumber();
        sb.append("[" + label + "]", span, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    public interface OnDiagnosticClickListener {
        void onClick(DiagnosticWrapper diagnostic);
    }

    public void setOnDiagnosticClickListener(OnDiagnosticClickListener listener) {
        mListener = listener;
    }

    class ErrorItem {
        private final String message;
        private final String path;
        private final DiagnosticWrapper diagnostic;

        ErrorItem(String message, String path, DiagnosticWrapper diagnostic) {
            this.message = message;
            this.path = path;
            this.diagnostic = diagnostic;
        }

        ErrorItem(String message, File path, DiagnosticWrapper diagnostic) {
            this(message, path.getAbsolutePath(), diagnostic);
        }

        String getMessage() {
            return message;
        }

        DiagnosticWrapper getDiagnosticWrapper() {
            return diagnostic;
        }

        String getPath() {
            return path;
        }

        File getFile() {
            return new File(path);
        }
    }
}
 
