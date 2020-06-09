package org.torproject.snowflake;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import androidx.annotation.Nullable;

import org.json.JSONException;
import org.torproject.snowflake.constants.BrokerConstants;
import org.torproject.snowflake.constants.ForegroundServiceConstants;
import org.torproject.snowflake.interfaces.PeerConnectionObserverCallback;
import org.torproject.snowflake.pojo.OfferRequestBody;
import org.torproject.snowflake.pojo.SDPOfferResponse;
import org.torproject.snowflake.services.GetOfferService;
import org.torproject.snowflake.services.RetroServiceGenerator;
import org.webrtc.DataChannel;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SessionDescription;

import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.LinkedList;
import java.util.List;

import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

/**
 * Main Snowflake implementation of foreground service to relay data in the background.
 */
public class MyPersistentService extends Service {
    private static final String TAG = "MyPersistentService";
    //WebRTC vars
    DataChannel mainDataChannel;
    PeerConnection mainPeerConnection;
    PeerConnectionFactory factory;
    private SharedPreferences sharedPreferences;
    private boolean isServiceStarted;
    private PowerManager.WakeLock wakeLock;
    private Disposable serviceDisposable;


    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Log.d(TAG, "onStartCommand: executed with startId: " + startId);
        sharedPreferences = getSharedPreferences(getString(R.string.sharedpreference_file), MODE_PRIVATE);

        if (intent != null) {
            if (intent.getAction().equals(ForegroundServiceConstants.ACTION_START))
                startService();
            else
                stopService();
        } else {
            Log.d("onStartCommand:", "Null intent detected"); //Intent is null if system restarts the service.
            startService(); //Starting the service since it's a "restart" of the service.
        }

