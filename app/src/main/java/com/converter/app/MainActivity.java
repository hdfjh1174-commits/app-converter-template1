package com.converter.app;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.webkit.GeolocationPermissions;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import java.io.FileOutputStream;
import java.io.OutputStream;

public class MainActivity extends Activity {

    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;
    private static final int FILE_CHOOSER_REQUEST_CODE = 51426;
    private static final int RUNTIME_PERMISSION_REQUEST_CODE = 51427;

    @SuppressLint("SetJavaScriptEnabled")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Ask for whichever dangerous runtime permissions this build enabled
        // (config flags are injected at build time — see res/values/config.xml)
        java.util.List<String> perms = new java.util.ArrayList<>();
        if (getResources().getBoolean(R.bool.enable_camera)) perms.add(Manifest.permission.CAMERA);
        if (getResources().getBoolean(R.bool.enable_location)) perms.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if (getResources().getBoolean(R.bool.enable_microphone)) perms.add(Manifest.permission.RECORD_AUDIO);
        java.util.List<String> toRequest = new java.util.ArrayList<>();
        for (String p : perms) {
            if (checkSelfPermission(p) != PackageManager.PERMISSION_GRANTED) {
                toRequest.add(p);
            }
        }
        if (!toRequest.isEmpty()) {
            requestPermissions(toRequest.toArray(new String[0]), RUNTIME_PERMISSION_REQUEST_CODE);
        }

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setAllowFileAccess(true);
        settings.setDatabaseEnabled(true);
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
        settings.setGeolocationEnabled(getResources().getBoolean(R.bool.enable_location));
        settings.setMediaPlaybackRequiresUserGesture(false);

        webView.setWebViewClient(new WebViewClient());

        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                filePathCallback = callback;
                Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                try {
                    startActivityForResult(Intent.createChooser(intent, "اختر ملف"), FILE_CHOOSER_REQUEST_CODE);
                } catch (Exception e) {
                    filePathCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onGeolocationPermissionsShowPrompt(String origin, GeolocationPermissions.Callback callback) {
                boolean allow = getResources().getBoolean(R.bool.enable_location)
                        && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
                callback.invoke(origin, allow, false);
            }

            @Override
            public void onPermissionRequest(PermissionRequest request) {
                java.util.List<String> granted = new java.util.ArrayList<>();
                for (String resource : request.getResources()) {
                    if (resource.equals(PermissionRequest.RESOURCE_VIDEO_CAPTURE)
                            && getResources().getBoolean(R.bool.enable_camera)
                            && checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        granted.add(resource);
                    }
                    if (resource.equals(PermissionRequest.RESOURCE_AUDIO_CAPTURE)
                            && getResources().getBoolean(R.bool.enable_microphone)
                            && checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                        granted.add(resource);
                    }
                }
                if (!granted.isEmpty()) {
                    request.grant(granted.toArray(new String[0]));
                } else {
                    request.deny();
                }
            }
        });

        // Native bridge so the web page can save files reliably (fixes the
        // classic WebView blob-download problem generic wrapper tools hit).
        webView.addJavascriptInterface(new WebAppInterface(), "AndroidBridge");

        String targetUrl = getString(R.string.target_url);
        if (targetUrl == null || targetUrl.trim().isEmpty()) {
            webView.loadUrl("file:///android_asset/index.html");
        } else {
            webView.loadUrl(targetUrl);
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) {
                super.onActivityResult(requestCode, resultCode, data);
                return;
            }
            Uri[] results = null;
            if (resultCode == Activity.RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    int count = data.getClipData().getItemCount();
                    results = new Uri[count];
                    for (int i = 0; i < count; i++) {
                        results[i] = data.getClipData().getItemAt(i).getUri();
                    }
                } else if (data.getData() != null) {
                    results = new Uri[]{data.getData()};
                }
            }
            filePathCallback.onReceiveValue(results);
            filePathCallback = null;
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    @Override
    public void onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack();
        } else {
            super.onBackPressed();
        }
    }

    private class WebAppInterface {

        @JavascriptInterface
        public void saveFile(String base64Data, String filename, String mimeType) {
            runOnUiThread(() -> {
                try {
                    String pureBase64 = base64Data;
                    int commaIndex = base64Data.indexOf(',');
                    if (base64Data.startsWith("data:") && commaIndex != -1) {
                        pureBase64 = base64Data.substring(commaIndex + 1);
                    }
                    byte[] bytes = Base64.decode(pureBase64, Base64.DEFAULT);

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        ContentValues values = new ContentValues();
                        values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
                        values.put(MediaStore.Downloads.MIME_TYPE, mimeType);
                        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

                        Uri uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                        if (uri != null) {
                            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                                if (out != null) {
                                    out.write(bytes);
                                }
                            }
                            Toast.makeText(MainActivity.this, "تم الحفظ في: التنزيلات/" + filename, Toast.LENGTH_LONG).show();
                        }
                    } else {
                        java.io.File downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                        if (!downloadsDir.exists()) downloadsDir.mkdirs();
                        java.io.File outFile = new java.io.File(downloadsDir, filename);
                        try (FileOutputStream fos = new FileOutputStream(outFile)) {
                            fos.write(bytes);
                        }
                        Toast.makeText(MainActivity.this, "تم الحفظ في: التنزيلات/" + filename, Toast.LENGTH_LONG).show();
                    }
                } catch (Exception e) {
                    Toast.makeText(MainActivity.this, "تعذّر الحفظ: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            });
        }
    }
}
