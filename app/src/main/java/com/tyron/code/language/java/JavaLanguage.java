package com.tyron.code.language.java;

import android.os.Bundle;
import android.util.Log;
import androidx.annotation.NonNull;
import com.tyron.code.analyzer.BaseTextmateAnalyzer;
import com.tyron.code.language.CompletionItemWrapper;
import com.tyron.code.language.EditorFormatter;
import com.tyron.completion.model.CompletionItem;
import com.tyron.completion.model.CompletionList;
import com.tyron.editor.Editor;
import io.github.rosemoe.editor.langs.java.JavaTextTokenizer;
import io.github.rosemoe.editor.langs.java.Tokens;
import io.github.rosemoe.sora.lang.Language;
import io.github.rosemoe.sora.lang.analysis.AnalyzeManager;
import io.github.rosemoe.sora.lang.completion.CompletionCancelledException;
import io.github.rosemoe.sora.lang.completion.CompletionHelper;
import io.github.rosemoe.sora.lang.completion.CompletionPublisher;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandleResult;
import io.github.rosemoe.sora.lang.smartEnter.NewlineHandler;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.text.TextUtils;
import io.github.rosemoe.sora.util.MyCharacter;
import io.github.rosemoe.sora.widget.SymbolPairMatch;
import java.io.ByteArrayInputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import io.github.rosemoe.sora.text.TextRange;
import io.github.rosemoe.sora.lang.format.AsyncFormatter;
import io.github.rosemoe.sora.lang.format.Formatter;
import io.github.rosemoe.sora.text.Content;
import androidx.annotation.Nullable;
import io.github.rosemoe.sora.lang.styling.Styles;
import io.github.rosemoe.sora.text.CharPosition;
import io.github.rosemoe.sora.text.Content;
//import com.google.googlejavaformat.java.Formatter;
import com.google.googlejavaformat.java.FormatterException;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import com.tyron.code.language.LanguageManager;

public class JavaLanguage implements Language, EditorFormatter {

  private final Editor mEditor;

  private BaseTextmateAnalyzer mAnalyzer;
  private final TextMateLanguage delegate;
  private static final String GRAMMAR_NAME = "java.tmLanguage.json";
  private static final String LANGUAGE_PATH = "textmate/java/syntaxes/java.tmLanguage.json";
  private static final String CONFIG_PATH = "textmate/java/language-configuration.json";
  private static final String SCOPENAME="source.java";
  private final Formatter formatter = new AsyncFormatter() {
  private final Formatter formatter = new AsyncFormatter() {
    @Nullable
    @Override
    public TextRange formatAsync(@NonNull Content text, @NonNull TextRange cursorRange) {
        String formatted;
        try {
            formatted = new com.google.googlejavaformat.java.Formatter()
                    .formatSource(text.toString());
        } catch (Exception e) {
            formatted = text.toString(); // fallback
        }

        if (!text.toString().equals(formatted)) {
            // احفظ موقع المؤشر القديم
            int oldCursor = cursorRange.getStartIndex();

            // امسح النص القديم وأدخل الجديد
            text.delete(0, text.length());
            text.insert(0, 0, formatted);

            // احسب الموقع الجديد للمؤشر
            int newCursor = Math.min(oldCursor, formatted.length());

            // حوله لـ CharPosition
            CharPosition pos = text.getIndexer().getCharPosition(newCursor);

            // رجع المؤشر
            return new TextRange(pos, pos);
        }

        return cursorRange;
    }

    @Nullable
    @Override
    public TextRange formatRegionAsync(@NonNull Content text,
                                       @NonNull TextRange rangeToFormat,
                                       @NonNull TextRange cursorRange) {
        return null;
    }
};
  
    @NonNull
    @Override
    public Formatter getFormatter() {
        return formatter;
    }

  public JavaLanguage(Editor editor) {
        this.mEditor = editor;
        delegate = LanguageManager.createTextMateLanguage(SCOPENAME, LANGUAGE_PATH, CONFIG_PATH, editor);
  }

  public boolean isAutoCompleteChar(char p1) {
    return p1 == '.' || MyCharacter.isJavaIdentifierPart(p1);
  }

  public int getIndentAdvance(String p1) {
    JavaTextTokenizer tokenizer = new JavaTextTokenizer(p1);
    Tokens token;
    int advance = 0;
    while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
      switch (token) {
        case LBRACE:
          advance++;
          break;
      }
    }
    return (advance * getTabWidth());
  }

  public int getFormatIndent(String line) {
    JavaTextTokenizer tokenizer = new JavaTextTokenizer(line);
    Tokens token;
    int advance = 0;
    while ((token = tokenizer.directNextToken()) != Tokens.EOF) {
      switch (token) {
        case LBRACE:
          advance++;
          break;
        case RBRACE:
          advance--;
      }
    }
    return (advance * getTabWidth());
  }

