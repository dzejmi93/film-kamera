package rs.filmkamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.KeyEvent;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.ValueCallback;
import android.content.ClipData;
import java.util.ArrayList;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Shows the camera page. The page develops the photos itself; this class only
 * gives it the camera, saves photos to the gallery and hands them to Google Drive.
 * Nothing is ever sent to a server.
 */
public class MainActivity extends Activity {
    private static final String HOST = "filmkamera.local";
    private static final String START = "https://" + HOST + "/index.html";
    private static final String DRIVE = "com.google.android.apps.docs";
    private static final int REQ_CAMERA = 1;
    private static final int REQ_STORAGE = 2;

    private WebView web;
    private PermissionRequest pending;
    private ValueCallback<Uri[]> fileCallback;
    private static final int REQ_FILES = 3;

    public class Bridge {
        /** Saves a JPEG (base64) to Pictures/Film Kamera. Returns true when saved. */
        @JavascriptInterface
        public boolean saveImage(String base64, String name) {
            try {
                return saveToGallery(Base64.decode(base64, Base64.DEFAULT), safeName(name));
            } catch (Exception e) {
                return false;
            }
        }

        /** Opens Google Drive (or the share menu) with this photo. */
        @JavascriptInterface
        public boolean shareImage(String base64, String name, boolean drive) {
            try {
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File f = new File(dir, safeName(name));
                FileOutputStream out = new FileOutputStream(f);
                out.write(Base64.decode(base64, Base64.DEFAULT));
                out.close();
                Uri uri = ShareProvider.uriFor(f);
                Intent send = new Intent(Intent.ACTION_SEND)
                        .setType("image/jpeg")
                        .putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                if (drive && isInstalled(DRIVE)) {
                    send.setPackage(DRIVE);
                    startActivity(send);
                } else {
                    startActivity(Intent.createChooser(send, "Send photo"));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Several photos at once: clear, add one by one, then send. */
        @JavascriptInterface
        public void stageClear() {
            File dir = new File(getCacheDir(), "share");
            if (!dir.exists()) dir.mkdirs();
            File[] old = dir.listFiles();
            if (old != null) for (File f : old) f.delete();
        }

        @JavascriptInterface
        public boolean stageImage(String base64, String name) {
            try {
                File dir = new File(getCacheDir(), "share");
                if (!dir.exists()) dir.mkdirs();
                FileOutputStream out = new FileOutputStream(new File(dir, safeName(name)));
                out.write(Base64.decode(base64, Base64.DEFAULT));
                out.close();
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Sends the staged photos to one app (Google Photos, Drive) or, with "", to the share menu. */
        @JavascriptInterface
        public boolean sendStaged(String pkg) {
            try {
                File[] files = new File(getCacheDir(), "share").listFiles();
                if (files == null || files.length == 0) return false;
                ArrayList<Uri> uris = new ArrayList<>();
                for (File f : files) uris.add(ShareProvider.uriFor(f));
                Intent send;
                if (uris.size() == 1) {
                    send = new Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris.get(0));
                } else {
                    send = new Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
                }
                send.setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                ClipData clip = ClipData.newRawUri("", uris.get(0));
                for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
                send.setClipData(clip);
                if (pkg != null && !pkg.isEmpty()) {
                    if (!isInstalled(pkg)) return false;
                    send.setPackage(pkg);
                    startActivity(send);
                } else {
                    startActivity(Intent.createChooser(send, "Send photos"));
                }
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        /** Screen flash for selfies: full brightness (1), or back to the system setting (-1). */
        @JavascriptInterface
        public void setBrightness(final float v) {
            runOnUiThread(() -> {
                WindowManager.LayoutParams lp = getWindow().getAttributes();
                lp.screenBrightness = v < 0 ? WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE : Math.min(1f, v);
                getWindow().setAttributes(lp);
            });
        }

        @JavascriptInterface
        public boolean hasDrive() {
            return isInstalled(DRIVE);
        }
    }

    private static String safeName(String n) {
        String s = (n == null ? "KADAR.jpg" : n).replaceAll("[^A-Za-z0-9_.-]", "_");
        return s.endsWith(".jpg") ? s : s + ".jpg";
    }

    private boolean isInstalled(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean saveToGallery(byte[] data, String name) throws Exception {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentResolver r = getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            v.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            v.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Kadar");
            v.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v);
            if (uri == null) return false;
            OutputStream out = r.openOutputStream(uri);
            if (out == null) return false;
            out.write(data);
            out.close();
            v.clear();
            v.put(MediaStore.Images.Media.IS_PENDING, 0);
            r.update(uri, v, null, null);
            return true;
        }
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return false;
        }
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Kadar");
        if (!dir.exists()) dir.mkdirs();
        File f = new File(dir, name);
        FileOutputStream out = new FileOutputStream(f);
        out.write(data);
        out.close();
        MediaScannerConnection.scanFile(this, new String[]{f.getAbsolutePath()}, new String[]{"image/jpeg"}, null);
        return true;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        web = new WebView(this);
        web.setBackgroundColor(Color.BLACK);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest req) {
                Uri u = req.getUrl();
                if (!HOST.equals(u.getHost())) return null;
                String path = u.getPath();
                if (path == null || path.equals("/") || path.isEmpty()) path = "/index.html";
                try {
                    InputStream in = getAssets().open(path.substring(1));
                    return new WebResourceResponse(mime(path), "UTF-8", in);
                } catch (Exception e) {
                    return new WebResourceResponse("text/plain", "UTF-8", 404, "Not found", null, null);
                }
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req) {
                return !HOST.equals(req.getUrl().getHost());
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            /** Upload button: pick photos from the phone's gallery. */
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> cb, FileChooserParams params) {
                if (fileCallback != null) fileCallback.onReceiveValue(null);
                fileCallback = cb;
                Intent pick = new Intent(Intent.ACTION_GET_CONTENT)
                        .addCategory(Intent.CATEGORY_OPENABLE)
                        .setType("image/*")
                        .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
                try {
                    startActivityForResult(Intent.createChooser(pick, "Choose photos"), REQ_FILES);
                } catch (Exception e) {
                    fileCallback = null;
                    return false;
                }
                return true;
            }

            @Override
            public void onPermissionRequest(final PermissionRequest request) {
                runOnUiThread(() -> {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                        grantCamera(request);
                    } else {
                        pending = request;
                        requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                    }
                });
            }
        });

