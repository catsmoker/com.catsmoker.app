package com.catsmoker.app;

import android.annotation.SuppressLint;
import android.app.DownloadManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.WebChromeClient;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.ProgressBar;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class WebsiteActivity extends AppCompatActivity {

    private static final String HOME_URL = "https://catsmoker.github.io/";
    private static final String PING_URL = "https://www.google.com";
    private static final int TIMEOUT_MS = 3000;
    private WebView webView;
    private ProgressBar progressBar;
    private ExecutorService executorService;
    private Handler mainHandler;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_website);
        setTitle("CatSmoker Website");

        executorService = Executors.newSingleThreadExecutor();
        mainHandler = new Handler(Looper.getMainLooper());

        initializeViews();
        configureWebView();
        handleBackButton();
        loadWebViewContent();
    }

    private void initializeViews() {
        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        webView.setLayerType(WebView.LAYER_TYPE_HARDWARE, null);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setLoadWithOverviewMode(true);
        webView.getSettings().setUseWideViewPort(true);
        webView.getSettings().setCacheMode(android.webkit.WebSettings.LOAD_NO_CACHE);

        webView.setWebViewClient(new CustomWebViewClient());
        webView.setWebChromeClient(new CustomWebChromeClient());
        webView.setDownloadListener(this::handleDownload);
    }

    private void handleBackButton() {
        OnBackPressedCallback callback = new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack();
                } else if (!HOME_URL.equals(webView.getUrl())) {
                    webView.loadUrl(HOME_URL);
                } else {
                    finish();
                }
            }
        };
        getOnBackPressedDispatcher().addCallback(this, callback);
    }

    private void loadWebViewContent() {
        progressBar.setVisibility(View.VISIBLE);
        checkInternetConnection(isConnected -> {
            progressBar.setVisibility(View.GONE);
            if (isConnected) {
                webView.loadUrl(HOME_URL);
            } else {
                loadLocalErrorPage();
            }
        });
    }

    private void checkInternetConnection(InternetCheckCallback callback) {
        // Step 1: Check network connectivity
        ConnectivityManager connectivityManager = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean isNetworkAvailable = false;

        if (connectivityManager != null) {
            NetworkCapabilities capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.getActiveNetwork());
            isNetworkAvailable = capabilities != null && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }

        if (!isNetworkAvailable) {
            callback.onResult(false);
            return;
        }

        executorService.execute(() -> {
            boolean hasInternet = pingTest();
            mainHandler.post(() -> callback.onResult(hasInternet));
        });
    }

    private boolean pingTest() {
        try {
            URL url = new URL(PING_URL);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            return responseCode >= 200 && responseCode < 300;
        } catch (IOException e) {
            return false;
        }
    }

    private interface InternetCheckCallback {
        void onResult(boolean isConnected);
    }

    private void loadLocalErrorPage() {
        try (InputStream inputStream = getAssets().open("no_internet.html")) {
            byte[] buffer = new byte[inputStream.available()];
            int bytesRead = inputStream.read(buffer);
            if (bytesRead != -1) {
                String htmlContent = new String(buffer, StandardCharsets.UTF_8);
                webView.loadDataWithBaseURL(null, htmlContent, "text/html", StandardCharsets.UTF_8.name(), null);
            }
        } catch (IOException e) {
            Toast.makeText(this, "No internet and failed to load error page", Toast.LENGTH_SHORT).show();
        }
    }

    private boolean isInternalUrl(String url) {
        return url.contains("catsmoker.github.io") ||
                url.contains("github.com") ||
                url.contains("objects.githubusercontent.com") ||
                url.contains("catsmoker-lab.blogspot.com") ||
                url.contains("catsmoker.pages.dev");
    }

    private void openExternalUrl(String url) {
        Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        startActivity(intent);
    }

    private void handleDownload(String url, String userAgent, String contentDisposition, String mimetype, long contentLength) {
        String fileName = URLUtil.guessFileName(url, contentDisposition, mimetype);

        if (!fileName.contains(".")) {
            String extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype);
            if (extension != null) {
                fileName += "." + extension;
            }
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        request.setMimeType(mimetype);
        request.addRequestHeader("User-Agent", userAgent);
        request.addRequestHeader("Accept-Encoding", "identity");
        request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, fileName);

        DownloadManager downloadManager = (DownloadManager) getSystemService(Context.DOWNLOAD_SERVICE);
        if (downloadManager != null) {
            downloadManager.enqueue(request);
            Toast.makeText(this, "Download started: " + fileName, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "DownloadManager unavailable", Toast.LENGTH_SHORT).show();
        }
    }

    private class CustomWebViewClient extends WebViewClient {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            progressBar.setVisibility(View.GONE);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, android.webkit.WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (isInternalUrl(url)) {
                return false;
            } else {
                openExternalUrl(url);
                return true;
            }
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (isInternalUrl(url)) {
                return false;
            } else {
                openExternalUrl(url);
                return true;
            }
        }
    }

    private class CustomWebChromeClient extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progressBar.setProgress(newProgress);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (executorService != null) {
            executorService.shutdown();
        }
        if (webView != null) {
            webView.destroy();
        }
    }
}