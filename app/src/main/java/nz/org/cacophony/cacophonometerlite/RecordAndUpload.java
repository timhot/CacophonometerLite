package nz.org.cacophony.cacophonometerlite;
import android.app.Service;
import android.content.Context;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.Message;
import android.telephony.TelephonyManager;
import android.util.Log;
//import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
//import ch.qos.logback.classic.Logger;

import java.io.File;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.Locale;
import java.util.TimeZone;

import static nz.org.cacophony.cacophonometerlite.Server.getToken;

/**
 * Created by User on 29-Mar-17.
 * This is where the action is - however the code starts, it gets here to do a recording and then send recording to server
 */

class RecordAndUpload {
    private static final String TAG = RecordAndUpload.class.getName();
//    private static Logger logger = null;



    private RecordAndUpload(){

    }



    static String doRecord(Context context, String typeOfRecording, Handler handler){
        String returnValue = null;
//        if (logger == null){
//            logger = Util.getAndConfigureLogger(context, LOG_TAG);
//            logger.info("Starting doRecord method");
//        }


        if (typeOfRecording == null){
            Log.e(TAG, "typeOfRecording is null");
//            Util.writeLocalLogEntryUsingLogback(context, LOG_TAG, "typeOfRecording is null");
//            logger.error("typeOfRecording is null");
            returnValue = "error";
            return returnValue;
        }

        Prefs prefs = new Prefs(context);


        long recordTimeSeconds =  (long)prefs.getRecordingDuration();

        if (prefs.getUseShortRecordings()){
            recordTimeSeconds = 1;
        }


            if (typeOfRecording.equalsIgnoreCase("testButton")  ){
                recordTimeSeconds = 5;  // short test
            }else if (typeOfRecording.equalsIgnoreCase("dawn") || typeOfRecording.equalsIgnoreCase("dusk")){
                recordTimeSeconds +=  2; // help to recognise dawn/dusk recordings
            }else if (typeOfRecording.equalsIgnoreCase("repeating") ){

                // Did this to try to fix bug of unknown origin that sometimes gives recordings every minute around dawn
                long dateTimeLastRepeatingAlarmFired = prefs.getDateTimeLastRepeatingAlarmFired();
                long now = new Date().getTime();
                long minFractionOfRepeatTimeToWait = (long)(prefs.getTimeBetweenRecordingsSeconds() * 1000 * 0.9) ; // Do not proceed if last repeating alarm occurred within 90% of time interval for repeating alarms
                if ((now - dateTimeLastRepeatingAlarmFired) < minFractionOfRepeatTimeToWait){
                    Log.w(TAG, "A repeating alarm happened to soon and so was aborted");
//                    logger.warn("A repeating alarm happened to soon and so was aborted");
                    return "repeating alarm happened to soon";
                }else{
                    prefs.setDateTimeLastRepeatingAlarmFired(now); // needed by above code next time repeating alarm fires.
                }

            }

        makeRecording(context, recordTimeSeconds);

        returnValue = "recorded successfully";

        if (typeOfRecording.equalsIgnoreCase("testButton")  ){
            if (handler !=null){
                Message message = handler.obtainMessage();
                if (prefs.getOffLineMode()){
//
                    returnValue = "recorded successfully no network";
                }else{
                    message.what = StartRecordingReceiver.RECORDING_FINISHED;
                    message.sendToTarget();
                }

            }
        }

        // only upload recordings if sufficient time has passed since last upload
        long dateTimeLastUpload = prefs.getDateTimeLastUpload();
        long now = new Date().getTime();
        long timeIntervalBetweenUploads = 1000 * (long)prefs.getTimeBetweenUploadsSeconds();
        boolean uploadedFilesSuccessfully = false;
        if (typeOfRecording.equalsIgnoreCase("testButton") ){

                // Always upload when test button pressed - unless no network checkbox in setup checked
            if (!prefs.getOffLineMode()) {
                uploadedFilesSuccessfully = uploadFiles(context);
                if (uploadedFilesSuccessfully){
                    returnValue = "recorded and uploaded successfully";
                }

                prefs.setDateTimeLastUpload(0); // this is to allow the recording to upload the next time the periodic recording happens
                prefs.setDateTimeLastRepeatingAlarmFired(0); // this will allow recording to be made next time repeating alarm fires

                // Always set up dawn/dusk alarms when test button pressed
                DawnDuskAlarms.configureDawnAlarms(context);
                DawnDuskAlarms.configureDuskAlarms(context);
                prefs.setDateTimeLastCalculatedDawnDusk(0);
            }
            }else if ((now - dateTimeLastUpload) > timeIntervalBetweenUploads){
            if (!prefs.getOffLineMode()) {
                uploadedFilesSuccessfully = uploadFiles(context);
                if (uploadedFilesSuccessfully){
                    returnValue = "recorded and uploaded successfully";
                    prefs.setDateTimeLastUpload(now);
                }else {
                    returnValue = "recorded BUT did not upload";
                    Log.e(TAG, "Files failed to upload");
                }
            }


        }



            if (typeOfRecording.equalsIgnoreCase("repeating")  ){
                // Update dawn/dusk times if it has been more than 23.5 hours since last time. It will do this if the current alarm is a repeating alarm or a dawn/dusk alarm
                long dateTimeLastCalculatedDawnDusk = prefs.getDateTimeLastCalculatedDawnDusk();
               //  long timeIntervalBetweenDawnDuskTimeCalculation = 1000 * 60 * 6 * 235;
                long timeIntervalBetweenDawnDuskTimeCalculation = 1000 * 60 ; // 1 minute for testing
//                long timeIntervalBetweenDawnDuskTimeCalculation = 1000 * 60 * 60 * 2; // 2 hours for testing
//                long timeIntervalBetweenDawnDuskTimeCalculation = 1000 * 60 * 60 ; // 1 hour for testing

                if ((now - dateTimeLastCalculatedDawnDusk) > timeIntervalBetweenDawnDuskTimeCalculation){
                    DawnDuskAlarms.configureDawnAlarms(context);
                    DawnDuskAlarms.configureDuskAlarms(context);
                    prefs.setDateTimeLastCalculatedDawnDusk(now);
                }

            }


            return returnValue;
    }

