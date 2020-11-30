//
// Fetch completed feedme feeds from a server and save them on this device.
//

package com.shallowsky.feedviewer;

import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.os.AsyncTask;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * FeedFetcher: fetch a directory of feeds (already converted from RSS)
 * from a server.
 * ServerURL: the base URL of the server.
 * LocalDir: the place where we'll save the feeds.
 *
 * First, we initiate the feed by fetching the special url
 *   $ServerURL/feedme/urlrss.cgi?xtraurls=STR
 * where STR is a concatenation of extra URLS wanted,
 * URL encoded and connected by the string '%0a'.
 * For instance,
 *   /feedme/urlrss.cgi?xtraurls=http%3A%2F%2Fblog.arduino.cc%2F2013%2F07%2F10%2Fsend-in-the-clones%2F%0ahttp%3A%2F%2Fread.bi%2F10Lbfh9%0ahttp%3A%2F%2Fwww.popsci.com%2Ftechnology%2Farticle%2F2013-07%2Fdrones-civil-war%0ahttp%3A%2F%2Fwww.thisamericanlife.org%2Fblog%2F2015%2F05%2Fcanvassers-study-in-episode-555-has-been-retracted
 * The saved URLs come from the (line separated) file $feeddir/saved-urls.
 *
 * Once the initial urlrss.cgi URL has been requested,
 * we wait for it to finish.
 * baseurl = serverurl + "/feeds/" + strftime("%m-%d-%a")
 * We wait for baseurl/MANIFEST to appear,
 * meanwhile showing progress by fetching baseurl and parsing it
 * to show which directories have appeared.
 *
 * Finally, when MANIFEST has appeared and stopped changing,
 * we download all files specified there.
 */

public class FeedFetcher {

    Context mContext;
    String mServerUrl;
    String mLocalDir;
    FeedProgress mFeedProgress;
    FetchFeedsTask mFetchTask = null;
    Boolean mFetchImages = true;
    String mSavedURLs;

    // We sometimes get zero or partial manifests.
    // So to make sure we've read the whole thing,
    // look for the special string ".EOF."
    // as the last line.
    String mEOFstr;
    int mEOFlen;

    // Ick ick ick! There's no way to pass multiple arguments to
    // publishProgress() or to overload it in order to do an optional
    // toast. So instead we use this class variable to signal that
    // the next progress update should also be toasted.
    // There must be a better way.
    // XXX Maybe the better way is just to call runOnUiThread for the
    // toasts rather than trusting publishProgress to manage threads.
    int mToastLength = 0;

    Boolean isStopped = false;

    public FeedFetcher(Context context, String serverurl, String localdir,
                       FeedProgress fp) {
        mContext = context;
        mServerUrl = serverurl;
        mLocalDir = localdir;
        mFeedProgress = fp;
        mEOFstr = ".EOF.";
        mEOFlen = mEOFstr.length();
        Log.d("FeedFetcher", "Initializing, mLocalDir = " + mLocalDir);
    }

    public void setServerURL(String serverurl) {
        mServerUrl = serverurl;
    }

    public void stop() {
        logProgress("Stopping FeedFetcher");
        if (mFetchTask == null) {
            // For some reason, we get into this clause
            // even when the task is still running.
            logProgress("Already stopped");
            return;
        }
        logProgress("Task still running: trying to cancel.");
        mFetchTask.cancel(true);
        // This might be too early to set mFetchTask to null:
        // I'm hoping it doesn't get garbage collected and
        // cleans up after itself, since I don't know how to
        // find out when it finishes.
        mFetchTask = null;

        // And send a signal to any downloaders:
        isStopped = true;
    }

    // Control whether images are fetched
    public Boolean toggleImages() {
        mFetchImages = !mFetchImages;
        if (mFetchImages)
            logProgress("Will include images.");
        else
            logProgress("NOT including images.");
        return mFetchImages;
    }

