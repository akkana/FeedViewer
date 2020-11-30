//
// The main activity controlling FeedViewer
//

package com.shallowsky.feedviewer;

import android.os.Bundle;

import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.android.material.snackbar.Snackbar;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

import android.os.SystemClock;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;

import android.view.Menu;
import android.view.MenuItem;
import android.view.WindowManager;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;

import static android.util.Log.*;

public class MainActivity extends AppCompatActivity implements GestureDetector.OnGestureListener {

    FeedWebView mWebView;
    TextView mStatusBar;
    int mBrightness;
    long mScrollLock;
    private GestureDetector mDetector;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        getSupportActionBar().setDisplayShowTitleEnabled(false);

        mStatusBar = (TextView)findViewById(R.id.statusbar);
        mWebView = (FeedWebView)findViewById(R.id.webview);
        mWebView.setActivity(this);

        mDetector = new GestureDetector(this, this);

        // This is supposed to hide the navigation bar at the bottom
        // of the screen, but it comes back whenever a user taps near
        // the bottom, which makes it super confusing.
        // Better to just put up with losing the extra couple lines.
        /*
        View decorView = getWindow().getDecorView();
        // Hide both the navigation bar and the status bar.
        int uiOptions = View.SYSTEM_UI_FLAG_HIDE_NAVIGATION;
        decorView.setSystemUiVisibility(uiOptions);
         */