    private static boolean makeRecording(Context context,  long recordTimeSeconds){

        // Get recording file.
        Date date = new Date(System.currentTimeMillis());
        // Calculate dawn and dusk offset in seconds will be sent to server to allow queries on this data
        Calendar nowToday =  new GregorianCalendar(TimeZone.getTimeZone("Pacific/Auckland"));

        Calendar dawn = Util.getDawn(context, nowToday);

        long relativeToDawn  =  nowToday.getTimeInMillis() - dawn.getTimeInMillis();
        relativeToDawn  = relativeToDawn /1000; // now in seconds

        Calendar dusk = Util.getDusk(context, nowToday);

        long relativeToDusk  = nowToday.getTimeInMillis() - dusk.getTimeInMillis();
        relativeToDusk  = relativeToDusk /1000; // now in seconds

        DateFormat fileFormat = new SimpleDateFormat("yyyy MM dd HH mm ss", Locale.UK);
        String fileName = fileFormat.format(date);

        if (Math.abs(relativeToDawn) < Math.abs(relativeToDusk)){
            fileName += " relativeToDawn " + relativeToDawn;
        }else{
            fileName += " relativeToDusk " + relativeToDusk;
        }

        if (Util.isAirplaneModeOn(context)){
            fileName += " airplaneModeOn";
        }else{
            fileName += " airplaneModeOff";
        }

        String batteryStatus = Util.getBatteryStatus(context);
        fileName += " " + batteryStatus;
        double batteryLevel = Util.getBatteryLevel(context);
        fileName += " " + batteryLevel;
        fileName += " " + recordTimeSeconds;
        fileName += ".3gp";

        File file = new File(Util.getRecordingsFolder(context), fileName);
        String filePath = file.getAbsolutePath();

        // Setup audio recording settings.
        MediaRecorder mRecorder = new MediaRecorder();

        // Try to prepare recording.
        try {
            mRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mRecorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
            mRecorder.setOutputFile(filePath);
            mRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB);
            mRecorder.prepare();

        } catch (Exception ex) {

//            logger.error("Setup recording failed.");
//            logger.error("Could be due to lack of sdcard");
//            logger.error("Could be due to phone connected to pc as usb storage");
//            logger.error(e.getLocalizedMessage());
            Log.e(TAG, "Setup recording failed. Could be due to lack of sdcard. Could be due to phone connected to pc as usb storage");
            Log.e(TAG, ex.getLocalizedMessage());

            return false;
        }

