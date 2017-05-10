package aec.hackathon.munich.forgelivescan.util;

import android.os.AsyncTask;
import android.util.Log;

import com.google.atap.tango.mesh.TangoMesh;
import com.google.atap.tango.mesh.io.TangoMeshIOProgressListener;
import com.google.atap.tango.mesh.io.obj.TangoMeshObjWriter;
import com.google.atap.tango.mesh.io.ply.TangoMeshPlyWriter;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;


/**
 * Created by Steffen Franz
 */

public class SaveMeshTask extends AsyncTask<Boolean, Void, String> {
    private static final String TAG = SaveMeshTask.class.getSimpleName();
    public AsyncResponse delegate = null;
    public TangoMesh mTangoMesh;
    private String mSavePath;
    private String exception = "";

    public SaveMeshTask(String savePath, AsyncResponse delegate){
        mSavePath = savePath;
        this.delegate = delegate;
        createSaveFolder();
    }

    private void createSaveFolder(){
        SimpleDateFormat formatter = new SimpleDateFormat("yyyy_MM_dd_HH_mm_ss");
        Date now = new Date();
        String fileName = formatter.format(now);
        mSavePath += fileName + "/";
        new File(mSavePath).mkdir();
    }

    @Override
    protected String doInBackground(Boolean... params) {
        try {
            if (params[0]){
                saveMeshPly();
            }
            if (params[1]){
                saveMeshObj();
            }

        } catch (IOException ex) {
            exception += ex.toString() + ": " + ex.getMessage() + "\n";
            Log.e(TAG, exception);
        }
        if (exception.isEmpty()) {
            return null;
        } else {
            return exception;
        }
    }

    private void saveMeshObj() throws IOException{
            TangoMeshObjWriter objWriter = new TangoMeshObjWriter();
            objWriter.writeMesh(mTangoMesh, mSavePath + "mesh.obj", new TangoMeshIOProgressListener() {
                @Override
                public void onProgress(long l, long l1) {

                }
            });
    }

    private void saveMeshPly() throws IOException{
            TangoMeshPlyWriter plyWriter = new TangoMeshPlyWriter();
            plyWriter.writeMesh(mTangoMesh, mSavePath + "mesh.ply", new TangoMeshIOProgressListener() {
                @Override
                public void onProgress(long l, long l1) {

                }
            });
    }

    @Override
    protected void onPostExecute(String result) {
        super.onPostExecute(result);
        if (result == null) {
            delegate.onSaveDataFinished("true");
        } else {
            delegate.onSaveDataFinished(exception);
        }
    }
}
