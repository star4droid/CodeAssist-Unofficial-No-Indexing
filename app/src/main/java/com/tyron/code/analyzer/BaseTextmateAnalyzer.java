package com.tyron.code.analyzer;

import android.os.Bundle;

import androidx.annotation.NonNull;

import com.tyron.editor.Editor;

import org.eclipse.tm4e.core.internal.theme.raw.IRawTheme;
import org.eclipse.tm4e.core.internal.theme.Theme;

import java.io.InputStream;
import java.io.Reader;
import java.lang.reflect.Field;

import io.github.rosemoe.sora.langs.textmate.TextMateAnalyzer;
import io.github.rosemoe.sora.langs.textmate.TextMateLanguage;
import io.github.rosemoe.sora.text.ContentReference;
import io.github.rosemoe.sora.widget.CodeEditor;
import org.eclipse.tm4e.core.grammar.IGrammar;
import io.github.rosemoe.sora.langs.textmate.registry.ThemeRegistry;
import io.github.rosemoe.sora.langs.textmate.registry.GrammarRegistry;
import org.eclipse.tm4e.languageconfiguration.internal.model.LanguageConfiguration;
import io.github.rosemoe.sora.langs.textmate.registry.model.ThemeModel;

/**
 * A text mate analyzer which does not use a TextMateLanguage
 */
public class BaseTextmateAnalyzer extends TextMateAnalyzer {

    private static final Field THEME_FIELD;

    public BaseTextmateAnalyzer(TextMateLanguage language, Editor editor,
                                IGrammar grammar,
                                LanguageConfiguration languageConfiguration,
                              ThemeRegistry theme) throws Exception {
        super(language,grammar,
                languageConfiguration, theme);
    }

    @Override
    public void reset(@NonNull ContentReference content, @NonNull Bundle extraArguments) {
        if (!extraArguments.getBoolean("loaded", false)) {
            return;
        }
        super.reset(content, extraArguments);
    }

  public void setTheme(Theme theme){
      ThemeModel tm = new ThemeModel();
      try{
          Field tmf = ThemeModel.class.getDeclaredField("theme");
          tmf.setAccessible(true);
          tmf.set(tm,theme);
          onChangeTheme(tm);
      }catch(Exception e){}
  }

    public Theme getTheme() {
        try {
            return (Theme) THEME_FIELD.get(this);
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }

    static {
        try {
            THEME_FIELD = TextMateAnalyzer.class.getDeclaredField("theme");
            THEME_FIELD.setAccessible(true);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
