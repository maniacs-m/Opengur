package com.kenny.openimgur.services;

import android.app.IntentService;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Build;
import android.os.PowerManager;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.RemoteInput;
import android.text.Html;
import android.text.TextUtils;
import android.text.format.DateUtils;

import com.kenny.openimgur.R;
import com.kenny.openimgur.activities.SettingsActivity;
import com.kenny.openimgur.api.ApiClient;
import com.kenny.openimgur.api.responses.NotificationResponse;
import com.kenny.openimgur.classes.ImgurAlbum;
import com.kenny.openimgur.classes.ImgurBaseObject;
import com.kenny.openimgur.classes.ImgurComment;
import com.kenny.openimgur.classes.ImgurConvo;
import com.kenny.openimgur.classes.ImgurMessage;
import com.kenny.openimgur.classes.ImgurPhoto;
import com.kenny.openimgur.classes.OpengurApp;
import com.kenny.openimgur.ui.BaseNotification;
import com.kenny.openimgur.ui.CircleBitmapDisplayer;
import com.kenny.openimgur.util.ImageUtil;
import com.kenny.openimgur.util.LogUtil;
import com.kenny.openimgur.util.NetworkUtils;
import com.kenny.openimgur.util.RequestCodes;
import com.kenny.openimgur.util.SqlHelper;
import com.nostra13.universalimageloader.core.assist.ImageSize;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import retrofit2.Response;

import static android.os.Build.VERSION_CODES.N;

/**
 * Created by kcampagna on 8/12/15.
 */
public class NotificationService extends IntentService {
    private static final String TAG = NotificationService.class.getSimpleName();

    public static Intent createIntent(Context context) {
        return new Intent(context, NotificationService.class);
    }

    public NotificationService() {
        super(TAG);
    }

    @Override
    protected void onHandleIntent(Intent intent) {
        OpengurApp app = OpengurApp.getInstance(getApplicationContext());
        boolean enabled = app.getPreferences().getBoolean(SettingsActivity.KEY_NOTIFICATIONS, true);

        if (!enabled) {
            LogUtil.v(TAG, "Notifications have been disabled, not fetching");
            return;
        }

        // Make sure we have a valid user
        if (app.getUser() != null) {
            PowerManager powerManager = (PowerManager) getSystemService(POWER_SERVICE);
            PowerManager.WakeLock wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
            wakeLock.acquire();

            try {
                Response<NotificationResponse> response = ApiClient.getService().getNotifications().execute();

                if (response != null && response.body() != null && response.body().hasNotifications()) {
                    NotificationResponse notificationResponse = response.body();

                    SqlHelper.getInstance(getApplicationContext()).insertNotifications(notificationResponse);
                    Notification notification = new Notification(getApplicationContext(), notificationResponse.data);
                    notification.postNotification();
                } else {
                    LogUtil.v(TAG, "No notifications found");
                }
            } catch (Exception ex) {
                LogUtil.e(TAG, "Error fetching notifications", ex);
            } finally {
                NetworkUtils.releaseWakeLock(wakeLock);
            }

            // Create the next alarm when everything is finished
            AlarmReceiver.createNotificationAlarm(getApplicationContext());
        } else {
            LogUtil.w(TAG, "Can not fetch notifications, no user found");
        }
    }

    private static class Notification extends BaseNotification {
        private static final int NOTIFICATION_ID = RequestCodes.NOTIFICATIONS;

        private static final int MIN_TEXT_LENGTH = 40;

        private static final int MAX_INBOX_LINES = 3;

        private String mTitle;

        private Uri mNotificationSound;

        private boolean mVibrate = false;

        public Notification(Context context, NotificationResponse.Data data) {
            super(context, false);
            SharedPreferences pref = app.getPreferences();
            mVibrate = pref.getBoolean(SettingsActivity.KEY_NOTIFICATION_VIBRATE, true);
            String ringTone = pref.getString(SettingsActivity.KEY_NOTIFICATION_RINGTONE, null);

            if (!TextUtils.isEmpty(ringTone)) {
                try {
                    mNotificationSound = Uri.parse(ringTone);
                } catch (Exception e) {
                    LogUtil.e(TAG, "Unable to parse ringtone", e);
                    mNotificationSound = null;
                }
            }

            buildNotification(data);
            build(context);
        }

