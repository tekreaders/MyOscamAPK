package com.tekreaders.myoscamapk;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Environment;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;

public class MainActivity extends AppCompatActivity {
    TextView outputView;
    Button startButton;
    Button stopButton;

    private String confDir = Environment.getExternalStorageDirectory().getPath() + "/oscam";
    private String tmpDir = Environment.getExternalStorageDirectory().getPath() + "/oscam/tmp";
    private String oscamFilename = "";
    private Process oscamProcess;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        outputView = (TextView)findViewById(R.id.outputView);
        startButton = (Button)findViewById(R.id.startButton);
        stopButton = (Button)findViewById(R.id.stopButton);
        outputView.setMovementMethod(new ScrollingMovementMethod());
        requestPermissions();
    }

    //request the runtime permissions, this required since Marshmallow
    public void requestPermissions() {
        if (Build.VERSION.SDK_INT >= 23) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
        }
    }

    //output logs and enable/disable buttons according to the running state
    public void setStatus(final boolean running, final String message){
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (message!=null) {
                    outputView.setText(outputView.getText()+"\n"+message);
                    if (outputView.getVisibility() == View.VISIBLE) {
                        final int scrollAmount = outputView.getLayout().getLineTop(outputView.getLineCount()) - outputView.getHeight();
                        if (scrollAmount > 0)
                            outputView.scrollTo(0, scrollAmount);
                        else
                            outputView.scrollTo(0, 0);
                    }
                }
                startButton.setEnabled(!running);
                stopButton.setEnabled(running);
            }
        });
    }

    public void startOscam(View view){
        if (oscamProcess == null) {
            //run this process on a new thread to avoid UI blocking
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        setStatus(true, "Initializing...");
                        //check the config and tmp folders
                        File cdir = new File(confDir);
                        File tdir = new File(tmpDir);
                        boolean confDirExists = cdir.exists();
                        boolean tmpDirExists = tdir.exists();
                        if (!confDirExists)
                            confDirExists = cdir.mkdir();
                        if (!tmpDirExists)
                            tmpDirExists = tdir.mkdir();
                        if (confDirExists && tmpDirExists) {
                            //check the stat file to sanitize the default errors
                            File stat = new File(tmpDir + "/stat");
                            if (!stat.exists()) {
                                FileWriter statfile = new FileWriter(stat);
                                statfile.write("");
                                statfile.close();
                            }
                            //extract the resource in the raw folder into the App private space and overwrite if it already exists
                            String appFileDirectory = getFilesDir().getParent();
                            oscamFilename = appFileDirectory + "/oscam";
                            InputStream ins = getResources().openRawResource(R.raw.oscam);
                            final byte[] buffer = new byte[ins.available()];
                            ins.read(buffer);
                            ins.close();
                            File destination = new File(oscamFilename);
                            if (destination.exists())
                                destination.delete();
                            FileOutputStream fos = new FileOutputStream(destination);
                            fos.write(buffer);
                            fos.close();
                            if (destination.exists()) {
                                //set executable rights on the file (chmod)
                                boolean isExecutable = destination.setExecutable(true);
                                if (isExecutable) {
                                    setStatus(true, "Launching oscam with confDir='" + confDir + "' and tmpDir='" + tmpDir + "'...");
                                    //start the Oscam process
                                    oscamProcess = new ProcessBuilder().command(oscamFilename, "--config-dir", confDir, "--temp-dir", tmpDir).redirectErrorStream(true).start();
                                    //start a thread to read Oscam logs and print them on our textview
                                    Thread readThread = new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(oscamProcess.getInputStream()));
                                                while (!Thread.currentThread().isInterrupted()) {
                                                    if (bufferedReader.ready()) {
                                                        String line = bufferedReader.readLine();
                                                        setStatus(true, line);
                                                    }
                                                }
                                                bufferedReader.close();
                                            } catch (Exception e) {
                                                e.printStackTrace();
                                            }
                                        }
                                    });
                                    readThread.start();
                                    //wait for the process to terminate
                                    oscamProcess.waitFor();
                                    //when we get here, the process is already dead, we should cleanup
                                    readThread.interrupt();
                                    oscamProcess = null;
                                    setStatus(false, "Oscam stopped!");
                                } else
                                    setStatus(false, "Oscam is not executable!");
                            } else
                                setStatus(false, "Oscam binary not found!");
                        } else
                            setStatus(false, "Unable to read/write configuration and tmp folder!");
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }).start();
        }
    }

    public void stopOscam(View view){
        if (oscamProcess != null) {
            try {
                //get the Oscam process PID by using reflection
                long pid = -1;
                try {
                    Field f = oscamProcess.getClass().getDeclaredField("pid");
                    f.setAccessible(true);
                    pid = f.getLong(oscamProcess);
                    f.setAccessible(false);
                } catch (Exception e) {
                    pid = -1;
                }
                //send a SIGTERM to the oscam process
                Runtime.getRuntime().exec("kill -15 " + pid);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }else
            setStatus(false, "Oscam stopped!");
    }


}
