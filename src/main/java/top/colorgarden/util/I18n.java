// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 colorgarden <colorgarden@outlook.com>

package top.colorgarden.util;


import arc.Core;
import arc.struct.Seq;
import arc.util.Nullable;
import arc.util.Strings;
import mindustry.ctype.Content;
import mindustry.ctype.ContentType;
import mindustry.ctype.UnlockableContent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Properties;

import static mindustry.Vars.content;

/**
 * Message lookup for everything the plugin prints (console, chat, API replies).
 *
 * <p>Keys live in {@code bundles/bundle.properties} (English, fallback) and
 * {@code bundles/bundle_<locale>.properties} (translations). On a client, Mindustry merges mod
 * bundles into {@code Core.bundle}, so the game translation is preferred; on a dedicated server the
 * files are read straight out of the mod jar, which keeps the console usable in either language.</p>
 */
public class I18n{
    private static final Properties english = new Properties();
    private static final Properties translated = new Properties();
    /** Content display names (game keys such as {@code block.conveyor.name}), see {@link #localizeContent()}. */
    private static final Properties contentNames = new Properties();
    /** Per-locale message and content-name tables, cached for the languages clients actually use. */
    private static final arc.struct.ObjectMap<String, Properties> perLocale = new arc.struct.ObjectMap<>();
    private static final arc.struct.ObjectMap<String, Properties> perLocaleContent = new arc.struct.ObjectMap<>();
    /** Locale of the player currently being served; null means "use the server language". */
    private static @Nullable String activeLocale;
    private static boolean loaded;
    private static String language = "en";

    public static void load(String configuredLanguage){
        language = configuredLanguage == null || configuredLanguage.isEmpty() ? "en" : configuredLanguage;
        if(loaded) return;
        loaded = true;

        read("bundles/bundle.properties", english);

        String code = localeCode(language);
        if(!code.isEmpty()){
            read("bundles/bundle_" + code + ".properties", translated);
        }

        //content names are a separate table: they are the game's keys, not the plugin's
        read("i18n/content.properties", contentNames);
        if(!code.isEmpty()){
            read("i18n/content_" + code + ".properties", contentNames);
        }
    }

    /**
     * Runs {@code body} with messages resolved in this player's language. Everything the plugin sends
     * while it runs uses that locale, then the previous one is restored.
     */
    public static void withLocale(@Nullable String locale, Runnable body){
        String previous = activeLocale;
        activeLocale = normalize(locale);
        try{
            body.run();
        }finally{
            activeLocale = previous;
        }
    }

    /** @return the locale code for a player (may be null/unknown, which means English). */
    public static @Nullable String localeOf(@Nullable mindustry.gen.Player player){
        return player == null ? null : normalize(player.locale);
    }

    /** @return the message table for a client locale, English when nothing matches. */
    private static Properties tableFor(@Nullable String code){
        Properties table = perLocale.get("default");
        if(table == null){
            table = english;
            perLocale.put("default", table);
        }

        if(code == null || code.isEmpty()) return table;

        Properties found = perLocale.get(code);
        if(found != null) return found;

        Properties custom = new Properties();
        custom.putAll(english);
        read("bundles/bundle_" + code + ".properties", custom);
        perLocale.put(code, custom);
        return custom;
    }

    /** @return the content-name table for a client locale, English when nothing matches. */
    private static Properties contentTableFor(@Nullable String code){
        if(code == null || code.isEmpty()) return contentNames;

        Properties found = perLocaleContent.get(code);
        if(found != null) return found;

        Properties custom = new Properties();
        custom.putAll(contentNames);
        read("i18n/content_" + code + ".properties", custom);
        perLocaleContent.put(code, custom);
        return custom;
    }

    /** @return the localized display name of a content entry for the active locale. */
    public static String content(@Nullable UnlockableContent value){
        if(value == null) return "";
        if(activeLocale == null) return value.localizedName;

        String found = contentTableFor(activeLocale).getProperty(value.getContentType() + "." + value.name + ".name");
        return found != null ? found : value.localizedName;
    }

    private static String normalize(@Nullable String locale){
        if(locale == null) return null;
        String trimmed = locale.trim().replace('-', '_');
        if(trimmed.isEmpty() || "default".equalsIgnoreCase(trimmed) || "auto".equalsIgnoreCase(trimmed)) return null;
        return trimmed;
    }

    /**
     * Fills {@code UnlockableContent#localizedName} from the shipped content-name table.
     *
     * <p>A dedicated server has no {@code Core.bundle} with the game's content keys, so vanilla leaves
     * {@code localizedName} at the internal id ({@code mechanical-drill} instead of {@code 机械钻头}).
     * Every message that names a block, item or sector would show that id, so the names are patched
     * once at startup. Clients keep their own translation - only display strings are touched.</p>
     */
    public static void localizeContent(){
        if(contentNames.isEmpty()) return;

        for(ContentType type : ContentType.all){
            Seq<Content> all = content.getBy(type);
            if(all == null) continue;

            for(Content c : all){
                if(!(c instanceof UnlockableContent unlockable)) continue;

                String value = contentNames.getProperty(type + "." + unlockable.name + ".name");
                if(value != null) unlockable.localizedName = value;
            }
        }
    }

    /** @return the effective key, formatted with {@code @} placeholders. */
    public static String get(String key, Object... args){
        String value = lookup(key);
        if(value == null) return key;
        return args == null || args.length == 0 ? value : Strings.format(value, args);
    }

    public static boolean has(String key){
        return lookup(key) != null;
    }

    private static String lookup(String key){
        //1. the language of the client currently being served, when there is one
        if(activeLocale != null){
            String value = tableFor(activeLocale).getProperty(key);
            if(value != null) return value;
        }

        //2. the language selected for this installation
        String value = translated.getProperty(key);
        if(value != null) return value;

        //3. the english fallback that ships with the mod
        value = english.getProperty(key);
        if(value != null) return value;

        //4. the game bundle, which also contains the mod bundle merged by Mindustry on a client
        try{
            if(Core.bundle != null){
                return Core.bundle.getOrNull(key);
            }
        }catch(Throwable ignored){
            //bundle not available (very early init)
        }

        return null;
    }

    private static String localeCode(String configured){
        if(configured != null && !configured.isEmpty() && !"auto".equalsIgnoreCase(configured)){
            return configured.replace('-', '_');
        }
        String tag = Locale.getDefault().toString();
        if(tag.isEmpty() || tag.startsWith("en")) return "";
        return tag.replace('-', '_');
    }

    private static void read(String path, Properties target){
        try(InputStream stream = I18n.class.getClassLoader().getResourceAsStream(path)){
            if(stream == null) return;
            target.load(new InputStreamReader(stream, StandardCharsets.UTF_8));
        }catch(Throwable ignored){
            //missing bundle file is not fatal, keys fall back to the key itself
        }
    }

    /** @return the configured language, for the diag command. */
    public static String language(){
        return language;
    }
}
