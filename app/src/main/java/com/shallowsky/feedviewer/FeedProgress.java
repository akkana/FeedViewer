package com.shallowsky.feedviewer;

import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

public class FeedProgress {
    TextView mTextView;
    ScrollView mScrollView;

    public FeedProgress(TextView tv, ScrollView sv) {
        mTextView = tv;
        mScrollView = sv;
    }

    public void log(String s) {
        mTextView.append(s);

        // The rest of this class is an attempt to get the dialog
        // to scroll to the bottom any time it's shown,
        // preferably without flashing horribly or hanging the UI,
        // which most of these solutions do if they do anything at all.

        // Scroll to the bottom. http://stackoverflow.com/a/4612082
        // (The easier and top-rated solution in that thread didn't work
        // because layout was null.)
        mScrollView.post(new Runnable() {
            public void run() {
                mScrollView.fullScroll(View.FOCUS_DOWN);
            }
        });

        /* Here are some more non-working solutions, from
         * http://stackoverflow.com/questions/19826693/how-can-i-make-a-textview-automatically-scroll-as-i-add-more-lines-of-text
         * The first one doesn't even compile because ScrollView has no
         * method setSelection().
         */
        //mTextView.setSelection(mTextView.getText().length());

        /* These two sorta work, but make the screen flash so much
         * you can't read it and make for a huge delay between when
         * you show the dialog and when you can do anything else.
         * XXX Maybe each of these scrollTo events takes a finite amount
         * of time and the events aren't collapsed together?
         * Maybe if we could batch them by timestamp somehow?
        mTextView.scrollTo(0, Integer.MAX_VALUE);
        mScrollView.scrollTo(0, Integer.MAX_VALUE);
         */

        /* Next attempt crashes with a NullPointerException on the first line:
        final int scrollAmount = mTextView.getLayout().getLineTop(mTextView.getLineCount()) - mTextView.getHeight();
        // if there is no need to scroll, scrollAmount will be <=0
        if (scrollAmount > 0)
            mTextView.scrollTo(0, scrollAmount);
        else
            mTextView.scrollTo(0, 0);
         */
    }
}