        //mStatusBar.setBackgroundColor(0x00334444);
        //mStatusBar.setCursorVisible(false);
        //mStatusBar.setTextColor(0xffffffff);
        showTextMessage(":-)");
    }
    @Override
    public void onDestroy() {
        mWebView.cleanUp();
        super.onDestroy();
    }

    @Override
    public void onPause() {
        mWebView.cleanUp();
        super.onPause();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        if (id == R.id.action_settings) {
            mWebView.editSettings();
            return true;
        }
        else if (id == R.id.action_back) {
            mWebView.goBack();
            return true;
        }
        else if (id == R.id.action_del) {
            mWebView.maybeDelete();
            return true;
        }
        /* For some reason, Android Studio doesn't accept toc or ToC even though it's defined
           identically to the other symbols: */
        else if (id == R.id.action_toc) {
            mWebView.tableOfContents();
            return true;
        }
         /* */
        else if (id == R.id.action_feeds) {
            mWebView.loadFeedList();
            return true;
        }
        else if (id == R.id.action_fetch) {
            mWebView.fetchFeeds();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    public void setBrightness(int value) {
        // If mBrightness is 0, brightness probably hasn't been read
        // from preferences yet.
        if (mBrightness <= 0)
            return;

        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = (float) value / 100;
        //lp.flags |= WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON;

        // This is supposed to turn off the annoying button lights: see
        // http://developer.android.com/reference/android/view/WindowManager.LayoutParams.html#buttonBrightness
        // Alas, it doesn't actually do anything.
        //lp.buttonBrightness = LayoutParams.BRIGHTNESS_OVERRIDE_OFF;

        getWindow().setAttributes(lp);

        // Save the new brightness:
        //saveStateInPreferences();
    }
    /************** Events **************/

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        showTextMessage("");
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mWebView.goBack();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    /*
     * For some reason, onTouchEvent() is needed to catch events on a WebView.
     * THANK YOU,

     * http://www.tutorialforandroid.com/2009/06/implement-gesturedetector-in-android.html
     * also, http://stackoverflow.com/questions/9519559/handle-touch-events-inside-webview-in-android
     *
     * For these gesture events, return true if we consumed the event,
     * false if we want the event to be handled normally. Except of
     * course for LongPress which for some reason doesn't let us
     * return a status, so we have to go through absurd machinations
     * to prevent triggering links.
     * XXX Though, something to try for the LongPress case: XXX
     * http://stackoverflow.com/questions/3329871/android-webview-long-press-not-on-link-i-e-in-white-space
     *
     * @see android.app.Activity#dispatchTouchEvent(android.view.MotionEvent)
    */
    @Override
    public boolean dispatchTouchEvent(MotionEvent me) {
        //showTextMessage("");
        super.dispatchTouchEvent(me);
        return mDetector.onTouchEvent(me);
    }

    // Tapping in the corners of the screen scroll up or down.
    public boolean scrollIfInTargetZone(MotionEvent e) {
        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        float screenWidth = metrics.widthPixels;
        float screenHeight = metrics.heightPixels;

        // Only accept taps in the corners, not the center --
        // otherwise we'll have no way to tap on links at top/bottom.
        float w = screenWidth / 4;
        if (e.getRawX() > w && e.getRawX() < screenWidth - w) {
            return false;
        }

        // Was the tap at the top or bottom of the screen?
        if (e.getRawY() > screenHeight * .8) {
            mScrollLock = SystemClock.uptimeMillis();
            mWebView.pageDown(false);
            // Don't try to save page position: we'll do that after scroll
            // when we have a new page position.
           return true;
        }
        // ... or near the top?
        else if (e.getRawY() < screenHeight * .2) {
            // ICK! but how do we tell how many pixels the buttons take? XXX
            mScrollLock = SystemClock.uptimeMillis();
            mWebView.pageUp(false);
            // Again, don't save page position here, wait for callback.
           return true;
        }

        // Else the tap was somewhere else: pass it along.
        return false;
    }

    // From the docs, I would have assumed I should use onSingleTapConfirmed
    // or maybe onTouchEvent to handle taps. But in practice those never fire,
    // and onSingleTapUp works. Go figure.
    public boolean onSingleTapConfirmed(MotionEvent e) {
        //showTextMessage("onSingleTapConfirmed");
        return scrollIfInTargetZone(e);
    }

    public boolean onSingleTapUp(MotionEvent e) {
        //showTextMessage("onSingleTapUp");
        return scrollIfInTargetZone(e);
    }

    // Set brightness if the user scrolls (drags)
    // along the left edge of the screen
    public boolean onScroll(MotionEvent e1, MotionEvent e2,
                            float distanceX, float distanceY) {
        final int XTHRESH = 30;    // How close to the left edge need it be?
        if (e1.getRawX() > XTHRESH) return false;
        if (e2.getRawX() > XTHRESH) return false;
        if (distanceY == 0) return false;

        DisplayMetrics metrics = new DisplayMetrics();
        getWindowManager().getDefaultDisplay().getMetrics(metrics);
        float screenWidth = metrics.widthPixels;
        float screenHeight = metrics.heightPixels;

        int y = (int)(screenHeight - e2.getRawY());
        int b = (int)(y * 100 / screenHeight);
        showTextMessage("bright " + b + " (y = " + y
                        + "/" + screenHeight + ")");
        setBrightness(b);
        mBrightness = b;
        return true;
    }

    /***** Events we're required to override to implement OnGestureListener
     *     even if we don't use them.
     */

    // Would like to use longpress for something useful, like following
    // a link or viewing an image, if I could figure out how.
    public void onLongPress(MotionEvent e) {
        // Want to convert a longpress into a regular single tap,
        // so the user can longpress to activate links rather than scrolling.
        showTextMessage("onLongPress");
        super.onTouchEvent(e);
        //scrollIfInTargetZone(e);
    }

    public boolean onDown(MotionEvent e) {
        //showTextMessage("onDown");
        return false;
    }

    // Horizontal flings do back/forward.
    // In practice this can be a pain since it can interfere with h scrolling.
    // Try to tune the velocity so that only real flings get caught here.
    public boolean onFling(MotionEvent e1, MotionEvent e2,
                           float velocityX, float velocityY) {
    /*
        //showTextMessage(Float.toString(velocityX));

        // If the event is too short, ignore it
        if (Math.abs(e1.getX() - e2.getX()) < 250.)
            return false;

        // If there was much vertical movement, ignore it:
        if (Math.abs(e1.getY() - e2.getY()) > 20.)
            return false;

        if (velocityX < -750.) {
            goBack();
            return true;
        }
        else if (velocityX > 750.) {
            goForward();
            return true;
        }
    */
        return false;
    }

    public void onShowPress(MotionEvent e) {
        //showTextMessage("onShowPress");
    }

    /* We don't actually have to implement onDoubleTap:
    public boolean onDoubleTap(MotionEvent e) {
        showTextMessage("onDoubleTap");
        return super.onTouchEvent(e);
    }
    */

    /***** End required OnGestureListener events we don't actually use */

    public void showTextMessage(String msg) {
        mStatusBar.setText(msg);
    }
}