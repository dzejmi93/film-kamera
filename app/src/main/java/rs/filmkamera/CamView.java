package rs.filmkamera;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.view.Surface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The real Android camera (Camera2) with the film look drawn live on the GPU.
 * Sits behind the transparent WebView; the page tells it where the viewfinder is,
 * which film is selected, the zoom, focus point, exposure and when to take a photo.
 * Full-resolution photos are handed back to the page, which develops them as before.
 */
public class CamView extends GLSurfaceView implements GLSurfaceView.Renderer, SurfaceTexture.OnFrameAvailableListener {

    public interface PhotoListener { void onPhoto(int id); void onError(String msg); }

    private final Activity act;
    private final CameraManager cm;
    private PhotoListener listener;

    // ---------- camera state ----------
    private HandlerThread thread;
    private Handler bg;
    private CameraDevice device;
    private CameraCaptureSession session;
    private CaptureRequest.Builder preview;
    private ImageReader reader;
    private Surface previewSurface;
    private String camId;
    private boolean front;
    private boolean wantOpen;
    private int sensorOrientation = 90;
    private Rect activeArray;
    private Range<Float> zoomRange;
    private Range<Integer> evRange = new Range<>(0, 0);
    private float evStep = 1f;
    private boolean hasFlash;
    private int maxAfRegions, maxAeRegions;
    private Size previewSize = new Size(1440, 1080);
    private float zoom = 1f, zoomShown = 1f;
    private int evSteps = 0;
    private boolean flashOn;
    private MeteringRectangle[] focusRegion;
    private Runnable focusReset;
    private Runnable zoomAnim;
    public volatile int deviceRotation = 0;
    private int photoSeq = 0;
    public final ConcurrentHashMap<Integer, byte[]> photos = new ConcurrentHashMap<>();

    // ---------- GL state ----------
    private SurfaceTexture st;
    private int oesTex = -1, lutTex, halTex, fbo;
    private int prMain, prDown;
    private final float[] stMat = new float[16];
    private FloatBuffer quad;
    private int viewW = 1, viewH = 1, halW, halH;
    private volatile boolean surfaceReady = false;

    // ---------- film (set from the page) ----------
    private volatile byte[] lut;
    private volatile boolean lutDirty = true;
    private volatile float sat = 1f, grain = 0f, grainColor = 0f, vignette = 0f, halation = 0f, halThresh = .72f;
    private volatile boolean bw = false;
    private final float[] hues = new float[21];

    public CamView(Activity a) {
        super(a);
        act = a;
        cm = (CameraManager) a.getSystemService(Activity.CAMERA_SERVICE);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        setRenderer(this);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        for (int i = 0; i < 7; i++) hues[i * 3 + 1] = 1f;
        byte[] l = new byte[256 * 4];
        for (int i = 0; i < 256; i++) { l[i * 4] = l[i * 4 + 1] = l[i * 4 + 2] = (byte) i; l[i * 4 + 3] = (byte) 255; }
        lut = l;
    }

    public void setListener(PhotoListener l) { listener = l; }

    /** True when this phone supports what we need (Android 11+ zoom ratio on a back camera). */
    public static boolean supported(CameraManager cm) {
        if (Build.VERSION.SDK_INT < 30) return false;
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics ch = cm.getCameraCharacteristics(id);
                Integer f = ch.get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == CameraCharacteristics.LENS_FACING_BACK) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    // ======================= page → camera =======================

    public void setFilm(String json) {
        try {
            JSONObject o = new JSONObject(json);
            JSONArray l = o.getJSONArray("lut");
            byte[] b = new byte[256 * 4];
            for (int i = 0; i < 256; i++) {
                b[i * 4] = (byte) l.getInt(i);
                b[i * 4 + 1] = (byte) l.getInt(256 + i);
                b[i * 4 + 2] = (byte) l.getInt(512 + i);
                b[i * 4 + 3] = (byte) 255;
            }
            sat = (float) o.optDouble("sat", 1);
            bw = o.optBoolean("bw", false);
            grain = (float) o.optDouble("grain", 0);
            grainColor = (float) o.optDouble("grainColor", 0);
            vignette = (float) o.optDouble("vignette", 0);
            halation = (float) o.optDouble("halation", 0);
            halThresh = (float) o.optDouble("halThresh", .72);
            JSONArray h = o.optJSONArray("hues");
            for (int i = 0; i < 7; i++) {
                if (h != null && i < h.length()) {
                    JSONArray b3 = h.getJSONArray(i);
                    hues[i * 3] = (float) b3.getDouble(0); hues[i * 3 + 1] = (float) b3.getDouble(1); hues[i * 3 + 2] = (float) b3.getDouble(2);
                } else { hues[i * 3] = 0; hues[i * 3 + 1] = 1; hues[i * 3 + 2] = 0; }
            }
            lut = b;
            lutDirty = true;
            requestRender();
        } catch (Exception ignored) {}
    }

