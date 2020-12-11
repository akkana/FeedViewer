# Fetching Feeds from a Server

Of course, you can set up the above directory structure by hand, and
update it with whatever file transfer tools work for you. But if
you have control over a web server, you
can trigger FeedViewer to run feedme on your own web server
each day, then download the result.

Unfortunately setting this up is a bit tricky. Here's a quick sketch.
If you find I've missed anything important in this description,
let me know: I'd like to make the documentation more complete.

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