        private void buildNotification(NotificationResponse.Data data) {
            // Remove potential duplicates
            Set<ImgurMessage> messages = new HashSet<>();
            Set<ImgurComment> replies = new HashSet<>();

            for (NotificationResponse.Messages m : data.messages) {
                messages.add(m.content);
            }

            for (NotificationResponse.Replies r : data.replies) {
                replies.add(r.content);
            }

            int messageNotifications = messages.size();
            int replyNotifications = replies.size();
            PendingIntent pendingIntent;
            ImgurBaseObject obj = null;

            if (messageNotifications > 0 && replyNotifications > 0) {
                // We have both messages and replies, create a generic notification
                int totalNotifications = messageNotifications + replyNotifications;
                mTitle = resources.getString(R.string.notifications_multiple_types, totalNotifications);
                builder.setContentText(resources.getString(R.string.notifications_multiple_types_body, totalNotifications));

                builder.setStyle(new NotificationCompat.InboxStyle()
                        .addLine(resources.getQuantityString(R.plurals.notification_messages, messageNotifications, messageNotifications))
                        .addLine(resources.getQuantityString(R.plurals.notification_replies, replyNotifications, replyNotifications))
                        .setBigContentTitle(mTitle));
            } else if (messageNotifications > 0) {
                boolean hasOnlyOne = messageNotifications == 1;
                mTitle = resources.getQuantityString(R.plurals.notification_messages, messageNotifications, messageNotifications);

                if (hasOnlyOne) {
                    // Only have one message, show the message and its contents
                    ImgurMessage newMessage = messages.iterator().next();
                    obj = new ImgurConvo(newMessage.getId(), newMessage.getFrom(), -1);
                    int messageLength = newMessage.getLastMessage().length() - MIN_TEXT_LENGTH;

                    // Big text style won't display if less than 40 characters
                    if (messageLength > 0) {
                        builder.setContentText(resources.getString(R.string.notification_new_message, newMessage.getFrom()));
                        builder.setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(newMessage.getLastMessage())
                                .setBigContentTitle(newMessage.getFrom()));
                    } else {
                        String formatted = resources.getString(R.string.notification_preview, newMessage.getFrom(), newMessage.getLastMessage());
                        builder.setContentText(Html.fromHtml(formatted));
                    }

                    // The Large icon will be the users names first letter
                    builder.setLargeIcon(createLargeIcon(-1, newMessage.getFrom()));
                    setupQuickReply(newMessage.getFrom());
                } else {
                    // Multiple messages, show the first 3
                    NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                    Iterator<ImgurMessage> m = messages.iterator();
                    int remaining = MAX_INBOX_LINES - messageNotifications;
                    int i = 0;

                    while (m.hasNext() && i < MAX_INBOX_LINES) {
                        ImgurMessage mess = m.next();
                        style.addLine(Html.fromHtml(resources.getString(R.string.notification_preview, mess.getFrom(), mess.getLastMessage())));
                        i++;
                    }

                    if (remaining < 0) {
                        style.setSummaryText(resources.getString(R.string.notification_remaining, Math.abs(remaining)));
                    }

                    builder.setStyle(style);
                    // The large icon will be the message icon
                    builder.setLargeIcon(createLargeIcon(R.drawable.ic_action_message_24dp, null));
                }
            } else {
                boolean hasOnlyOne = replyNotifications == 1;
                mTitle = resources.getQuantityString(R.plurals.notification_replies, replyNotifications, replyNotifications);

                if (hasOnlyOne) {
                    // Only have one reply, show its contents
                    ImgurComment comment = replies.iterator().next();
                    obj = comment;
                    int messageLength = comment.getComment().length() - MIN_TEXT_LENGTH;

                    // Big text style won't display if less than 40 characters
                    if (messageLength > 0) {
                        builder.setContentText(resources.getString(R.string.notification_new_message, comment.getAuthor()));
                        builder.setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(comment.getComment())
                                .setBigContentTitle(comment.getAuthor()));
                    } else {
                        String formatted = resources.getString(R.string.notification_preview, comment.getAuthor(), comment.getComment());
                        builder.setContentText(Html.fromHtml(formatted));
                    }

                    String photoUrl;

                    if (TextUtils.isEmpty(comment.getAlbumCoverId())) {
                        photoUrl = ApiClient.IMGUR_URL + comment.getImageId() + ImgurPhoto.THUMBNAIL_MEDIUM + ".jpeg";
                    } else {
                        photoUrl = String.format(ImgurAlbum.ALBUM_COVER_URL, comment.getAlbumCoverId() + ImgurPhoto.THUMBNAIL_MEDIUM);
                    }

                    Bitmap b = getReplyIcon(photoUrl);

                    if (b != null) {
                        builder.setLargeIcon(b);
                    } else {
                        builder.setLargeIcon(createLargeIcon(-1, comment.getAuthor()));
                    }
                } else {
                    // Multiple replies, show the first three
                    NotificationCompat.InboxStyle style = new NotificationCompat.InboxStyle();
                    Iterator<ImgurComment> r = replies.iterator();
                    int remaining = MAX_INBOX_LINES - replyNotifications;
                    int i = 0;

                    while (r.hasNext() && i < MAX_INBOX_LINES) {
                        ImgurComment comment = r.next();
                        style.addLine(Html.fromHtml(resources.getString(R.string.notification_preview, comment.getAuthor(), comment.getComment())));
                        i++;
                    }

                    if (remaining < 0) {
                        style.setSummaryText(resources.getString(R.string.notification_remaining, Math.abs(remaining)));
                    }

                    // The Large icon will be the reply icon
                    builder.setStyle(style);
                    builder.setLargeIcon(createLargeIcon(R.drawable.ic_reply_all_24dp, null));
                }
            }

