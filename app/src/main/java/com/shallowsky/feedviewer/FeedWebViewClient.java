//
// WebViewClient events for FeedViewer
//

package com.shallowsky.feedviewer;

import android.os.SystemClock;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.net.URI;
import java.net.URISyntaxException;

import static android.util.Log.d;

public class FeedWebViewClient extends WebViewClient {
    boolean mDontFollowLinks;

    public FeedWebViewClient() {
        mDontFollowLinks = false;
    }

    /*
    @Override
    public void onPageFinished(WebView webView, final String url) {
        // d("FeedViewer", "finished loading " + url);
        // try to restore scroll position
        FeedWebView feedWebView = (FeedWebView)webView;
        feedWebView.restoreScroll();
    }
     */

    @Override
    public boolean shouldOverrideUrlLoading(WebView webView, String url) {
        d("FeedViewer", "shouldOverrideUrlLoading " + url);
        FeedWebView feedWebView = (FeedWebView)webView;
        feedWebView.saveScrollPos();

        if (mDontFollowLinks)
            return true;

        // Don't load if we're waiting for a longpress to time out:
        /*
        long scrollDelay = 2000;
        if (SystemClock.uptimeMillis() < (mScrollLock + scrollDelay)) {
            return true;
        }
         */

        // Otherwise, we'll try to load something.
        // First, save our position on the current page.
        //saveStateInPreferences();
        // saveScrollPos();

        try {
            URI uri = new URI(url);
            if (uri.getScheme().equals("file")) {
                // If it's file: then we're moving between
                // internal pages. Go ahead and go to the link.
                return false;
            } else {
                // The scheme isn't file:// so offer to save it for later.
                // Android Studio doesn't like just casting it in place when calling handle,
                // so use another variable.
                FeedWebView fwv = (FeedWebView)webView;
                fwv.handleExternalLink(url);
                return true;
            }
        } catch (URISyntaxException e) {
            return false;
        }
    }
    // If shouldOverrideUrlLoading turns out not to work for everything FeedViewer needs,
    // public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request)
    // is a more general approach. But it's called even for assets like embedded images,
    // so shouldOverrideUrlLoading is better if possible.
    // https://developer.android.com/guide/webapps/migrating.html#URLs
    // discusses cases where it won't be called.
}
