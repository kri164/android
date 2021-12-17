package org.owntracks.android.services;

import static android.os.Process.killProcess;
import static android.os.Process.myPid;
import static androidx.core.app.NotificationCompat.PRIORITY_LOW;
import static org.owntracks.android.App.NOTIFICATION_CHANNEL_EVENTS;
import static org.owntracks.android.App.NOTIFICATION_CHANNEL_ONGOING;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.location.Location;
import android.media.AudioManager;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.style.StyleSpan;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.lifecycle.LifecycleService;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;
import org.jetbrains.annotations.NotNull;
import org.owntracks.android.R;
import org.owntracks.android.data.EndpointState;
import org.owntracks.android.data.repos.ContactsRepo;
import org.owntracks.android.data.repos.EndpointStateRepo;
import org.owntracks.android.data.repos.LocationRepo;
import org.owntracks.android.data.repos.WaypointsRepo;
import org.owntracks.android.geocoding.GeocoderProvider;
import org.owntracks.android.location.Geofence;
import org.owntracks.android.location.LocationAvailability;
import org.owntracks.android.location.LocationCallback;
import org.owntracks.android.location.LocationProviderClient;
import org.owntracks.android.location.LocationRequest;
import org.owntracks.android.location.LocationResult;
import org.owntracks.android.model.FusedContact;
import org.owntracks.android.model.messages.MessageLocation;
import org.owntracks.android.model.messages.MessageTransition;
import org.owntracks.android.services.worker.Scheduler;
import org.owntracks.android.support.DateFormatter;
import org.owntracks.android.support.Events;
import org.owntracks.android.support.Preferences;
import org.owntracks.android.support.RunThingsOnOtherThreads;
import org.owntracks.android.support.ServiceBridge;
import org.owntracks.android.ui.map.MapActivity;

import java.util.Date;
import java.util.LinkedList;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;

import dagger.hilt.android.AndroidEntryPoint;
import timber.log.Timber;

@AndroidEntryPoint
public class BackgroundService extends LifecycleService implements SharedPreferences.OnSharedPreferenceChangeListener, ServiceBridge.ServiceBridgeInterface {
    private static final int INTENT_REQUEST_CODE_CLEAR_EVENTS = 1263;

    private static final int NOTIFICATION_ID_ONGOING = 1;
    private static final int NOTIFICATION_ID_EVENT_GROUP = 2;
    public static final String BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG = "backgroundRestrictionNotification";

    private static int notificationEventsID = 3;

    private final String NOTIFICATION_GROUP_EVENTS = "events";

    // NEW ACTIONS ALSO HAVE TO BE ADDED TO THE SERVICE INTENT FILTER
    public static final String INTENT_ACTION_CLEAR_NOTIFICATIONS = "org.owntracks.android.CLEAR_NOTIFICATIONS";
    private static final String INTENT_ACTION_SEND_LOCATION_USER = "org.owntracks.android.SEND_LOCATION_USER";
    public static final String INTENT_ACTION_REREQUEST_LOCATION_UPDATES = "org.owntracks.android.REREQUEST_LOCATION_UPDATES";
    private static final String INTENT_ACTION_CHANGE_MONITORING = "org.owntracks.android.CHANGE_MONITORING";
    private static final String INTENT_ACTION_EXIT = "org.owntracks.android.EXIT";

    private static final String INTENT_ACTION_BOOT_COMPLETED = "android.intent.action.BOOT_COMPLETED";
    private static final String INTENT_ACTION_PACKAGE_REPLACED = "android.intent.action.MY_PACKAGE_REPLACED";

    private LocationCallback locationCallback;
    private LocationCallback locationCallbackOnDemand;
    private MessageLocation lastLocationMessage;

    private NotificationCompat.Builder activeNotificationCompatBuilder;
    private NotificationCompat.Builder eventsNotificationCompatBuilder;
    private NotificationManager notificationManager;

    private NotificationManagerCompat notificationManagerCompat;