            Intent intent = NotificationReceiver.createNotificationIntent(app, obj);
            pendingIntent = PendingIntent.getBroadcast(app, getNotificationId(), intent, PendingIntent.FLAG_ONE_SHOT);
            builder.setContentIntent(pendingIntent);

            Intent readIntent = NotificationReceiver.createReadNotificationsIntent(app, getNotificationId());
            PendingIntent readPIntent = PendingIntent.getBroadcast(app, RequestCodes.NOTIFICATIONS_READ, readIntent, PendingIntent.FLAG_ONE_SHOT);
            String msg = resources.getQuantityString(R.plurals.notification_mark_read, messageNotifications + replyNotifications);
            builder.addAction(R.drawable.ic_done_24dp, msg, readPIntent);
        }

        @Override
        protected Uri getNotificationSound() {
            return mNotificationSound;
        }

        @Override
        protected long getVibration() {
            return mVibrate ? DateUtils.SECOND_IN_MILLIS : 0;
        }

        @NonNull
        @Override
        protected String getTitle() {
            return mTitle;
        }

        @Override
        protected int getNotificationId() {
            return NOTIFICATION_ID;
        }

        @Override
        protected void postNotification(android.app.Notification notification) {
            notification.flags |= android.app.Notification.FLAG_ONLY_ALERT_ONCE;
            super.postNotification(notification);
        }

        @Nullable
        private Bitmap getReplyIcon(String url) {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    Bitmap bitmap = ImageUtil.getImageLoader(app).loadImageSync(url);
                    if (bitmap != null) return CircleBitmapDisplayer.getRoundedBitmap(bitmap);
                } else {
                    int iconSize = resources.getDimensionPixelSize(R.dimen.notification_icon);
                    return ImageUtil.getImageLoader(app).loadImageSync(url, new ImageSize(iconSize, iconSize));
                }
            } catch (Exception ex) {
                LogUtil.e(TAG, "Unable to load gallery thumbnail", ex);

            }

            return null;
        }

        private void setupQuickReply(@NonNull String with) {
            if (Build.VERSION.SDK_INT >= N) {
                RemoteInput remoteInput = new RemoteInput.Builder(NotificationReceiver.KEY_QUICK_REPLY_KEY)
                        .setLabel(resources.getString(R.string.convo_message_hint))
                        .build();

                Intent intent = NotificationReceiver.createQuickReplyIntent(app, getNotificationId(), with);

                NotificationCompat.Action action = new NotificationCompat.Action.Builder(R.drawable.ic_action_send_24dp,
                        resources.getString(R.string.reply),
                        PendingIntent.getBroadcast(app, getNotificationId(), intent, PendingIntent.FLAG_ONE_SHOT))
                        .addRemoteInput(remoteInput)
                        .build();

                builder.addAction(action);
            }
        }
    }
}
