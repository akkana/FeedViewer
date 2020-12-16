//
// The main code for FeedViewer: a class inherited from WebView.
//

package com.shallowsky.feedviewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.preference.PreferenceManager;
import android.text.method.ScrollingMovementMethod;
import android.util.AttributeSet;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.view.GestureDetectorCompat;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Map;

import static android.os.Environment.getExternalStorageDirectory;
import static android.util.Log.d;

public class FeedWebView extends WebView {
    MainActivity mActivity;

    // The main, writable, feed dir: where to download feeds
    File mFeedDir;

    // Paths from which to read feeds. mFeedDir plus maybe others.
    ArrayList<File> mBasePaths = new ArrayList<File>();

    FeedWebViewClient mWebViewClient;

    private SharedPreferences mSharedPreferences;

    // The FeedFetcher and its dialog
    FeedFetcher mFeedFetcher = null;
    Dialog mFeedFetcherDialog = null;
    TextView mFeedFetcherText = null;
    // The server from which we'll fetch the feeds
    String mFeedServer = null;

    WebSettings mWebSettings; // Settings for the WebView, e.g. font size

    private GestureDetectorCompat mDetector;

    // Might eventually need ObservableWebView methods here

    public FeedWebView(final Context context) {
        super(context);
    }

    public FeedWebView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
    }

    //
    // Java/AndroidStudio doesn't seem to allow having constructors
    // with different arguments from the base class, and/or I can't
    // figure out how it decides what args to pass to something
    // constructed from XML. So let's just pass the activity
    // separately. This should be called right at the beginning of the
    // activity, and can be used for initializing anything that would
    // otherwise be done in a constructor.
    //
    public void setActivity(Activity activity) {
        mActivity = (MainActivity) activity;
        mFeedDir = activity.getExternalFilesDir(null);
        mBasePaths.add(mFeedDir);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(mActivity.getApplicationContext());
        // d("FeedViewer", "Got feed dir " + mFeedDir);

        mWebSettings = getSettings();
        mWebSettings.setJavaScriptEnabled(false);
        mWebSettings.setAllowFileAccess(true);

        // Enable pinch-zoom
        mWebSettings.setBuiltInZoomControls(true);
        mWebSettings.setDisplayZoomControls(false);

        mWebViewClient = new FeedWebViewClient();
        setWebViewClient(mWebViewClient);
        // https://stackoverflow.com/a/22920457
        setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int progress) {
                if (view.getProgress() == 100) {
                    // When finished loading, reposition to last location
                    d("FeedViewer", "restoreScroll from onProgressChanged");
                    restoreScroll();
                }
            }
        });

        // https://developer.android.com/training/gestures/detector
        mDetector = new GestureDetectorCompat(mActivity.getApplicationContext(),
                                              new MyGestureListener());

        restoreLastPage();
    }

    //
    // If the activity is destroyed, be sure to stop any running feedFetcher.
    // Probably best to do it on pause too.
    //
    public void stopFeedFetcher() {
        if (mFeedFetcher != null)
            mFeedFetcher.stop();
    }

    //
    // Save any state that needs to be saved on pause or destroy,
    // or at any other time the webview might forget its contents.
    //
    public void saveState() {
        savePagePos();
    }

    // Clean up any scroll preferences for deleted files/directories.
    // That includes not just what we just immediately deleted, but anything
    // that was deleted previously and somehow didn't get its pref removed.
    private void cleanUpScrollPrefs() {
        Log.d("FeedViewer", "Trying to delete old scroll prefs");
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        Map<String, ?> allprefs = mSharedPreferences.getAll();
        for (Map.Entry<String, ?> entry : allprefs.entrySet()) {
            String key = entry.getKey();
            if (key.startsWith("scroll_") && !key.endsWith("feeds")) {
                String path = key.substring(7);
                File file = new File(path);
                if (!file.exists()) {
                    editor.remove(key);
                    Log.d("FeedViewer", "Removed pref " + key);
                }
            }
        }
        editor.commit();

        // printPreferences();
    }

    ////////////////////////////
    // Settings and preferences.
    // There aren't really any now except the feed server.
    public void editSettings() {
        promptForFeedServer();
    }

    // The url passed in here should already have had named anchors stripped.
    private String url_to_scrollpos_key(String url) {
        if (onFeedsPage(url) || nullURL(url))
            return "scroll_feeds";

        // First, remove any named anchor
        try {
            int hash = url.indexOf('#');
            if (hash > 0)
                url = url.substring(0, hash);
        } catch (Exception e) {
            Log.d("FeedViewer",
                    "Exception in remove_named_anchor, url = " + url);
        }

        if (url.startsWith("file://")) {
            url = url.substring(7);
        }

        return "scroll_" + url;
    }

    public void savePagePos() {
        SharedPreferences.Editor editor = mSharedPreferences.edit();
        String url = getUrl();
        String scrollkey = url_to_scrollpos_key(url);
        int scrollpos = getScrollY();
        editor.putInt(scrollkey, scrollpos);
        editor.putString("url", url);
        editor.commit();
        // d("FeedViewer", "Saved scroll position " + scrollpos
        //   + " for " + scrollkey);
        // printPreferences();
    }

    // Restore the scroll position from preferences.
    // This is typically called on create and from
    // WebChromeClient::onProgressChanged when a page load finishes.
    public void restoreScroll() {
        String url = getUrl();
        // Is it a named anchor? If so, don't scroll.
        if (url.contains("#")) {
            //d("FeedViewer", url + "is a named anchor: not scrolling");
            return;
        }
        String scrollkey = url_to_scrollpos_key(url);
        int scrollpos = mSharedPreferences.getInt(scrollkey, 0);

        // Scroll twice. First, try to scroll right now.
        // BUt since that often doesn't work, for reasons no one seems to know,
        // also schedule a second scroll a few centiseconds from now, and hope
        // one or the other of them works.
        scrollTo(0, scrollpos);
        postDelayed(new Runnable() {
            @Override
            public void run() {
                scrollTo(0, scrollpos);
            }
        }, 300);
        d("FeedViewer", "Scrolled to " + scrollpos + " on " + scrollkey);
    }

    private void printPreferences() {
        Log.d("FeedViewer", "========== Now complete pref list looks like:");
        Map<String, ?> allprefs = mSharedPreferences.getAll();
        for (Map.Entry<String, ?> entry : allprefs.entrySet())
            if (entry != null && entry.getKey() != null) {
                if (entry.getValue() == null)
                    Log.d("FeedViewer", entry.getKey() + " : null");
                else
                    Log.d("FeedViewer", entry.getKey() + " : '"
                            + entry.getValue() + "'");
            }
        Log.d("FeedViewer", "==========");
    }

    ////// End settings

    //
    // Restore the last page from preferences.
    //
    public void restoreLastPage() {
        String url = mSharedPreferences.getString("url", null);
        if (nullURL(url))
            loadFeedList();
        else
            loadUrl(url);
    }

    //
    // Display the top-level page, listing all feeds present.
    //
    public void loadFeedList() {
        //d("FeedViewer", "Loading feed list");
        //d("FeedViewer", "mBasePaths: " + mBasePaths.toString());

        StringBuilder resultspage = new StringBuilder("<html>\n<head>\n");
        resultspage.append("<title>Feeds</title>\n");
        resultspage.append("<meta http-equiv=\"Content-Type\" content=\"text/html; charset=utf-8\">\n");
        resultspage.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n");
        resultspage.append("<link rel=\"stylesheet\" type=\"text/css\" title=\"Feeds\"\n");
        resultspage.append("      href=\"file://" + mFeedDir + "/feeds.css\"/>\n");
        resultspage.append("</head>\n<body>\n");

        // Loop over the basedirs again to find story dirs to show:
        for (int base = 0; base < mBasePaths.size(); ++base) {
            File basedir = mBasePaths.get(base);
            //d("FeedViewer", "base dir: " + base);
            if (!basedir.isDirectory()) {
                resultspage.append(basedir.getPath());
                resultspage.append(" not a directory.<br>\n");
                continue;
            }
            // Loop over days, in reverse order, most recent first:
            File[] daydirs = basedir.listFiles();
            Arrays.sort(daydirs);
            for (int day = daydirs.length - 1; day >= 0; --day) {
                //d("FeedViewer", "daydir: " + daydirs[day].toString());
                if (daydirs[day].isDirectory()) {
                    Boolean showedHeader = false;
                    // Loop over feeds for that day
                    File[] feeds = daydirs[day].listFiles();
                    Arrays.sort(feeds);
                    for (int feed = 0; feed < feeds.length; ++feed) {
                        //d("FeedViewer", "  feed: " + feeds[feed].toString());
                        if (feeds[feed].isDirectory()) {
                            File indexfile = new File(feeds[feed].getPath()
                                    + File.separator
                                    + "index.html");
                            if (indexfile.canRead()) {
                                // If there's any real content, show a
                                // header for the directory.
                                if (!showedHeader) {
                                    // AndroidStudio doesn't like += on the next line:
                                    resultspage.append(daydirs[day].getName());
                                    resultspage.append(":<br>\n");
                                    showedHeader = true;
                                }
                                resultspage.append("<div class=\"index\"><a href='");

                                // indexfile.toURI() creates file:/storage/... while
                                // Uri.fromFile() creates file:///storage/...
                                // It's anyone's guess why there are both java.net.URI and
                                // Android.net.Uri types, and what the difference might be.
                                // URI fileuri = indexfile.toURI();
                                Uri fileuri = Uri.fromFile(indexfile);
                                resultspage.append(fileuri);
                                // d("FeedViewer", "  link: " + fileuri);
                                resultspage.append("'>" + daydirs[day].getName());
                                resultspage.append(" ");
                                resultspage.append(feeds[feed].getName() + "</a></div>\n");
                                // mFeedDir will be the first of
                                // mBasePaths that actually has files in it.
                                if (mFeedDir == null) {
                                    mFeedDir = new File(basedir.getPath());
                                    Log.d("FeedViewer",
                                            "Setting mFeedDir from loadFeedsList to " + mFeedDir);
                                }
                            } else {
                                // If we erroneously don't get an
                                // index.html file, we'll end up
                                // showing the directory but giving no
                                // way to read or delete it. So show
                                // something:
                                resultspage.append(daydirs[day].getName());
                                resultspage.append(" ");
                                resultspage.append(feeds[feed].getName());
                                resultspage.append("</a> (no index!)<br>\n");
                            }
                        }
                    }
                }
            }
        }
        resultspage.append("<p>End of feed list\n</body>\n</html>\n");

        // d("FeedViewer", "Full index page:\n" + resultspage.toString());
        // d("FeedViewer", "Loading with base url " + "file://" + mFeedDir);

        loadDataWithBaseURL("file://" + mFeedDir, resultspage.toString(),
                "text/html", "utf-8", null);
    }

    //////////////////////////////////////////////////////////
    // Functions related to feed fetching
    //

    public void fetchFeeds() {
        setBackgroundColor(0xffbbbbff);

        if (mFeedServer == null)
            mFeedServer = mSharedPreferences.getString("feed_server", null);
        if (mFeedServer == null)
            promptForFeedServer();
        else
            showFeedFetcherProgress();
    }

    //
    // Prompt dialog for feed server URL. Eventually might have more prefs.
    //
    private void promptForFeedServer() {
        Log.d("FeedViewer", "No feed server assigned");

        // Pop up a prompt dialog to read the server URL
        LayoutInflater li = LayoutInflater.from(mActivity);
        View promptView = li.inflate(R.layout.prompt, null);

        AlertDialog.Builder alertDialogBuilder
                = new AlertDialog.Builder(mActivity, R.style.AlertDialogCustom);
        alertDialogBuilder.setView(promptView);

        final EditText userInput = (EditText)promptView
                .findViewById(R.id.editTextDialogUserInput);

        if (mFeedServer != null)
            userInput.setText(mFeedServer);

        // set dialog message
        alertDialogBuilder
                .setCancelable(false)
                .setPositiveButton("OK",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int id) {
                                mFeedServer = userInput.getText().toString();
                                if (mFeedServer != null) {
                                    SharedPreferences.Editor editor
                                            = mSharedPreferences.edit();
                                    editor.putString("feed_server",
                                            mFeedServer);
                                    editor.commit();
                                    Log.d("FeedViewer",
                                            "Saved new feed server: "
                                                    + mFeedServer);
                                    printPreferences();

                                    // Don't fetch feeds just because the
                                    // user changed the server URL.
                                    //showFeedFetcherProgress();
                                }
                            }
                        })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int id) {
                                dialog.cancel();
                            }
                        });

        // create alert dialog
        AlertDialog alertDialog = alertDialogBuilder.create();

        // show it
        alertDialog.show();
    }

    //
    // Bring up the feed fetcher progress dialog;
    // start fetching a feet, if there isn't one going already.
    // Called from the Fetch Feeds menu item if there's a feed server set.
    //
    private void showFeedFetcherProgress() {

        Button imgToggle;

        // Does the dialog already exist? Then show it again.
        if (mFeedFetcherDialog != null) {
            mFeedFetcherText.append("\n\nRe-showing the old dialog\n");

            // Try to scroll to the bottom, though this doesn't always work,
            // particularly during the actual downloading phase:
            ScrollView scrollView = (ScrollView) mFeedFetcherDialog.findViewById(R.id.fetcherTextScroller);
            if (scrollView != null)
                scrollView.fullScroll(View.FOCUS_DOWN);

            // We'll need to set the text of the imgToggle button,
            // so find it now.
            imgToggle =
                    (Button) mFeedFetcherDialog.findViewById(R.id.ffImgToggle);
        }

        // The dialog didn't exist yet, so create a new dialog.
        else {
            mFeedFetcherDialog = new Dialog(mActivity);
            mFeedFetcherDialog.setTitle("Feed fetcher progress");
            mFeedFetcherDialog.setContentView(R.layout.feedfetcher);

            mFeedFetcherText =
                    (TextView) mFeedFetcherDialog.findViewById(R.id.feedFetcherText);
            mFeedFetcherText.setMovementMethod(new ScrollingMovementMethod());

            imgToggle =
                    (Button) mFeedFetcherDialog.findViewById(R.id.ffImgToggle);
            imgToggle.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    //toggleFeedFetcherImages(v);
                    d("FeedViewer", "Can't toggle images yet");
                }
            });

            Button stopBtn =
                    (Button) mFeedFetcherDialog.findViewById(R.id.ffStopBtn);
            stopBtn.setOnClickListener(new OnClickListener() {
                public void onClick(View v) {
                    stopFetching();
                    d("FeedViewer", "Can't stop feeds yet");
                }
            });

            mFeedFetcherText.setText("Making a brand new dialog\n\n");
        }

        if (mFeedFetcher == null) {
            mFeedFetcherText.append("Creating a new Feed Fetcher\n");
            if (mFeedServer == null) {
                Log.d("FeedViewer", "Can't fetch feeds: mFeedServer is null!");
                return;
            }

            Log.d("FeedFetcher", "Creating a FeedFetcher with mFeedDir = "
                    + mFeedDir + " and server " + mFeedServer);
            mFeedFetcher = new FeedFetcher(mActivity, mFeedServer, mFeedDir.getAbsolutePath(),
                    new FeedProgress(mFeedFetcherText,
                            (ScrollView) mFeedFetcherDialog.findViewById(R.id.fetcherTextScroller)));
            if (!mFeedFetcher.fetchFeeds())
                mFeedFetcherText.append("\n\nCouldn't run fetchFeeds\n");
        }

        // Make sure the Toggle Images button matches the state of
        // mFeedFetcher. If we've run previously and the user has blocked
        // images, we want to keep that setting on.
        if (mFeedFetcher.mFetchImages) {
            mFeedFetcherText.append("Will be fetching images");
            imgToggle.setText("No images");
        } else {
            mFeedFetcherText.append("Will be SKIPPING images");
            imgToggle.setText("Images");
        }

        mFeedFetcherDialog.show();

        // Scroll to the bottom any time we view it.
        // If we're still fetching, this should happen automatically as
        // the FeedProgress updates, but if we're done fetching, it can
        // show the top of the dialog and it takes forever for the user
        // to fling-scroll all the way to the bottom.
        ((ScrollView) mFeedFetcherDialog.findViewById(R.id.fetcherTextScroller)).fullScroll(View.FOCUS_DOWN);
    }

    private void stopFetching() {
        mFeedFetcher.stop();
        mFeedFetcher = null;
        mFeedFetcherDialog.dismiss();

        // If currently displaying the feeds page, it will have
        // to be reloaded to show the newly-fetched entries.
        if (onFeedsPage())
            loadFeedList();
    }

    //////////// Done with feed fetching

    //////////// page navigation and utility functions

    // Figure out a sane URI that can be turned into a path
    // for the webview's current URI. Otherwise, we'll get things
    // like URISyntaxExceptions if there's a named anchor
    // or any other weirdness.
    // Returns null if not a file: URI.
    String getPathForURI() throws URISyntaxException {
        URI uri = new URI(getUrl());
        if (!uri.getScheme().equals("file"))
            return null;
        return uri.getPath();
    }

    Boolean nullURL(String url) {
        return (url == null || url.equals("null") || url.length() == 0
                || url.isEmpty() || url.equals("about:blank"));
    }

    boolean onFeedsPage(String url) {
        if (nullURL(url)) {
            return true;
        }
        if (url.startsWith("file://") &&
                (url.endsWith("/feeds") ||
                        url.endsWith("com.shallowsky.FeedViewer")))
            return true;
        return false;
    }

    boolean onFeedsPage() {
        d("FeedViewer", "getUrl is " + getUrl());
        return onFeedsPage(getUrl());
    }

    //
    // Go back to the previous page, or re-generate the feeds list if needed.
    //
    @Override
    public void goBack() {
        // Save scroll position in the current document, without a delay:
        savePagePos();

        String urlstring = getUrl();

        try {
            if (onFeedsPage(urlstring)) {
                // already on a generated page, probably feeds
                d("FeedViewer", "Already on feeds page");
                return;
            }

            // Now we know we have a location of some sort. Where is it?
            d("FeedViewer", "URI: " + urlstring);
            URI uri = new URI(urlstring);
            String scheme = uri.getScheme();
            final File filepath = new File(uri.getPath());
            d("FeedViewer", "path " + filepath.toString());
            d("FeedViewer", "up " + filepath.getParentFile().toString());
            d("FeedViewer", "upup " + filepath.getParentFile().getParentFile().toString());
            String upupup = filepath.getParentFile().getParentFile()
                    .getParentFile().getName();
            d("FeedViewer", "upup = " + upupup);
            d("FeedViewer", "file name = " + filepath.getName());

            // Unfortunately WebView doesn't handle history properly
            // for generated pages, so canGoBack() will return true
            // when back would lead to the feeds list (even though we
            // set the history entry to null), but then goBack() will
            // fail since it's forgotten the generated data.
            // So intercept the case where we're on a ToC page and back
            // would lead to the feeds list.
            Boolean upupFeeds = (upupup.equals("feeds") || upupup.equals("files") ||
                    upupup.equals("com.shallowsky.FeedViewer"));
            if (upupFeeds &&
                    filepath.getName().equals("index.html")) {
                loadFeedList();
            }
            /*
             * WebView.canGoBack() isn't reliable: it often returns true
             * even when it can't go back, and goBack() will be a no-op.
             * Unfortunately there's no way to check after calling goBack
             * that it failed, so the only option seems to be to
             * stop trusting canGoBack() and never use goBack().
            else if (mWebView.canGoBack()) {
                // Try to use mWebView.goBack() if we can:
                Log.d("FeedViewer", "Trying to goBack()");
                mWebView.goBack();
                mWebSettings.setDefaultFontSize(mFontSize);
                //updateBatteryLevel();
            }
             */
            else if (upupFeeds) {
                // We're on a third-level page;
                // go to the table of contents page for this feed.
                Log.d("FeedViewer", "going to table of contents");
                tableOfContents();
            } else {
                // Don't know where we are! Shouldn't happen, but probably does.
                d("FeedViewer", "Can't go back! " + uri);
                return;
            }
        } catch (Exception e) {
            Log.d("FeedViewer", "Ouch! " + getUrl());
            Log.d("FeedViewer", "Exception was: " + e.toString());
        }

        // Save the new document location regardless of where we ended up:
        // But this won't work because the document hasn't loaded yet.
        //saveStateInPreferences();
    }

    public void goForward() {
        savePagePos();
        super.goForward();
    }

    //////////// end page navigation

    //
    // Generate the Table of Contents for the current feed
    //
    public void tableOfContents() {
        if (onFeedsPage())
            return;

        // Save scroll position in the current document, without delay:
        savePagePos();

        // In theory, we're already in the right place, so just load relative
        // index.html -- but nope, that doesn't work.
        try {
            URI uri = new URI(getUrl());
            File feeddir = new File(uri.getPath()).getParentFile();
            loadUrl("file://" + feeddir.getAbsolutePath()
                    + File.separator + "index.html");
        } catch (URISyntaxException e) {
            d("FeedViewer", "ToC: URI Syntax: URL was " + getUrl());
        } catch (NullPointerException e) {
            d("FeedViewer", "NullPointerException: URL was " + getUrl());
        }
    }

    //
    // Recursively delete a directory.
    //
    boolean deleteDir(File dir) {
        if (dir.isDirectory()) {
            String[] children = dir.list();
            for (int i = 0; i < children.length; i++) {
                boolean success = deleteDir(new File(dir, children[i]));
                if (!success) {
                    d("FeedViewer", "Couldn't delete");
                    return false;
                }
            }
        }
        // The directory is now empty so delete it.
        return dir.delete();
    }

    //
    // Actually delete a feed, including the whole directory
    // and any related prefs.
    //
    public void deleteFeedDir(File feeddir) {
        Log.d("FeedViewer", "deleting " + feeddir.getAbsolutePath());

        deleteDir(feeddir);

        // If this was the last feed and the parent (daydir) is now
        // empty, delete it too. Don't want to do this in deleteDir()
        // since it would have to check on every recursion.
        File parent = feeddir.getParentFile();
        File[] children = parent.listFiles();
        if (children.length == 0)
            parent.delete();
        else {
            // There might still be files there, like LOG, but as long
            // as there are no more subdirectories, it's time to delete.
            Boolean hasChildDirs = false;
            for (int i = 0; i < children.length; ++i)
                if (children[i].isDirectory()) {
                    hasChildDirs = true;
                    break;
                }
            if (!hasChildDirs) {
                deleteDir(parent);
            }
        }

        // Load the feeds list before cleaning up scroll prefs.
        // Do this before cleaning up the scroll prefs, because
        // the first thing loadFeedList does is save position on the
        // old page, which we just deleted.
        loadFeedList();

        // Don't retain scroll position for deleted pages.
        cleanUpScrollPrefs();
    }

    //
    // Confirm whether to delete the current feed, then do so.
    //
    public void maybeDelete() {
        try {
            // We're reading a file in feeds/dayname/feedname/filename.html
            // or possibly the directory itself, feeds/dayname/feedname/
            // and what we want to delete is the dir feeds/dayname/feedname
            // along with everything inside it.
            final File feeddir;
            final File curfile = new File(getPathForURI());

            if (curfile.isDirectory())
                feeddir = curfile;
            else
                feeddir = curfile.getParentFile();
            final String feedname = feeddir.getName();
            final String dayname = feeddir.getParentFile().getName();

            // Pop up a question dialog:
            AlertDialog.Builder builder
                = new AlertDialog.Builder(mActivity, R.style.AlertDialogCustom);
            builder.setMessage("Delete" + dayname + " " + feedname + "?")
                    .setCancelable(false)
                    .setPositiveButton("Delete",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                                    int id) {
                                    deleteFeedDir(feeddir);
                                }
                            })
                    .setNegativeButton("Cancel",
                            new DialogInterface.OnClickListener() {
                                public void onClick(DialogInterface dialog,
                                                    int id) {
                                    dialog.cancel();
                                }
                            });
            AlertDialog alert = builder.create();
            alert.show();

        } catch (URISyntaxException e) {
            mActivity.showTextMessage("URI syntax exception on " + getUrl());
            e.printStackTrace();
        } catch (Exception e) {
            mActivity.showTextMessage("Couldn't delete " + getUrl());
        }

    }

    //
    // On an external link, the user gets a choice: save it to be fetched
    // by FeedMe tomorrow, view it right now in a browser, or cancel.
    //
    public void handleExternalLink(final String url) {
        // Pop up a dialog to ask what to do:
        AlertDialog.Builder builder
            = new AlertDialog.Builder(mActivity, R.style.AlertDialogCustom);
        builder.setMessage("Action for external link " + url + " ?")
                .setCancelable(false)
                .setNeutralButton("Save",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int id) {
                                saveUrlForLater(url);
                            }
                        })
                .setPositiveButton("Browse",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int id) {
                                Intent browserIntent
                                        = new Intent(Intent.ACTION_VIEW,
                                        Uri.parse(url));
                                mActivity.startActivity(browserIntent);
                            }
                        })
                .setNegativeButton("Cancel",
                        new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog,
                                                int id) {
                                dialog.cancel();
                            }
                        });
        AlertDialog alert = builder.create();
        alert.show();
    }

    // Add the URL to the saved-urls list to be fetched tomorrow.
    public void saveUrlForLater(String url) {
        String savedUrlPath = mFeedDir + File.separator + "saved-urls";
        try {
            FileOutputStream fos;
            fos = new FileOutputStream(new File(savedUrlPath), true);  // append
            //fos = openFileOutput("saved_urls", Context.MODE_APPEND);
            fos.write((url + "\n").getBytes());
            fos.close();
            mActivity.showTextMessage("Saved " + url);
        } catch (Exception e) {
            mActivity.showTextMessage("Couldn't save URL to " + savedUrlPath);
        }
    }

    ///////////////
    // Events
    //

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        this.mDetector.onTouchEvent(event);
        return super.onTouchEvent(event);
    }

    class MyGestureListener extends GestureDetector.SimpleOnGestureListener {
        @Override
        public boolean onDown(MotionEvent event) {
            return true;
        }

        /*
        @Override
        public boolean onFling(MotionEvent event1, MotionEvent event2,
                               float velocityX, float velocityY) {
            Log.d("FeedViewer",
                  "onFling: " + event1.toString() + event2.toString());
            return true;
        }
         */

        final double LEFTEDGE  = .23;
        final double RIGHTEDGE = .77;

        @Override
        public boolean onSingleTapUp(MotionEvent event) {
            DisplayMetrics metrics = new DisplayMetrics();
            mActivity.getWindowManager().getDefaultDisplay().getMetrics(metrics);
            double percentX = event.getRawX() / metrics.widthPixels;
            double percentY = event.getRawY() / metrics.heightPixels;

            // HACK: In theory, returning true means we handled the event,
            // don't pass it on. In practice, the web view follows
            // links regardless of what onSingleTapUp returns
            // (maybe it's going off a different event?
            // I haven't found any documentation that explains it)
            // so if we treat this tap as a scroll event, we also
            // have to inhibit link following for a short time.

            // Ignore taps near the X center:
            if (percentX > LEFTEDGE && percentX < RIGHTEDGE)
                return false;

            if (percentY < .2) {       // Near top
                int scrollpos = getScrollY();
                // Is the view scrolled to the top? Then ignore scroll-up taps.
                if (scrollpos == 0)
                    return false;

                pageUp(false);
                inhibitLinks();
                return true;
            }
            else if (percentY >.8) {  // Near bottom
                int scrollpos = getScrollY();

                // Is the view scrolled down to the bottom? Then ignore.
                // WebView.getContentHeight() is in some unspecified units that
                // must be scaled with getScale.
                int contentHeight = (int)(getContentHeight() * getScale());
                if (scrollpos + metrics.heightPixels >= contentHeight)
                    return false;

                pageDown(false);
                inhibitLinks();
                return true;
            }

            return false;
        }

    }

    // Temporarily inhibit link following (see comment in onSingleTapUp).
    public void inhibitLinks() {
        //d("FeedViewer", "Inhibiting links");
        mWebViewClient.mDontFollowLinks = true;
        postDelayed(new Runnable() {
            @Override
            public void run() {
                mWebViewClient.mDontFollowLinks = false;
                //d("FeedViewer", "reactivating links");
            }
        }, 500);
    }
}