        // Start recording.
        try {
            mRecorder.start();
        }catch (Exception e){

//            logger.error("mRecorder.start " + e.getLocalizedMessage());
            Log.e(TAG, "mRecorder.start " + e.getLocalizedMessage());
            return false;
        }

        // Sleep for duration of recording.
        try {

            Thread.sleep(recordTimeSeconds * 1000);

        } catch (InterruptedException e) {

//            logger.error("Failed sleeping in recording thread.");
            Log.e(TAG, "Failed sleeping in recording thread.");
            return false;
        }

        // Stop recording.
        mRecorder.stop();
//        mRecorder.release();
        //https://stackoverflow.com/questions/9609479/android-mediaplayer-went-away-with-unhandled-events
        mRecorder.reset();
        mRecorder.release();

        mRecorder = null; // attempting to fix error media recorder went away with unhandled events

             // Give time for file to be saved.
        try {
            Thread.sleep(5 * 1000);
        } catch (InterruptedException ex) {
//            logger.error("Failed sleeping in recording thread." +  ex.getLocalizedMessage());
            Log.e(TAG, "Failed sleeping in recording thread." +  ex.getLocalizedMessage());
        }
        return true;
    }

    private static boolean uploadFiles(Context context){
        boolean returnValue = true;
        try {
            File recordingsFolder = Util.getRecordingsFolder(context);
            if (recordingsFolder == null){

//                logger.error("Error getting recordings folder");
                Log.e(TAG,"Error getting recordings folder" );
                return false;
            }
            File recordingFiles[] = recordingsFolder.listFiles();
            if (recordingFiles != null) {
                Util.disableFlightMode(context);

                // Now wait for network connection as setFlightMode takes a while
                if (!Util.waitForNetworkConnection(context, true)){
//                    logger.error("Failed to disable airplane mode");
                    Log.e(TAG, "Failed to disable airplane mode");
                    return false;
                }


                // Check here to see if can connect to server and abort (for all files) if can't
                // Check that there is a JWT (JSON Web Token)
                if (getToken() == null) {

                    if (!Server.login(context)) {
//                        logger.warn("sendFile: no JWT. Aborting upload");
                        Log.w(TAG, "sendFile: no JWT. Aborting upload");


                        return false; // Can't upload without JWT, login/register device to get JWT.
                    }
                }
                for (File aFile : recordingFiles) {

                    if (sendFile(context, aFile)) {
                        // deleting files can cause app to crash when phone connected to pc, so put in try catch
                        boolean fileSuccessfullyDeleted = false;
                        try {
                            fileSuccessfullyDeleted = aFile.delete();

                        }catch (Exception ex){
//                            logger.error(ex.getLocalizedMessage());
                            Log.e(TAG, ex.getLocalizedMessage());

                        }
                        if (!fileSuccessfullyDeleted) {
                            // for some reason file did not delete so exit for loop
//                            logger.error("Failed to delete file");
                            Log.e(TAG, "Failed to delete file");
                            returnValue = false;
                            break;
                        }
                    } else {
                        returnValue = false;
//                        logger.error("Failed to upload file to server");
                        Log.e(TAG, "Failed to upload file to server");
                        return false;
                    }
                }
            }
            return returnValue;
        }catch (Exception ex){

//            logger.error(ex.getLocalizedMessage());
            Log.e(TAG, ex.getLocalizedMessage());
            return false;
        }finally {
//            Util.enableFlightMode(context);
//            // Now wait for network connection to close as  setFlightMode takes a while
//            if (!Util.waitForNetworkConnection(context, false)){
//
//                logger.error("Failed to disable airplane mode");
//
//            }
        }

    }

    private static boolean sendFile(Context context, File aFile) {
        Prefs prefs = new Prefs(context);

        // Get metadata and put into JSON.
        JSONObject audioRecording = new JSONObject();

        String fileName = aFile.getName();

        // http://stackoverflow.com/questions/3481828/how-to-split-a-string-in-java
        //http://stackoverflow.com/questions/3387622/split-string-on-dot-as-delimiter
        String[] fileNameParts = fileName.split("[. ]");
        // this code breaks if old files exist, so delete them and move on

        if (fileNameParts.length != 14) {
          if (aFile.delete()){
//              Log.i(LOG_TAG, "deleted file: " + fileName);
//              logger.error("deleted file: " + fileName);
              return false;
          }

        }

        String year = fileNameParts[0];
        String month = fileNameParts[1];
        String day = fileNameParts[2];
        String hour = fileNameParts[3];
        String minute = fileNameParts[4];
        String second = fileNameParts[5];
        String relativeTo = fileNameParts[6];
        String relativeToOffset = fileNameParts[7];
        String airplaneMode = fileNameParts[8];
        String batteryStatus = fileNameParts[9];
        String batteryLevel = fileNameParts[10];
        batteryLevel += ".";
        batteryLevel += fileNameParts[11];
        String recordTimeSeconds = fileNameParts[12];
        boolean airplaneModeOn = false;
        if (airplaneMode.equalsIgnoreCase("airplaneModeOn")) {
            airplaneModeOn = true;
        }



        String localFilePath = Util.getRecordingsFolder(context) + "/" + fileName;
        if (! new File(localFilePath).exists()){

//            logger.error(localFilePath + " does not exist");
            Log.e(TAG,localFilePath + " does not exist" );
            return false;
        }
        String recordingDateTime = year + "-" + month + "-" + day + " " + hour + ":" + minute + ":" + second;
        String recordingTime = hour + ":" + minute + ":" + second;

        try {

            JSONArray location = new JSONArray();
            location.put(prefs.getLatitude());
            location.put(prefs.getLongitude());
            audioRecording.put("location", location);
            audioRecording.put("duration", recordTimeSeconds);
            audioRecording.put("localFilePath", localFilePath);
            audioRecording.put("recordingDateTime", recordingDateTime);
            audioRecording.put("recordingTime", recordingTime);
            audioRecording.put("batteryCharging", batteryStatus);

            audioRecording.put("batteryLevel", batteryLevel);
            audioRecording.put("airplaneModeOn", airplaneModeOn);

            if (relativeTo.equalsIgnoreCase("relativeToDawn")) {
                audioRecording.put("relativeToDawn", relativeToOffset);
            }
            if (relativeTo.equalsIgnoreCase("relativeToDusk")) {
                audioRecording.put("relativeToDusk", relativeToOffset);
            }
            String versionName = BuildConfig.VERSION_NAME;
            audioRecording.put("version", versionName);


            JSONObject additionalMetadata = new JSONObject();

//            String localLog = Util.getAllLocalLogEntries(context);
//
//            additionalMetadata.put("Local Log ", localLog);
//
//            String logLevel = ((ch.qos.logback.classic.Logger)logger).getLevel().levelStr;
//            additionalMetadata.put("Log level is ", logLevel);

            additionalMetadata.put("Android API Level", Build.VERSION.SDK_INT);
            additionalMetadata.put("Phone has been rooted", prefs.getHasRootAccess());
            additionalMetadata.put("Phone manufacturer", Build.MANUFACTURER);
            additionalMetadata.put("Phone model", Build.MODEL);

            TelephonyManager mTelephonyManager = (TelephonyManager) context
                    .getSystemService(Service.TELEPHONY_SERVICE);

            additionalMetadata.put("SIM state",Util.getSimStateAsString( mTelephonyManager.getSimState()));
            if (mTelephonyManager.getSimState() == TelephonyManager.SIM_STATE_READY) {
                additionalMetadata.put("SimOperatorName", mTelephonyManager.getSimOperatorName());
            }

            audioRecording.put("additionalMetadata", additionalMetadata);

        } catch (JSONException ex) {
         //   ex.printStackTrace();
//            logger.error(ex.getLocalizedMessage());
            Log.e(TAG,ex.getLocalizedMessage() );
        }
        return Server.uploadAudioRecording(aFile, audioRecording, context);
    }
}
