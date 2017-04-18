package aec.hackathon.munich.forgelivescan.util;

import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import org.xwalk.core.XWalkView;

import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */

public abstract class CustomWebView {

    public static void loadUrl(String url, AtomicReference<Object> view){
        if (view.get() instanceof XWalkView){
            ((XWalkView) view.get()).loadUrl(url);
        } else if (view.get() instanceof WebView){
            ((WebView) view.get()).loadUrl(url);
        }
    }

    public static void clearCache(boolean bool, AtomicReference<Object> view){
        if (view.get() instanceof XWalkView){
            ((XWalkView) view.get()).clearCache(bool);
        } else if (view.get() instanceof WebView){
            ((WebView) view.get()).clearCache(bool);
        }
    }

    public static void reload(AtomicReference<Object> view){
        if (view.get() instanceof XWalkView){
            ((XWalkView) view.get()).reload(XWalkView.RELOAD_IGNORE_CACHE);
        } else if (view.get() instanceof WebView){
            ((WebView) view.get()).reload();
        }
    }

    public static void sendViewToBack(final View child) {
        final ViewGroup parent = (ViewGroup)child.getParent();
        if (null != parent) {
            parent.removeView(child);
            parent.addView(child, 0);
        }
    }
}
