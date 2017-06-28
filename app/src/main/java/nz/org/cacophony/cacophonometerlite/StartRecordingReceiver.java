package nz.org.cacophony.cacophonometerlite;


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.widget.Toast;

import static nz.org.cacophony.cacophonometerlite.Util.getBatteryLevelByIntent;

public class StartRecordingReceiver extends BroadcastReceiver {
    public static final int RECORDING_STARTED = 1;
    public static final int RECORDING_FAILED = 2;
    public static final int RECORDING_FINISHED = 3;
    public static final int NO_PERMISSIONS_TO_RECORD = 4;
    public static final int UPLOADING_FINISHED = 5;
    private static final String LOG_TAG = StartRecordingReceiver.class.getName();
    //public static String intentTimeUriMessage = null;

    private Context context = null;
    // Handler to pass to recorder.
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message inputMessage) {
            Log.d(LOG_TAG, "StartRecordingReceiver handler.");
            if (context == null) {
                Log.e(LOG_TAG, "Context was null for handler.");
            }
            switch (inputMessage.what) {
                case RECORDING_STARTED:
                   // Toast.makeText(context, "Recording started.", Toast.LENGTH_SHORT).show();
                    Util.getToast(context,"Recording started", false ).show();
                    break;
                case RECORDING_FAILED:
                   // Toast.makeText(context, "Recording failed.", Toast.LENGTH_SHORT).show();
                    Util.getToast(context,"Recording failed", true ).show();
                    break;
                case RECORDING_FINISHED:
                   // Toast.makeText(context, "Recording finished", Toast.LENGTH_SHORT).show();
                    Util.getToast(context,"Recording has finished. Now uploading it to server ", false ).show();
                    break;
                case UPLOADING_FINISHED:
                    // Toast.makeText(context, "Recording finished", Toast.LENGTH_SHORT).show();
                    Util.getToast(context,"Recording has been uploaded to the server", false ).show();
                    break;
                case NO_PERMISSIONS_TO_RECORD:
                   // Toast.makeText(context, "Did not have proper permissions to record.", Toast.LENGTH_SHORT).show();
                    Util.getToast(context,"Did not have proper permissions to record", true ).show();
                    break;
                default:
                    Log.w(LOG_TAG, "Unknown handler what.");
                    break;
            }
        }
    };

    @Override
    public void onReceive(final Context context, Intent intent) {
        Log.i(LOG_TAG, "Called receiver method");


        this.context = context;
        if (!Util.checkPermissionsForRecording(context)) {
           // Toast.makeText(context, "Don't have proper permissions to record..", Toast.LENGTH_SHORT).show();
            Util.getToast(context,"Do not have proper permissions to record", true ).show();
            Log.e(LOG_TAG, "Don't have proper permissions to record");
            return;
        }
        Prefs prefs = new Prefs(context);

        // need to determine the source of the intent ie Main UI or boot receiver
        Bundle bundle = intent.getExtras();
        String alarmIntentType = bundle.getString("type");
      //  Log.e(LOG_TAG, "alarmIntentType is " + alarmIntentType);

        if (alarmIntentType == null){
            Log.e(LOG_TAG, "Intent does not have a type");
           // return;
            alarmIntentType = "unknown"; // shouldn't get here
        }



        // First check to see if battery level is sufficient to continue.


        double batteryLevel = Util.getBatteryLevelUsingSystemFile();
        if (batteryLevel != -1){ // looks like getting battery level using system file worked
            String batteryStatus = Util.getBatteryStatus(context);
            prefs.setBatteryLevel(batteryLevel); // had to put it into prefs as I could not ready battery level from UploadFiles class (looper error)
            if (batteryStatus.equalsIgnoreCase("FULL")) {
                // The current battery level must be the maximum it can be!
                prefs.setMaximumBatteryLevel(batteryLevel);
            }


            double batteryRatioLevel = batteryLevel / prefs.getMaximumBatteryLevel();
            double batteryPercent = batteryRatioLevel * 100;
            if (!enoughBatteryToContinue( batteryPercent, alarmIntentType)){
                Log.w(LOG_TAG, "Battery level too low to do a recording");
                return;
            }


        }else { // will need to get battery level using intent method
            double batteryPercentLevel = getBatteryLevelByIntent(context);

            if (!enoughBatteryToContinue( batteryPercentLevel, alarmIntentType)){
                Log.w(LOG_TAG, "Battery level too low to do a recording");
                return;
            }
        }


        // need to determine the source of the intent ie Main UI or boot receiver

        if (alarmIntentType.equalsIgnoreCase("testButton")){
            try {
                // Start recording in new thread.
                Thread thread = new Thread() {
                    @Override
                    public void run() {
                        MainThread mainThread = new MainThread(context, handler);
                        mainThread.run();
                    }
                };
                thread.start();
            } catch (Exception e) {
                e.printStackTrace();
            }

        }else{ // intent came from boot receiver or app (not test record)

            Intent mainServiceIntent = new Intent(context, MainService.class);
            try {
                mainServiceIntent.putExtra("type",alarmIntentType);

            }catch (Exception e){
                Log.e(LOG_TAG, "Error setting up intent");
            }
            context.startService(mainServiceIntent);

            // RecordAndUpload.doRecord(context); This gave error - android.os.NetworkOnMainThreadException, but worked from a Service
        }



    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    private static boolean enoughBatteryToContinue(double batteryPercent, String alarmType){
        // The battery level required to continue depends on the type of alarm

        if (alarmType.equalsIgnoreCase("testButton")){
            // Test button was pressed
            return true;
        }

        if (alarmType.equalsIgnoreCase("repeating")){
            return batteryPercent > 75;
        }else { // must be a dawn or dusk alarm
            return batteryPercent > 50;
        }

    }

}