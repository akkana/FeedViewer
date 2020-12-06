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
import android.widget.Toast;

import static android.util.Log.*;

public class MainActivity extends AppCompatActivity {

    FeedWebView mWebView;
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

        mWebView = (FeedWebView)findViewById(R.id.webview);
        mWebView.setActivity(this);
    }

    // onSaveInstanceState() is called whenever the app is going to go away,
    // onStop, or on configuration (like orientation) change, etc.
    // Save prefs.
    @Override
    public void onSaveInstanceState(Bundle outState) {
        mWebView.saveScrollPos();
        super.onSaveInstanceState(outState);
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
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            mWebView.goBack();
            return true;
        }

        return super.onKeyDown(keyCode, event);
    }

    public void showTextMessage(String msg) {
        Toast.makeText(this, "Settings not yet implemented",
                Toast.LENGTH_LONG).show();
    }
}
