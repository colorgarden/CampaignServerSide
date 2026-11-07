package top.colorgarden.util;

import mindustry.ctype.UnlockableContent;

import java.lang.reflect.Field;

/**
 * Turns content into the private-use-area glyph Mindustry ships for it.
 *
 * <p>{@code mindustry.gen.Iconc} holds one {@code char} per icon; item icons are named
 * {@code item<CamelName>} (copper, phaseFabric, ...) and block icons {@code block<CamelName>}. A
 * vanilla client renders those characters with its bundled icon font, so a menu sent as plain text
 * can show real item icons without any client mod.</p>
 */
public final class Icons{
    private Icons(){
    }

    /** @return the glyph for an item, or "" when Mindustry has no icon for it. */
    public static String item(UnlockableContent content){
        return glyph("item" + camel(content == null ? "" : content.name));
    }

    /** @return the glyph for a block, or "" when Mindustry has no icon for it. */
    public static String block(UnlockableContent content){
        return glyph("block" + camel(content == null ? "" : content.name));
    }

    /** @return the glyph stored under that {@code Iconc} field, or "" when it does not exist. */
    public static String glyph(String field){
        if(field == null || field.isEmpty()) return "";

        try{
            Field constant = Class.forName("mindustry.gen.Iconc").getField(field);
            if(constant.getType() == char.class) return String.valueOf(constant.getChar(null));
        }catch(Throwable ignored){
            //older/newer versions simply have no icon for this content
        }

        return "";
    }

    /** @return "phase-fabric" -> "PhaseFabric". */
    private static String camel(String name){
        if(name == null || name.isEmpty()) return "";

        StringBuilder out = new StringBuilder(name.length());
        boolean upper = true;
        for(int i = 0; i < name.length(); i++){
            char c = name.charAt(i);
            if(c == '-' || c == '_' || c == ' '){
                upper = true;
                continue;
            }
            out.append(upper ? Character.toUpperCase(c) : c);
            upper = false;
        }
        return out.toString();
    }
}