    private final LinkedList<Spannable> activeNotifications = new LinkedList<>();
    private int lastQueueLength = 0;

    private boolean hasBeenStartedExplicitly = false;

    private static final int updateCurrentIntentFlags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.M ? PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT : PendingIntent.FLAG_UPDATE_CURRENT;

    @Inject
    Preferences preferences;

    @Inject
    EventBus eventBus;

    @Inject
    Scheduler scheduler;

    @Inject
    LocationProcessor locationProcessor;

    @Inject
    GeocoderProvider geocoderProvider;

    @Inject
    ContactsRepo contactsRepo;

    @Inject
    LocationRepo locationRepo;

    @Inject
    RunThingsOnOtherThreads runThingsOnOtherThreads;

    @Inject
    WaypointsRepo waypointsRepo;

    @Inject
    ServiceBridge serviceBridge;

    @Inject
    MessageProcessor messageProcessor;

    @Inject
    EndpointStateRepo endpointStateRepo;

    @Inject
    LocationProviderClient locationProviderClient;

    @Override
    public void onCreate() {
        super.onCreate();
        Timber.v("Background service onCreate. ThreadID: %s", Thread.currentThread());
        serviceBridge.bind(this);

        notificationManagerCompat = NotificationManagerCompat.from(this);
        notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);

        locationCallback = new LocationCallback() {
            @Override
            public void onLocationAvailability(@NotNull LocationAvailability locationAvailability) {
                Timber.d("BackgroundService location availability %s", locationAvailability);
            }

            @Override
            public void onLocationResult(@NotNull LocationResult locationResult) {
                Timber.d("BackgroundService location result received: %s", locationResult);
                onLocationChanged(locationResult.getLastLocation(), MessageLocation.REPORT_TYPE_DEFAULT);
            }
        };

        locationCallbackOnDemand = new LocationCallback() {
            @Override
            public void onLocationAvailability(@NotNull LocationAvailability locationAvailability) {

            }

            @Override
            public void onLocationResult(@NotNull LocationResult locationResult) {
                Timber.d("BackgroundService On-demand location result received: %s", locationResult);
                onLocationChanged(locationResult.getLastLocation(), MessageLocation.REPORT_TYPE_RESPONSE);
            }
        };

        startForeground(NOTIFICATION_ID_ONGOING, getOngoingNotification());

        setupLocationRequest();

        scheduler.scheduleLocationPing();

        eventBus.register(this);