  @NonNull
  @Override
  public AnalyzeManager getAnalyzeManager() {
    return delegate.getAnalyzeManager();
  }

  @Override
  public int getInterruptionLevel() {
    return INTERRUPTION_LEVEL_SLIGHT;
  }

  @Override
  public void requireAutoComplete(
      @NonNull ContentReference content,
      @NonNull CharPosition position,
      @NonNull CompletionPublisher publisher,
      @NonNull Bundle extraArguments)
      throws CompletionCancelledException {
    char c = content.charAt(position.getIndex() - 1);
    if (!isAutoCompleteChar(c)) {
      return;
    }
    String prefix = CompletionHelper.computePrefix(content, position, this::isAutoCompleteChar);
    JavaAutoCompleteProvider provider = new JavaAutoCompleteProvider(mEditor);
    CompletionList list =
        provider.getCompletionList(prefix, position.getLine(), position.getColumn());
    if (list == null) {
      return;
    }
    for (CompletionItem item : list.getItems()) {
      CompletionItemWrapper wrapper = new CompletionItemWrapper(item);
      publisher.addItem(wrapper);
    }
  }

  @Override
  public int getIndentAdvance(@NonNull ContentReference content, int line, int column) {
    String text = content.getLine(line).substring(0, column);
    return getIndentAdvance(text);
  }

  @Override
  public boolean useTab() {
    return true;
  }

  public int getTabWidth() {
    return 4;
  }

  
  public CharSequence format(CharSequence p1) {
    return format(p1, 0, p1.length());
  }

  @NonNull
  @Override
  public CharSequence format(@NonNull CharSequence text, int start, int end) {

    CharSequence formatted = null;

    /*try {

      StringWriter out = new StringWriter();
      StringWriter err = new StringWriter();

      com.google.googlejavaformat.java.Main main =
          new com.google.googlejavaformat.java.Main(
              new PrintWriter(out, true),
              new PrintWriter(err, true),
              new ByteArrayInputStream(text.toString().getBytes(StandardCharsets.UTF_8)));
      int exitCode = main.format("-");

      formatted = out.toString();

      if (exitCode != 0) {
        formatted = text;
      }

   //    formatted = new com.google.googlejavaformat.java.Formatter().formatSource(text.toString());
    } catch (Exception e) {
    }*/
    try{
     //formatted = new com.google.googlejavaformat.java.Formatter().formatSource(text.toString());
      formatted = com.tyron.eclipse.formatter.Formatter.format(text.toString(),start,end-start);
     }catch(Exception e){
        // throw new Error(e.fillInStackTrace());
    } 

    if (formatted == null) {
      formatted = text;
    }
    return formatted;
  }

  @Override
  public SymbolPairMatch getSymbolPairs() {
    return delegate.getSymbolPairs();
  }

  private final NewlineHandler[] newLineHandlers =
      new NewlineHandler[] {
        new BraceHandler(), new TwoIndentHandler(), new JavaDocStartHandler(), new JavaDocHandler()
      };

  @Override
  public NewlineHandler[] getNewlineHandlers() {
    return newLineHandlers;
  }

  @Override
  public void destroy() {}

  class CallParenHandler implements NewlineHandler {

    @Override
    public boolean matchesRequirement(@NonNull Content text,
                                      @NonNull CharPosition position,
                                      @Nullable Styles style) {
        int line = position.line;
        if (line < 0 || line >= text.getLineCount()) return false;

        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();


        String after = text.subContent(
        line, position.column,
        line, text.getLine(line).length())
        .toString();


        return before.replace("\r", "").trim().startsWith(".") == false
                && before.trim().endsWith(")")
                && !after.trim().startsWith(";");
    }

    @Override
    @NonNull
    public NewlineHandleResult handleNewline(@NonNull Content text,
                                             @NonNull CharPosition position,
                                             @Nullable Styles style,
                                             int tabSize) {
        int line = position.line;
        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();


        int indent = TextUtils.countLeadingSpaceCount(before, tabSize);
        int advance = getIndentAdvance(before) + 8; // +8 = 4*2
        String indentStr = TextUtils.createIndent(indent + advance, tabSize, false);

        return new NewlineHandleResult(new StringBuilder("\n").append(indentStr), 0);
    }
}
  
final class TwoIndentHandler implements NewlineHandler {