    // Fetch feeds. Return true for success or false otherwise.
    // We're still on the main thread here.
    public Boolean fetchFeeds() {
        Log.d("FeedFetcher", "Trying to fetch feeds.");

        // Before attempting to fetch anything, makes sure the net's up:
        ConnectivityManager connMgr = (ConnectivityManager)
            mContext.getSystemService(Context.CONNECTIVITY_SERVICE);
        // Next line crashes. Why?
        NetworkInfo networkInfo = connMgr.getActiveNetworkInfo();

        // If the net is up, start an async task to fetch the URL:
        if (networkInfo == null || !networkInfo.isConnected()) {
            logProgress("No network connection available.");
            return false;
        }

        // We must pass xtraurls= in the URL even if we have no extras,
        // because that's what urlrss.cgi uses to decide if it's
        // being called as a CGI script.
        String urlrssURL = mServerUrl + "/feedme/urlrss.cgi?xtraurls=";
        Boolean haveURLs = false;

        // Read any saved URLs we need to pass to urlrss.
        // XXX Of course this should be relative to wherever we're
        // reading feeds, or have several options of path. Eventually.
        mSavedURLs =
            mLocalDir + File.separator + "saved-urls";
        logProgress("Will look for saved urls in " + mSavedURLs);
        try {
            InputStream fis = new FileInputStream(mSavedURLs);
            logProgress("Opened saved urls file " + mSavedURLs);
            InputStreamReader isr = null;
            isr = new InputStreamReader(fis);
            BufferedReader br = new BufferedReader(isr);
            String line;
            while ((line = br.readLine()) != null) {
                if (haveURLs)
                    urlrssURL += "%0A";
                else {
                    haveURLs = true;
                }
                urlrssURL += URLEncoder.encode(line, "UTF-8");
                logProgress("URL: " + line);
            }
            isr.close();
            // Closing the reader closes the stream as well.
            // Doing it in finally {} is a lot more elaborate because
            // InputStreamReader.close() throws IOException so you have
            // to add another try inside the finally. <eyeroll>
            // Nobody seems to want to bother with this in code samples
            // I've found online, and neither do I.
        } catch(Throwable t) {
            logProgress("Couldn't read any saved urls");
            logProgress("looking in " + mSavedURLs);
            // Zero out the filename so we won't later try to delete it.
            mSavedURLs = null;
            urlrssURL += "none";
        }

        mFetchTask = new FetchFeedsTask(mLocalDir);
        mFetchTask.execute(urlrssURL);

        // Execute will return right away, while the task runs on.
        // XXX Which means that returning true or false from this function
        // is pretty pointless, and maybe should be rethought.
        return true;
    }

    // Fetch MANIFEST and check to make sure it's complete.
    // Throws IOException if the manifest isn't there.
    // If it's there but incomplete, loop up to a set number of times.
    String fetchManifest(String manifestURL) throws IOException {
        int MAX_RETRIES = 10;
        for (int i=0; i<MAX_RETRIES; ++i) {
            String manifest = downloadUrl(manifestURL);

            if (manifest.length() == 0) {
                Log.d("FeedFetcher",
                      "MANIFEST was zero length");
                throw new IOException("MANIFEST was empty");
            }

            Log.d("FeedFetcher", "Got MANIFEST. Was it complete?");
            int manlen = manifest.length() - 1;
            // Strip newlines off the end.
            // These should always be just \n,
            // but why count on it?
            while (manlen > 0 &&
                   (manifest.charAt(manlen) == '\n' ||
                    manifest.charAt(manlen) == '\r'))
                manlen -= 1;
            // manlen should now point to the last non-newline.

            String endman = manifest.substring(manlen-mEOFlen+1, manlen+1);
            if (endman.equals(mEOFstr)) {
                Log.d("FeedFetcher", "End of MANIFEST was fine");
                return manifest;
            }

            // We got a partial manifest. Sleep then loop around again.
            // In theory it shouldn't take long to write the manifest;
            // all feeds have been read, it's just writing a list of filenames.
            Log.d("FeedFetcher",
                  "Partial MANIFEST of length " + manlen);
            Log.d("FeedFetcher", "end string was " + endman);
            sleep(2000);
        }

        // If we get here and haven't returned a manifest after numerous tries,
        // there's a problem. Better throw an error.
        throw new IOException("MANIFEST not complete after " + MAX_RETRIES
                              + " tries");
    }

