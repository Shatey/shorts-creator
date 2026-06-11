package com.shortscreator;

import java.text.MessageFormat;
import java.util.Locale;
import java.util.ResourceBundle;

public final class I18n {
    private static final String BUNDLE_NAME = "com.shortscreator.Messages";
    private Locale locale;
    private ResourceBundle bundle;

    public I18n(String languageCode) {
        setLanguage(languageCode);
    }

    public void setLanguage(String languageCode) {
        String normalized = "ru".equalsIgnoreCase(languageCode) ? "ru" : "en";
        locale = Locale.forLanguageTag(normalized);
        bundle = ResourceBundle.getBundle(BUNDLE_NAME, locale);
    }

    public String languageCode() {
        return locale.getLanguage();
    }

    public String text(String key) {
        return bundle.getString(key);
    }

    public String format(String key, Object... args) {
        return MessageFormat.format(text(key), args);
    }
}
