# FeedViewer

An Android program for offline reading of RSS feeds generated by FeedMe

This is a new version of FeedViewer, compatible with more modern devices
like Android 10. (The old version of FeedViewer, in my *android* repository,
required a device with a menu button, and modern build tools like
Android Studio can't build or even import it.)

## Warning

This new version of FeedMe is not yet fully functional.
In particular, it doesn't remember scroll position for each feed,
so it's likely to be frustrating to use.
I'll remove this warning when that's fixed.

## To Do

Important things not yet implemented:

- Save scroll position
- Allow font resizing
- fetch feeds.css from server


## Feed Directory Structure

FeedMe is a Python program for fetching RSS feeds once a day,
to read offline on a variety of devices. Learn more about it:
https://shallowsky.com/software/feedme/
https://github.com/akkana/feedme


FeedViewer reads, imports, and deletes HTML files in directories
arranged in the directory structure produced by FeedMe:

```
[root]
|    feeds.css
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
... etc.

For each directory, the _index.html_ provides links to the numbered
HTML files.

feeds.css is not fetched automatically; you'll have to copy it to
your Android device yourself. I hope to make this automatic eventually.

## Fetching Feeds

Of course, you can set up the above directory structure by hand, and
update it with whatever file transfer tools work for you. But if
you have control over a web server, you
can trigger FeedViewer to run feedme on your own web server
each day, then download the result.

Unfortunately setting this up is a bit tricky. Here's a quick sketch.
If you find I've missed anything important in this description

1. The server root is the name of your web server,
e.g. ```https://example.com```.
(I think, theoretically, you could use https://example.com/some/dir,
but I've only tested it at the top level of a server.)

2. In your server root, create the following structure:

```
[web root]
|
|___feedme/
|   |    feedme           (a link to feedme.py)
|   |    feedmeparser.py  (link to feedmeparser.py)
|   |    urlrss.cgi       (executable by the web server)
|   |
|   |__  .config.
|   |    |
|   |    |___ feedme.
|   |    |    |   feedme.conf
|   |    |    |   a-word-a-day.conf, etc. ...
|   |
|   |__ .cache/           (writable by the web server)
|        |
|
|___feeds/                (writable by the web server)
|   |
```

Populate feedme/.config with the feedme conf files you want to use,
as if it were ~/.config/feedme.
(I use symbolic links to the directory where I've
checked out the feedme source, e.g.
[web root]/feedme/.config/feedme/a-word-a-day.conf
links to ~/src/feedme/siteconf/a-word-a-day.conf.)

The first time you run FeedFetcher's *Fetch feeds* menu item,
it will prompt for your server root. Then it will visit
*$SERVER_ROOT/urlrss.cgi*, which will kick off a feedme process.
FeedFetcher will then poll periodically, waiting for the MANIFEST
file that signals feedme has fetched everything, and then FeedFetcher
will download all the files in today's feed.

Sorry this is so complicated. But at least it only has to be done once.