        web.addJavascriptInterface(new Bridge(), "Native");
        setContentView(web);
        web.loadUrl(START);
    }

    private static String mime(String p) {
        if (p.endsWith(".html")) return "text/html";
        if (p.endsWith(".js")) return "application/javascript";
        if (p.endsWith(".css")) return "text/css";
        if (p.endsWith(".png")) return "image/png";
        if (p.endsWith(".webmanifest") || p.endsWith(".json")) return "application/json";
        return "application/octet-stream";
    }

    private void grantCamera(PermissionRequest r) {
        for (String res : r.getResources()) {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(res)) {
                r.grant(new String[]{PermissionRequest.RESOURCE_VIDEO_CAPTURE});
                return;
            }
        }
        r.deny();
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_FILES || fileCallback == null) return;
        Uri[] out = null;
        if (result == RESULT_OK && data != null) {
            if (data.getClipData() != null) {
                ClipData c = data.getClipData();
                out = new Uri[c.getItemCount()];
                for (int i = 0; i < c.getItemCount(); i++) out[i] = c.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                out = new Uri[]{data.getData()};
            }
        }
        fileCallback.onReceiveValue(out);
        fileCallback = null;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code == REQ_CAMERA && pending != null) {
            if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) grantCamera(pending);
            else pending.deny();
            pending = null;
        }
    }

    /** Volume buttons take the photo, like a real camera. */
    @Override
    public boolean onKeyDown(int keyCode, KeyEvent e) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            if (e.getRepeatCount() == 0 && web != null) web.evaluateJavascript("window.__shutter&&window.__shutter()", null);
            return true;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (web == null) { super.onBackPressed(); return; }
        web.evaluateJavascript("window.__back?window.__back():false", v -> {
            if (!"true".equals(v)) MainActivity.super.onBackPressed();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) { web.onResume(); web.resumeTimers(); }
    }

    @Override
    protected void onPause() {
        if (web != null) { web.onPause(); web.pauseTimers(); }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (web != null) web.destroy();
        super.onDestroy();
    }
}
