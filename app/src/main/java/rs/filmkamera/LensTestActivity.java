package rs.filmkamera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.util.SizeF;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Test screen: a real Android camera (Camera2) with 1x / 2x / 3x zoom, to check whether
 * the phone switches to its telephoto lens for other apps. Shows which physical lens is active.
 */
public class LensTestActivity extends Activity {
    private TextureView view;
    private TextView info;
    private CameraManager cm;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder req;
    private HandlerThread thread;
    private Handler bg;
    private String camId;
    private float zoom = 1f;
    private String activePhysical = "?";
    private float reportedZoom = 0f;
    private long lastUi = 0;
    private String allCams = "";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        cm = (CameraManager) getSystemService(Context.CAMERA_SERVICE);

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        view = new TextureView(this);
        root.addView(view, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        info = new TextView(this);
        info.setTextColor(Color.WHITE);
        info.setTextSize(13);
        info.setBackgroundColor(0x99000000);
        info.setPadding(30, 30, 30, 30);
        FrameLayout.LayoutParams ip = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        root.addView(info, ip);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setBackgroundColor(0x99000000);
        bottom.setPadding(20, 20, 20, 40);

        LinearLayout zooms = new LinearLayout(this);
        zooms.setGravity(Gravity.CENTER);
        for (final float z : new float[]{0.6f, 1f, 2f, 3f, 5f}) {
            Button btn = new Button(this);
            btn.setText(z == 0.6f ? "0.6×" : ((int) z) + "×");
            btn.setOnClickListener(v -> setZoom(z));
            zooms.addView(btn);
        }
        bottom.addView(zooms);

        // one button per back camera id, to try each lens directly
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout ids = new LinearLayout(this);
        StringBuilder sb = new StringBuilder();
        String firstBack = null;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                boolean back = f != null && f == CameraCharacteristics.LENS_FACING_BACK;
                sb.append("#").append(id).append(back ? " back " : " front ").append(mm(ch)).append("mm");
                if (Build.VERSION.SDK_INT >= 28 && !ch.getPhysicalCameraIds().isEmpty()) {
                    sb.append(" lenses[");
                    for (String p : ch.getPhysicalCameraIds()) {
                        sb.append(p).append(":").append(mm(cm.getCameraCharacteristics(p))).append("mm ");
                    }
                    sb.append("]");
                }
                if (Build.VERSION.SDK_INT >= 30) {
                    Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                    if (zr != null) sb.append(" zoom ").append(zr.getLower()).append("–").append(zr.getUpper());
                }
                sb.append("\n");
                if (back) {
                    if (firstBack == null) firstBack = id;
                    final String cid = id;
                    Button btn = new Button(this);
                    btn.setText("Camera #" + id);
                    btn.setOnClickListener(v -> { zoom = 1f; open(cid); });
                    ids.addView(btn);
                }
            }
        } catch (Exception e) {
            sb.append("Error: ").append(e.getMessage());
        }
        allCams = sb.toString();
        hs.addView(ids);
        bottom.addView(hs);

        root.addView(bottom, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        camId = firstBack;
        updateInfo();
    }

    private static long mm(CameraCharacteristics ch) {
        float[] fl = ch.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        SizeF ss = ch.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        if (fl == null || fl.length == 0 || ss == null) return 0;
        return Math.round(fl[0] * 43.27 / Math.hypot(ss.getWidth(), ss.getHeight()));
    }

    private void updateInfo() {
        final String text = "LENS TEST — cover each back lens with a finger to see which one is in use\n\n"
                + "Camera #" + camId + "   requested " + zoom + "×   reported " + (reportedZoom > 0 ? reportedZoom + "×" : "?") + "\n"
                + "Active physical lens: " + activePhysical + "\n\n" + allCams;
        runOnUiThread(() -> info.setText(text));
    }

    @Override
    protected void onResume() {
        super.onResume();
        thread = new HandlerThread("lens");
        thread.start();
        bg = new Handler(thread.getLooper());
        if (view.isAvailable()) open(camId);
        else view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { open(camId); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });
    }

    @Override
    protected void onPause() {
        close();
        if (thread != null) { thread.quitSafely(); thread = null; }
        super.onPause();
    }

    private void close() {
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (device != null) device.close(); } catch (Exception ignored) {}
        session = null;
        device = null;
    }

    @SuppressLint("MissingPermission")
    private void open(String id) {
        if (id == null || bg == null) return;
        close();
        camId = id;
        activePhysical = "?";
        reportedZoom = 0f;
        try {
            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) { device = d; startPreview(); }
                @Override public void onDisconnected(CameraDevice d) { d.close(); }
                @Override public void onError(CameraDevice d, int e) { d.close(); activePhysical = "camera error " + e; updateInfo(); }
            }, bg);
        } catch (Exception e) {
            activePhysical = "open failed: " + e.getMessage();
            updateInfo();
        }
    }

    private void startPreview() {
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size best = new Size(1920, 1080);
            if (map != null) {
                for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
                    if (s.getWidth() <= 1920 && s.getHeight() <= 1440 && s.getWidth() * 3 == s.getHeight() * 4) { best = s; break; }
                }
            }
            SurfaceTexture st = view.getSurfaceTexture();
            st.setDefaultBufferSize(best.getWidth(), best.getHeight());
            Surface surface = new Surface(st);
            req = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            req.addTarget(surface);
            req.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            device.createCaptureSession(Arrays.asList(surface), new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession s) { session = s; applyZoom(); }
                @Override public void onConfigureFailed(CameraCaptureSession s) { activePhysical = "session failed"; updateInfo(); }
            }, bg);
        } catch (Exception e) {
            activePhysical = "preview failed: " + e.getMessage();
            updateInfo();
        }
    }

    private void setZoom(float z) {
        zoom = z;
        if (bg != null) bg.post(this::applyZoom);
    }

    private void applyZoom() {
        if (session == null || req == null) return;
        try {
            CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            boolean ratioApi = false;
            if (Build.VERSION.SDK_INT >= 30) {
                Range<Float> zr = ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
                if (zr != null) {
                    float z = Math.max(zr.getLower(), Math.min(zr.getUpper(), zoom));
                    req.set(CaptureRequest.CONTROL_ZOOM_RATIO, z);
                    ratioApi = true;
                }
            }
            if (!ratioApi) {
                Rect a = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
                float z = Math.max(1f, zoom);
                int w = (int) (a.width() / z), h = (int) (a.height() / z);
                req.set(CaptureRequest.SCALER_CROP_REGION, new Rect(a.centerX() - w / 2, a.centerY() - h / 2, a.centerX() + w / 2, a.centerY() + h / 2));
            }
            session.setRepeatingRequest(req.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(CameraCaptureSession s, CaptureRequest r, TotalCaptureResult res) {
                    long now = System.currentTimeMillis();
                    if (now - lastUi < 400) return;
                    lastUi = now;
                    if (Build.VERSION.SDK_INT >= 29) {
                        String p = res.get(CaptureResult.LOGICAL_MULTI_CAMERA_ACTIVE_PHYSICAL_ID);
                        if (p != null) {
                            try { activePhysical = "#" + p + " (" + mm(cm.getCameraCharacteristics(p)) + "mm)"; }
                            catch (CameraAccessException e) { activePhysical = "#" + p; }
                        } else {
                            activePhysical = "not reported by the phone";
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 30) {
                        Float zr = res.get(CaptureResult.CONTROL_ZOOM_RATIO);
                        if (zr != null) reportedZoom = zr;
                    }
                    updateInfo();
                }
            }, bg);
        } catch (Exception e) {
            activePhysical = "zoom failed: " + e.getMessage();
            updateInfo();
        }
    }
}