    // Most of FeedFetcher runs as an AsyncTask,
    // comuunicating back to the parent thread about its progress.
    // Within the AsyncTask, we'll just wait for feeds.
    // FeedFetcher can kill the task if it takes way too long.
    // https://developer.android.com/training/basics/network-ops/connecting.html
    private class FetchFeedsTask extends AsyncTask<String, String, String> {

        String mLocalDir = null;

        public FetchFeedsTask(String localdir) {
            mLocalDir = localdir;
        }

        @Override
        protected String doInBackground(String... urls) {
            // params comes from the execute() call: params[0] is the url.
            String output;
            String filepath = null;

            // Figure out our feed directory based on the date:
            Date curDate = new Date();
            SimpleDateFormat format = new SimpleDateFormat("MM-dd-EEE");
            String todayStr = format.format(curDate);
            String feeddirbase = mServerUrl + "/feeds/";
            String feeddir = feeddirbase + todayStr + "/";
            String manifestURL = feeddir + "MANIFEST";
            String manifest = null;

            // How many failures will we tolerate in a row before we abort?
            // (Probable sign the network has gone down.)
            int maxSuccessiveFailures = 3;
            int successiveFailures = 0;

            // How many total failures will we tolerate before we abort?
            // (Sign of a generally flaky connection.)
            int maxTotalFailures = 10;
            int totalFailures = 0;

            // Has feedme already run? Check whether the manifest
            // is already there.
            try {
                manifest = fetchManifest(manifestURL);
                publishProgress("Feedme already ran.\n");
            } catch (IOException e) {
                manifest = null;
                publishProgress("No MANIFEST there yet\n");
            }

            if (manifest == null) {
                // feedme hasn't finished running, but has it started?
                // If it has, the directory should be there.
                try {
                    output = downloadUrl(feeddir);
                } catch (IOException e) {
                    // Directory isn't there yet, so we need to run feedme.
                    output = null;
                }

                // First, call urlrss to initiate feedme:
                // XXX On the Galaxy S5 under Marshmallow, this
                // almost always fails the first time with an IOException.
                // It works the second time.
                // It always worked the first time on the Galaxy S4, KitKat.
                // urls[0] is something like:
                // http://example.com/feedme/urlrss.cgi?xtraurls=http%3A%2F%2Fwww.theatlantic.com%2Ftechnology%2Farchive%2F2013%2F12%2Fno-old-maps-actually-say-here-be-dragons%2F282267%2F%0Ahttp%3A%2F%2Frss.slashdot.org%2F%7Er%2FSlashdot%2Fslashdot%2F%7E3%2F4sxG352Ro_I%2Fbarnes-noble-announces-a-new-50-android-tablet
                // The second time, we don't fetch this because we see
                // that feedme already ran or is already running.
                // But we should also check for LOG in case it's
                // in themiddle of running but hasn't finished.
                if (output == null) {
                    try {
                        publishProgress(urls[0]);
                        output = downloadUrl(urls[0]);
                        publishProgress("\nStarting feedme ...\n");
                        publishProgress(output);
                    } catch (IOException e) {
                        // Ugly, but the only way to get stack trace as string:
                        StringWriter sw = new StringWriter();
                        PrintWriter pw = new PrintWriter(sw);
                        e.printStackTrace(pw);
                        return "Couldn't initiate feedme: IOException on "
                            + urls[0]
                            + "\n Exception is" + e.getMessage()
                            + "\n and stack trace is:\n" + sw.toString();
                    }
                }

                ////////////////////////////////////////////////////////
                // Feedme has been initiated.
                // Now, we wait for MANIFEST to appear,
                // periodically checking what's in the directory.
                int delay = 5000;   // polling interval, milliseconds
                Boolean feedmeRan = false;
                Set<String> subdirSet = new TreeSet<String>();
                while (true) {
                    sleep(delay);

                    // AsyncTask has to check itself for cancellation.
                    // But this doesn't work: even after calling cancel
                    // it runs forever.
                    if (isCancelled()) {
                        return "Cancelled -- not looking for more directories.";
                    }

                    // Now check what directories are there so far:
                    try {
                        output = downloadUrl(feeddir);
                    } catch (IOException e) {
                        publishProgress("Couldn't read dirs: IOException on "
                                        + feeddir);
                        continue;
                    }
                    List<String> subdirs = HTMLDirToList(output);
                    for (String subdir : subdirs) {
                        if (! subdirSet.contains(subdir)) {
                            subdirSet.add(subdir);
                            publishProgress("  " + subdir);
                        }
                        // Ideally it would be nice to show dots periodically,
                        // to show something's still happening, but it's
                        // not clear how to append to a textview without
                        // a newline and still see the update.
                        else
                            publishProgress(".");
                        if (subdir.startsWith("MANIFEST")) {
                            feedmeRan = true;
                        }
                    }

                    if (feedmeRan) {
                        // Feedme ran: get the manifest.
                        // But just because we've seen the manifest
                        // doesn't mean it's fully populated yet.
                        // Loop until it's really there, or we've
                        // waited too long for it.
                        IOException ioex = null;
                        for (int i=0; i<10; ++i) {
                            sleep(3000);

                            try {
                                manifest = fetchManifest(manifestURL);
                                if (manifest.length() > 0) {
                                    Log.d("FeedFetcher", "Got MANIFEST");
                                    break;
                                }
                                Log.d("FeedFetcher", "MANIFEST is zero-length");
                                continue;
                            } catch (IOException e) {
                                ioex = e;
                                Log.d("FeedFetcher", "No MANIFEST yet");
                                continue;
                            }
                        }
                        if (ioex != null || manifest.length() == 0)
                            return "Couldn't read MANIFEST";

                        // If we get here we have a nonzero manifest.
                        mToastLength = Toast.LENGTH_LONG;
                        publishProgress("feedme ran");

                        // Delete the local saved-urls file, if any.
                        if (mSavedURLs != null) {
                            File savefile = new File(mSavedURLs);
                            if (savefile.exists()) {
                                savefile.delete();
                                publishProgress("Deleted " + mSavedURLs);
                            }
                        }
                        break;
                    }
                }
            }
            Log.d("FeedFetcher", "Fetched complete manifest");

            ////////////////////////////////////////////////////////
            // Feedme ran and we fetched the manifest.
            // Now download the files.
            Log.d("FeedFetcher", "\n=======================\nDownloading");

            String datedir = mLocalDir + "/" + todayStr + "/";
            File dd = new File(datedir);
            dd.mkdir();
            String[] filenames = manifest.split("\n+");
            for (String f : filenames) {
                if (isCancelled())
                    return "Cancelling file downloads.";

                if (f.equals(mEOFstr)) {
                    Log.d("FeedDetcher", "Found EOF string, end of manifest");
                    continue;    // don't break, because ../feeds.css is after the MANIFEST
                }

                // Skip directories; we'll make them later with mkdirs.
                if (f.endsWith("/")) {
                    Log.d("FeedDetcher", f + " is a directory, skipping");
                    continue;
                }
                if (!mFetchImages) {
                    String fl = f.toLowerCase();
                    if (fl.endsWith(".jpg") || fl.endsWith("jpeg")
                        || fl.endsWith(".svg")
                        || fl.endsWith(".png") || fl.endsWith("gif")) {
                        Log.d("FeedFetcher", "Skipping image " + f);
                        continue;
                    }
                }
                String furl = feeddir + f;
                filepath = datedir + f;
                publishProgress("Saving " + furl);
                publishProgress("  to " + filepath);
                File fstat = new File(filepath);
                if (fstat.exists()) {
                    publishProgress(filepath + " is already here");
                    continue;
                }

                // Create the parent directories, if need be.
                File dirfile = fstat.getParentFile();
                Log.d("FeedDetcher", "dirfile is " + dirfile);
                if (!dirfile.exists()) {
                    Log.d("FeedDetcher", "mkdirs " + dirfile);
                    dirfile.mkdirs();
                    if (!dirfile.exists()) {
                        publishProgress("Skipping " + filepath
                                        + ", can't make directory "
                                        + dirfile.getPath());
                        continue;
                    }
                }

                try {
                    FileOutputStream fos = new FileOutputStream(fstat);
                    downloadUrlToFile(furl, fos);
                    fos.close();
                    successiveFailures = 0;
                } catch (FileNotFoundException e) {
                    publishProgress("Skipping " + filepath
                                    + ":  FileNotFoundException: "
                                    + fstat);
                    continue;
                } catch (IOException e) {
                    // The download failed, maybe a network timeout.
                    // Have we had so many failures that we should give up?
                    // XXX Would be nice to treat html and images differently,
                    // be more persistent for HTML.
                    publishProgress("Couldn't download " + furl
                                    + ": IOException");
                    if (++successiveFailures >= maxSuccessiveFailures) {
                        return "More than " + maxSuccessiveFailures
                            + " successive download failures: giving up.";
                    }
                    if (++totalFailures >= maxTotalFailures) {
                        return "More than " + maxTotalFailures
                            + " total download failures: giving up.";
                    }
                    continue;
                }
            }

            // Try to get the feeds.css, in case it's changed.
            try {
                String cssurl = feeddirbase  + "/feeds.css";
                String cssfile = mLocalDir + "/feeds.css";
                FileOutputStream fos = new FileOutputStream(cssfile);
                downloadUrlToFile(cssurl, fos);
                fos.close();
                publishProgress("Fetched css file from " + cssurl + " to " + cssfile);
            } catch (Exception e) {
                publishProgress("Couldn't fetch css file");
            }

            mToastLength = Toast.LENGTH_LONG;
            publishProgress("Fetched feeds");
            return "Finished fetching feeds";
        }

