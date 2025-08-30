package com.tyron.code.analyzer;

import android.graphics.Color;
import java.time.Duration;
import android.os.Bundle;
import androidx.annotation.NonNull;
import com.tyron.code.language.textmate.BaseIncrementalAnalyzeManager;
import com.tyron.code.language.textmate.CodeBlockUtils;
import com.tyron.editor.Editor;
import io.github.rosemoe.sora.lang.styling.CodeBlock;
import io.github.rosemoe.sora.lang.styling.Span;
import io.github.rosemoe.sora.lang.styling.TextStyle;
import io.github.rosemoe.sora.langs.textmate.folding.FoldingRegions;
import io.github.rosemoe.sora.langs.textmate.folding.IndentRange;
import io.github.rosemoe.sora.text.Content;
import io.github.rosemoe.sora.text.ContentReference;
import org.eclipse.tm4e.core.grammar.IGrammar;
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult;
import org.eclipse.tm4e.core.grammar.IStateStack;
import org.eclipse.tm4e.core.grammar.IToken;
import org.eclipse.tm4e.core.internal.grammar.StateStack;
import org.eclipse.tm4e.core.internal.grammar.tokenattrs.EncodedTokenAttributes;
import org.eclipse.tm4e.core.registry.Registry;
import org.eclipse.tm4e.core.registry.IGrammarSource;
import org.eclipse.tm4e.core.internal.theme.FontStyle;
import org.eclipse.tm4e.core.internal.theme.raw.IRawTheme;
import org.eclipse.tm4e.core.internal.theme.Theme;
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration;
import org.eclipse.tm4e.languageconfiguration.internal.model.FoldingRules;
import io.github.rosemoe.sora.util.ArrayList;
import io.github.rosemoe.sora.widget.schemes.EditorColorScheme;
import java.io.InputStream;
import java.io.Reader;
import java.util.List;

/** A text mate analyzer which does not use a TextMateLanguage */
public class BaseTextmateAnalyzer extends BaseIncrementalAnalyzeManager<IStateStack, Span> {

  /** Maximum for code block count */
  public static int MAX_FOLDING_REGIONS_FOR_INDENT_LIMIT = 5000;

  private final Registry registry = new Registry();
  private final IGrammar grammar;
  private Theme theme;
  private final Editor editor;
  private final LanguageConfiguration configuration;

  public BaseTextmateAnalyzer(
      Editor editor,
      String grammarName,
      InputStream grammarIns,
      Reader languageConfiguration,
      IRawTheme theme)
      throws Exception {   
    this.editor = editor;
    this.theme = Theme.createFromRawTheme(theme,new io.github.rosemoe.sora.util.ArrayList<String>());
     registry.setTheme(this.theme);
      
        registry.addGrammar(IGrammarSource.fromInputStream(grammarIns,grammarName, null));
    this.grammar = registry.grammarForScopeName(grammarName);
        if (languageConfiguration != null) {
      configuration = LanguageConfiguration.load(languageConfiguration);
    } else {
      configuration = null;
    }
  }

  public void analyzeCodeBlocks(
      Content model, List<CodeBlock> blocks, CodeBlockAnalyzeDelegate delegate) {
    if (configuration == null) {
      return;
    }
    FoldingRules folding = configuration.getFolding();
    if (folding == null) {
      return;
    }
    try {
      FoldingRegions foldingRegions =
          CodeBlockUtils.computeRanges(
              model,
              editor.getTabCount(),
              folding.offSide,
              folding,
              MAX_FOLDING_REGIONS_FOR_INDENT_LIMIT,
              delegate);
      for (int i = 0; i < foldingRegions.length() && !delegate.isCancelled(); i++) {
        int startLine = foldingRegions.getStartLineNumber(i);
        int endLine = foldingRegions.getEndLineNumber(i);
        if (startLine != endLine) {
          CodeBlock codeBlock = new CodeBlock();
          codeBlock.toBottomOfEndLine = true;
          codeBlock.startLine = startLine;
          codeBlock.endLine = endLine;

          // It's safe here to use raw data because the Content is only held by this
          // thread
          int length = model.getColumnCount(startLine);
          char[] chars = model.getLine(startLine).getBackingCharArray();

          codeBlock.startColumn =
              IndentRange.computeStartColumn(
                  chars, length, editor.useTab() ? 1 : editor.getTabCount());
          codeBlock.endColumn = codeBlock.startColumn;
          blocks.add(codeBlock);
        }
      }
      blocks.sort(CodeBlock.COMPARATOR_END);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }

  @Override
  public IStateStack getInitialState() {
    return null;
  }

  @Override 
  public void onAddState(IStateStack state){}

  @Override 
  public void onAbandonState(IStateStack state){}

  @Override 
  public LineTokenizeResult<IStateStack, Span> tokenizeLine(CharSequence line, IStateStack state, int lineIndex){
    return null;
  } 
  @Override
  public boolean stateEquals(IStateStack state, IStateStack another) {
    if (state == null && another == null) {
      return true;
    }
    if (state != null && another != null) {
      return state.equals(another);
    }
    return false;
  }

  @Override
public Result<IStateStack, Span> tokenizeLine(CharSequence lineC, IStateStack state) {
    String line = lineC.toString();
    ArrayList<Span> tokens = new ArrayList<>();
    ITokenizeLineResult<int[]> lineTokens =
            grammar.tokenizeLine2(line, state, java.time.Duration.ofMillis(10));
    int[] raw = lineTokens.getTokens();
    for (int i = 0, len = raw.length / 2; i < len; i++) {
        int startIndex = raw[2 * i];
        int metadata   = raw[2 * i + 1];
        int foreground = EncodedTokenAttributes.getForeground(metadata);
      int fontStyle = EncodedTokenAttributes.getFontStyle(metadata);

        Span span = Span.obtain(
                startIndex,
                TextStyle.makeStyle(
                        foreground + 255,
                        0,
                        (fontStyle & FontStyle.Bold) != 0,
                        (fontStyle & FontStyle.Italic) != 0,
                        false));
        if ((fontStyle & FontStyle.Underline) != 0) {
            String color = theme.getColor(foreground);
            if (color != null) {
                span.setUnderlineColor(Color.parseColor(color));
            }
        }
        tokens.add(span);
    }
    return new Result<>(lineTokens.getRuleStack(), null, tokens);
}


  @Override
  public List<Span> generateSpansForLine(LineTokenizeResult<IStateStack, Span> tokens) {
    return null;
  }

  @Override
  public List<CodeBlock> computeBlocks(Content text, CodeBlockAnalyzeDelegate delegate) {
    List<CodeBlock> list = new java.util.ArrayList<>();
    analyzeCodeBlocks(text, list, delegate);
    return list;
  }

  @Override
  public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
    if (!extraArguments.getBoolean("loaded", false)) {
      return;
    }
    super.reset(content, extraArguments);
  }

  public void updateTheme(IRawTheme theme,ArrayList<String> colorMap) {
    this.theme = Theme.createFromRawTheme(theme, colorMap);
    registry.setTheme(getTheme()); 
  }

  protected Theme getTheme() {
    return theme;
  }
}
