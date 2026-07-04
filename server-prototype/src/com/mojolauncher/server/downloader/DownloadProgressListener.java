package com.mojolauncher.server.downloader;

public interface DownloadProgressListener {
    void onProgress(String fileName, long downloaded, long total);
    void onComplete(String fileName);
    void onError(String fileName, Exception e);
}