        /**
         * Show progress in the log, in the dialog,
         * and optionally as a toast of a given length.
         * This is called on the UI thread from publishProgress().
         */
        protected void onProgressUpdate(String... progress) {
            logProgress(progress[0]);

            /*
            // This is always supposed to be called on the UI thread,
            // according to the AsyncTask docs.
            // But we're getting some weird behaviors, so just in case:
            if (Looper.myLooper() != Looper.getMainLooper()) {
                throw new AssertionError("Not the main thread!");
            }
            */
            if (mToastLength > 0) {
                Toast.makeText(mContext, progress[0], mToastLength).show();
                mToastLength = 0;
            }
        }

        // onPostExecute displays the return value of the AsyncTask.
        // It's run on the UI thread.
        protected void onPostExecute(String message) {
            logProgress("\nDone with FeedFetcher!\n");
            logProgress(message);

            // We're done, so no point in our parent holding on to the task.
            mFetchTask = null;
        }

        // I think onCancelled is also run on the UI thread.
        protected void onCancelled(String message) {
            logProgress("FeedFetcher task cancelled: message: " + message);
            mFetchTask = null;
        }
    }

    private void sleep(int millisecs) {
        try {
            Thread.sleep(millisecs);
        } catch (InterruptedException e) {
            // Thread.sleep() requires that we catch this.
            // But throwing this error clears the interrupt bit,
            // so in case we actually needed to be interrupted:
            Thread.currentThread().interrupt();
        }
    }

