# FeedViewer

An Android program for offline reading of RSS feeds generated by FeedMe.

This is a new version of FeedViewer, compatible with more modern devices
like Android 10. (The old version of FeedViewer, in my *android* repository,
required a device with a menu button, and modern build tools like
Android Studio can't build or even import it.)


For information on building FeedViewer with Gradle, see the
[INSTALL document](INSTALL.md).


The FeedMe directory structure is documented below.
For information on how to configure a web server so that FeedViewer
can fetch daily feeds, see the
[FeedFetching document](FeedFetching.md).


## Wishlist

- Way to keep a directory of saved articles

- Toolbar buttons spaced out instead of right-justified
  (this may have something to do with the Toolbar's automatically
  created ActionMenuView, which isn't in the layout files so there's
  no obvious way to style it)
- Less toolbar vertical padding -- probably not possible, Android standard

- Allow font size changing
- Settings menu to include font sizing

### Low priority wishlist

- Space out buttons across toolbar instead of right justification
- Multiple style sheets, e.g. night and day
- Set brightness by dragging down the left side.


## Feed Directory Structure

FeedMe is a Python program for fetching RSS feeds once a day,
to read offline on a variety of devices. Learn more about it:
[FeedMe page on ShallowSky.com](https://shallowsky.com/software/feedme/)
[FeedMe source on GitHub](https://github.com/akkana/feedme)


FeedViewer reads, imports, and deletes collections of  HTML files in
directories arranged in the directory structure produced by FeedMe:

```
[feeds root]
|
|____feeds.css
|
|____11-16-Mon
|    |
|    |_ A_Word_A_Day
|    |      index.html
|    |      0.html
|    |
|    |_ Slashdot
|    |      index.html
|    |
|    |_ BBC_News_Science
|    |      index.html
|    |      0.html
|    |      1.html
|    |      2.html
|    |      long_name_1.jpg
|    |      long_name_2.png
```

(There are also a few other files that FeedViewer will ignore, like
_log-urlrss_ at the top level and _MANIFEST_ in each daily directory,
that are useful for debugging FeedMe problems.)

For each directory, the _index.html_ provides links to the numbered
HTML files.

You can put a feeds.css immediately under feeds on the server and
it will automatically be downloaded along with the day's feeds.