        //If the service is killed. OS will restart this service if it's START_STICKY.
        return START_STICKY;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "onCreate: Service Created");

        sharedPreferences = getSharedPreferences(getString(R.string.sharedpreference_file), MODE_PRIVATE); //Assigning the shared preferences
        sharedPreferencesHelper(ForegroundServiceConstants.SERVICE_RUNNING); //Editing the shared preferences
        Notification notification = createPersistentNotification(false, null);
        startForeground(ForegroundServiceConstants.DEF_NOTIFICATION_ID, notification);
    }

    @Override
    public void onDestroy() {
        Log.d(TAG, "onDestroy: Service Destroyed");
        sharedPreferencesHelper(ForegroundServiceConstants.SERVICE_STOPPED);

        //TODO: Dispose all connections.

        super.onDestroy();
    }

    /**
     * Helper function to edit shared preference file.
     *
     * @param setState State from ForegroundServiceConstants
     */
    private void sharedPreferencesHelper(final int setState) {
        Log.d(TAG, "sharedPreferencesHelper: Setting Shared Preference Running To: " + setState);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        if (setState == ForegroundServiceConstants.SERVICE_RUNNING) {
            editor.putBoolean(getString(R.string.is_service_running_bool), true);
        } else {
            editor.putBoolean(getString(R.string.is_service_running_bool), false);
        }
        editor.apply();
    }

    /**
     * Create a new persistent notification
     *
     * @param isUpdate is this new notification an update to current one?
     * @param update   String that is to be updated will current. Send "null" if isUpdate is false.
     * @return New Notification with given parameters.
     */
    private Notification createPersistentNotification(final boolean isUpdate, final String update) {
        Intent persistentNotIntent = new Intent(this, MyPersistentService.class);
        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, persistentNotIntent, 0);

        Notification.Builder builder;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            builder = new Notification.Builder(this, ForegroundServiceConstants.NOTIFICATION_CHANNEL_ID);
        else
            builder = new Notification.Builder(this);

        builder
                .setContentTitle("Snowflake Service")
                .setContentIntent(pendingIntent)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setPriority(Notification.PRIORITY_HIGH); // Android 26 and above needs priority.

        //If it's a notification update. Set the text to updated notification.
        if (isUpdate) {
            builder.setContentText(update)
                    .setTicker(update);
        } else {
            builder.setContentText("Snowflake Proxy Running")
                    .setTicker("Snowflake Proxy Running");
        }

        return builder.build();
    }

    /////////////// Start/Stop Service ////////////////////////

    /**
     * Use to star/re-start the service
     */
    private void startService() {
        if (isServiceStarted) {
            Log.d(TAG, "startService: Service Already running.");
            return;
        }
        Log.d(TAG, "startService: Starting foreground service");
        isServiceStarted = true;

        PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                "MyApp::MyWakelockTag");
        wakeLock.acquire(); //WakeLock acquired for unlimited amount of time.

        ///
        startWebRTCConnection(); //Starting WebRTC
    }

    /**
     * Use to stop the service.
     */
    private void stopService() {
        Log.d(TAG, "stopService:Stopping the foreground service");
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            stopForeground(true); //To remove the notification.
            stopSelf(); //Calls onDestroy to destroy the service and dispose all the connections
        } catch (Exception e) {
            Log.d(TAG, "stopService: Failed with: " + e.getMessage());
        }
        isServiceStarted = false;
    }

    /////////////// WebRTC ////////////////////////

    /**
     * Initializing and starting WebRTC connection.
     */
    private void startWebRTCConnection() {
        initializePeerConnectionFactory(); //Android Specific, you can Ignore.
        mainPeerConnection = createPeerConnection(factory); //Creating New Peer Connection.
        //TODO: Fetch offer from broker
    }

    /**
     * Initializing peer connection factory.
     */
    private void initializePeerConnectionFactory() {
        Log.d(TAG, "initializePeerConnectionFactory: Started");
        PeerConnectionFactory.InitializationOptions initializationOptions =
                PeerConnectionFactory.InitializationOptions.builder(this)
                        .createInitializationOptions();
        PeerConnectionFactory.initialize(initializationOptions);

        PeerConnectionFactory.Options options = new PeerConnectionFactory.Options();
        factory = PeerConnectionFactory.builder()
                .setOptions(options)
                .createPeerConnectionFactory();
        Log.d(TAG, "initializePeerConnectionFactory: Finished");
    }

    /**
     * Creating a new peer connection.
     *
     * @param factory PeerConnectionFactory
     * @return New PeerConnection
     */
    private PeerConnection createPeerConnection(PeerConnectionFactory factory) {
        Log.d(TAG, "createPeerConnection: Creating a new peer connection");
        List<PeerConnection.IceServer> iceServers = new LinkedList<>();
//        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()); To Add custom ICE servers.
        PeerConnection.RTCConfiguration rtcConfiguration = new PeerConnection.RTCConfiguration(iceServers);
        PeerConnection.Observer pcObserver = new MyPeerConnectionObserver(TAG, new PeerConnectionObserverCallback() {

            @Override
            public void onIceGatheringFinish() {
                if (mainPeerConnection.connectionState() != PeerConnection.PeerConnectionState.CLOSED) {
                    Log.d(TAG, "onIceGatheringFinish: Ice Gathering Finished. Sending Answer to broker...\n" + mainPeerConnection.getLocalDescription().description);
                    //Sending the SDP Answer to the server.
                    //TODO:Send Answer
                }
            }

            @Override
            public void onMessage(DataChannel.Buffer buffer) {
                //Relay it to WebSocket
                ByteBuffer data = ByteBuffer.wrap("HELLO".getBytes(Charset.defaultCharset())); //Sending some temporary data to test.
                mainDataChannel.send(new DataChannel.Buffer(data, false));
            }

            @Override
            public void onDataChannel(DataChannel dataChannel) {
                Log.d(TAG, "onDataChannel: Setting Data Channel");
                mainDataChannel = dataChannel;
            }

            @Override
            public void iceConnectionFailed() {
                Log.d(TAG, "iceConnectionFailed: ");
                //TODO: Figuring out with trac ticket.
            }

            @Override
            public void dataChannelStateChange(final DataChannel.State STATE) {
                Log.d(TAG, "dataChannelStateChange: Data Channel State: " + STATE);
            }
        });

        Log.d(TAG, "createPeerConnection: Finished creating peer connection.");
        return factory.createPeerConnection(rtcConfiguration, pcObserver);
    }
    /////////////// Network Calls ////////////////////////
    /**
     * Sending post request to get offer from the broker.
     */
    private void fetchOffer() {
        Log.d(TAG, "fetchOffer: Fetching offer from broker.");
        ///Retrofit call
        final GetOfferService getOfferService = RetroServiceGenerator.createService(GetOfferService.class);
        Observable<SDPOfferResponse> offer = getOfferService.getOffer(new OfferRequestBody("555"));
        serviceDisposable = offer.subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread()).subscribe(this::offerRequestSuccess, this::offerRequestFailure);
    }

    /**
     * Fetching offer is a success.
     * @param sdpOfferResponse
     */
    public void offerRequestSuccess(SDPOfferResponse sdpOfferResponse) {
        if (sdpOfferResponse.getStatus().equals(BrokerConstants.CLIENT_MATCH)) {
            Log.d(TAG, "requestSuccess: CLIENT MATCH");
            //TODO:Serialize SDP, SetRemote Description, Send Answer.

        } else {
            Log.d(TAG, "requestSuccess: NO CLIENT MATCH");
//            if (isServiceStarted)
            //fetchOffer(); //Sending request for offer again.
        }
    }

    public void offerRequestFailure(Throwable t){
        Log.d(TAG, "requestFailure: " + t.getMessage());
        createPersistentNotification(true,"Failed getting offer. Retrying.");
        //TODO:Set a time out to resending offer.
        fetchOffer(); //Sending request for offer again.
    }
}