    @Override
    public boolean matchesRequirement(@NonNull Content text,
                                      @NonNull CharPosition position,
                                      @Nullable Styles style) {
        int line = position.line;
        if (line < 0 || line >= text.getLineCount()) return false;

        String before = text.subContent(line, 0, line, position.column).toString();
        String after  = text.subContent(line, position.column, line,
                                        text.getLine(line).length()).toString();

        return !before.replace("\r", "").trim().startsWith(".") &&
               before.trim().endsWith(")") &&
               !after.trim().startsWith(";");
    }

    @Override
    @NonNull
    public NewlineHandleResult handleNewline(@NonNull Content text,
                                             @NonNull CharPosition position,
                                             @Nullable Styles style,
                                             int tabSize) {
        int line = position.line;
        String before = text.subContent(line, 0, line, position.column).toString();

        int indent      = TextUtils.countLeadingSpaceCount(before, tabSize);
        int extraIndent = 8;                       // 4 * 2
        String indentStr = TextUtils.createIndent(indent + extraIndent, tabSize, false);

        return new NewlineHandleResult(new StringBuilder("\n").append(indentStr), 0);
    }
}


   class BraceHandler implements NewlineHandler {

    @Override
    public boolean matchesRequirement(@NonNull Content text,
                                      @NonNull CharPosition position,
                                      @Nullable Styles style) {
        int line = position.line;
        if (line < 0 || line >= text.getLineCount()) return false;

        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();


        String after = text.subContent(
        line, position.column,
        line, text.getLine(line).length())
        .toString();


        return before.trim().endsWith("{") && after.trim().startsWith("}");
    }

    @Override
    @NonNull
    public NewlineHandleResult handleNewline(@NonNull Content text,
                                             @NonNull CharPosition position,
                                             @Nullable Styles style,
                                             int tabSize) {
        int line = position.line;

        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();


        String after = text.subContent(
        line, position.column,
        line, text.getLine(line).length())
        .toString();


        int indentBase = TextUtils.countLeadingSpaceCount(before, tabSize);
        int advanceBefore = getIndentAdvance(before); // or your helper
        int advanceAfter  = getIndentAdvance(after);

        String indent = TextUtils.createIndent(indentBase + advanceBefore, tabSize, false);
        String closingIndent = TextUtils.createIndent(indentBase + advanceAfter, tabSize, false);

        StringBuilder sb = new StringBuilder("\n")
                .append(indent)
                .append('\n')
                .append(closingIndent);

        int cursorShiftBack = closingIndent.length() + 1;
        return new NewlineHandleResult(sb, cursorShiftBack);
    }
}


   class JavaDocStartHandler implements NewlineHandler {

    private final boolean shouldCreateEnd = true;

    @Override
    public boolean matchesRequirement(@NonNull Content text,
                                      @NonNull CharPosition position,
                                      @Nullable Styles style) {
        int line = position.line;
        if (line < 0 || line >= text.getLineCount()) return false;

        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();

        return before.trim().startsWith("/**");
    }

    @Override
    @NonNull
    public NewlineHandleResult handleNewline(@NonNull Content text,
                                             @NonNull CharPosition position,
                                             @Nullable Styles style,
                                             int tabSize) {
        int line = position.line;
        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();

        int indent = TextUtils.countLeadingSpaceCount(before, tabSize);

        StringBuilder sb = new StringBuilder("\n")
                .append(TextUtils.createIndent(indent, tabSize, false))
                .append(" * ");

        if (shouldCreateEnd) {
            String endIndent = TextUtils.createIndent(indent, tabSize, false);
            sb.append("\n")
              .append(endIndent)
              .append(" */");
            int cursorShift = endIndent.length() + 4; // back-up to " * "
            return new NewlineHandleResult(sb, cursorShift);
        }

        return new NewlineHandleResult(sb, 0);
    }
}


   class JavaDocHandler implements NewlineHandler {

    @Override
    public boolean matchesRequirement(@NonNull Content text,
                                      @NonNull CharPosition position,
                                      @Nullable Styles style) {
        int line = position.line;
        if (line < 0 || line >= text.getLineCount()) return false;

        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();

        String trimmed = before.trim();
        return trimmed.startsWith("*") && !trimmed.startsWith("*/");
    }

    @Override
    @NonNull
    public NewlineHandleResult handleNewline(@NonNull Content text,
                                             @NonNull CharPosition position,
                                             @Nullable Styles style,
                                             int tabSize) {
        int line = position.line;
        String before = text.subContent(
        line, 0,
        line, position.column)
        .toString();


        int indent = TextUtils.countLeadingSpaceCount(before, tabSize);
        StringBuilder sb = new StringBuilder("\n")
                .append(TextUtils.createIndent(indent, tabSize, false))
                .append("* ");
        return new NewlineHandleResult(sb, 0);
    }
}

  } 
