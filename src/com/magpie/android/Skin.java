package com.magpie.android;

import android.content.Context;
import android.webkit.WebView;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** The phone stylesheet and script injected into magpie's own page (main window and floating window). */
final class Skin {
    private static final String SCRIPT_ASSET = "magpie-mobile.js";
    private static final String SKIN_ASSET = "magpie-mobile.css";
    private static final String FLOAT_ASSET = "magpie-float.css";

    private Skin() {}

    static void paint(Context c, WebView web, boolean floating) {
        try {
            String css = read(c, SKIN_ASSET);
            if (floating) {
                css += "\n" + read(c, FLOAT_ASSET);
            }
            String js = read(c, SCRIPT_ASSET);
            web.evaluateJavascript("(function(){var id='magpie-mobile-css',el=document.getElementById(id);if(!el){el=document.createElement('style');el.id=id;(document.head||document.documentElement).appendChild(el);}el.textContent=" + JSONObject.quote(css) + ";})();", null);
            web.evaluateJavascript("window.magpieBun = " + (MagpieGateway.hasBundledBun(c) ? "true" : "false") + ";", null);
            web.evaluateJavascript("window.magpieAndroid = true; window.magpieFloating = " + (floating ? "true" : "false") + ";", null);
            if (floating) {
                web.evaluateJavascript("document.documentElement.classList.add('magpie-floating');", null);
            }
            web.evaluateJavascript(js, null);
        } catch (IOException ignored) {
        }
    }

    static String read(Context c, String name) throws IOException {
        try (InputStream in = c.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) >= 0) {
                out.write(b, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