        preferences.registerOnPreferenceChangedListener(this);
    }


    @Override
    public void onDestroy() {
        stopForeground(true);
        preferences.unregisterOnPreferenceChangedListener(this);
        messageProcessor.stopSendingMessages();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            handleIntent(intent);
        }

        endpointStateRepo.getEndpointQueueLength().observe(this, queueLength -> {
            lastQueueLength = queueLength;
            updateOngoingNotification();
        });
        endpointStateRepo.getEndpointState().observe(this, state -> updateOngoingNotification());

        endpointStateRepo.setServiceStartedNow();

        return START_STICKY;
    }

    private void handleIntent(@NonNull Intent intent) {
        if (intent.getAction() != null) {
            Timber.v("intent received with action:%s", intent.getAction());

            switch (intent.getAction()) {
                case INTENT_ACTION_SEND_LOCATION_USER:
                    locationProcessor.publishLocationMessage(MessageLocation.REPORT_TYPE_USER);
                    return;
                case INTENT_ACTION_CLEAR_NOTIFICATIONS:
                    clearEventStackNotification();
                    return;
                case INTENT_ACTION_REREQUEST_LOCATION_UPDATES:
                    setupLocationRequest();
                    return;
                case INTENT_ACTION_CHANGE_MONITORING:
                    if (intent.hasExtra(preferences.getPreferenceKey(R.string.preferenceKeyMonitoring))) {
                        preferences.setMonitoring(intent.getIntExtra(preferences.getPreferenceKey(R.string.preferenceKeyMonitoring), preferences.getMonitoring()));
                    } else {
                        // Step monitoring mode if no mode is specified
                        preferences.setMonitoringNext();
                    }
                    hasBeenStartedExplicitly = true;
                    notificationManager.cancel(BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG, 0);
                    return;
                case INTENT_ACTION_BOOT_COMPLETED:
                case INTENT_ACTION_PACKAGE_REPLACED:
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasBeenStartedExplicitly) {
                        notifyUserOfBackgroundLocationRestriction();
                    }
                    return;
                case INTENT_ACTION_EXIT:
                    exit();
                    return;
                default:
                    Timber.v("unhandled intent action received: %s", intent.getAction());
            }
        } else {
            hasBeenStartedExplicitly = true;
        }
    }

    private void exit() {
        stopSelf();
        scheduler.cancelAllTasks();
        killProcess(myPid());
    }

    private void notifyUserOfBackgroundLocationRestriction() {
        Intent activityLaunchIntent = new Intent(getApplicationContext(), MapActivity.class);
        activityLaunchIntent.setAction("android.intent.action.MAIN");
        activityLaunchIntent.addCategory("android.intent.category.LAUNCHER");
        activityLaunchIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        String notificationText = getString(R.string.backgroundLocationRestrictionNotificationText);
        String notificationTitle = getString(R.string.backgroundLocationRestrictionNotificationTitle);

        Notification notification = new NotificationCompat.Builder(getApplicationContext(), GeocoderProvider.ERROR_NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setContentText(notificationText)
                .setAutoCancel(true)
                .setSmallIcon(R.drawable.ic_owntracks_80)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText))
                .setContentIntent(PendingIntent.getActivity(getApplicationContext(), 0, activityLaunchIntent, updateCurrentIntentFlags))
                .setPriority(PRIORITY_LOW)
                .setSilent(true)
                .build();

        notificationManager.notify(BACKGROUND_LOCATION_RESTRICTION_NOTIFICATION_TAG, 0, notification);
    }

    @Nullable
    private NotificationCompat.Builder getOngoingNotificationBuilder() {
        if (activeNotificationCompatBuilder != null)
            return activeNotificationCompatBuilder;

        Intent resultIntent = new Intent(this, MapActivity.class);
        resultIntent.setAction("android.intent.action.MAIN");
        resultIntent.addCategory("android.intent.category.LAUNCHER");
        resultIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent resultPendingIntent = PendingIntent.getActivity(this, 0, resultIntent, updateCurrentIntentFlags);
        Intent publishIntent = new Intent();
        publishIntent.setAction(INTENT_ACTION_SEND_LOCATION_USER);
        PendingIntent publishPendingIntent = PendingIntent.getService(this, 0, publishIntent, updateCurrentIntentFlags);

        publishIntent.setAction(INTENT_ACTION_CHANGE_MONITORING);
        PendingIntent changeMonitoringPendingIntent = PendingIntent.getService(this, 0, publishIntent, updateCurrentIntentFlags);


        activeNotificationCompatBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ONGOING)
                .setContentIntent(resultPendingIntent)
                .setSortKey("a")
                .addAction(R.drawable.ic_baseline_publish_24, getString(R.string.publish), publishPendingIntent)
                .addAction(R.drawable.ic_owntracks_80, getString(R.string.notificationChangeMonitoring), changeMonitoringPendingIntent)
                .setSmallIcon(R.drawable.ic_owntracks_80)
                .setPriority(preferences.getNotificationHigherPriority() ? NotificationCompat.PRIORITY_DEFAULT : NotificationCompat.PRIORITY_MIN)
                .setSound(null, AudioManager.STREAM_NOTIFICATION)
                .setOngoing(true);

        if (android.os.Build.VERSION.SDK_INT >= 23) {
            activeNotificationCompatBuilder.setColor(getColor(com.mikepenz.materialize.R.color.primary))
                    .setCategory(NotificationCompat.CATEGORY_SERVICE)
                    .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        }

        return activeNotificationCompatBuilder;
    }

    private void updateOngoingNotification() {
        notificationManager.notify(NOTIFICATION_ID_ONGOING, getOngoingNotification());
    }

    private Notification getOngoingNotification() {
        NotificationCompat.Builder builder = getOngoingNotificationBuilder();

        if (builder == null)
            return null;

        if (this.lastLocationMessage != null && preferences.getNotificationLocation()) {
            builder.setContentTitle(this.lastLocationMessage.getGeocode());
            builder.setWhen(TimeUnit.SECONDS.toMillis(this.lastLocationMessage.getTimestamp()));
            builder.setNumber(lastQueueLength);
        } else {
            builder.setContentTitle(getString(R.string.app_name));
        }

        // Show monitoring mode if endpoint state is not interesting
        EndpointState lastEndpointState = endpointStateRepo.getEndpointState().getValue();
        if (lastEndpointState == EndpointState.CONNECTED || lastEndpointState == EndpointState.IDLE || lastEndpointState == null) {
            builder.setContentText(getMonitoringLabel(preferences.getMonitoring()));
        } else if (lastEndpointState == EndpointState.ERROR && lastEndpointState.getMessage() != null) {
            builder.setContentText(lastEndpointState.getLabel(this) + ": " + lastEndpointState.getMessage());
        } else {
            builder.setContentText(lastEndpointState.getLabel(this));
        }
        return builder.build();
    }


    private String getMonitoringLabel(int mode) {
        switch (mode) {
            case LocationProcessor.MONITORING_QUIET:
                return getString(R.string.monitoring_quiet);
            case LocationProcessor.MONITORING_MANUAL:
                return getString(R.string.monitoring_manual);
            case LocationProcessor.MONITORING_SIGNIFICANT:
                return getString(R.string.monitoring_significant);
            case LocationProcessor.MONITORING_MOVE:
                return getString(R.string.monitoring_move);
        }
        return getString(R.string.na);
    }

    private void sendEventNotification(MessageTransition message) {
        NotificationCompat.Builder builder = getEventsNotificationBuilder();

        if (builder == null) {
            Timber.e("no builder returned");
            return;
        }

        FusedContact c = contactsRepo.getById(message.getContactKey());

        String location = message.getDescription();

        if (location == null) {
            location = getString(R.string.aLocation);
        }
        String title = message.getTrackerId();
        if (c != null)
            title = c.getFusedName();
        else if (title == null) {
            title = message.getContactKey();
        }

        String text = String.format("%s %s", getString(message.getTransition() == Geofence.GEOFENCE_TRANSITION_ENTER ? R.string.transitionEntering : R.string.transitionLeaving), location);

        long timestampInMs = TimeUnit.SECONDS.toMillis(message.getTimestamp());

        eventsNotificationCompatBuilder.setContentTitle(title);
        eventsNotificationCompatBuilder.setContentText(text);
        eventsNotificationCompatBuilder.setWhen(timestampInMs);
        eventsNotificationCompatBuilder.setShowWhen(true);
        eventsNotificationCompatBuilder.setGroup(NOTIFICATION_GROUP_EVENTS);
        // Deliver notification
        Notification n = eventsNotificationCompatBuilder.build();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            sendEventStackNotification(title, text, new Date(timestampInMs));
        } else {
            notificationManagerCompat.notify(notificationEventsID++, n);
        }
    }

    @RequiresApi(23)
    private void sendEventStackNotification(String title, String text, Date timestamp) {
        Timber.v("SDK_INT >= 23, building stack notification");

        String whenStr = DateFormatter.formatDate(timestamp);

        Spannable newLine = new SpannableString(String.format("%s %s %s", whenStr, title, text));
        newLine.setSpan(new StyleSpan(Typeface.BOLD), 0, whenStr.length() + 1, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        activeNotifications.push(newLine);
        Timber.v("groupedNotifications: %s", activeNotifications.size());
        String summary = getResources().getQuantityString(R.plurals.notificationEventsTitle, activeNotifications.size(), activeNotifications.size());

        NotificationCompat.InboxStyle inbox = new NotificationCompat.InboxStyle();
        inbox.setSummaryText(summary);

        for (Spannable n : activeNotifications) {
            inbox.addLine(n);
        }

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_EVENTS)
                .setContentTitle(getString(R.string.events))
                .setContentText(summary)
                .setGroup(NOTIFICATION_GROUP_EVENTS) // same as group of single notifications
                .setGroupSummary(true)
                .setColor(getColor(com.mikepenz.materialize.R.color.primary))
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setSmallIcon(R.drawable.ic_owntracks_80)
                .setLocalOnly(true)
                .setDefaults(Notification.DEFAULT_ALL)
                .setNumber(activeNotifications.size())
                .setStyle(inbox)
                .setContentIntent(PendingIntent.getActivity(this, (int) System.currentTimeMillis() / 1000, new Intent(this, MapActivity.class).putExtra(INTENT_ACTION_CLEAR_NOTIFICATIONS, true), updateCurrentIntentFlags))
                .setDeleteIntent(PendingIntent.getService(this, INTENT_REQUEST_CODE_CLEAR_EVENTS, (new Intent(this, BackgroundService.class)).setAction(INTENT_ACTION_CLEAR_NOTIFICATIONS), updateCurrentIntentFlags));

        Notification stackNotification = builder.build();
        notificationManagerCompat.notify(NOTIFICATION_GROUP_EVENTS, NOTIFICATION_ID_EVENT_GROUP, stackNotification);

    }

    private void clearEventStackNotification() {
        Timber.v("clearing notification stack");
        activeNotifications.clear();
    }

    void onLocationChanged(@Nullable Location location, @Nullable String reportType) {
        if (location == null) {
            Timber.e("no location provided");
            return;
        }
        Timber.v("location update received: tst:%s, acc:%s, lat:%s, lon:%s type:%s", location.getTime(), location.getAccuracy(), location.getLatitude(), location.getLongitude(), reportType);

        if (location.getTime() > locationRepo.getCurrentLocationTime()) {
            locationProcessor.onLocationChanged(location, reportType);
        } else {
            Timber.v("Not re-sending message with same timestamp as last");
        }
    }

    public void requestOnDemandLocationUpdate() {
        if (missingLocationPermission()) {
            Timber.e("missing location permission");
            return;
        }

        LocationRequest request = new LocationRequest();

        request.setNumUpdates(1);
        request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
        request.setExpirationDuration(TimeUnit.MINUTES.toMillis(1));

        Timber.d("On demand location request");

        locationProviderClient.requestLocationUpdates(request, locationCallbackOnDemand, runThingsOnOtherThreads.getBackgroundLooper());
    }

    private boolean setupLocationRequest() {
        Timber.d("Setting up location request");
        if (missingLocationPermission()) {
            Timber.e("missing location permission");
            return false;
        }

        if (locationProviderClient == null) {
            Timber.e("FusedLocationClient not available");
            return false;
        }
        int monitoring = preferences.getMonitoring();

        LocationRequest request = new LocationRequest();

        switch (monitoring) {
            case LocationProcessor.MONITORING_QUIET:
            case LocationProcessor.MONITORING_MANUAL:
                request.setInterval(TimeUnit.SECONDS.toMillis(preferences.getLocatorInterval()));
                request.setSmallestDisplacement((float) preferences.getLocatorDisplacement());
                request.setPriority(LocationRequest.PRIORITY_LOW_POWER);
                break;
            case LocationProcessor.MONITORING_SIGNIFICANT:
                request.setInterval(TimeUnit.SECONDS.toMillis(preferences.getLocatorInterval()));
                request.setSmallestDisplacement((float) preferences.getLocatorDisplacement());
                request.setPriority(getLocationRequestPriority());
                break;
            case LocationProcessor.MONITORING_MOVE:
                request.setInterval(TimeUnit.SECONDS.toMillis(preferences.getMoveModeLocatorInterval()));
                request.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);
                break;
        }
        Timber.d("Location update request params: %s", request);
        locationProviderClient.flushLocations();
        locationProviderClient.requestLocationUpdates(request, locationCallback, runThingsOnOtherThreads.getBackgroundLooper());
        return true;
    }

    private int getLocationRequestPriority() {
        switch (preferences.getLocatorPriority()) {
            case 0:
                return LocationRequest.PRIORITY_NO_POWER;
            case 1:
                return LocationRequest.PRIORITY_LOW_POWER;
            case 3:
                return LocationRequest.PRIORITY_HIGH_ACCURACY;
            case 2:
            default:
                return LocationRequest.PRIORITY_BALANCED_POWER_ACCURACY;
        }
    }

    private boolean missingLocationPermission() {
        return ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_DENIED;
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.ModeChanged e) {
        setupLocationRequest();
        updateOngoingNotification();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Events.MonitoringChanged e) {
        setupLocationRequest();
        updateOngoingNotification();
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(MessageTransition message) {
        Timber.d("transition isIncoming:%s topic:%s", message.isIncoming(), message.getTopic());
        if (message.isIncoming())
            sendEventNotification(message);
    }

    @Subscribe(threadMode = ThreadMode.BACKGROUND)
    public void onEvent(Location location) {
        MessageLocation messageLocation = MessageLocation.fromLocation(location);
        if (lastLocationMessage == null || lastLocationMessage.getTimestamp() < messageLocation.getTimestamp()) {
            this.lastLocationMessage = messageLocation;
            geocoderProvider.resolve(messageLocation, this);
        }
    }

    public void onGeocodingProviderResult(MessageLocation m) {
        if (m == lastLocationMessage) {
            updateOngoingNotification();
        }
    }

    private NotificationCompat.Builder getEventsNotificationBuilder() {
        if (!preferences.getNotificationEvents())
            return null;

        Timber.d("building notification builder");

        if (eventsNotificationCompatBuilder != null)
            return eventsNotificationCompatBuilder;

        Timber.d("builder not present, lazy building");
        Intent openIntent = new Intent(this, MapActivity.class);
        openIntent.setAction("android.intent.action.MAIN");
        openIntent.addCategory("android.intent.category.LAUNCHER");
        openIntent.setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent openPendingIntent = PendingIntent.getActivity(this, 0, openIntent, updateCurrentIntentFlags);
        eventsNotificationCompatBuilder = new NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_EVENTS)
                .setContentIntent(openPendingIntent)
                .setSmallIcon(R.drawable.ic_baseline_add_24)
                .setAutoCancel(true)
                .setShowWhen(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            eventsNotificationCompatBuilder.setColor(getColor(com.mikepenz.materialize.R.color.primary));
        }
        return eventsNotificationCompatBuilder;
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (preferences.getPreferenceKey(R.string.preferenceKeyLocatorInterval).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyLocatorDisplacement).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyLocatorPriority).equals(key) ||
                preferences.getPreferenceKey(R.string.preferenceKeyMoveModeLocatorInterval).equals(key)
        ) {
            Timber.d("locator preferences changed. Resetting location request.");
            setupLocationRequest();
        }
    }

    public void reInitializeLocationRequests() {
        runThingsOnOtherThreads.postOnServiceHandlerDelayed(() -> {
            if (setupLocationRequest()) {
                Timber.d("Getting last location");
                Location lastLocation = locationProviderClient.getLastLocation();
                if (lastLocation != null) {
                    onLocationChanged(lastLocation, MessageLocation.REPORT_TYPE_DEFAULT);
                }
            }
        }, 0);

    }

    private final IBinder localServiceBinder = new LocalBinder();

    public class LocalBinder extends Binder {
        public BackgroundService getService() {
            return BackgroundService.this;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        Timber.d("Background service bound");
        return localServiceBinder;
    }
}
