package aec.hackathon.munich.forgelivescan;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.hardware.display.DisplayManager;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceFragment;
import android.preference.PreferenceManager;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Log;
import android.util.TypedValue;
import android.view.Display;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageButton;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.atap.tango.mesh.TangoMesh;
import com.google.atap.tango.reconstruction.TangoFloorplanLevel;
import com.google.atap.tango.reconstruction.TangoPolygon;
import com.google.atap.tangoservice.Tango;
import com.google.atap.tangoservice.TangoCameraIntrinsics;
import com.google.atap.tangoservice.TangoConfig;
import com.google.atap.tangoservice.TangoCoordinateFramePair;
import com.google.atap.tangoservice.TangoErrorException;
import com.google.atap.tangoservice.TangoEvent;
import com.google.atap.tangoservice.TangoInvalidException;
import com.google.atap.tangoservice.TangoOutOfDateException;
import com.google.atap.tangoservice.TangoPointCloudData;
import com.google.atap.tangoservice.TangoPoseData;
import com.google.atap.tangoservice.TangoXyzIjData;
import com.google.atap.tangoservice.experimental.TangoImageBuffer;
import com.projecttango.tangosupport.TangoSupport;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.xwalk.core.XWalkView;

import java.io.BufferedWriter;
import java.io.File;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import aec.hackathon.munich.forgelivescan.floorplanbuilder.TangoFloorplanner;
import aec.hackathon.munich.forgelivescan.meshbuilder.MeshBuilderRenderer;
import aec.hackathon.munich.forgelivescan.meshbuilder.TangoMesher;
import aec.hackathon.munich.forgelivescan.util.AsyncResponse;
import aec.hackathon.munich.forgelivescan.util.CustomWebView;
import aec.hackathon.munich.forgelivescan.util.SaveMeshTask;

public class ForgeLiveScanActivity extends Activity implements View.OnClickListener, SharedPreferences.OnSharedPreferenceChangeListener, AsyncResponse {
    private static final String TAG = ForgeLiveScanActivity.class.getSimpleName();
    private static final String TANGO_PACKAGE_NAME = "com.google.tango";
    private static final int MIN_TANGO_VERSION = 11925;
    private static final String CAMERA_PERMISSION = Manifest.permission.CAMERA;
    private static final int CAMERA_PERMISSION_CODE = 0;

    String JSON_FLOORPLAN_SPACE = "space";
    String JSON_FLOORPLAN_WALL = "wall";
    String JSON_FLOORPLAN_FURNITURE = "furniture";
    String JSON_DEVICE_ID = "device_id";
    String JSON_DEVICE_TRANSLATION = "device_translation";
    String JSON_DEVICE_ROTATION = "device_rotation";
    String JSON_DEVICE_OFFSET = "offset";

    public static final String KEY_PREF_SERVER_URL = "pref_server_url";
    public static final String KEY_PREF_SERVER_SEND_DELAY = "pref_server_send_delay";
    public static final String KEY_PREF_SCAN_ACCURACY = "pref_scan_accuracy";

    boolean mSavePly = true;
    boolean mSaveObj = true;

    private volatile List<TangoPolygon> mPolygons = new ArrayList<>();
    public static Context contextOfApplication;

    private TangoFloorplanner mTangoFloorplanner;
    private Tango mTango;
    private TangoConfig mConfig;
    private boolean mTangoServiceIsConnected = false;
    private boolean mTangoServiceIsPaused = true;
    private int mDelay;
    private int mDelayCount;

    public AtomicReference<Object> mWebView;
    private GLSurfaceView mMeshView;
    private MeshBuilderRenderer mRenderer;
    private TangoCameraIntrinsics mIntrinsics;
    private TangoMesher mTangoMesher;
    private volatile TangoMesh[] mMeshVector;
    private boolean mClearMeshes;

    private int mDisplayRotation = 0;

    private float mMinAreaSpace = 0;
    private float mMinAreaWall = 0f;
    private String mSavePath;
    private String mSaveDir = getResources().getString(R.string.app_name);