    private List<String> HTMLDirToList(String html) {
        List<String> subdirs = new ArrayList<String>();

        String linkpat = "<a [^>]+>(.+?)</a>";
        Matcher matcher = Pattern.compile(linkpat,
                   Pattern.CASE_INSENSITIVE|Pattern.DOTALL).matcher(html);
        Boolean started = false;
        while (matcher.find()) {
            if (started)
                subdirs.add(matcher.group(1));
            if (matcher.group(1).equals("Parent Directory"))
                started = true;
        }
        return subdirs;
    }

    private void logProgress(String s) {
        // Special case for dot: don't include a newline.
        if (s.equals("."))
            mFeedProgress.log(" . ");
        else
            mFeedProgress.log(s + "\n");
        Log.d("FeedFetcher", s);
    }

    private void logProgressOnUIThread(String s) {
        // Should append to the textview in the FeedViewer dialog.
        Log.d("FeedFetcher", s);
        // The call inside runOnUiThread doesn't see s,
        // but it will see a new final string that's a copy of s:
        final String ss = "(subthread) " + s + "\n";
        ((Activity)mContext).runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mFeedProgress.log(ss);
                }
            });
    }

    // Given a URL, establishes an HttpUrlConnection and retrieves
    // the web page content as a InputStream, which it returns as
    // a string. Synchronous.
    // Don't use this for non-string content, like files with image data.
    private String downloadUrl(String urlstr) throws IOException {
        Log.d("FeedFetcher", "downloadUrl " + urlstr);
        InputStream is = null;

        try {
            URL url = new URL(urlstr);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            Log.d("FeedFetcher", "Set up connection");
            conn.setReadTimeout(10000    /* milliseconds */);
            conn.setConnectTimeout(15000 /* milliseconds */);
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            Log.d("FeedFetcher", "Set all the timeouts");
            // Starts the query
            conn.connect();
            Log.d("FeedFetcher", "Connected");

            // getResponseCode() will throw IOException rather than
            // returning the response code if it thinks it's a
            // code that indicates an error, e.g. 404.
            int response = conn.getResponseCode();
            Log.d("FeedFetcher",
                  "Response code: " + response + " for " + urlstr);
            /*
            logProgressOnUIThread("Response code: " + response);
            if (response != 200)
                logProgressOnUIThread("Response code: " + response);
            else
                Log.d("FeedFetcher", "Response code: " + response);
            */

            // If we get here, presumably there's something there to read.
            is = conn.getInputStream();
            Log.d("FeedFetcher", "Got the input stream");

            // Convert the InputStream into a string
            String contentAsString = readIt(is);
            Log.d("FeedFetcher", "Read the output");
            return contentAsString;

            // Makes sure that the InputStream is closed after the app is
            // finished using it.
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    // Given a URL, establishes an HttpUrlConnection and retrieves
    // the web page content as a byte stream, then writes it to a file.
    // Ick: this has a lot of duplicated code from the previous function.
    private void downloadUrlToFile(String urlstr, FileOutputStream fos)
        throws IOException {

        Log.d("FeedFetcher", "downloadUrlToFile " + urlstr);
        InputStream is = null;

        int bufferSize = 1024;
        byte[] buffer = new byte[bufferSize];

        try {
            URL url = new URL(urlstr);
            HttpURLConnection conn = (HttpURLConnection)url.openConnection();
            conn.setReadTimeout(10000);      /* milliseconds */
            conn.setConnectTimeout(15000);   /* milliseconds */
            conn.setRequestMethod("GET");
            conn.setDoInput(true);
            // Starts the query
            conn.connect();
            int response = conn.getResponseCode();
            Log.d("FeedFetcher", "Response code: " + response);
            if (response != 200)
                logProgressOnUIThread("Response code: " + response);
            is = conn.getInputStream();
            Log.d("FeedFetcher", "Got input stream");

            // we need to know how may bytes were read
            // to write them to the output stream
            int len = 0;
            while ((len = is.read(buffer)) != -1) {
                fos.write(buffer, 0, len);
                if (isStopped)
                    break;
            }

            // Makes sure that the InputStream is closed after the app is
            // finished using it.
        } finally {
            if (is != null) {
                is.close();
            }
        }
    }

    // Reads an InputStream and converts it to a String.
    // http://stackoverflow.com/a/5445161
    public String readIt(InputStream stream)
        throws IOException, UnsupportedEncodingException {
        java.util.Scanner s = new java.util.Scanner(stream).useDelimiter("\\A");
        return s.hasNext() ? s.next() : "";
    }

}