    public synchronized void start(boolean useFront) {
        front = useFront;
        wantOpen = true;
        if (thread == null) {
            thread = new HandlerThread("kadar-cam");
            thread.start();
            bg = new Handler(thread.getLooper());
        }
        if (surfaceReady) bg.post(this::openCamera);
    }

    public synchronized void stop() {
        wantOpen = false;
        if (bg != null) bg.post(this::closeCamera);
    }

    public String info() {
        try {
            JSONObject o = new JSONObject();
            o.put("evMin", evRange.getLower() * evStep);
            o.put("evMax", evRange.getUpper() * evStep);
            o.put("evStep", evStep);
            o.put("hasFlash", hasFlash);
            o.put("zoomMax", zoomRange != null ? zoomRange.getUpper() : 1);
            o.put("front", front);
            return o.toString();
        } catch (Exception e) { return "{}"; }
    }

    // ======================= camera =======================

    @SuppressLint("MissingPermission")
    private void openCamera() {
        closeCamera();
        if (!wantOpen || st == null) return;
        try {
            camId = null;
            int want = front ? CameraCharacteristics.LENS_FACING_FRONT : CameraCharacteristics.LENS_FACING_BACK;
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == want) { camId = id; break; }   // first one = the main (logical) camera
            }
            if (camId == null) { fail("No camera"); return; }
            CameraCharacteristics ch = cm.getCameraCharacteristics(camId);
            Integer so = ch.get(CameraCharacteristics.SENSOR_ORIENTATION);
            sensorOrientation = so == null ? 90 : so;
            activeArray = ch.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
            zoomRange = Build.VERSION.SDK_INT >= 30 ? ch.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) : null;
            Range<Integer> er = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            evRange = er != null ? er : new Range<>(0, 0);
            Rational rs = ch.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
            evStep = rs != null ? rs.floatValue() : 1f;
            Boolean fl = ch.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
            hasFlash = fl != null && fl;
            Integer ma = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
            Integer me = ch.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
            maxAfRegions = ma == null ? 0 : ma;
            maxAeRegions = me == null ? 0 : me;

            StreamConfigurationMap map = ch.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size pv = new Size(1440, 1080), jp = new Size(4000, 3000);
            if (map != null) {
                Size bestP = null, bestJ = null;
                for (Size s : map.getOutputSizes(SurfaceTexture.class)) {
                    if (s.getWidth() * 3 == s.getHeight() * 4 && s.getWidth() <= 1920 && (bestP == null || s.getWidth() > bestP.getWidth())) bestP = s;
                }
                for (Size s : map.getOutputSizes(ImageFormat.JPEG)) {
                    if (s.getWidth() * 3 == s.getHeight() * 4 && (bestJ == null || (long) s.getWidth() * s.getHeight() > (long) bestJ.getWidth() * bestJ.getHeight())) bestJ = s;
                }
                if (bestP != null) pv = bestP;
                if (bestJ != null) jp = bestJ;
            }
            previewSize = pv;
            st.setDefaultBufferSize(pv.getWidth(), pv.getHeight());
            previewSurface = new Surface(st);
            reader = ImageReader.newInstance(jp.getWidth(), jp.getHeight(), ImageFormat.JPEG, 2);
            reader.setOnImageAvailableListener(r -> {
                Image img = r.acquireNextImage();
                if (img == null) return;
                ByteBuffer buf = img.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buf.remaining()];
                buf.get(bytes);
                img.close();
                int id = ++photoSeq;
                photos.put(id, bytes);
                if (listener != null) listener.onPhoto(id);
            }, bg);

            cm.openCamera(camId, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice d) {
                    device = d;
                    try {
                        d.createCaptureSession(Arrays.asList(previewSurface, reader.getSurface()), new CameraCaptureSession.StateCallback() {
                            @Override public void onConfigured(CameraCaptureSession s) {
                                session = s;
                                try {
                                    preview = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                    preview.addTarget(previewSurface);
                                    zoomShown = zoom;
                                    applyPreview();
                                } catch (Exception e) { fail("Preview: " + e.getMessage()); }
                            }
                            @Override public void onConfigureFailed(CameraCaptureSession s) { fail("Camera session failed"); }
                        }, bg);
                    } catch (Exception e) { fail("Session: " + e.getMessage()); }
                }
                @Override public void onDisconnected(CameraDevice d) { d.close(); device = null; }
                @Override public void onError(CameraDevice d, int e) { d.close(); device = null; fail("Camera error " + e); }
            }, bg);
        } catch (Exception e) {
            fail("Open: " + e.getMessage());
        }
    }

    private void closeCamera() {
        if (zoomAnim != null && bg != null) bg.removeCallbacks(zoomAnim);
        if (focusReset != null && bg != null) bg.removeCallbacks(focusReset);
        try { if (session != null) session.close(); } catch (Exception ignored) {}
        try { if (device != null) device.close(); } catch (Exception ignored) {}
        try { if (reader != null) reader.close(); } catch (Exception ignored) {}
        try { if (previewSurface != null) previewSurface.release(); } catch (Exception ignored) {}
        session = null; device = null; reader = null; previewSurface = null; preview = null;
    }

    private void fail(String m) { if (listener != null) listener.onError(m); }

    /** Applies zoom, exposure, flash and focus to the live preview. */
    private void applyPreview() {
        if (session == null || preview == null) return;
        try {
            fill(preview, zoomShown);
            preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);
            session.setRepeatingRequest(preview.build(), null, bg);
        } catch (Exception ignored) {}
    }

    private void fill(CaptureRequest.Builder b, float z) {
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);
        b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        b.set(CaptureRequest.FLASH_MODE, flashOn && hasFlash ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, Math.max(evRange.getLower(), Math.min(evRange.getUpper(), evSteps)));
        if (focusRegion != null) {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            if (maxAfRegions > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, focusRegion);
            if (maxAeRegions > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, focusRegion);
        } else {
            b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            b.set(CaptureRequest.CONTROL_AF_REGIONS, null);
            b.set(CaptureRequest.CONTROL_AE_REGIONS, null);
        }
        if (Build.VERSION.SDK_INT >= 30 && zoomRange != null) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, Math.max(zoomRange.getLower(), Math.min(zoomRange.getUpper(), z)));
        } else if (activeArray != null) {
            float zz = Math.max(1f, z);
            int w = (int) (activeArray.width() / zz), h = (int) (activeArray.height() / zz);
            b.set(CaptureRequest.SCALER_CROP_REGION, new Rect(activeArray.centerX() - w / 2, activeArray.centerY() - h / 2, activeArray.centerX() + w / 2, activeArray.centerY() + h / 2));
        }
    }

    /**
     * Zoom. 1x↔2x glides; going to 3x always jumps from 1x, because the phone only
     * switches to its telephoto lens on a jump from 1x (tested on the Galaxy S23).
     */
    public void setZoom(final float target) {
        if (bg == null) return;
        bg.post(() -> {
            if (zoomAnim != null) bg.removeCallbacks(zoomAnim);
            zoom = target;
            final float from = zoomShown;
            if (target >= 2.5f || from >= 2.5f) {
                if (target >= 2.5f && from > 1.05f) {
                    zoomShown = 1f; applyPreview();
                    zoomAnim = () -> { zoomShown = target; applyPreview(); };
                    bg.postDelayed(zoomAnim, 160);
                } else { zoomShown = target; applyPreview(); }
                return;
            }
            final long t0 = System.currentTimeMillis();
            zoomAnim = new Runnable() {
                @Override public void run() {
                    float t = Math.min(1f, (System.currentTimeMillis() - t0) / 320f);
                    float e = t < .5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
                    zoomShown = from + (target - from) * e;
                    applyPreview();
                    if (t < 1f) bg.postDelayed(this, 16);
                }
            };
            bg.post(zoomAnim);
        });
    }

    public void setEV(final float ev) {
        if (bg == null) return;
        bg.post(() -> { evSteps = Math.round(ev / (evStep > 0 ? evStep : 1f)); applyPreview(); });
    }

    public void setFlash(final boolean on) {
        if (bg == null) return;
        bg.post(() -> { flashOn = on; /* torch only while taking the photo */ });
    }

    /** Tap to focus. u, v: 0..1 from the top-left of the viewfinder. */
    public void focus(final float u, final float v) {
        if (bg == null) return;
        bg.post(() -> {
            if (session == null || preview == null || activeArray == null) return;
            // viewfinder → camera frame (the viewfinder crops the frame to fill it)
            float[] sc = coverScale();
            float x = (u - .5f) * sc[0] + .5f, y = (v - .5f) * sc[1] + .5f;
            // upright frame → sensor (landscape) coordinates
            float sx, sy;
            int rot = sensorOrientation % 360;
            if (front) x = 1 - x;
            switch (rot) {
                case 90: sx = y; sy = 1 - x; break;
                case 180: sx = 1 - x; sy = 1 - y; break;
                case 270: sx = 1 - y; sy = x; break;
                default: sx = x; sy = y;
            }
            int W = activeArray.width(), H = activeArray.height(), half = (int) (Math.min(W, H) * .06f);
            int cx = (int) (sx * W), cy = (int) (sy * H);
            Rect r = new Rect(Math.max(0, cx - half), Math.max(0, cy - half), Math.min(W - 1, cx + half), Math.min(H - 1, cy + half));
            focusRegion = new MeteringRectangle[]{ new MeteringRectangle(r, MeteringRectangle.METERING_WEIGHT_MAX - 1) };
            try {
                fill(preview, zoomShown);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL);
                session.capture(preview.build(), null, bg);
                preview.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
                session.capture(preview.build(), null, bg);
                applyPreview();
            } catch (Exception ignored) {}
            if (focusReset != null) bg.removeCallbacks(focusReset);
            focusReset = () -> { focusRegion = null; applyPreview(); };
            bg.postDelayed(focusReset, 6000);
        });
    }

    /** Takes a full-resolution photo; the page is told its id when it is ready. */
    public void capture() {
        if (bg == null) return;
        bg.post(() -> {
            if (session == null || device == null || reader == null) { fail("Camera not ready"); return; }
            try {
                final CaptureRequest.Builder b = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                b.addTarget(reader.getSurface());
                b.addTarget(previewSurface);
                fill(b, zoomShown);
                if (focusRegion == null) b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                b.set(CaptureRequest.JPEG_QUALITY, (byte) 95);
                int dev = deviceRotation;
                int jo = front ? (sensorOrientation - dev + 360) % 360 : (sensorOrientation + dev) % 360;
                b.set(CaptureRequest.JPEG_ORIENTATION, jo);
                Runnable shoot = () -> {
                    try {
                        session.capture(b.build(), new CameraCaptureSession.CaptureCallback() {}, bg);
                        if (flashOn && hasFlash) bg.postDelayed(this::applyPreview, 250);
                    } catch (Exception e) { fail("Capture: " + e.getMessage()); }
                };
                if (flashOn && hasFlash) {
                    // light up first so exposure can adjust, then take the photo with the light on
                    preview.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH);
                    session.setRepeatingRequest(preview.build(), null, bg);
                    bg.postDelayed(shoot, 650);
                } else {
                    shoot.run();
                }
            } catch (Exception e) { fail("Capture: " + e.getMessage()); }
        });
    }

    // ======================= GL =======================

    private static final String VS =
            "attribute vec2 p;uniform mat4 ST;uniform vec2 SC;varying vec2 v;varying vec2 tc;" +
            "void main(){v=p*.5+.5;vec2 c=(v-.5)*SC+.5;tc=(ST*vec4(c,0.,1.)).xy;gl_Position=vec4(p,0.,1.);}";

    private static final String FS_DOWN =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision mediump float;varying vec2 v;varying vec2 tc;uniform samplerExternalOES S;uniform vec2 T;" +
            "void main(){vec3 a=vec3(0.);for(int i=-1;i<=1;i++)for(int j=-1;j<=1;j++)a+=texture2D(S,tc+vec2(float(i),float(j))*T).rgb;" +
            "gl_FragColor=vec4(a/9.,1.);}";

    private static final String FS_MAIN =
            "#extension GL_OES_EGL_image_external : require\n" +
            "precision highp float;varying vec2 v;varying vec2 tc;\n" +
            "uniform samplerExternalOES S;uniform sampler2D L,HT;uniform vec2 R,HR;\n" +
            "uniform float SAT,BW,G,GC,VG,SEED,HAL,HTH;uniform float HC[7];uniform vec3 HB[7];\n" +
            "float h(vec2 q){return fract(sin(dot(q,vec2(12.9898,78.233)))*43758.5453);}\n" +
            "float vn(vec2 p){vec2 i=floor(p),f=fract(p);f=f*f*(3.-2.*f);return mix(mix(h(i),h(i+vec2(1.,0.)),f.x),mix(h(i+vec2(0.,1.)),h(i+vec2(1.,1.)),f.x),f.y);}\n" +
            "float lut(float x,int ch){vec4 t=texture2D(L,vec2((clamp(x,0.,1.)*255.+.5)/256.,.5));return ch==0?t.r:(ch==1?t.g:t.b);}\n" +
            "vec3 rgb2hsv(vec3 c){vec4 K=vec4(0.,-1./3.,2./3.,-1.);vec4 p=mix(vec4(c.bg,K.wz),vec4(c.gb,K.xy),step(c.b,c.g));" +
            "vec4 q=mix(vec4(p.xyw,c.r),vec4(c.r,p.yzx),step(p.x,c.r));float d=q.x-min(q.w,q.y);return vec3(abs(q.z+(q.w-q.y)/(6.*d+1e-10)),d/(q.x+1e-10),q.x);}\n" +
            "vec3 hsv2rgb(vec3 c){vec4 K=vec4(1.,2./3.,1./3.,3.);vec3 p=abs(fract(c.xxx+K.xyz)*6.-K.www);return c.z*mix(K.xxx,clamp(p-K.xxx,0.,1.),c.y);}\n" +
            "float br(vec3 x){return max(0.,(dot(x,vec3(.299,.587,.114))-HTH)/(1.-HTH));}\n" +
            "void main(){\n" +
            "  vec3 c=texture2D(S,tc).rgb, o;\n" +
            "  if(BW>.5){ float y=dot(c,vec3(.30,.59,.11)); o=vec3(lut(y,0)); }\n" +
            "  else { o=vec3(lut(c.r,0),lut(c.g,1),lut(c.b,2)); float l=dot(o,vec3(.299,.587,.114)); o=l+(o-l)*SAT;\n" +
            "    vec3 hv=rgb2hsv(clamp(o,0.,1.)); float hd=hv.x*360., sh=0., sm=0., lm=0., sw=smoothstep(.04,.3,hv.y);\n" +
            "    for(int i=0;i<7;i++){ float dd=abs(hd-HC[i]); dd=min(dd,360.-dd); float w=clamp(1.-dd/40.,0.,1.); w=w*w*(3.-2.*w)*sw;\n" +
            "      sh+=w*HB[i].x; sm+=w*(HB[i].y-1.); lm+=w*HB[i].z; }\n" +
            "    hv.x=fract((hd+sh)/360.+1.); hv.y=clamp(hv.y*(1.+sm),0.,1.); hv.z=clamp(hv.z+lm*hv.y,0.,1.); o=hsv2rgb(hv); }\n" +
            "  if(HAL>0.){ float gs=0.; for(int i=0;i<12;i++){ float a=float(i)*.5236; vec2 d=vec2(cos(a),sin(a));\n" +
            "      gs+=br(texture2D(HT,v+d*HR*3.5).rgb)+.7*br(texture2D(HT,v+d*HR*8.).rgb); }\n" +
            "    gs/=20.4; float hv2=max(0.,gs-br(c)*.55); vec3 hc=clamp(vec3(1.,.3,.1)*hv2*HAL*1.8,0.,1.); o=1.-(1.-o)*(1.-hc); }\n" +
            "  if(G>0.){ vec2 q=gl_FragCoord.xy; float n=(vn(q*.9+SEED)-.5)*.9+(vn(q*.45+SEED*1.7)-.5)*.6+(h(q+SEED)-.5)*.5;\n" +
            "    float c1=vn(q*.8+SEED+31.)-.5, c2=vn(q*.8+SEED+57.)-.5; float l=clamp(dot(o,vec3(.299,.587,.114)),0.,1.);\n" +
            "    o+=(vec3(n)+GC*vec3(c1,c2,-(c1+c2)))*G*1.6*(.3+1.4*l*(1.-l)); }\n" +
            "  if(VG>0.){ vec2 d=(v-.5)*R; float r0=min(R.x,R.y)*.35, r1=length(R)*.55; o*=1.-VG*clamp((length(d)-r0)/(r1-r0),0.,1.); }\n" +
            "  gl_FragColor=vec4(clamp(o,0.,1.),1.);\n" +
            "}";

    private static int shader(int type, String src) {
        int s = GLES20.glCreateShader(type);
        GLES20.glShaderSource(s, src);
        GLES20.glCompileShader(s);
        int[] ok = new int[1];
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) { String log = GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw new RuntimeException(log); }
        return s;
    }

    private static int program(String vs, String fs) {
        int p = GLES20.glCreateProgram();
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs));
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs));
        GLES20.glLinkProgram(p);
        return p;
    }

    private static int newTex(int target, int filter) {
        int[] t = new int[1];
        GLES20.glGenTextures(1, t, 0);
        GLES20.glBindTexture(target, t[0]);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return t[0];
    }

    @Override
    public void onSurfaceCreated(GL10 gl, EGLConfig config) {
        try {
            prMain = program(VS, FS_MAIN);
            prDown = program(VS, FS_DOWN);
        } catch (RuntimeException e) {
            fail("Shader: " + e.getMessage());
            return;
        }
        oesTex = newTex(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_LINEAR);
        lutTex = newTex(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST);
        halTex = newTex(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR);
        int[] f = new int[1];
        GLES20.glGenFramebuffers(1, f, 0);
        fbo = f[0];
        halW = halH = 0;
        lutDirty = true;
        quad = ByteBuffer.allocateDirect(8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(new float[]{-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        if (st != null) st.release();
        st = new SurfaceTexture(oesTex);
        st.setOnFrameAvailableListener(this);
        surfaceReady = true;
        synchronized (this) { if (wantOpen && bg != null) bg.post(this::openCamera); }
    }

    @Override
    public void onSurfaceChanged(GL10 gl, int w, int h) { viewW = w; viewH = h; }

    @Override
    public void onFrameAvailable(SurfaceTexture s) { requestRender(); }

    /** How much of the camera frame the viewfinder shows (it fills the viewfinder, like CSS "cover"). */
    private float[] coverScale() {
        float fw = previewSize.getHeight(), fh = previewSize.getWidth();   // frame is portrait after rotation
        float frameAspect = fw / fh, viewAspect = (float) viewW / viewH;
        return viewAspect > frameAspect ? new float[]{1f, frameAspect / viewAspect} : new float[]{viewAspect / frameAspect, 1f};
    }

    private void bindQuad(int pr) {
        int ap = GLES20.glGetAttribLocation(pr, "p");
        GLES20.glEnableVertexAttribArray(ap);
        GLES20.glVertexAttribPointer(ap, 2, GLES20.GL_FLOAT, false, 0, quad);
        float[] sc = coverScale();
        GLES20.glUniformMatrix4fv(GLES20.glGetUniformLocation(pr, "ST"), 1, false, stMat, 0);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(pr, "SC"), sc[0], sc[1]);
    }

    @Override
    public void onDrawFrame(GL10 gl) {
        if (st == null || prMain == 0) return;
        try { st.updateTexImage(); } catch (Exception e) { return; }
        st.getTransformMatrix(stMat);

        if (lutDirty) {
            lutDirty = false;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, 256, 1, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.wrap(lut));
        }

        float hal = halation;
        if (hal > 0) {
            int sw = Math.max(48, viewW / 10), sh = Math.max(48, viewH / 10);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, halTex);
            if (sw != halW || sh != halH) {
                halW = sw; halH = sh;
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, sw, sh, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
                GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, halTex, 0);
            }
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
            GLES20.glViewport(0, 0, sw, sh);
            GLES20.glUseProgram(prDown);
            bindQuad(prDown);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
            GLES20.glUniform1i(GLES20.glGetUniformLocation(prDown, "S"), 0);
            GLES20.glUniform2f(GLES20.glGetUniformLocation(prDown, "T"), 1f / previewSize.getHeight() * 3f, 1f / previewSize.getWidth() * 3f);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glUseProgram(prMain);
        bindQuad(prMain);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, oesTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, lutTex);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, halTex);
        int pr = prMain;
        GLES20.glUniform1i(GLES20.glGetUniformLocation(pr, "S"), 0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(pr, "L"), 1);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(pr, "HT"), 2);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(pr, "R"), viewW, viewH);
        GLES20.glUniform2f(GLES20.glGetUniformLocation(pr, "HR"), halW > 0 ? 1f / halW : 0, halH > 0 ? 1f / halH : 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "SAT"), sat);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "BW"), bw ? 1 : 0);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "G"), grain * .8f);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "GC"), grainColor);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "VG"), vignette);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "SEED"), (float) (Math.random() * 100));
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "HAL"), hal);
        GLES20.glUniform1f(GLES20.glGetUniformLocation(pr, "HTH"), halThresh);
        GLES20.glUniform1fv(GLES20.glGetUniformLocation(pr, "HC"), 7, new float[]{0, 30, 60, 120, 180, 225, 300}, 0);
        GLES20.glUniform3fv(GLES20.glGetUniformLocation(pr, "HB"), 7, hues, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }
}