    private ImageButton mTransUpButton;
    private ImageButton mTransDownButton;
    private ImageButton mTransLeftButton;
    private ImageButton mTransRightButton;
    private ImageButton mRotLeftButton;
    private ImageButton mRotRightButton;
    private ImageButton mSettingsButton;
    private ImageButton mStartScanButton;
    private ImageButton mPauseScanButton;
    private ImageButton mRefreshButton;
    private TextView mOfflineTextView;
    private TextView mAreaText;
    private TextView mHeightText;
    private TextView mDistanceText;
    private TextView mResponseCodeTextView;
    private ImageButton mSaveButton;


    private String mDeviceId;
    private String mServerURL;
    private float[] mOffset = {0f, 0f, 0f, 0f};
    SharedPreferences mSharedPref;

    public static class SettingsFragment extends PreferenceFragment implements View.OnKeyListener {

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            view.setBackgroundColor(Color.GRAY);
            return view;
        }

        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            // Load the preferences from an XML resource
             addPreferencesFromResource(R.xml.preferences);
        }

        @Override
        public boolean onKey(View v, int keyCode, KeyEvent event) {
            return keyCode == KeyEvent.KEYCODE_BACK;
        }

    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        switch (key){
            case KEY_PREF_SERVER_URL:
                mServerURL = mSharedPref.getString(KEY_PREF_SERVER_URL, getResources().getString(R.string.pref_server_url_def));
                //CustomWebView.loadUrl("about:blank", mWebView);
                //CustomWebView.clearCache(true, mWebView);
                CustomWebView.loadUrl(mServerURL, mWebView);
                CustomWebView.reload(mWebView);
                Log.d(TAG, "URL changed: " + mServerURL);
                break;
            case KEY_PREF_SERVER_SEND_DELAY:
                mDelay = mSharedPref.getInt(KEY_PREF_SERVER_SEND_DELAY, getResources().getInteger(R.integer.pref_server_send_delay_def));
                break;
        }
    }

    public void resetPreferences(){
        // Reset preferences
        SharedPreferences.Editor editor = PreferenceManager.getDefaultSharedPreferences(this).edit();
        editor.clear();
        editor.apply();
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        contextOfApplication = getApplicationContext();
        //resetPreferences();
        mSharedPref = PreferenceManager.getDefaultSharedPreferences(this);

        mSavePath = Environment.getExternalStorageDirectory().getAbsolutePath() + "/" + mSaveDir + "/";
        if (new File(mSavePath).mkdir()){
            Log.d(TAG, "save folder " + mSaveDir +" created");
        }

        mServerURL = mSharedPref.getString(KEY_PREF_SERVER_URL, getResources().getString(R.string.pref_server_url_def));
        mDelay = mSharedPref.getInt(KEY_PREF_SERVER_SEND_DELAY, getResources().getInteger(R.integer.pref_server_send_delay_def));
        mDelayCount = mDelay;


        LayoutInflater mInflater = LayoutInflater.from(this);
        View contentView = mInflater.inflate(R.layout.activity_forge_live_scan, null, true);
        RelativeLayout root = (RelativeLayout) contentView.findViewById(R.id.viewer_layout);

        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.KITKAT) {
            // http://stackoverflow.com/questions/15395245/enabling-webgl-support-for-android-webview
            XWalkView xView = new XWalkView(this);
            xView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
            root.addView(xView);
            CustomWebView.sendViewToBack(xView);
            mWebView = new AtomicReference<Object>(xView);
            ((XWalkView) mWebView.get()).getSettings().setJavaScriptEnabled(true);
            Log.d(TAG, "Device is running Android version " + Build.VERSION.SDK_INT + " --> adding XWalkView");
        } else {
            WebView wView = new WebView(this);
            wView.setLayoutParams(new RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT));
            root.addView(wView);
            CustomWebView.sendViewToBack(wView);
            mWebView = new AtomicReference<Object>(wView);
            ((WebView) mWebView.get()).getSettings().setJavaScriptEnabled(true);
            ((WebView) mWebView.get()).setBackgroundColor(Color.CYAN);
            Log.d(TAG, "Device is running Android version " + Build.VERSION.SDK_INT + " --> adding WebView");
        }

        setContentView(contentView);
        CustomWebView.loadUrl(mServerURL, mWebView);

        mMeshView = (GLSurfaceView) findViewById(R.id.meshview);
        mMeshView.setZOrderOnTop(false);

        mTransUpButton = (ImageButton) this.findViewById(R.id.translation_up_button);
        mTransUpButton.setOnClickListener(this);
        mTransDownButton = (ImageButton) this.findViewById(R.id.translation_down_button);
        mTransDownButton.setOnClickListener(this);
        mTransLeftButton = (ImageButton) this.findViewById(R.id.translation_left_button);
        mTransLeftButton.setOnClickListener(this);
        mTransRightButton = (ImageButton) this.findViewById(R.id.translation_right_button);
        mTransRightButton.setOnClickListener(this);
        mRotLeftButton = (ImageButton) this.findViewById(R.id.rotation_left_button);
        mRotLeftButton.setOnClickListener(this);
        mRotRightButton = (ImageButton) this.findViewById(R.id.rotation_right_button);
        mRotRightButton.setOnClickListener(this);
        mSettingsButton = (ImageButton) this.findViewById(R.id.settings_button);
        mSettingsButton.setOnClickListener(this);
        mStartScanButton = (ImageButton) this.findViewById(R.id.scan_button);
        mStartScanButton.setOnClickListener(this);
        mPauseScanButton = (ImageButton) this.findViewById(R.id.pause_button);
        mPauseScanButton.setOnClickListener(this);
        mRefreshButton = (ImageButton) this.findViewById(R.id.refresh_button);
        mRefreshButton.setOnClickListener(this);
        mSaveButton = (ImageButton) contentView.findViewById(R.id.save_button);
        mSaveButton.setOnClickListener(this);


        mOfflineTextView = (TextView) this.findViewById(R.id.scan_offline_textview);
        mAreaText = (TextView) this.findViewById(R.id.scan_area_value_textview);
        mResponseCodeTextView = (TextView) contentView.findViewById(R.id.response_code_value_textView);
        mHeightText = (TextView) contentView.findViewById(R.id.scan_height_value_textview);
        mDistanceText = (TextView) contentView.findViewById(R.id.scan_floordistance_value_textview);


        TypedValue typedValue = new TypedValue();
        getResources().getValue(R.dimen.min_area_space, typedValue, true);
        mMinAreaSpace = typedValue.getFloat();

        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        if (displayManager != null) {
            displayManager.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int displayId) {
                }

                @Override
                public void onDisplayChanged(int displayId) {
                    synchronized (this) {
                        setDisplayRotation();
                    }
                }

                @Override
                public void onDisplayRemoved(int displayId) {
                }
            }, null);
        }
        connectRenderer();

        WifiManager manager = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
        WifiInfo info = manager.getConnectionInfo();
        mDeviceId = info.getMacAddress().replace(":","");
        Log.d(TAG, "device id: " + mDeviceId);
    }

    @Override
    protected void onStart() {
        super.onStart();
        mMeshView.onResume();

        // Set render mode to RENDERMODE_CONTINUOUSLY to force getting onDraw callbacks until
        // the Tango Service is properly set up and we start getting onFrameAvailable callbacks.
        mMeshView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        // Check and request camera permission at run time.
        if (checkAndRequestPermissions()) {
            bindTangoService();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        // Set up a listener whenever a key changes
        mSharedPref.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    public void onPause() {
        super.onPause();
        // Unregister the listener whenever a key changes
        mSharedPref.unregisterOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Synchronize against disconnecting while the service is being used in the OpenGL thread or
        // in the UI thread.
        synchronized (this) {
            try {

                if (mTangoMesher != null) {
                    mTangoMesher.stopSceneReconstruction();
                    mTangoMesher.resetSceneReconstruction();
                    mTangoMesher.release();
                }
                if (mTangoFloorplanner != null) {
                    mTangoFloorplanner.stopFloorplanning();
                    mTangoFloorplanner.resetFloorplan();
                    mTangoFloorplanner.release();
                }
                mRenderer.clearMeshes();
                mTango.disconnect();
                mTangoServiceIsConnected = false;
                mTangoServiceIsPaused = true;
            } catch (TangoErrorException e) {
                Log.e(TAG, getString(R.string.exception_tango_error), e);
            }
        }
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()){
            case R.id.translation_up_button:
                mOffset[1] += 0.1f;
                Log.d(TAG, "offset translation y: " + mOffset[1] + " | moving up");
                break;
            case R.id.translation_down_button:
                mOffset[1] -= 0.1f;
                Log.d(TAG, "offset translation y: " + mOffset[1] + " | moving down");
                break;
            case R.id.translation_left_button:
                mOffset[0] -= 0.1f;
                Log.d(TAG, "offset translation x: " + mOffset[0] + " | moving left");
                break;
            case R.id.translation_right_button:
                mOffset[0] += 0.1f;
                Log.d(TAG, "offset translation x: " + mOffset[0] + " | moving right");
                break;
            case R.id.rotation_left_button:
                mOffset[3] -= 1f;  // not yet used
                Log.d(TAG, "offset rotation z: " + mOffset[3] + " | turning left");
                break;
            case R.id.rotation_right_button:
                mOffset[3] += 1f;  // not yet used
                Log.d(TAG, "offset rotation z: " + mOffset[3] + " | turning right");
                break;
            case R.id.settings_button:
                getFragmentManager().beginTransaction()
                        .replace(android.R.id.content, new SettingsFragment()).addToBackStack( "settings" )
                        .commit();
                break;
            case R.id.scan_button:
                mTangoFloorplanner.startFloorplanning();
                mTangoMesher.startSceneReconstruction();
                mTangoServiceIsPaused = !mTangoServiceIsPaused;
                mStartScanButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_play_circle_outline_black_24dp_active));
                mPauseScanButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_pause_circle_outline_black_24dp));
                mOfflineTextView.setVisibility(View.GONE);
                break;
            case R.id.pause_button:
                mTangoFloorplanner.stopFloorplanning();
                mTangoMesher.stopSceneReconstruction();
                mTangoServiceIsPaused = !mTangoServiceIsPaused;
                mStartScanButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_play_circle_outline_black_24dp));
                mPauseScanButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_pause_circle_outline_black_24dp_active));
                mOfflineTextView.setVisibility(View.VISIBLE);
                break;
            case R.id.refresh_button:
                //CustomWebView.reload(mWebView);
                CustomWebView.loadUrl("javascript:window.location.reload( true )", mWebView);
                Log.d(TAG, "reload url: " + mServerURL);
                break;
            case R.id.save_button:
                if (!mTangoServiceIsPaused){
                    showsToastOnUiThread("Saving not possible while scan still active!");
                    break;
                }
                mSaveButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_save_black_24dp_active));
                try {
                    saveData(mSavePly, mSaveObj);
                }catch (ExecutionException ex){
                    ex.printStackTrace();
                }catch (InterruptedException ex){
                    ex.printStackTrace();
                }
                break;
        }
    }

    /**
     * Initialize Tango Service as a normal Android Service.
     */
    private void bindTangoService() {
        // Initialize Tango Service as a normal Android Service.
        // Since we call mTango.disconnect() in onPause, this will unbind Tango Service,
        // so every time onResume gets called we should create a new Tango object.
        mTango = new Tango(ForgeLiveScanActivity.this, new Runnable() {
            // Pass in a Runnable to be called from UI thread when Tango is ready,
            // this Runnable will be running on a new thread.
            // When Tango is ready, we can call Tango functions safely here only
            // when there are no UI thread changes involved.
            @Override
            public void run() {
                synchronized (ForgeLiveScanActivity.this) {
                    try {
                        TangoSupport.initialize();
                        mConfig = setupTangoConfig(mTango);
                        mTango.connect(mConfig);
                        startupTango();
                        mTangoServiceIsConnected = true;
                        //mTangoServiceIsPaused = false;
                        setDisplayRotation();
                    } catch (TangoOutOfDateException e) {
                        Log.e(TAG, getString(R.string.exception_out_of_date), e);
                        showsToastAndFinishOnUiThread(R.string.exception_out_of_date);
                    } catch (TangoErrorException e) {
                        Log.e(TAG, getString(R.string.exception_tango_error), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_error);
                    } catch (TangoInvalidException e) {
                        Log.e(TAG, getString(R.string.exception_tango_invalid), e);
                        showsToastAndFinishOnUiThread(R.string.exception_tango_invalid);
                    }
                }
            }
        });
    }

    /**
     * Sets up the Tango configuration object. Make sure mTango object is initialized before
     * making this call.
     */
    private TangoConfig setupTangoConfig(Tango tango) {
        // Use default configuration for Tango Service, plus color camera, low latency
        // IMU integration, depth, smooth pose and dataset recording.
        TangoConfig config = tango.getConfig(TangoConfig.CONFIG_TYPE_DEFAULT);
        // NOTE: Low latency integration is necessary to achieve a precise alignment of virtual
        // objects with the RGB image and produce a good AR effect.
        config.putBoolean(TangoConfig.KEY_BOOLEAN_LOWLATENCYIMUINTEGRATION, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_SMOOTH_POSE, true);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_DEPTH, true);
        config.putInt(TangoConfig.KEY_INT_DEPTH_MODE, TangoConfig.TANGO_DEPTH_MODE_POINT_CLOUD);
        config.putBoolean(TangoConfig.KEY_BOOLEAN_COLORCAMERA, true);
        return config;
    }

    /**
     * Set up the callback listeners for the Tango Service and obtain other parameters required
     * after Tango connection.
     * Listen to updates from the point cloud.
     */
    private void startupTango() {
        checkTangoVersion();
        mTangoMesher = new TangoMesher(new TangoMesher.OnTangoMeshesAvailableListener() {
            @Override
            public void onMeshesAvailable(TangoMesh[] tangoMeshes) {
                mMeshVector = tangoMeshes;
            }
        });
        // Set camera intrinsics to TangoMesher.
        mIntrinsics = mTango.getCameraIntrinsics(TangoCameraIntrinsics.TANGO_CAMERA_COLOR);
        mTangoMesher.setColorCameraCalibration(mIntrinsics);
        mTangoMesher.setDepthCameraCalibration(mTango.getCameraIntrinsics(TangoCameraIntrinsics
                .TANGO_CAMERA_DEPTH));


        mTangoFloorplanner = new TangoFloorplanner(new TangoFloorplanner
                .OnFloorplanAvailableListener() {
            @Override
            public void onFloorplanAvailable(List<TangoPolygon> polygons,
                                             List<TangoFloorplanLevel> levels) {
                calculateAndUpdateArea(polygons);
                updateFloorAndCeiling(levels);
                if (mDelayCount == 0) {
                    sendData(toJSON(polygons).toString());
                    mDelayCount = mDelay;
                } else {
                    mDelayCount--;
                }
            }
        });
        // Set camera intrinsics to TangoFloorplanner.
        mTangoFloorplanner.setDepthCameraCalibration(mTango.getCameraIntrinsics
                (TangoCameraIntrinsics.TANGO_CAMERA_DEPTH));

        if (!mTangoServiceIsPaused){
            mTangoMesher.startSceneReconstruction();
            mTangoFloorplanner.startFloorplanning();
        }

        // Connect listeners to Tango Service and forward point cloud and camera information to
        // TangoFloorplanner.
        List<TangoCoordinateFramePair> framePairs = new ArrayList<TangoCoordinateFramePair>();
        mTango.connectListener(framePairs, new Tango.OnTangoUpdateListener() {
            @Override
            public void onPoseAvailable(TangoPoseData tangoPoseData) {
                // We are not using onPoseAvailable for this app.
            }

            @Override
            public void onXyzIjAvailable(TangoXyzIjData tangoXyzIjData) {
                // We are not using onXyzIjAvailable for this app.
            }

            @Override
            public void onFrameAvailable(int i) {
                // We are not using onFrameAvailable for this app.
            }

            @Override
            public void onTangoEvent(TangoEvent tangoEvent) {
                // We are not using onTangoEvent for this app.
            }

            @Override
            public void onPointCloudAvailable(TangoPointCloudData tangoPointCloudData) {
                mTangoFloorplanner.onPointCloudAvailable(tangoPointCloudData);
                mTangoMesher.onPointCloudAvailable(tangoPointCloudData);
            }
        });
        mTango.experimentalConnectOnFrameListener(TangoCameraIntrinsics.TANGO_CAMERA_COLOR, new
                Tango.OnFrameAvailableListener() {
                    @Override
                    public void onFrameAvailable(TangoImageBuffer tangoImageBuffer, int i) {
                        mTangoMesher.onFrameAvailable(tangoImageBuffer, i);
                    }
                });
    }

    /**
     * Connects the view and renderer to the color camera and callbacks.
     */
    private void connectRenderer() {
        mMeshView.setEGLContextClientVersion(2);
        mRenderer = new MeshBuilderRenderer(new MeshBuilderRenderer.RenderCallback() {
            @Override
            public void preRender() {
                // NOTE: This is called from the OpenGL render thread after all the renderer
                // onRender callbacks have a chance to run and before scene objects are rendered
                // into the scene.
                try {
                    // Synchronize against disconnecting while using the service.
                    synchronized (ForgeLiveScanActivity.this) {
                        // Don't execute any tango API actions if we're not connected to the
                        // service.
                        if (!mTangoServiceIsConnected) {
                            return;
                        }
                        // Calculate the camera color pose at the camera frame update time in
                        // OpenGL engine.
                        TangoSupport.TangoMatrixTransformData ssTdev =
                                TangoSupport.getMatrixTransformAtTime(
                                        0.0, TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                                        TangoPoseData.COORDINATE_FRAME_DEVICE,
                                        TangoSupport.TANGO_SUPPORT_ENGINE_TANGO,
                                        TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                                        mDisplayRotation);

                        if (ssTdev.statusCode == TangoPoseData.POSE_VALID) {
                            // Update the camera pose from the renderer.
                            mRenderer.updateViewMatrix(ssTdev.matrix);
                        } else {
                            Log.w(TAG, "Can't get last camera pose");
                        }
                    }
                    // Update mesh.
                    updateMeshMap();
                } catch (TangoErrorException e) {
                    Log.w(TAG, "Tango API call error within the OpenGL thread", e);
                } catch (TangoInvalidException e) {
                    Log.w(TAG, "Tango API call error within the OpenGL thread", e);
                }
            }
        });
        mMeshView.setRenderer(mRenderer);
    }

    /**
     * Updates the rendered mesh map if a new mesh vector is available.
     * This is run in the OpenGL thread.
     */
    private void updateMeshMap() {
        if (mClearMeshes) {
            mRenderer.clearMeshes();
            mClearMeshes = false;
        }
        if (mMeshVector != null) {
            for (TangoMesh tangoMesh : mMeshVector) {
                if (tangoMesh != null && tangoMesh.numFaces > 0) {
                    mRenderer.updateMesh(tangoMesh);
                }
            }
            mMeshVector = null;
        }
    }

    /**
     * Check the minimum Tango core version needed by the Java 3D reconstruction library.
     */
    private void checkTangoVersion() {
        try {
            PackageInfo packageInfo = getPackageManager().getPackageInfo(TANGO_PACKAGE_NAME, 0);
            int version = Integer.parseInt(Integer.toString(packageInfo.versionCode).substring(2));
            if (version < MIN_TANGO_VERSION) {
                throw new TangoOutOfDateException();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.d(TAG, "Tango package could not be found");
        }
    }

    private JSONObject toJSON(List<TangoPolygon> in) {
        JSONArray[] lines = {new JSONArray(), new JSONArray(), new JSONArray()};
        float[] vertex;
        for (TangoPolygon tp : in) {
            // skip irrelevant or to small ones
            //if ( Math.abs(polygon.area) > mMinAreaWall )
            if (!tp.isClosed || tp.vertices2d.size() < 4) {
                continue;
            }

            JSONArray poly = new JSONArray();
            int c = 0;
            for (Iterator it = tp.vertices2d.iterator(); it.hasNext(); ) {
                vertex = (float[]) it.next();
                try {
                    // offset on translation & rotation (only around z-axis)
                    float x = (float) (vertex[0] * Math.cos(mOffset[3] * Math.PI / 180) - vertex[1] * Math.sin(mOffset[3] * Math.PI / 180) + mOffset[0]);
                    float y = (float) (vertex[1] * Math.cos(mOffset[3] * Math.PI / 180) + vertex[0] * Math.sin(mOffset[3] * Math.PI / 180) + mOffset[1]);

                    poly.put(x);
                    poly.put(y);
                    poly.put(0.1f + mOffset[2]); // tp.level --- API change in Gankino release!
                    c++;
                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }
            lines[tp.layer].put(poly);
        }
        JSONObject out = null;
        synchronized (ForgeLiveScanActivity.this) {
            TangoPoseData devicePose = TangoSupport.getPoseAtTime(0.0,
                    TangoPoseData.COORDINATE_FRAME_START_OF_SERVICE,
                    TangoPoseData.COORDINATE_FRAME_DEVICE,
                    TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                    TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                    mDisplayRotation);


            out = new JSONObject();
            try {
                out.put(JSON_FLOORPLAN_SPACE, lines[TangoPolygon.TANGO_3DR_LAYER_SPACE]);
                out.put(JSON_FLOORPLAN_WALL, lines[TangoPolygon.TANGO_3DR_LAYER_WALLS]);
                out.put(JSON_FLOORPLAN_FURNITURE, lines[TangoPolygon.TANGO_3DR_LAYER_FURNITURE]);
                out.put(JSON_DEVICE_ID, mDeviceId);
                out.put(JSON_DEVICE_TRANSLATION, new JSONArray(devicePose.getTranslationAsFloats()));
                out.put(JSON_DEVICE_ROTATION, new JSONArray(devicePose.getRotationAsFloats()));
                out.put(JSON_DEVICE_OFFSET, new JSONArray(mOffset));

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
        return out;
    }

    private void sendData(String data) {
        if (mServerURL.isEmpty()) {
            Log.e(TAG, "server url empty");
            return;
        }

        URL url;
        String response = "";
        try {
            url = new URL(mServerURL + "/geometryupdate");

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            OutputStream os = conn.getOutputStream();
            BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
            writer.write(data);
            writer.flush();
            writer.close();
            os.close();

            final int responseCode = conn.getResponseCode();
            final String responseMessage = "(" + conn.getResponseMessage() + ")";
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mResponseCodeTextView.setText(responseCode + responseMessage);
                }
            });
            if (responseCode != 200) {
                Log.e(TAG, "sent data – response code: " + responseCode);
                Log.e(TAG, "sent data – response message: " + responseMessage);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void saveData(boolean ply, boolean obj) throws ExecutionException, InterruptedException {
        Log.d(TAG, "save data to: " + mSavePath);
        SaveMeshTask sTask = new SaveMeshTask(mSavePath, this){};

        sTask.mTangoMesh = mTangoMesher.mTango3dReconstruction.extractFullMesh();
        sTask.execute(ply, obj);
    }

    /**
     * AsyncTask Responses
     * @param result
     */
    @Override
    public void onSaveDataFinished(String result) {
        if (result.equals("true")){
            showsToastOnUiThread("Data successfully saved!");
        } else {
            showsToastOnUiThread("Saving data failed, see log.");
        }
        mSaveButton.setImageDrawable(getResources().getDrawable(R.drawable.ic_save_black_24dp));
    }

    /**
     * Set the display rotation.
     */
    private void setDisplayRotation() {
        Display display = getWindowManager().getDefaultDisplay();
        mDisplayRotation = display.getRotation();
    }

    /**
     * Calculate the total explored space area and update the text field with that information.
     */
    private void calculateAndUpdateArea(List<TangoPolygon> polygons) {
        double area = 0;
        for (TangoPolygon polygon: polygons) {
            if (polygon.layer == TangoPolygon.TANGO_3DR_LAYER_SPACE) {
                // If there is more than one free space polygon, only count those
                // that have an area larger than two square meters to suppress unconnected
                // areas (which might occur in front of windows).
                if (area == 0 || (polygon.area > mMinAreaSpace || polygon.area < 0)) {
                    area += polygon.area;
                }
            }
        }
        final String areaText = String.format("%.2f", area);
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                mAreaText.setText(areaText);
            }
        });
    }

    /**
     * Given the Floorplan levels, calculate the ceiling height and the current distance from the
     * device to the floor.
     */
    private void updateFloorAndCeiling(List<TangoFloorplanLevel> levels) {
        if (levels.size() > 0) {
            // Currently only one level is supported by the floorplanning API.
            TangoFloorplanLevel level = levels.get(0);
            float ceilingHeight = level.maxZ - level.minZ;
            final String ceilingHeightText = String.format("%.2f", ceilingHeight);
            // Query current device pose and calculate the distance from it to the floor.
            TangoPoseData devicePose;
            // Synchronize against disconnecting while using the service.
            synchronized (this) {
                // Don't execute any Tango API actions if we're not connected to
                // the service.
                if (!mTangoServiceIsConnected) {
                    return;
                }
                devicePose = TangoSupport.getPoseAtTime(0.0,
                        TangoPoseData.COORDINATE_FRAME_AREA_DESCRIPTION,
                        TangoPoseData.COORDINATE_FRAME_DEVICE,
                        TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                        TangoSupport.TANGO_SUPPORT_ENGINE_OPENGL,
                        mDisplayRotation);
            }
            float devToFloorDistance = devicePose.getTranslationAsFloats()[1] - level.minZ;
            final String distanceText = String.format("%.2f", devToFloorDistance);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    mHeightText.setText(ceilingHeightText);
                    mDistanceText.setText(distanceText);
                }
            });
        }
    }

    /**
     * Check to see if we have the necessary permissions for this app; ask for them if we don't.
     *
     * @return True if we have the necessary permissions, false if we don't.
     */
    private boolean checkAndRequestPermissions() {
        if (!hasCameraPermission()) {
            requestCameraPermission();
            return false;
        }
        return true;
    }

    /**
     * Check to see if we have the necessary permissions for this app.
     */
    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, CAMERA_PERMISSION) ==
                PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Request the necessary permissions for this app.
     */
    private void requestCameraPermission() {
        if (ActivityCompat.shouldShowRequestPermissionRationale(this, CAMERA_PERMISSION)) {
            showRequestPermissionRationale();
        } else {
            ActivityCompat.requestPermissions(this, new String[]{CAMERA_PERMISSION},
                    CAMERA_PERMISSION_CODE);
        }
    }

    /**
     * If the user has declined the permission before, we have to explain that the app needs this
     * permission.
     */
    private void showRequestPermissionRationale() {
        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setMessage("ForgeLiveScan requires camera permission")
                .setPositiveButton("Ok", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialogInterface, int i) {
                        ActivityCompat.requestPermissions(ForgeLiveScanActivity.this,
                                new String[]{CAMERA_PERMISSION}, CAMERA_PERMISSION_CODE);
                    }
                })
                .create();
        dialog.show();
    }

    private void showsToastOnUiThread(final String string) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ForgeLiveScanActivity.this,
                        string, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * Display toast on UI thread.
     *
     * @param resId The resource id of the string resource to use. Can be formatted text.
     */
    private void showsToastAndFinishOnUiThread(final int resId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(ForgeLiveScanActivity.this,
                        getString(resId), Toast.LENGTH_LONG).show();
                finish();
            }
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (hasCameraPermission()) {
            bindTangoService();
        } else {
            Toast.makeText(this, "ForgeLiveScan requires camera permission",
                    Toast.LENGTH_LONG).show();
        }
    }
}
