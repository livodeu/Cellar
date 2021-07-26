# Cellar
Cellar is a download manager for Android.

## Downloading files

### Automatic
Cellar offers itself as a target for data sent by other apps. 
It will then store the data locally on the device.
This app is intended to handle as many downloads as sensible and possible.
It will, however, not be adapted to sources that are not freely accessible, for example, to sources that need an account.

### Clipboard
While the app is open, it attempts to detect URLs being added to the system clipboard. It will then offer to download the data that the URL points to.
On devices running Android prior to version 10, it can monitor the clipboard even if the app is not active.

By the way, the [KDEConnect](https://community.kde.org/KDEConnect "https://invent.kde.org/network/kdeconnect-android") app can synchronize the clipboard with a 🐧 computer.

### Manually
Additionally, a download can be initiated manually via the app's "New" menu entry.

## Accessing files
Downloaded files are accessible via both the app and the system's documents app.

## Backing up files
To backup your downloaded files, use the corresponding entry in the settings which will pack your downloads into a zip file that you can send wherever you like.

## Prerequisites
Cellar needs Android version 5 (API 21) or newer to run.

## Credits
Cellar uses code from these projects (alphabetically):

- [FFmpegMediaMetadataRetriever](https://github.com/wseemann/FFmpegMediaMetadataRetriever) Apache License 2.0
- [jcraft](http://www.jcraft.com/jsch/) License: http://www.jcraft.com/jsch/LICENSE.txt
- [LibVLC](https://code.videolan.org/videolan/) LGPLv2
- [NewPipe](https://teamnewpipe.github.io/) GPLv3
- [okhttp](https://github.com/square/okhttp) Apache License 2.0
- [TiffBitmapFactory](https://github.com/Beyka/Android-TiffBitmapFactory) MIT License
- [zip4j](https://github.com/srikanth-lingala/zip4j) Apache License 2.0
